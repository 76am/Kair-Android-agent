package dev.kair.app.app

import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.int
import dev.kair.app.core.tools.numProp
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.str
import dev.kair.app.core.tools.strProp
import kotlinx.serialization.json.JsonObject

/**
 * `background_task` 工具：启动 / 查询 / 取消 / 列出后台命令。
 *
 * 面向模型的文本一律英文（AGENTS.md §4）。工具返回**只表示已启动**，最终结果由
 * [BackgroundTaskManager] 以 system note 回注（U18：忙时入 STEER、下一步请求前并入，
 * 空闲时直接起唤醒回合，**不等** `TurnState.Idle`）—— 一次 tool_call 只能有一个结果，
 * 不能把完成输出再补成第二个 tool result。
 */
internal class BackgroundTaskTool(private val manager: BackgroundTaskManager) : Tool {
    override val name = "background_task"
    override val category = ToolCategory.EXECUTE
    override val promptSnippet =
        "Run a long command in the background (returns a task id; the result arrives later as a system note)"

    override val promptGuidelines = listOf(
        "Use background_task for commands that take minutes (builds, test suites, installs, " +
            "downloads); keep short commands in container_shell. After starting, do NOT wait: end your turn " +
            "or do work that does not depend on it. Never sleep / wait / tail -f or poll status.",
        "For a server that must keep running indefinitely, pass timeout=0. Do not wait for it either; " +
            "check it once with status when you need to know, and stop it with cancel.",
        "The persistent Linux process starts automatically. If it is not ready yet, tell the user to " +
            "check the Linux workspace panel.",
    )

    override val description =
        "Start a shell command in the persistent Linux process in the background and return " +
            "immediately. The command runs inside the container, not on Android. Actions: start " +
            "(requires `command`; optional `cwd` relative to the workspace and " +
            "`timeout` in seconds, default $DEFAULT_TIMEOUT_SECONDS, max 3600, 0 = no time limit for " +
            "servers), status (`task_id`), cancel (`task_id`), list. Logs are written to " +
            "$TASKS_DIR/<task_id>.log in the workspace. A task process starts with a " +
            "$MAX_FILE_SOFT_LABEL per-file soft limit; if the command must write one bigger file, " +
            "its shell can lift the limit first (e.g. `ulimit -f unlimited && <command>`)."

    override val parameters = schemaOf(
        props(
            "action" to strProp("One of: start, status, cancel, list"),
            "command" to strProp("Shell command to run in the background (required for start)"),
            "cwd" to strProp("Working directory relative to the workspace (optional; default = workspace root)"),
            "timeout" to numProp(
                "Timeout in seconds for start (default $DEFAULT_TIMEOUT_SECONDS, max 3600; " +
                    "0 = no time limit, for servers that must keep running)",
            ),
            "task_id" to strProp("Task id returned by start (required for status / cancel)"),
        ),
        "action",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        when (val action = args.reqStr("action")) {
            "start" -> start(args, ctx)
            "status" -> status(args, ctx)
            "cancel" -> cancel(args, ctx)
            "list" -> list(ctx)
            else -> ToolResult("Unknown action \"$action\". Use start, status, cancel or list.", isError = true)
        }

    private suspend fun start(args: JsonObject, ctx: ToolContext): ToolResult {
        // 0 = 不限时（常驻服务，proot 档唯一出口），原样透传；其余钳进 30..3600。
        val requested = args.int("timeout") ?: DEFAULT_TIMEOUT_SECONDS.toInt()
        val timeout = if (requested == 0) 0L else requested.coerceIn(30, 3600).toLong()
        val result = manager.start(
            sessionId = ctx.sessionId,
            hostWorkspace = ctx.workspace,
            command = args.reqStr("command"),
            cwd = args.str("cwd"),
            timeoutSeconds = timeout,
        )
        return when (result) {
            is StartResult.Failure -> ToolResult(result.message, isError = true)
            is StartResult.Started -> {
                val task = result.task
                // 不限时任务不会「finish」：承诺完成通知会把模型引去空等。
                val notice = if (task.timeoutSeconds == 0L) {
                    // U49：进程死掉也会投递一次终态通知，别再写「永远没有通知」；
                    // 「不要等」是文案批加的，两面都保留 —— 少一句要么谎报通知、要么模型空等。
                    "It runs until cancelled or the session closes; there is no completion notice " +
                        "unless its process dies - do not wait for it."
                } else {
                    "You will receive a system note when it finishes - do not wait for it."
                }
                ToolResult(
                    "Started background task ${task.id} (pid ${task.pid}; ${timeoutText(task.timeoutSeconds)}).\n" +
                        "Log: $TASKS_DIR/${task.id}.log\n" +
                        "$notice End your turn or do unrelated work; use action=\"status\" with " +
                        "task_id=\"${task.id}\" only for a one-off check (never in a loop), or " +
                        "action=\"cancel\" to stop it.",
                )
            }
        }
    }

    private fun status(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.reqStr("task_id")
        val task = manager.find(ctx.sessionId, id)
            ?: return ToolResult("No background task \"$id\" in this session.", isError = true)
        return ToolResult(statusText(task, manager.logTail(task.id)))
    }

    private suspend fun cancel(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.reqStr("task_id")
        val ok = manager.cancel(ctx.sessionId, id)
        return if (ok) {
            ToolResult("Cancelled background task $id. A short notice will be added to the conversation.")
        } else {
            ToolResult("Task \"$id\" is not running (unknown or already finished).", isError = true)
        }
    }

    private fun list(ctx: ToolContext): ToolResult {
        val tasks = manager.list(ctx.sessionId)
        if (tasks.isEmpty()) return ToolResult("No background tasks in this session.")
        val now = System.currentTimeMillis()
        return ToolResult(
            tasks.joinToString("\n") { task ->
                "- ${task.id} ${modelState(task.state)} ${elapsedSeconds(task, now)}s: ${task.command.lineSequence().first()}"
            },
        )
    }
}

internal fun modelState(state: BackgroundTaskState): String = when (state) {
    BackgroundTaskState.RUNNING -> "running"
    BackgroundTaskState.SUCCEEDED -> "succeeded"
    BackgroundTaskState.FAILED -> "failed"
    BackgroundTaskState.CANCELLED -> "cancelled"
    BackgroundTaskState.TIMED_OUT -> "timed out"
    BackgroundTaskState.TERMINATED -> "terminated"
}

internal fun elapsedSeconds(task: BackgroundTask, now: Long): Long =
    (((task.finishedAt ?: now) - task.startedAt) / 1000).coerceAtLeast(0)

/** 时限的模型文案：0 = 不限时。绝不能写成「timeout 0s」—— 会被读成 0 秒即超时。 */
internal fun timeoutText(seconds: Long): String =
    if (seconds == 0L) "no timeout" else "timeout ${seconds}s"

internal fun statusText(task: BackgroundTask, tail: String): String = buildString {
    append("Task ").append(task.id).append(": ").append(modelState(task.state))
    append(" (").append(elapsedSeconds(task, System.currentTimeMillis())).append("s")
    if (task.running) append(", pid ").append(task.pid).append(", ").append(timeoutText(task.timeoutSeconds))
    else task.exitCode?.let { append(", exit code ").append(it) }
    append(")\n")
    append("Command: ").append(task.command.lineSequence().first()).append('\n')
    append("Directory: ").append(task.cwd.ifEmpty { "." }).append('\n')
    append("Log: ").append(TASKS_DIR).append('/').append(task.id).append(".log\n")
    if (tail.isNotBlank()) {
        append("--- last output ---\n")
        append(tail.trimEnd()).append('\n')
    }
}
