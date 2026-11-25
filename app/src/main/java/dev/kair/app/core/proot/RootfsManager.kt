package dev.kair.app.core.proot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * rootfs 的安装 / 卸载 / 状态机。**Ubuntu base 22.04.5**（约 28 MB gzip tar）：
 * 与 Alpine 相比体积大一个量级，但换来 glibc / apt / 完整发行版生态。
 *
 * 布局（prootDir = storageRoot/proot）：
 * ```
 * proot/
 *   rootfs/          # 解包产物，容器根
 *   version.txt      # 已安装的 rootfs 版本（如 22.04.5）
 *   distro.txt       # 发行版标记（ubuntu）；缺 / 不匹配 = 需要重装
 *   download.tar.gz  # 下载暂存（安装成功后删除）
 * ```
 * 安装流程：下载 → 校验 SHA256SUMS → 解到 rootfs.tmp → 打补丁（DNS / apt 源）→ 原子改名。
 * 任何一步失败都不会污染已有可用 rootfs。
 */
class RootfsManager(
    private val prootDir: File,
    /** 设备 ABI，如 `arm64-v8a` / `x86_64`；null = 不支持（无对应 proot 二进制）。 */
    private val abi: String?,
    private val client: OkHttpClient = OkHttpClient(),
    /**
     * 当前镜像预设（host 从设置读取，安装 / 切源时实时取，换源不重建插件）。
     * apt 仓库、pip index 与 rootfs 下载地址三处都从它派生（见 [ContainerMirror]）。
     */
    private val mirror: () -> ContainerMirror = { ContainerMirror.DEFAULT },
    /**
     * 诊断日志（`.sha256` 拿不到时至少留一条）。**core 不得 import Android framework**
     * （`ArchitectureGuardTest`），所以真实日志器由宿主注入（见 `ProotPlugin`）；
     * 默认空实现，单测可注入记录器。
     */
    private val warn: (String) -> Unit = {},
) {

    sealed interface State {
        /** 未安装（或 ABI 不支持，见 [unsupportedReason]）。 */
        data object NotInstalled : State

        /** 下载中，progress ∈ [0,1]；无 content-length 时为 null。 */
        data class Downloading(val progress: Double?) : State

        /** 解包 / 打补丁中。 */
        data object Extracting : State

        /** 就绪。 */
        data class Ready(val version: String) : State

        /**
         * 失败。[checksumMismatch] 让 UI 能换成 strings.xml 里的专用文案 ——
         * core 只抛 / 携带英文详情，不在内核写中文用户文案（`AGENTS.md` §4）。
         */
        data class Error(val message: String, val checksumMismatch: Boolean = false) : State

        /**
         * 磁盘上是一份旧发行版（Alpine 时代）的 rootfs：不能当 Ubuntu 用，也不能假装 Ready。
         * 安装会原子替换 rootfs；项目文件在宿主挂载、不受影响，容器内软件与 `/root` 会丢。
         *
         * [installed] 是读到的旧标记（缺失时为 `alpine`），只用于 UI 解释现状。
         */
        data class ReinstallRequired(val installed: String) : State
    }

    val rootfsDir: File get() = File(prootDir, "rootfs")

    private val _state = MutableStateFlow<State>(detectState())
    val state: StateFlow<State> = _state.asStateFlow()

    /** ABI 不支持（无对应 proot / loader 二进制）时的原因；可用时为 null。 */
    val unsupportedReason: String? =
        when (abi) {
            null -> "无法确定设备 ABI"
            "arm64-v8a", "x86_64" -> null
            else -> "设备 ABI（$abi）暂不支持，需要 arm64 或 x86_64"
        }

    /** 是否已可运行（已安装且 ABI 支持）。 */
    val isReady: Boolean
        get() = abi != null && unsupportedReason == null && _state.value is State.Ready

    private fun detectState(): State {
        if (unsupportedReason != null) return State.Error(unsupportedReason)
        val marker = File(prootDir, "version.txt")
        if (!marker.exists() || !linkExists(File(rootfsDir, "bin/sh"))) return State.NotInstalled
        val distro = runCatching { File(prootDir, "distro.txt").readText().trim() }.getOrNull()
        return if (distro == DISTRO) {
            State.Ready(marker.readText().trim())
        } else {
            // 旧 Alpine rootfs 没有 distro.txt；绝不能只凭 bin/sh 存在就判 Ready。
            State.ReinstallRequired(distro?.ifBlank { "unknown" } ?: "alpine")
        }
    }

    /** 下载并安装当前 ABI 对应的 rootfs。已在安装中时为无操作。 */
    suspend fun install() = withContext(Dispatchers.IO) {
        if (unsupportedReason != null) {
            _state.update { State.Error(unsupportedReason) }
            return@withContext
        }
        if (_state.value is State.Downloading || _state.value is State.Extracting) return@withContext

        val selected = mirror()
        val arch = requireNotNull(abi).toArch()

        try {
            prootDir.mkdirs()
            _state.value = State.Downloading(null)
            val archive = File(prootDir, "download.tar.gz")
            val used = downloadRootfs(archive, selected, arch)
            // 下载后校验：Ubuntu 的校验文件是目录级 SHA256SUMS（不是逐文件 .sha256）。
            // 不一致就删文件并中止，绝不拿被篡改 / 半截的包去解包。
            verifyArchive(
                archive,
                sha256SumsUrl(used, arch),
                fetch = { fetchChecksum(it, rootfsFileName(arch)) },
                warn = warn,
            )

            _state.value = State.Extracting
            installFromArchive(archive.inputStream(), gzip = true, mirror = used, arch = arch)
            archive.delete()
        } catch (e: CancellationException) {
            // 协程取消不是失败：页面销毁 / scope 取消时状态不能写成 Error，
            // 否则下次进面板看到的是「下载失败」而不是「未安装」。
            // 也不能停在 Downloading / Extracting —— install() 开头的「已在安装中」
            // guard 会把它永久吞掉，而这两个状态在面板里没有重试按钮。复位成磁盘真实状态。
            _state.value = detectState()
            throw e
        } catch (e: Exception) {
            _state.value = State.Error(
                message = e.message ?: e.javaClass.simpleName,
                checksumMismatch = e is ChecksumMismatchException,
            )
        }
    }

    /**
     * 下载 rootfs。所选镜像拿不到 `releases/`（同步延迟 / 站点故障）时**回退官方源**，
     * 并留一条日志（不许静默）；返回**实际成功使用的镜像** —— SHA256SUMS 从同一个 base 取。
     */
    private fun downloadRootfs(archive: File, mirror: ContainerMirror, arch: String): ContainerMirror {
        val progress: (Double?) -> Unit = { p -> _state.value = State.Downloading(p) }
        val primary = rootfsUrl(mirror, arch)
        return try {
            download(primary, archive, progress)
            mirror
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mirror == ContainerMirror.OFFICIAL) throw e
            val fallback = rootfsUrl(ContainerMirror.OFFICIAL, arch)
            warn("rootfs download from ${mirror.id} failed (${e.message}); falling back to official: $fallback")
            download(fallback, archive, progress)
            ContainerMirror.OFFICIAL
        }
    }

    /** 从本地 tar 流安装（[install] 的核心步骤，抽出便于单测与未来的导入入口）。 */
    suspend fun installFromArchive(
        input: InputStream,
        gzip: Boolean,
        version: String = UBUNTU_VERSION,
        mirror: ContainerMirror = ContainerMirror.DEFAULT,
        arch: String = abi?.toArch() ?: "arm64",
    ) =
        withContext(Dispatchers.IO) {
            val tmp = File(prootDir, "rootfs.tmp")
            try {
                deleteRecursive(tmp)
                val stream = if (gzip) GZIPInputStream(input) else input
                val files = TarExtractor.extract(stream, tmp)
                if (files <= 0 || !linkExists(File(tmp, "bin/sh"))) {
                    throw IOException("archive does not look like a Linux rootfs (bin/sh missing)")
                }
                RootfsPatcher.patch(tmp, mirror, arch)

                // 原子切换：先把旧 rootfs 改名备份，失败时还能换回来。
                // 之前是「先删旧目录再 rename」，rename 失败就两空，旧容器白白丢掉。
                val backup = File(prootDir, "rootfs.old")
                deleteRecursive(backup)
                val hadOld = rootfsDir.exists()
                if (hadOld && !rootfsDir.renameTo(backup)) {
                    throw IOException("failed to move the previous rootfs aside")
                }
                if (!tmp.renameTo(rootfsDir)) {
                    // 回滚：把旧目录放回去，保证容器仍然可用。
                    if (hadOld) backup.renameTo(rootfsDir)
                    throw IOException("failed to move extracted rootfs into place")
                }
                deleteRecursive(backup)

                File(prootDir, "version.txt").writeText(version)
                File(prootDir, "distro.txt").writeText(DISTRO)
                _state.value = State.Ready(version)
            } finally {
                deleteRecursive(tmp)
            }
        }

    /**
     * 对**已安装**的 rootfs 重写镜像配置（不重装、不重新下载）。
     * 未安装（rootfs 目录不存在）时返回 false：不创建目录、不报错。
     */
    suspend fun reapplyMirror(mirror: ContainerMirror): Boolean = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) return@withContext false
        val arch = abi?.let { runCatching { it.toArch() }.getOrNull() } ?: return@withContext false
        RootfsPatcher.applyMirror(rootfsDir, mirror, arch)
        true
    }

    /** 卸载 rootfs（容器内 apt 装的包一并删除）。 */
    suspend fun uninstall() = withContext(Dispatchers.IO) {
        deleteRecursive(rootfsDir)
        if (rootfsDir.exists()) {
            // 部分文件被占用 / 权限不足时不能假装卸干净：保留 version.txt，
            // 让下一次 detectState 仍能识别这份 rootfs，用户可重试。
            _state.value = State.Error("rootfs 未能完全删除，请重试")
            return@withContext
        }
        File(prootDir, "version.txt").delete()
        File(prootDir, "distro.txt").delete()
        File(prootDir, "download.tar.gz").delete()
        _state.value = detectState()
    }

    // ---- 内部 ------------------------------------------------------------

    private fun download(url: String, dest: File, onProgress: (Double?) -> Unit) {
        val response = client.newBuilder()
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
            .newCall(Request.Builder().url(url).build())
            .execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            dest.outputStream().use { out ->
                body.byteStream().use { stream ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        // 节流：每 256KB 或末尾才推状态，避免高频状态更新
                        if (total != null && copied - lastReport >= 256 * 1024) {
                            lastReport = copied
                            onProgress(copied.toDouble() / total)
                        }
                    }
                    onProgress(total?.let { 1.0 })
                }
            }
        }
    }

    private fun fetchChecksum(url: String, fileName: String): String? {
        val response = client.newBuilder()
            .callTimeout(1, TimeUnit.MINUTES)
            .build()
            .newCall(Request.Builder().url(url).build())
            .execute()
        response.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            return parseSha256Sums(body.string(), fileName)
        }
    }

    private fun deleteRecursive(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursive(it) }
        file.delete()
    }

    /**
     * 检查路径**本身**存在，不跟随软链（`lstat` 语义）。
     *
     * 必需：rootfs 里的 `bin/sh` 通常是软链（Ubuntu 是 `bin -> usr/bin` + `sh -> dash`；
     * Alpine 是 `bin/sh -> /bin/busybox`），在宿主设备上解析不到，用 `File.exists()`
     * （跟随软链）会把装好的 rootfs 误判为「缺 bin/sh」。proot 把容器 `/` 映射到 rootfs，
     * 软链在容器内可正常解析，故只看链接本身是否存在。
     */
    private fun linkExists(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        /** 锁定版本保证可复现；升级 = 改这两个常量并重装。 */
        const val UBUNTU_RELEASE = "jammy"
        const val UBUNTU_VERSION = "22.04.5"

        /** `distro.txt` 的期望值；缺失 / 不匹配 = 旧发行版 rootfs，需要重装。 */
        const val DISTRO = "ubuntu"

        /** ABI → Ubuntu 架构名（镜像文件名与 apt base 共用）。 */
        internal fun String.toArch(): String = when (this) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "amd64"
            else -> throw IllegalArgumentException("unsupported abi: $this")
        }
    }
}

/** Ubuntu base 镜像文件名（版本与架构都由代码拼，不让用户填）。 */
internal fun rootfsFileName(arch: String): String =
    "ubuntu-base-${RootfsManager.UBUNTU_VERSION}-base-$arch.tar.gz"

/**
 * 由镜像预设 + 架构拼出完整 rootfs 下载地址。
 *
 * `imageBase` 已经含 `/ubuntu-base`（如 `…/ubuntu-cdimage/ubuntu-base`），
 * 这里只拼 `releases/jammy/release/<文件名>`，不会再重复发行版段。
 */
internal fun rootfsUrl(mirror: ContainerMirror, arch: String): String =
    "${mirror.imageBase}/releases/${RootfsManager.UBUNTU_RELEASE}/release/${rootfsFileName(arch)}"

/** 同一镜像目录下的校验文件（Ubuntu 是目录级 `SHA256SUMS`，不是逐文件 `.sha256`）。 */
internal fun sha256SumsUrl(mirror: ContainerMirror, arch: String): String =
    "${mirror.imageBase}/releases/${RootfsManager.UBUNTU_RELEASE}/release/SHA256SUMS"

/** 从 Ubuntu 的 `SHA256SUMS` 里按文件名取摘要（格式：`<hash> *<filename>`）。 */
internal fun parseSha256Sums(text: String, fileName: String): String? =
    text.lineSequence()
        .firstOrNull { it.trimEnd().endsWith(fileName) }
        ?.let { Regex("[0-9a-fA-F]{64}").find(it)?.value?.lowercase() }

/** 从单行 sha256 文本里取第一个 64 位十六进制摘要（兼容 `sha256sum` 输出）。 */
internal fun parseSha256(text: String): String? =
    Regex("[0-9a-fA-F]{64}").find(text)?.value?.lowercase()

/**
 * 下载后校验：拿到 sha 就比对；拿不到（镜像不提供 / 请求失败）**只告警、放行** ——
 * 自有镜像可能没有校验文件，但绝不静默通过。返回是否真的比对过。
 */
internal fun verifyArchive(
    archive: File,
    shaUrl: String,
    fetch: (String) -> String?,
    warn: (String) -> Unit,
): Boolean {
    val expected = try {
        fetch(shaUrl)
    } catch (e: Exception) {
        warn("sha256 fetch failed at $shaUrl (${e.message}); install continues without verification")
        return false
    }
    if (expected == null) {
        warn("sha256 unavailable at $shaUrl; install continues without verification")
        return false
    }
    verifySha256(archive, expected)
    return true
}

/** 比对文件 sha256；不一致时**删除文件**并抛 [ChecksumMismatchException]（不留下半截包）。 */
internal fun verifySha256(file: File, expected: String) {
    val actual = sha256Hex(file)
    if (!actual.equals(expected, ignoreCase = true)) {
        file.delete()
        throw ChecksumMismatchException(expected, actual)
    }
}

/** 文件内容的 sha256（小写十六进制）。 */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** 下载包与镜像发布的 sha256 不一致（[RootfsManager.install] 会删除该文件并中止安装）。 */
class ChecksumMismatchException(expected: String, actual: String) : IOException("sha256 mismatch: expected $expected, got $actual")
