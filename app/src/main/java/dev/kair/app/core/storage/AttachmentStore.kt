package dev.kair.app.core.storage

import dev.kair.app.core.model.AttachmentRef
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * 附件存储（内容寻址，本地文件实现）。
 *
 * 图片本体的唯一落盘处：会话日志只保存 [AttachmentRef]（sha256 + 元数据），本体按内容
 * 落在这里。同一字节永远得到同一文件名（去重），因此「按引用读回」是确定性的 —— 水合出的
 * base64 逐字节稳定，不会破坏请求前缀的缓存。
 *
 * 布局：`<root>/<sha 前两位>/<sha>`（两位分桶，避免单目录堆几万个文件）。
 *
 * 写入用「临时文件 + 原子改名」，避免半截文件被后续读回成坏图。读取失败一律返回 null，
 * 由调用方降级（保留原部件 / 显示「图片不可用」）而不是抛异常。
 */
class AttachmentStore(private val root: File) {

    /** 落地一张图片的本体，返回其内容寻址引用；解码失败返回 null（调用方保留内联 base64）。 */
    fun put(base64: String, mediaType: String): AttachmentRef? {
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        return putBytes(bytes, mediaType)
    }

    /** [put] 的字节版：供已持有原始字节的调用方（如 read_image）直接落地，免一次 base64 往返。 */
    fun putBytes(bytes: ByteArray, mediaType: String): AttachmentRef? = runCatching {
        val sha = sha256(bytes)
        val target = fileFor(sha)
        if (!target.exists()) writeAtomically(target, bytes)
        val (w, h) = imageSize(bytes)
        AttachmentRef(sha256 = sha, mediaType = mediaType, width = w, height = h)
    }.getOrNull()

    /** 临时文件 + 原子改名写入，避免半截文件被后续读成坏图；不支持原子移动时退化为普通改名。 */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 按引用读回 base64 本体；文件缺失或读取失败返回 null。 */
    fun read(ref: AttachmentRef): String? =
        readBytes(ref)?.let { Base64.getEncoder().encodeToString(it) }

    /** 按引用读回原始字节；文件缺失或读取失败返回 null。 */
    fun readBytes(ref: AttachmentRef): ByteArray? {
        val file = fileFor(ref.sha256)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 引用对应的本体文件路径（UI 渲染 / 排查用）。 */
    fun fileFor(ref: AttachmentRef): File = fileFor(ref.sha256)

    private fun fileFor(sha: String): File = File(File(root, sha.take(2)), sha)

    /**
     * 回收：删除所有 sha 不在 [referenced] 里的本体，并清掉随之变空的分桶目录。
     *
     * 只删「正式文件名」（恰为 64 位小写十六进制 sha）；写入中途的 `<sha>.tmp` 不在此列，
     * 避免与并发写竞态。比 [minAgeMillis] 更新的文件一并豁免 —— 兜底「刚落盘、引用还没写进
     * 日志」的并发窗口。
     *
     * **只删无引用的本体**是硬约束：引用是写进历史前缀的内容，误删仍被引用的本体就会让水合
     * 出的字节改变、破坏缓存前缀（见 AGENTS.md §3）。因此调用方给的引用集合必须取并集、
     * 宁可多留。
     *
     * @return 实际删除的文件数
     */
    fun sweep(referenced: Set<String>, minAgeMillis: Long = 0L): Int {
        if (!root.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - minAgeMillis
        var removed = 0
        root.listFiles().orEmpty().forEach { bucket ->
            if (!bucket.isDirectory) return@forEach
            bucket.listFiles().orEmpty().forEach { file ->
                if (isShaName(file.name) &&
                    file.name !in referenced &&
                    file.lastModified() <= cutoff &&
                    file.delete()
                ) {
                    removed++
                }
            }
            if (bucket.listFiles().orEmpty().isEmpty()) bucket.delete()
        }
        return removed
    }

    companion object {
        /** 工作区默认附件根 `<workspace>/.kair/attachments/v1`；内核默认值与宿主回收共用这一出处。 */
        fun forWorkspace(workspace: File): AttachmentStore =
            AttachmentStore(File(workspace, ".kair/attachments/v1"))

        /** 正式本体文件名：恰为 64 位小写十六进制 sha（借此排除写入中途的 `<sha>.tmp`）。 */
        internal fun isShaName(name: String): Boolean =
            name.length == 64 && name.all { it in '0'..'9' || it in 'a'..'f' }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * 从图片头解析像素尺寸（PNG / JPEG / GIF 常见格式；其余返回 0,0）。
         *
         * 纯字节解析，不依赖 Android，故可在 JVM 单测里验证。拿不到尺寸时退化为 0 —— token
         * 估算会退回固定估值，而不是误算成 0。
         */
        internal fun imageSize(bytes: ByteArray): Pair<Int, Int> = when {
            isPng(bytes) -> pngSize(bytes)
            isGif(bytes) -> gifSize(bytes)
            isJpeg(bytes) -> jpegSize(bytes)
            else -> 0 to 0
        }

        private fun isPng(b: ByteArray): Boolean =
            b.size >= 24 &&
                b[0] == 0x89.toByte() &&
                b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() &&
                b[3] == 0x47.toByte()

        /** PNG：IHDR 紧跟在 8 字节签名之后，宽 / 高为大端 32 位（偏移 16 / 20）。 */
        private fun pngSize(b: ByteArray): Pair<Int, Int> =
            be32(b, 16) to be32(b, 20)

        private fun isGif(b: ByteArray): Boolean =
            b.size >= 10 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

        /** GIF：逻辑屏幕描述符里宽 / 高为小端 16 位（偏移 6 / 8）。 */
        private fun gifSize(b: ByteArray): Pair<Int, Int> =
            le16(b, 6) to le16(b, 8)

        private fun isJpeg(b: ByteArray): Boolean =
            b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

        /**
         * JPEG：在段标记流里找 SOF（Start Of Frame，C0–CF 去掉非帧标记），
         * 其段内紧跟「精度(1) 高(2, 大端) 宽(2, 大端)」。
         */
        private fun jpegSize(b: ByteArray): Pair<Int, Int> {
            var i = 2
            while (i + 9 < b.size) {
                if (b[i] != 0xFF.toByte()) { i++; continue }
                val marker = b[i + 1].toInt() and 0xFF
                // 填充字节（连续 0xFF）跳过；无长度字段的独立标记跳过。
                if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD9) { i += 2; continue }
                val segLen = be16(b, i + 2)
                if (segLen < 2) break
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                // SOF 段内依次是：精度(1) 高(2) 宽(2) —— 返回 (宽, 高)。
                if (isSof && i + 9 < b.size) return be16(b, i + 7) to be16(b, i + 5)
                i += 2 + segLen
            }
            return 0 to 0
        }

        private fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun be32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}
