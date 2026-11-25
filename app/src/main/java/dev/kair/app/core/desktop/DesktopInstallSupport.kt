package dev.kair.app.core.desktop

import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.Mount
import dev.kair.app.core.proot.ProotCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 目录内所有普通文件的字节数（不存在 / 读失败按 0 处理；用于 apt 下载进度）。 */
internal fun directoryBytes(dir: File): Long =
    runCatching {
        if (!dir.isDirectory) return 0L
        dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }.getOrDefault(0L)

/**
 * 桌面安装的空间预检：`usableSpace` 为 0（文件系统不报可用空间）时不拦截；
 * 低于 [requiredMb] 返回可直接展示的中文原因。安装峰值（lists + cache + 解包）比
 * 「装后体积」高得多；空间不足时 dpkg 会在最后几个大包上报错，尾部通常只看到
 * 「Errors were encountered while processing」，很难定位到空间不足。
 */
internal fun desktopSpaceError(freeBytes: Long, requiredMb: Long = DesktopRunner.INSTALL_SPACE_REQUIRED_MB): String? {
    if (freeBytes <= 0) return null
    val freeMb = freeBytes / (1024 * 1024)
    if (freeMb >= requiredMb) return null
    return "磁盘空间不足：桌面安装峰值约需 $requiredMb MB，当前可用 $freeMb MB。清理设备空间后重试。"
}

/**
 * 从 apt/dpkg 输出里挑出**真正说明原因的行**。
 *
 * 只用尾部 2000 字是不够的（真机 2026-11-16）：`Errors were encountered while processing:`
 * 之后是一串包名，真正的第一条 `dpkg: error processing ...` 在更前面，被截掉了 ——
 * 截图里只剩包名列表，看不出是空间不足、postinst 失败还是依赖没配上。
 *
 * 过滤不到错误行时退回原文（不吞掉可能的意外输出）。
 */
internal fun aptFailureExcerpt(output: String, limit: Int = 1200): String {
    val lines = output.lines()
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { index, line ->
        keep[index] = line.startsWith("kair:") ||
            line.startsWith("dpkg: error") ||
            line.startsWith("E: ") ||
            line.contains("No space left on device") ||
            line.contains("error code") ||
            line.contains("dpkg-deb: error") ||
            line.contains("Sub-process") ||
            // 包管理器之外的失败源：postinst 里的 accout 工具（真机 2026-11-16：
            // `groupadd: cannot lock /etc/group` 才是 openssh-client 装不上的真因，
            // 它不以 `E: ` 开头，原来会被整段过滤掉）。
            line.contains("groupadd:") ||
            line.contains("useradd:") ||
            line.contains("adduser:") ||
            // dpkg 的报错头下一行才是原因（`post-installation script subprocess returned error exit status 1`）。
            (index > 0 && lines[index - 1].startsWith("dpkg: error"))
    }
    val text = if (keep.none { it }) output.trim() else lines.filterIndexed { index, _ -> keep[index] }.joinToString("\n")
    return text.takeLast(limit)
}

/** 经会话共用的一次性通道跑命令（返回 exit + 输出尾巴）。 */
internal suspend fun runDesktopOneShot(
    session: ContainerSession,
    runner: ProotCommandRunner,
    command: String,
): Pair<Int, String> = runDesktopOneShot(runner, session.mountedMounts, command)

/** 一次性 proot 命令：不占用共享终端的命令队列，也不往用户的转录里写。 */
internal suspend fun runDesktopOneShot(
    runner: ProotCommandRunner,
    mounts: List<Mount>,
    command: String,
): Pair<Int, String> = withContext(Dispatchers.IO) {
    val proc = runCatching { runner.spawnOneShot(mounts, command) }.getOrNull()
        ?: return@withContext -1 to "spawn failed"
    val out = StringBuilder()
    proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
    proc.waitFor()
    proc.exitValue() to out.toString()
}
