package dev.kair.app.core.proot

import dev.kair.app.core.term.TerminalParser
import dev.kair.app.core.term.TerminalSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** 用户终端（PTY 通道）的状态。与 [SessionState] 同风格，但多了「已退出（退出码）」。 */
sealed interface PtyState {
    data object Stopped : PtyState
    data object Starting : PtyState
    data object Running : PtyState

    /** shell 正常退出；[code] 为 null 表示没取到（如 /proc 读不到）。 */
    data class Exited(val code: Int?) : PtyState

    /** 启动失败或异常退出；[message] 是给用户看的原因（U83 起终端面板直接显示它，不再回退）。 */
    data class Error(val message: String) : PtyState
}

/**
 * 用户终端会话（路线 H）：宿主侧 `openpty` + JNI `fork/execve`，master fd 留在本进程。
 *
 * 与 [ContainerSession] 的关系（任务文档 §3 D3 / D2-6）：
 * - **两条独立通道**：帧协议会话一行不改；本类只新增 PTY；
 * - **启动参数不自己拼**：proot 的 `(path, argv, env)` 直接消费 [SessionLaunchFactory]
 *   （经 [ContainerSession.launchFactory] 取得）；U78 起只有 proot 一档（ADR 0007）；
 * - 共享同一个 rootfs / `/projects` 挂载，所以启停由 `ContainerSessionHost` 统一串行。
 *
 * 已知边界（任务文档 §5 / §9）：
 * - master 用 `FileDescriptor` 直读直写（不套缓冲，避免压住 `0x03` 与 ESC）；
 * - 回滚缓冲 / 鼠标 / 组合字符不做；宽字符按两列处理；
 * - JNI 通路只能真机验（V4），本类不提供假 native 替身。
 */
class PtySession(
    private val manager: RootfsManager,
    private val mounts: () -> List<Mount>,
    /** 只借用它的启动三元组构造，不改变它的会话状态。 */
    private val session: ContainerSession,
    private val resolveChannel: suspend () -> SessionChannel,
) {
    private val lifecycleLock = Mutex()
    private val parserLock = Any()
    private val writeLock = Any()
    private val closeLock = Any()
    private val parser = TerminalParser(DEFAULT_ROWS, DEFAULT_COLS)

    private val _state = MutableStateFlow<PtyState>(PtyState.Stopped)
    val state: StateFlow<PtyState> = _state.asStateFlow()

    private val _screen = MutableStateFlow(TerminalSnapshot.empty(DEFAULT_ROWS, DEFAULT_COLS))
    val screen: StateFlow<TerminalSnapshot> = _screen.asStateFlow()

    @Volatile
    private var channel: SessionChannel = SessionChannel.UNAVAILABLE

    /** 当前通道（面板显示用）；未启动 / 失败时为 [SessionChannel.UNAVAILABLE]。 */
    val activeChannel: SessionChannel get() = channel

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private var gridRows: Int = DEFAULT_ROWS
    private var gridCols: Int = DEFAULT_COLS

    private var firstSignal: CompletableDeferred<Int?>? = null

    // ---- 生命周期 ------------------------------------------------------------

    suspend fun start(rows: Int = gridRows, cols: Int = gridCols) = lifecycleLock.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value is PtyState.Running || _state.value is PtyState.Starting) return@withContext
            if (!manager.isReady) {
                fail("rootfs 未就绪，PTY 终端不可用。")
                return@withContext
            }
            val resolved = runCatching { resolveChannel() }.getOrElse { SessionChannel.UNAVAILABLE }
            if (resolved == SessionChannel.UNAVAILABLE) {
                fail("当前执行通道不可用：proot 未就绪。")
                return@withContext
            }
            val list = runCatching { mounts() }.getOrElse {
                fail("无法确定要挂载的项目：${it.message}")
                return@withContext
            }
            // 启动三元组复用行式会话的构造（D2-6）：不在这里另拼 argv / env。
            val launch = session.launchFactory.proot(list, pty = true).getOrElse {
                fail("无法构造 proot 启动参数：${it.message}")
                return@withContext
            }

            _state.value = PtyState.Starting
            gridRows = rows.coerceIn(1, MAX_DIMENSION)
            gridCols = cols.coerceIn(1, MAX_DIMENSION)
            // 重启会复用同一个实例：不清网格，上一轮 vim / top 的画面会与新 shell 的提示符
            // 叠在一起（真机 U59 §12.6）。先清屏再定尺寸，重放时才是一张白纸。
            synchronized(parserLock) {
                parser.reset()
                parser.resize(gridRows, gridCols)
            }
            publish()

            val master = runCatching { PtyNative.open(gridRows, gridCols) }.getOrElse {
                fail("PTY native 不可用：${it.message}")
                return@withContext
            }
            if (master < 0) {
                fail("openpty 失败（-1）：容器 /dev/pts 可能没挂上，或宿主 /dev/ptmx 不可用。")
                return@withContext
            }
            val env = launch.env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
            val pid = runCatching {
                PtyNative.spawn(master, launch.path, launch.argv.toTypedArray(), env, gridRows, gridCols)
            }.getOrElse {
                runCatching { PtyNative.close(master) }
                fail("PTY 启动失败：${it.message}")
                return@withContext
            }
            if (pid <= 0) {
                runCatching { PtyNative.close(master) }
                fail("PTY 启动失败（fork/execve 前置检查失败）。")
                return@withContext
            }
            val fd = runCatching { descriptorOf(master) }.getOrElse {
                runCatching { PtyNative.close(master) }
                fail("PTY fd 包装失败：${it.message}")
                return@withContext
            }

            masterFd = master
            childPid = pid
            output = FileOutputStream(fd)
            firstSignal = CompletableDeferred()
            startReader(fd, pid)
            channel = resolved

            // 启动闸：execve 失败会立刻以 127 退出，不能在那种情况下把状态写成 Running。
            withTimeoutOrNull(START_GRACE_MS) { firstSignal?.await() }
            if (_state.value is PtyState.Starting) _state.value = PtyState.Running
        }
    }

    suspend fun stop() = lifecycleLock.withLock {
        withContext(Dispatchers.IO) { stopInternal() }
    }

    /** 最后一次布局尺寸（重启时沿用；默认 24×80）。 */
    fun currentRows(): Int = gridRows
    fun currentCols(): Int = gridCols

    /** 诊断 / 真机冒烟用：当前子进程 pid（-1 = 未运行）。 */
    internal fun currentChildPid(): Int = childPid

    // ---- 输入 / 尺寸 --------------------------------------------------------

    /** 写原始字节（Ctrl-C = `0x03`、ESC 序列、粘贴内容都由调用方编码）。 */
    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            runCatching {
                output?.write(bytes)
                output?.flush()
            }
        }
    }

    suspend fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /**
     * 面板尺寸变化 → 网格重建（保留内容）+ `TIOCSWINSZ`（子进程收 SIGWINCH）。
     * 调用方负责去抖（Compose 尺寸回调里 100ms 一次），本函数不做节流。
     */
    fun resize(rows: Int, cols: Int) {
        val r = rows.coerceIn(1, MAX_DIMENSION)
        val c = cols.coerceIn(1, MAX_DIMENSION)
        if (r == gridRows && c == gridCols) return
        gridRows = r
        gridCols = c
        synchronized(parserLock) { parser.resize(r, c) }
        publish()
        val master = masterFd
        if (master >= 0) runCatching { PtyNative.resize(master, r, c) }
    }

    // ---- 读循环 --------------------------------------------------------------

    private fun startReader(fd: FileDescriptor, pid: Int) {
        reader = Thread {
            val input = runCatching { FileInputStream(fd) }.getOrNull()
            if (input == null) {
                handleExit(readExitCode(pid))
                return@Thread
            }
            val buf = ByteArray(READ_BUFFER)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(parserLock) { parser.feed(buf.copyOf(n)) }
                    publish()
                    firstSignal?.complete(null)
                }
            } catch (_: Exception) {
                // 关闭 master 会让读线程以 IOException 结束；EOF 与错误都走同一条收尾路径。
            }
            handleExit(readExitCode(pid))
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** EOF 收尾：给出退出态、通知启动闸、回收子进程（不先 kill，先让 SIGHUP 生效）。 */
    private fun handleExit(code: Int?) {
        val cur = _state.value
        if (cur is PtyState.Running || cur is PtyState.Starting) {
            _state.value = exitState(code)
        }
        if (firstSignal?.isCompleted == false) firstSignal?.complete(code)
        closeNative()
    }

    private fun exitState(code: Int?): PtyState = when (code) {
        127 -> PtyState.Error("启动命令失败（退出码 127）：execve 失败（路径或环境不对）。")
        null -> PtyState.Exited(null)
        else -> PtyState.Exited(code)
    }

    private fun stopInternal() {
        closeNative()
        reader?.join(READER_JOIN_MS)
        reader = null
        output = null
        firstSignal = CompletableDeferred<Int?>().also { it.complete(null) }
        _state.value = PtyState.Stopped
    }

    /** 幂等且串行：stop() 与 EOF 两条路径都可能走到这里，fd 只能关一次。 */
    private fun closeNative() {
        synchronized(closeLock) {
            val master = masterFd
            if (master < 0) return
            masterFd = -1
            childPid = -1
            runCatching { PtyNative.close(master) }
        }
    }

    // ---- 辅助 ----------------------------------------------------------------

    private fun fail(message: String) {
        _state.value = PtyState.Error(message)
        channel = SessionChannel.UNAVAILABLE
    }

    private fun publish() {
        _screen.value = synchronized(parserLock) { parser.snapshot() }
    }

    /**
     * `/proc/<pid>/stat` 第 52 字段 = exit_code（仅在僵尸态有意义）。
     * 注意它是 **wait status 原始值**（正常退出 `7` 存成 `1792` = `7 << 8`），要解码。
     * native close 会 waitpid 回收，所以必须在 close 之前取；子进程刚退出时短暂等它变 Z。
     */
    private fun readExitCode(pid: Int): Int? {
        if (pid <= 0) return null
        repeat(10) {
            val text = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
            val fields = text.substringAfterLast(')').trim().split(' ')
            if (fields.firstOrNull() == "Z") {
                return fields.getOrNull(EXIT_CODE_FIELD)?.toIntOrNull()?.let(::decodeWaitStatus)
            }
            Thread.sleep(20)
        }
        return null
    }

    /** wait status → 退出码：低 7 位是信号（0 = 正常退出，高 8 位是退出码）。 */
    private fun decodeWaitStatus(raw: Int): Int {
        val signal = raw and 0x7F
        return if (signal == 0) (raw shr 8) and 0xFF else 128 + signal
    }

    /** fd 直读直写需要 FileDescriptor；Android 没有公开的「把 int 变成 fd」构造，用反射（失败即降级）。 */
    private fun descriptorOf(raw: Int): FileDescriptor {
        val fd = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fd, raw)
        return fd
    }

    companion object {
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_COLS = 80
        private const val MAX_DIMENSION = 512
        private const val READ_BUFFER = 4096
        private const val READER_JOIN_MS = 1_000L

        /** 启动闸：这段时间内既没输出也没退出，就按「已运行」处理（交互 shell 可能慢）。 */
        private const val START_GRACE_MS = 300L

        /** `/proc/<pid>/stat` 去掉 `pid (comm)` 后按空格切分，exit_code 的下标。 */
        private const val EXIT_CODE_FIELD = 49
    }
}
