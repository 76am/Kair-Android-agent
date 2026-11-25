package dev.kair.app.core.proot

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 容器命令的内存守卫与退出诊断（U 系列：proot 重计算闪退）。
 *
 * 背景：proot 是 app 的子进程，纯 Python 的重计算（大网格 / 矩阵）会把整个 app 拖到
 * 系统的 LMK 线上，表现是无异常闪退。这里做两件事：
 * 1. 轮询设备可用内存 + 容器进程树 RSS，在系统动手之前主动杀命令并说明原因；
 * 2. 给异常退出补一行诊断（信号 / 峰值内存 / 耗时），让「被系统杀」「proot 崩」
 *    「真超时」在工具结果里能直接分开。
 *
 * 只读 `/proc`，不依赖 Android Context（core 不依赖 android.*）。
 */

/** 轮询间隔：400ms 足够在 LMK 之前留出一次反应窗口，又不会把 /proc 读成热点。 */
internal const val MEMORY_POLL_MS = 400L

/** 低于「总内存 1/16」或 300MB（取较大者）即视为内存紧张。 */
private const val LOW_MEMORY_FRACTION = 16L
private const val LOW_MEMORY_FLOOR_KB = 300L * 1024

/** 进程树至少用掉这么多才介入：一条 grep 不值得因为别的应用吃内存就被杀。 */
private const val GUARD_MIN_TREE_RSS_KB = 384L * 1024

/** 进程树遍历上限：防 /proc 异常时递归失控。 */
private const val MAX_TREE_NODES = 64

internal enum class ContainerExitKind { OK, TIMEOUT, KILLED, CRASH, SIGNAL, NON_ZERO, MEMORY_ABORT }

/** 一次等待的结局：正常结束 / 超时 / 内存中止，以及峰值 RSS（KB）。 */
internal data class WaitResult(
    val finished: Boolean,
    val timedOut: Boolean,
    val memoryAborted: Boolean,
    val peakRssKb: Long,
)

/** 轮询等待进程结束；内存紧张时提前返回（由调用方 killTree）。 */
internal fun awaitProcess(process: Process, hardWaitSeconds: Long?): WaitResult {
    val deadlineNs = hardWaitSeconds?.let { System.nanoTime() + it * 1_000_000_000L }
    var peak = 0L
    while (true) {
        if (process.waitFor(MEMORY_POLL_MS, TimeUnit.MILLISECONDS)) {
            peak = max(peak, processTreeRssKb(process.pidOrZero()))
            return WaitResult(finished = true, timedOut = false, memoryAborted = false, peakRssKb = peak)
        }
        val rss = processTreeRssKb(process.pidOrZero())
        if (rss > peak) peak = rss
        val (total, available) = readDeviceMemory()
        if (shouldAbortForMemory(total, available, rss)) {
            return WaitResult(finished = false, timedOut = false, memoryAborted = true, peakRssKb = peak)
        }
        if (deadlineNs != null && System.nanoTime() >= deadlineNs) {
            return WaitResult(finished = false, timedOut = true, memoryAborted = false, peakRssKb = peak)
        }
    }
}

internal data class MemorySnapshot(
    val totalKb: Long,
    val availableKb: Long,
    val treeRssKb: Long,
)

/** 是否该在系统 LMK 之前主动中止。纯函数，便于单测钉阈值。 */
internal fun shouldAbortForMemory(totalKb: Long, availableKb: Long, treeRssKb: Long): Boolean {
    if (totalKb <= 0 || availableKb <= 0) return false
    val threshold = max(totalKb / LOW_MEMORY_FRACTION, LOW_MEMORY_FLOOR_KB)
    return availableKb < threshold && treeRssKb >= GUARD_MIN_TREE_RSS_KB
}

/** 退出分类：正常 / 超时 / 主动内存中止 / 信号（含 SIGSEGV）/ 普通非零。 */
internal fun classifyExit(exitCode: Int, timedOut: Boolean, memoryAborted: Boolean): ContainerExitKind = when {
    memoryAborted -> ContainerExitKind.MEMORY_ABORT
    timedOut -> ContainerExitKind.TIMEOUT
    exitCode == 0 -> ContainerExitKind.OK
    exitCode == 139 || exitCode == 134 -> ContainerExitKind.CRASH // SIGSEGV / SIGABRT
    exitCode > 128 -> ContainerExitKind.SIGNAL
    else -> ContainerExitKind.NON_ZERO
}

/**
 * 异常退出的诊断行（英文，模型可见）：只对超时 / 内存中止 / 信号补，普通非零
 * （grep 没匹配、测试失败）不动，避免污染正常输出。
 */
internal fun exitDiagnostic(
    kind: ContainerExitKind,
    exitCode: Int,
    peakRssKb: Long,
    durationMs: Long,
): String? {
    val rss = formatBytes(peakRssKb * 1024)
    val secs = (durationMs / 1000).coerceAtLeast(0)
    return when (kind) {
        ContainerExitKind.MEMORY_ABORT ->
            "[kair] Command aborted: device memory is low (container used $rss in ${secs}s). " +
                "Reduce memory usage (fewer segments/points, OPENBLAS_NUM_THREADS=1, avoid multiprocessing) " +
                "or split the computation."
        ContainerExitKind.CRASH ->
            "[kair] exit $exitCode (${signalName(exitCode)}) after ${secs}s; peak RSS $rss. " +
                "The guest process crashed inside the container (native library / proot). " +
                "Retry with fewer threads (OPENBLAS_NUM_THREADS=1) or a lighter computation."
        ContainerExitKind.SIGNAL ->
            "[kair] exit $exitCode (${signalName(exitCode)}) after ${secs}s; peak RSS $rss. " +
                "The process was killed by a signal (likely memory pressure or a hard timeout)."
        else -> null
    }
}

private fun signalName(exitCode: Int): String = when (val signal = exitCode - 128) {
    9 -> "SIGKILL"
    11 -> "SIGSEGV"
    6 -> "SIGABRT"
    15 -> "SIGTERM"
    else -> "signal $signal"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.1f GB", gb)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}

/** 读取 `/proc/meminfo`；读不到时返回 0，守卫自动降级为不介入。 */
internal fun readDeviceMemory(): Pair<Long, Long> {
    val text = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return 0L to 0L
    var total = 0L
    var available = 0L
    text.lineSequence().forEach { line ->
        when {
            line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            line.startsWith("MemAvailable:") -> available = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
    }
    return total to available
}

/** 容器进程树 RSS（KB）：proot 的 tracee 是子进程，只看 proot 自身会严重低估。 */
internal fun processTreeRssKb(rootPid: Long): Long {
    if (rootPid <= 0L) return 0L
    val seen = HashSet<Long>()
    val queue = ArrayDeque<Long>()
    queue.add(rootPid)
    var total = 0L
    while (queue.isNotEmpty() && seen.size < MAX_TREE_NODES) {
        val pid = queue.removeFirst()
        if (!seen.add(pid)) continue
        total += readRssKb(pid)
        childrenOf(pid).forEach { queue.addLast(it) }
    }
    return total
}

/** `Process.pid()` 在 API 26+ 存在，但编译期按 JDK 解析会报缺失：反射取，拿不到就当 0。 */
private fun Process.pidOrZero(): Long = runCatching {
    javaClass.getMethod("pid").invoke(this) as? Long ?: 0L
}.getOrDefault(0L)

private fun readRssKb(pid: Long): Long {
    val text = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return 0L
    val line = text.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
    return line.filter { it.isDigit() }.toLongOrNull() ?: 0L
}

/** 子进程列表：优先 `/proc/<pid>/task/<pid>/children`，不支持时扫 `/proc` 下各进程 stat 的 PPID。 */
private fun childrenOf(pid: Long): List<Long> {
    val direct = runCatching {
        File("/proc/$pid/task/$pid/children").readText()
    }.getOrNull()
    if (direct != null) {
        return direct.split(' ').mapNotNull { it.trim().toLongOrNull() }
    }
    return runCatching {
        File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val child = dir.name.toLongOrNull() ?: return@mapNotNull null
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@mapNotNull null
            // stat: pid (comm) state ppid ... —— comm 里可能有空格与括号，取最后一个 ')' 之后。
            val after = stat.substringAfterLast(')').trim().split(' ')
            val ppid = after.getOrNull(1)?.toLongOrNull()
            child.takeIf { ppid == pid }
        }
    }.getOrDefault(emptyList())
}
