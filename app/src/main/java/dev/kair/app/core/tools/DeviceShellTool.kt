package dev.kair.app.core.tools

import dev.kair.app.core.device.AdbOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 设备 shell 的执行后端。真实实现是 `AdbLink.shell`；单测注入假实现，避免碰 Android。 */
fun interface DeviceShellRunner {
    suspend fun run(command: String): AdbOutcome
}

/**
 * U91 · `device_shell`：以 adb shell 身份（uid 2000）在**这台手机**上跑一条命令。
 *
 * 为什么不并进 `android_shell`：设备 shell 没有工作区语义（读不到 `/projects`、读不到应用私有目录），
 * 把它做成第 N 条执行通道等于把 ADR 0007 删掉的「通道选择器」请回来。独立成工具，描述里把边界写死。
 *
 * 描述与错误文案是英文：它们进系统提示的缓存稳定前缀、作为 tool 结果回给模型，不是界面文案；
 * core 里也不允许出现中文字面量（AGENTS.md §4 / DocsConsistencyTest A6-4）。
 */
class DeviceShellTool(private val runner: DeviceShellRunner) : Tool {
    override val name = "device_shell"

    override val category = ToolCategory.EXECUTE

    override val promptSnippet =
        "device_shell — run a shell command on this Android phone (adb shell user)"

    override val promptGuidelines = listOf(
        "device_shell runs on the phone itself as the adb shell user (uid 2000), not in your workspace: it " +
            "sees the real Android filesystem but cannot read /projects or the app's private directory. Use " +
            "container_shell for anything about project files.",
        "device_shell needs wireless debugging to be on. If it reports the port as unreachable, ask the user " +
            "to switch wireless debugging back on instead of retrying in a loop.",
        // 四条都是模型必然踩、且踩了会连着错下去的坑，与其让它试三轮，不如写在前面。
        "Four gotchas: (1) `input text` needs spaces written as %s — `input text 'hello%sworld'`, or only " +
            "the first word lands. (2) `input text` is ASCII-only: Chinese and any other non-ASCII " +
            "character makes the device throw a NullPointerException — ask the user to type those. " +
            "(3) `logcat` must be bounded — `logcat -d -t 200` — a bare `logcat` never returns and dies on " +
            "timeout. (4) There is no binary upload over this channel, so `pm install` only works for an " +
            "apk that is already at a shell-readable path; otherwise ask the user to install it on the phone.",
    )

    override val description =
        "Run a shell command on THIS Android phone as the adb shell user (SELinux u:r:shell:s0), over " +
            "loopback, like `adb shell`. It is toybox and sees the real Android filesystem, but not " +
            "your workspace files. Needs wireless debugging on and one-time ADB pairing; " +
            "the link is reused. Prefer read-only probes (getprop, dumpsys, pm list) before changing state."

    override val parameters = schemaOf(
        props(
            "command" to strProp("Shell command to run on the device (toybox sh syntax)"),
        ),
        "command",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.reqStr("command").trim()
        if (command.isEmpty()) throw ToolException("Empty command")
        if (ctx.isAborted()) throw ToolException("Aborted")
        return when (val outcome = withContext(Dispatchers.IO) { runner.run(command) }) {
            is AdbOutcome.Ok -> when {
                outcome.output.isEmpty() -> ToolResult("(no output)")
                // 这条通道不带退出码（libadb 的 `shell:` 服务就是一条裸字节流），所以设备端抛异常时
                // 命令会以 exit 255 失败，而我们只拿到 stderr 正文 —— 报成 `Ok` 就等于告诉模型
                // 「成功，附赠一堆堆栈」。识别 ShellCommand 的包装格式把它翻回失败。
                isShellCommandCrash(outcome.output) ->
                    ToolResult(outcome.output + "\n\n" + SHELL_CRASH_NOTE, isError = true)

                else -> ToolResult(outcome.output)
            }

            is AdbOutcome.Failed -> ToolResult(adbFailureHint(outcome.reason, outcome.detail), isError = true)
        }
    }

    private companion object {
        /**
         * 设备端 shell 命令抛异常的包装格式（`ShellCommand.exec` 打的）。
         *
         * 两个串**同时**命中也算：`Exception occurred while executing` 单独一个有可能出现在
         * `logcat -d` 抓到的应用堆栈里，而 `BasicShellCommandHandler` 只在 shell 命令自己崩时出现。
         */
        const val CRASH_MARK = "Exception occurred while executing"
        const val CRASH_FRAME = "BasicShellCommandHandler"

        fun isShellCommandCrash(output: String): Boolean =
            CRASH_MARK in output && CRASH_FRAME in output

        const val SHELL_CRASH_NOTE =
            "[kair] The command FAILED on the device — the trace above is how it reports that (this " +
                "channel carries no exit code, so failures arrive as text instead of an error status). " +
                "`input text` with Chinese or other non-ASCII characters is the most common cause: the " +
                "device's key-event map has no such keys, so it throws. Ask the user to type such text " +
                "themselves instead of retrying with different quoting."
    }
}
