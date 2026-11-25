package dev.kair.app.app

import java.io.File
import java.io.RandomAccessFile

// 后台任务的「给谁看」那一段：日志尾部读取 + 面向模型的完成通知文本。
//
// 从 [BackgroundTaskManager] 里分出来是因为管理器贴着 500 行阈值 —— 这两段是纯函数，
// 搬出来既不让主源码继续变胖，也让「通知长什么样」有一个唯一的落点。
// 通知正文面向模型，按 `AGENTS.md` §4 必须是英文。常量由 `private` 放宽到 `internal`
// （同模块可见，管理器仍按名字引用）。

internal const val NOTE_TAIL_CHARS = 6_000
internal const val NOTE_TAIL_LINES = 120
internal const val PANEL_TAIL_CHARS = 20_000
internal const val PANEL_TAIL_LINES = 400

/** 读日志尾部：最多 [maxChars] 字节、最后 [maxLines] 行；截断时丢掉第一行残片。 */
internal fun tailText(file: File, maxChars: Int, maxLines: Int): String {
    if (!file.isFile || file.length() == 0L) return ""
    val text = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxChars).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val raw = String(bytes, Charsets.UTF_8)
            if (start > 0) raw.substringAfter('\n', raw) else raw
        }
    }.getOrElse { return "" }
    val lines = text.trimEnd().lineSequence().toList()
    return if (lines.size <= maxLines) text.trimEnd() else lines.takeLast(maxLines).joinToString("\n")
}

/** 面向模型的完成通知（英文，AGENTS.md §4）；正文只回尾部，全文让模型自己 read。 */
internal fun taskNote(task: BackgroundTask, tail: String): String = buildString {
    val elapsed = (((task.finishedAt ?: task.startedAt) - task.startedAt) / 1000).coerceAtLeast(0)
    append("[background task ").append(task.id).append(": ")
    when (task.state) {
        BackgroundTaskState.SUCCEEDED -> append("finished with exit code 0")
        BackgroundTaskState.FAILED -> append("failed with exit code ").append(task.exitCode ?: "unknown")
        BackgroundTaskState.TIMED_OUT -> append("timed out after ").append(task.timeoutSeconds).append("s and was killed")
        BackgroundTaskState.CANCELLED -> append("was cancelled by the user")
        // 用户没碰它、也没有退出码：必须与「用户取消」区分开，否则模型会判成是用户干的。
        BackgroundTaskState.TERMINATED -> append("was terminated (its process died without writing an exit code)")
        BackgroundTaskState.RUNNING -> append("is still running")
    }
    append(" after ${elapsed}s]\n")
    append("Command: ").append(task.command.lineSequence().first()).append('\n')
    append("Directory: ").append(task.cwd.ifEmpty { "." }).append('\n')
    append("Log: ").append(TASKS_DIR).append('/').append(task.id).append(".log\n")
    if (tail.isNotBlank()) {
        append("--- last output ---\n")
        append(tail.trimEnd()).append('\n')
    }
    append("This is an automated completion notice from the background task manager, not a user message.")
}
