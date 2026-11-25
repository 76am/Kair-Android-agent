package dev.kair.app.core.desktop

import dev.kair.app.core.proot.Mount
import dev.kair.app.core.proot.ProotCommandRunner
import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 容器内常驻 `xdotool -` 的持有者（U89 §F；从 `DesktopRunner` 拆出满足体量守卫）：
 * 进程 + 串行写者 + 上一次按键状态。桌面启停归 `DesktopRunner`，这里只管管道。
 *
 * 为什么不用 shell 每次 fork：单次进程启动 5–10 ms，指针一拖就一跳一跳
 * （2026-11-14 光标测试已量过）。一个常驻进程把「解析 + XTest」压在毫秒级。
 */
internal class DesktopInputChannel(
    private val scope: CoroutineScope,
    /** 失败原因上抛给宿主（`DesktopRunner` 写进 `DesktopState.Error`）。 */
    private val onError: (String) -> Unit,
) {
    private var process: Process? = null
    private var writer: Job? = null
    private var outbound: Channel<Out>? = null
    private var previousMask = 0

    /** 起子进程跑 `xdotool type` 时要用（U89I）：容器命令行构造与环境变量都在 runner 上。 */
    private var runner: ProotCommandRunner? = null
    private var mounts: List<Mount> = emptyList()

    /**
     * 起容器内常驻 `xdotool -`：stdin 每行一条命令（`mousemove` / `mousedown` / `keydown` …）。
     * 调用方必须已在 IO 上下文（每轮会等 [INPUT_START_GRACE_MS] 确认进程没退）。
     *
     * 启动即退多半是 X 还没 listen（见 `DesktopRunner.awaitReady` 的竞态说明）：
     * 重试 [START_ATTEMPTS] 次再报错，而不是一次失败就把整个桌面判死。
     */
    suspend fun start(runner: ProotCommandRunner, mounts: List<Mount>): Boolean {
        var attempt = 0
        var proc: Process? = null
        var reason = ""
        while (attempt < START_ATTEMPTS) {
            proc = runCatching {
                ProcessBuilder(runner.buildDesktopCommandArgs(mounts, "export DISPLAY=:1; exec xdotool -"))
                    .directory(runner.rootfsDir.parentFile)
                    .apply {
                        environment().clear()
                        environment().putAll(runner.buildEnv())
                    }
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse {
                onError("cannot start input process: ${it.message}")
                return false
            }
            process = proc
            delay(INPUT_START_GRACE_MS)
            if (proc.isAlive) break
            reason = runCatching { proc.inputStream.bufferedReader().readText().trim().take(400) }
                .getOrDefault("")
            process = null
            runCatching { proc.killTree() }
            attempt++
            if (attempt < START_ATTEMPTS) delay(INPUT_RETRY_DELAY_MS)
        }
        val input = proc
        if (input == null || !input.isAlive) {
            onError("xdotool input exited: $reason")
            return false
        }
        previousMask = 0
        this.runner = runner
        this.mounts = mounts
        val queue = Channel<Out>(Channel.UNLIMITED)
        outbound = queue
        writer = scope.launch(Dispatchers.IO) {
            runCatching {
                input.outputStream.bufferedWriter().use { sink ->
                    for (item in queue) {
                        when (item) {
                            is Out.Line -> {
                                sink.write(item.command)
                                sink.newLine()
                                sink.flush()
                            }
                            // 文本要单开进程；先把管道里攒的键刷干净，保证
                            // 「先打的字母 → 后打的中文」不会反序。
                            is Out.Text -> {
                                sink.flush()
                                typeText(item.value)
                            }
                        }
                    }
                }
            }
        }
        // 必须耗掉 stdout/stderr：管道写满会把 xdotool 卡死在打印上。
        Thread {
            runCatching { input.inputStream.bufferedReader().forEachLine { /* discard */ } }
        }.apply { isDaemon = true; start() }
        return true
    }

    /**
     * 指针事件（framebuffer 坐标 + mask 语义：1=左、2=右、8=滚轮上、16=滚轮下）。
     * UI 主线程调用；只入队，真正的管道写发生在 IO 协程里。
     */
    fun pointer(x: Int, y: Int, mask: Int) {
        val out = outbound ?: return
        val commands = xdotoolPointerCommands(x, y, mask, previousMask)
        previousMask = mask
        for (command in commands) out.trySend(Out.Line(command))
    }

    /** 键盘事件（X keysym）；桌面未运行时静默丢弃。 */
    fun key(keysym: Int, down: Boolean) {
        val out = outbound ?: return
        for (command in xdotoolKeyCommands(keysym, down)) out.trySend(Out.Line(command))
    }

    /**
     * 非 Latin-1 文本（中文、emoji、带空格的整句）：单开一个进程跑 `xdotool type`。
     *
     * 为什么不能走常驻的 `xdotool -`：它的 stdin 按空白切分参数、不认引号，见
     * [xdotoolTypeShellCommand] 的说明。桌面未运行时静默丢弃。
     */
    fun text(value: String) {
        val out = outbound ?: return
        if (value.isEmpty()) return
        out.trySend(Out.Text(value))
    }

    /** 起一个短命进程把文本“真键盘”地敲进去；全程在 writer 协程里，与按键串行。 */
    private fun typeText(value: String) {
        val current = runner ?: return
        runCatching {
            val proc = ProcessBuilder(
                current.buildDesktopCommandArgs(mounts, "export DISPLAY=:1; ${xdotoolTypeShellCommand(value)}"),
            )
                .directory(current.rootfsDir.parentFile)
                .apply {
                    environment().clear()
                    environment().putAll(current.buildEnv())
                }
                .redirectErrorStream(true)
                .start()
            // 管道写满会把子进程卡死在打印上（同 start 里的说明）。
            proc.inputStream.bufferedReader().forEachLine { /* discard */ }
            if (!proc.waitFor(TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) proc.killTree()
        }
    }

    /** 关输入通道；先关队列再杀进程，避免 UI 继续往死管道写。 */
    fun stop() {
        outbound?.close()
        outbound = null
        writer?.cancel()
        writer = null
        previousMask = 0
        val proc = process ?: return
        process = null
        runCatching { proc.waitFor(1, TimeUnit.SECONDS) }
        runCatching { proc.killTree() }
    }
}

/** 输入通道启动重试：X 还没 listen 时 xdotool 会立刻退出，值得再等几轮（见 start 注释）。 */
private const val START_ATTEMPTS = 5
private const val INPUT_START_GRACE_MS = 150L
private const val INPUT_RETRY_DELAY_MS = 200L

/** 一次 `xdotool type` 的刀落上限：`TYPE_DELAY_MS × 字数` 之外再给余量（见 xdotoolTypeShellCommand）。 */
private const val TYPE_TIMEOUT_MS = 15_000L

/** 队列元素：常驻进程的一行命令，还是一次文本输入（需单开进程）。 */
private sealed interface Out {
    data class Line(val command: String) : Out
    data class Text(val value: String) : Out
}
