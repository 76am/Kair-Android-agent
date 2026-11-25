package dev.kair.app.core.proot

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 纯 Kotlin 的 tar 解包器，服务 rootfs 安装（无外部依赖、无 JNI）。
 *
 * 支持 ustar / GNU / PAX 三种头部扩展中 rootfs 实际用到的子集：
 * 普通文件、目录、软链、硬链、GNU 长文件名（L）/ 长链接名（K）、
 * PAX 扩展头（x/g，取 path / linkpath / size 记录）。
 * 设备节点与 FIFO 会被跳过（proot 容器内不需要真实节点）。
 *
 * 安全（rootfs 来自网络下载，必须防 tar-slip）：
 * - 条目路径经 normalize 后必须仍在 [root] 下；
 * - 写文件前用 canonicalFile 复核——若某个已解出的祖先目录是**指向 root 外**的
 *   软链，写入会被拒绝（指向 root 内的软链则按真实位置落盘，语义等价）。
 */
internal object TarExtractor {

    private const val BLOCK = 512
    private const val NAME_LEN = 100
    private const val LINK_LEN = 100
    private const val PREFIX_LEN = 155

    /**
     * 把 [input]（已解压的 tar 字节流）解到 [root]，返回解出的普通文件数。
     *
     * [createLink] 只在测试里替换：真机文件系统可能拒 `link()`，需要覆盖降级路径。
     */
    fun extract(
        input: InputStream,
        root: File,
        createLink: (link: Path, existing: Path) -> Unit = { link, existing ->
            Files.createLink(link, existing)
        },
    ): Int {
        root.mkdirs()
        val rootPath = root.canonicalFile.toPath()
        var fileCount = 0
        input.buffered().use { stream ->
            while (true) {
                val header = readBlock(stream) ?: break // 流结束
                if (header.all { it == 0.toByte() }) break // 结尾零块（宽容：不要求凑满两块）
                when (processEntry(stream, header, rootPath, createLink)) {
                    EntryResult.WRITTEN -> fileCount++
                    EntryResult.META, EntryResult.SKIPPED -> {}
                    EntryResult.CORRUPT -> throw IOException("corrupt tar stream")
                }
            }
        }
        return fileCount
    }

    private enum class EntryResult { WRITTEN, META, SKIPPED, CORRUPT }

    /** 解析一个条目；WRITTEN = 落盘普通文件；META = 目录/软链（落盘但不计入文件数）；
     *  SKIPPED = 元头/设备节点/拒绝；CORRUPT = 流损坏。 */
    private fun processEntry(
        stream: InputStream,
        header: ByteArray,
        root: Path,
        createLink: (link: Path, existing: Path) -> Unit,
    ): EntryResult {
        val type = header[156].toInt().toChar()
        val size = parseOctal(header, 124, 12)

        return when (type) {
            'L' -> { // GNU 长文件名：内容是下一条目的真实路径
                pendingLongName = readData(stream, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                EntryResult.SKIPPED
            }
            'K' -> { // GNU 长链接名
                pendingLongLink = readData(stream, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                EntryResult.SKIPPED
            }
            'x', 'g' -> { // PAX 扩展头：解析后作用于下一条目
                readPaxRecords(readData(stream, size))
                EntryResult.SKIPPED
            }
            '0', '\u0000', '7' -> { // 普通文件 / 连续文件
                // PAX 的 size 记录才是权威（超大文件时头部字段放不下）；流中数据长度以它为准
                val actualSize = pendingPaxSize ?: size
                val target = resolveTarget(header, root)
                    ?: return if (skipData(stream, actualSize)) EntryResult.SKIPPED else EntryResult.CORRUPT
                writeRegularFile(stream, actualSize, target, header)
                EntryResult.WRITTEN
            }
            '5' -> { // 目录
                val target = resolveTarget(header, root)
                if (target != null) target.toFile().mkdirs()
                if (skipData(stream, size)) EntryResult.META else EntryResult.CORRUPT
            }
            '2' -> { // 软链
                val target = resolveTarget(header, root)
                if (target != null) {
                    val link = pendingPaxLinkpath ?: pendingLongLink ?: parseString(header, 157, LINK_LEN)
                    target.parent?.toFile()?.mkdirs()
                    runCatching { Files.deleteIfExists(target) } // 覆盖旧链/旧空目录
                    Files.createSymbolicLink(target, File(link).toPath())
                }
                if (skipData(stream, size)) EntryResult.META else EntryResult.CORRUPT
            }
            '1' -> { // 硬链：指向之前已解出的文件（落盘真实文件，计入文件数）
                val target = resolveTarget(header, root)
                if (target != null) {
                    val source = parseString(header, 157, LINK_LEN)
                    val sourcePath = root.resolve(source).normalize()
                    if (sourcePath.startsWith(root)) {
                        target.parent?.toFile()?.mkdirs()
                        runCatching { Files.deleteIfExists(target) }
                        linkOrCopy(createLink, sourcePath, target)
                    }
                }
                if (skipData(stream, size)) EntryResult.WRITTEN else EntryResult.CORRUPT
            }
            else -> // 设备 / FIFO / 其他：容器内用不到，跳过
                if (skipData(stream, size)) EntryResult.SKIPPED else EntryResult.CORRUPT
        }
    }

    /**
     * 硬链落盘：优先真硬链，文件系统拒绝 `link()` 时退化成复制。
     *
     * 2026-11-16 真机：Ubuntu base 里 `/usr/bin/perl5.34.0`、`/usr/bin/uncompress` 是硬链条目，
     * `Files.createLink` 在应用私有目录上直接抛 `FileSystemException`，整次安装卡在 Extracting。
     * Alpine minirootfs 的 tar **没有任何硬链条目**（518 条 0 个），所以这条路径此前从未在设备上跑过。
     * rootfs 不依赖 inode 同一性；复制内容并沿用源文件的权限位（含可执行位）语义等价。
     */
    private fun linkOrCopy(
        createLink: (link: Path, existing: Path) -> Unit,
        source: Path,
        target: Path,
    ) {
        try {
            createLink(target, source)
        } catch (e: IOException) {
            copyInsteadOfLink(source, target)
        } catch (e: UnsupportedOperationException) {
            copyInsteadOfLink(source, target)
        }
    }

    private fun copyInsteadOfLink(source: Path, target: Path) {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        // 硬链与源共享 inode，所以权限位也应当与源一致（tar 头里硬链条目的 mode 不可靠）
        val src = source.toFile()
        val dst = target.toFile()
        dst.setReadable(src.canRead())
        dst.setWritable(src.canWrite())
        dst.setExecutable(src.canExecute())
    }

    // ---- 待生效的 GNU/PAX 覆盖（只作用于紧随其后的条目，用后即清） ----

    private var pendingLongName: String? = null
    private var pendingLongLink: String? = null
    private var pendingPaxPath: String? = null
    private var pendingPaxLinkpath: String? = null
    private var pendingPaxSize: Long? = null

    private fun clearPending() {
        pendingLongName = null
        pendingLongLink = null
        pendingPaxPath = null
        pendingPaxLinkpath = null
        pendingPaxSize = null
    }

    private fun readPaxRecords(data: ByteArray) {
        // 记录格式：<len> <key>=<value>\n，len 含自身与换行
        var i = 0
        while (i < data.size) {
            val space = data.indexOf(' '.code.toByte(), i)
            if (space < 0) break
            val len = data.copyOfRange(i, space).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: break
            val end = i + len
            if (end <= i || end > data.size) break
            val record = data.copyOfRange(space + 1, end - 1).toString(Charsets.UTF_8) // 去掉 \n
            val eq = record.indexOf('=')
            if (eq > 0) {
                when (record.substring(0, eq)) {
                    "path" -> pendingPaxPath = record.substring(eq + 1)
                    "linkpath" -> pendingPaxLinkpath = record.substring(eq + 1)
                    "size" -> pendingPaxSize = record.substring(eq + 1).toLongOrNull()
                }
            }
            i = end
        }
    }

    /**
     * 计算条目落盘路径；逃逸 root 的条目返回 null（拒绝写入，数据照常跳过）。
     *
     * 防穿透不能整体用 canonicalFile / toRealPath：目标文件尚未存在，而
     * canonicalFile 在「软链指向不存在的目标」时会静默回退成词法路径（OpenJDK
     * 的 resolveParent 回退），检测直接失效。因此对 root 之下的**每一段已存在
     * 祖先**单独用 toRealPath 验证：段不存在 → 之后全是新建路径，安全；
     * 段存在但解析失败（断链 / 循环链）或解析到 root 外 → 拒绝。
     * 指向 root 内的软链会通过检查，写入时由文件系统穿透到真实位置（语义等价）。
     */
    private fun resolveTarget(header: ByteArray, root: Path): Path? {
        val raw = pendingPaxPath ?: pendingLongName ?: ustarName(header)
        val normalized = (if (raw.startsWith("/")) raw.drop(1) else raw).replace('\\', '/')
        val lexical = root.resolve(normalized).normalize()
        clearPending()
        if (!lexical.startsWith(root)) return null
        var probe = root
        for (i in root.nameCount until lexical.nameCount) {
            probe = probe.resolve(lexical.getName(i))
            if (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) return lexical
            val real = runCatching { probe.toRealPath() }.getOrNull() ?: return null
            if (!real.startsWith(root)) return null
        }
        return lexical
    }

    /** ustar 名字 = prefix/name（GNU 老布局），两者任一可能为空。 */
    private fun ustarName(header: ByteArray): String {
        val prefix = parseString(header, 345, PREFIX_LEN)
        val name = parseString(header, 0, NAME_LEN)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun writeRegularFile(stream: InputStream, size: Long, target: Path, header: ByteArray) {
        val bytes = readData(stream, size)
        target.parent?.toFile()?.mkdirs()
        val tmp = target.resolveSibling(target.fileName.toString() + ".kair-partial")
        Files.write(tmp, bytes)
        runCatching { Files.deleteIfExists(target) }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        // 权限位：rootfs 内的脚本需要可执行位（owner 位为主，读位兜底开放）
        val mode = parseOctal(header, 100, 8).toInt()
        val f = target.toFile()
        f.setReadable(mode and 0x100 != 0 || mode and 0x004 != 0)
        f.setWritable(mode and 0x080 != 0 || mode and 0x002 != 0)
        f.setExecutable(mode and 0x40 != 0)
    }

    private fun skipData(stream: InputStream, size: Long): Boolean {
        var left = align512(size)
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(left.toInt(), BLOCK))
            if (n < 0) return false
            left -= n
        }
        return true
    }

    private fun readData(stream: InputStream, size: Long): ByteArray {
        val padded = ByteArray(align512(size).toInt())
        var off = 0
        while (off < padded.size) {
            val n = stream.read(padded, off, padded.size - off)
            if (n < 0) throw IOException("unexpected end of tar stream")
            off += n
        }
        return padded.copyOf(size.toInt())
    }

    private fun readBlock(stream: InputStream): ByteArray? {
        val buf = ByteArray(BLOCK)
        var off = 0
        while (off < BLOCK) {
            val n = stream.read(buf, off, BLOCK - off)
            if (n < 0) return null // 不足一块 = 流结束或损坏，统一按结束处理
            off += n
        }
        return buf
    }

    private fun align512(n: Long): Long = (n + BLOCK - 1) / BLOCK * BLOCK

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val s = parseString(header, offset, length).trim(' ', '\u0000')
        // GNU base-256 编码（高位 bit 置 1）在 rootfs 内不会出现，按 0 处理
        return if (s.isEmpty()) 0L else s.toLongOrNull(radix = 8) ?: 0L
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = minOf(offset + length, header.size)
        while (end < max && header[end] != 0.toByte()) end++
        return header.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }
}
