package dev.kair.app.core.proot

import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** 会话用哪条执行通道。chroot 档已由 U78 删除，只剩 proot（ADR 0007）。 */
enum class SessionChannel {
    PROOT,

    /** proot 不可用（rootfs 未就绪或 proot 二进制缺失）。 */
    UNAVAILABLE,
}

/** 常驻会话的状态。与 [RootfsManager.State] 同风格：界面用 `when` 分支即可渲染。 */
sealed interface SessionState {
    data object Stopped : SessionState
    data object Starting : SessionState
    data object Running : SessionState
    data object Stopping : SessionState

    /** 启动失败或进程意外退出；[message] 是给用户看的原因。 */
    data class Error(val message: String) : SessionState
}

/** 会话里一条命令的结果。[exitCode] 为 null 表示命令没跑完（会话被停止 / 进程退出）。 */
data class SessionResult(val output: String, val exitCode: Int?) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * 常驻容器会话：一条**长跑**的容器进程 —— 挂载一次、一直挂着，宿主经它的 stdin 下发命令、
 * 从 stdout 读结果 —— 相当于给容器开了一个「终端会话」。
 *
 * 挂的是**工作区父目录**（容器内 `/projects`），不是「当前项目」：终端存在的理由是让端口进程常驻，
 * 而容器没有 `unshare` / `nsenter`，「每项目一条会话」在本机做不到
 * （取证见 `docs/review/2026-10-28-共享终端取证.md`），所以切换项目不重启会话。
 * 项目是 `/projects` 下的真实子目录 —— 新建 / 改名 / 删除都不需要重新挂载。
 *
 * 与一次性执行（[ProotCommandRunner]）的差别，正是「服务能不能常驻」：
 * - 一次性：命令结束即按 `setsid` 组收掉全部后代（挂载虽常驻，进程仍会被收）→
 *   容器里起的服务活不下来；`nohup server &` 只会让那条命令一直挂到超时被看门狗杀掉。
 * - 会话：进程不退出 = 后代不被收 = 容器里的 `python -m http.server &` 之类**一直活着**；
 *   容器与 App 同处一个网络命名空间（无 `unshare -n`），因此容器内监听
 *   `127.0.0.1:<port>` 就是全设备可见，安卓浏览器可直接访问。
 *
 * 帧协议（宿主 ↔ 容器内循环脚本，见 [entryScript]）：
 * ```
 * 宿主 → 容器   一行命令；或 __KAIR_STOP__ 表示结束会话
 * 容器 → 宿主   __KAIR_READY__        挂载完成，会话可用
 *               __KAIR_RC_<code>__   上一条命令已结束，退出码 <code>
 * ```
 * 命令自身打到 stdout 的内容会**直接混进同一条流**（就像真终端），所以面板看到的是连续
 * 日志；`__KAIR_RC_` 前缀足够独特，误判概率可忽略。
 *
 * 生命周期：
 * - [start]：挂载由 proot 的 `-b` 参数在启动时一次给定（进程内模拟绑定），不产生宿主挂载。
 * - [stop]：写 `__KAIR_STOP__` 尽力让脚本走正常退出路径，短等后 [killTree] 收掉 proot
 *   与容器内全部后代（含常驻服务）。
 *
 * 已知限制（v1，写在这里而不是留给使用者踩）：
 * - **单条命令没有超时 / 取消**：会话就是「终端」，前台命令卡住时整个会话在等它（真终端有
 *   Ctrl-C，这里没有）。要中断只有 [stop]。
 * - 命令读不到 stdin：每条命令经 `</dev/null` 执行，避免吃掉宿主的命令流。
 * - proot 的 `-b` 是模拟绑定、`--kill-on-exit` 自收 tracee：本会话不产生宿主挂载。
 *
 * rootfs 未就绪 / proot 不可用时**不**回退本地 shell：会话必须真的在容器里，落不到就
 * 如实报错，而不是悄悄变成一个「Android 本机 shell」骗过使用者。
 *
 * 路径全部由装配层注入，本类不感知 Android Context（core 不依赖 android.*）。
 */
class ContainerSession(
    private val manager: RootfsManager,
    /**
     * 本次会话挂进容器的宿主目录清单（容器内路径 → 宿主路径）。
     * 常驻终端与 agent 的 `container_shell` 是**同一份**：宿主工作区父目录 → `/projects`
     * （`Mount.kt` 的 [workspaceMounts]）。项目是它下面的真实子目录，所以会话运行期间
     * 新建 / 改名 / 删除项目都不需要重新挂载。
     *
     * 启动时取一次，运行中不变。
     */
    private val mounts: () -> List<Mount>,
    private val proot: ProotCommandRunner,
    /** 解析本次会话用的通道（proot 就绪 / 不可用）；须在 IO 线程调用。 */
    private val resolveChannel: suspend () -> SessionChannel,
    /**
     * 进程工厂缝（U44）：**只**换来「谁来起这个进程」，启动三元组仍由 [SessionLaunchFactory] 决定。
     *
     * 存在的理由是启动期生命周期（READY / 提前断流 / 旧代迟到回调）必须能确定性驱动 ——
     * 真 su / proot 起不来也杀不掉，测不了「READY 之前就 EOF」。生产一律走默认实现。
     *
     * 参数是 `(argv, env)` 而不是 internal 的 `SessionLaunch`：公开构造函数不能暴露 internal 类型。
     */
    private val spawnProcess: (List<String>, Map<String, String>) -> Process = { argv, env ->
        ProcessBuilder(argv)
            .directory(manager.rootfsDir.parentFile)
            .apply {
                environment().clear()
                environment().putAll(env)
            }
            .redirectErrorStream(true)
            .start()
    },
) {

    private val lifecycleLock = Mutex()
    private val execLock = Mutex()
    private val writeLock = Any()

    /** 保护「检查 + 迁移」这一对动作：启动发布 Running 与读线程写 Error 必须互斥。 */
    private val stateLock = Any()

    private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** 面板可见的输出转录（裁剪 / 清空 / 取尾巴都在里面）。 */
    private val transcript = SessionTranscript(LOG_LIMIT)
    val log: StateFlow<String> = transcript.log

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 「上一条命令超时未回帧」的可观察标记（U60 兜底）。
     *
     * 超时**不**撤销队列条目（撤销会让下一条拿到别人的退出帧），所以 [busy] 会继续保持为真；
     * 面板据本标记提示「会话可能已卡住，可重启」，并在重启 / 队列清空后复位。
     */
    private val _suspectStalled = MutableStateFlow(false)
    val suspectStalled: StateFlow<Boolean> = _suspectStalled.asStateFlow()

    /** 「命令 ↔ 退出帧」的配对队列：一条命令一个条目，退出帧结算队首；细节见 [ExecFrameQueue]。 */
    private val execQueue = ExecFrameQueue(LOG_LIMIT)

    private var process: Process? = null
    private var reader: Thread? = null

    @Volatile
    private var channel: SessionChannel = SessionChannel.UNAVAILABLE

    /** 当前会话使用的执行通道（面板展示用）；未启动 / 已停止时为 [SessionChannel.UNAVAILABLE]。 */
    val activeChannel: SessionChannel get() = channel

    /** 本次启动时挂入容器的清单；停止后为空。 */
    @Volatile
    private var activeMounts: List<Mount> = emptyList()

    /** 会话当前挂载的清单（后台任务解析容器路径用）；未运行为空。 */
    val mountedMounts: List<Mount> get() = activeMounts

    // ---- U60：后台任务脱离启动所需的通道句柄（同模块 internal，不扩公开面）---------

    internal val prootRunner: ProotCommandRunner get() = proot

    /** 启动闸门：READY、启动期断流、启动代次都收敛在这里（U44，见 [SessionStartGate]）。 */
    private val startGate = SessionStartGate(START_TIMEOUT_SECONDS * 1000)

    /** 清空日志（只清界面可见的转录，不影响会话本身）。 */
    fun clearLog() = transcript.clear()

    suspend fun start() = lifecycleLock.withLock {
        withContext(Dispatchers.IO) {
            if (_state.value is SessionState.Running || _state.value is SessionState.Starting) return@withContext
            // 换代与置 Starting 同一临界区：否则上一次会话的读线程会在「已置 Starting、尚未换代」的窗口里迟到 EOF 写 Error（B 审 V6）。
            val gen = synchronized(stateLock) { startGate.begin().also { _state.value = SessionState.Starting } }
            clearLog()
            if (!manager.isReady) {
                _state.value = SessionState.Error("rootfs 未就绪，先到「Linux 工作区」面板安装。")
                return@withContext
            }
            val ch = runCatching { resolveChannel() }.getOrElse { SessionChannel.UNAVAILABLE }
            if (ch == SessionChannel.UNAVAILABLE) {
                _state.value = SessionState.Error("当前执行通道不可用：proot 未就绪。")
                return@withContext
            }
            channel = ch

            // 会话级音频（审阅回执二 §3）：`.asoundrc` 与自查脚本属于整个容器，不归桌面。 宿主侧纯文件写入；失败不阻塞会话（终端照常可用，只是没声音）。
            runCatching { installSessionAudio(manager.rootfsDir) }

            // 容器内入口脚本：宿主写进 rootfs 的 /tmp 下，按同一路径执行，
            // 零 shell 转义问题（与一次性命令的 /tmp/.kair-cmd 同一套路）。
            val list = runCatching { mounts() }.getOrElse {
                _state.value = SessionState.Error("无法确定要挂载的项目：${it.message}")
                return@withContext
            }
            val entry = File(manager.rootfsDir, RootCommandRunner.SESSION_ENTRY.removePrefix("/"))
            runCatching {
                entry.parentFile?.mkdirs()
                entry.writeText(entryScript())
            }.onFailure {
                _state.value = SessionState.Error("无法写入会话脚本：${it.message}")
                return@withContext
            }

            val proc = processLauncher.start(list) ?: return@withContext
            process = proc
            // 挂载清单只表示「本次会话真的起来了」：起进程失败的那些出口一个都没赋值，不留残留。
            activeMounts = list
            startReader(proc, gen)

            when (val outcome = startGate.await(gen)) {
                StartOutcome.Ready -> {
                    // READY 之后紧跟 EOF 的竞态：读线程可能已把状态写成 Error。复核与写入**必须同一把锁**，
                    // 否则「读到 Starting →（读线程写 Error）→ 写 Running」会把终态改回运行中（Linux 上实测）。
                    val entered = synchronized(stateLock) {
                        if (_state.value !is SessionState.Starting) return@synchronized false
                        _state.value = SessionState.Running
                        true
                    }
                    if (!entered) return@withContext
                    // 一行启动提示：项目从哪里进、宿主根目录是哪个 —— 面板上不报，用户就以为终端是空的。
                    appendLog("共享终端：项目在 $PROJECTS_ROOT 下（${list.firstOrNull()?.hostPath?.absolutePath ?: "未挂载"}）\n")
                    _suspectStalled.value = false
                    ensureSessionBase(manager.rootfsDir, activeMounts, proot)
                }

                // 启动期断流：读线程已按退出原因置 Error；这里只释放进程与待决命令，**绝不**发布 Running。
                is StartOutcome.Closed -> {
                    releaseProcess()
                    if (_state.value is SessionState.Starting) _state.value = SessionState.Error(outcome.reason)
                }

                // 超时：进程还活着就收掉并报错；已退出说明结论还在路上，别拿「超时」盖掉更具体的原因。
                null -> if (proc.isAlive) {
                    stopInternal()
                    _state.value = SessionState.Error(
                        "会话启动超时（未收到就绪标记）：\n" + tailOfLog(),
                    )
                } else {
                    releaseProcess()
                    if (_state.value is SessionState.Starting) {
                        _state.value = SessionState.Error("会话启动未完成就退出了。\n" + tailOfLog())
                    }
                }
            }
        }
    }

    suspend fun stop() = lifecycleLock.withLock {
        stopInternal()
    }

    /**
     * 在会话里跑一条命令，挂起到收到自己的退出帧或 [EXEC_TIMEOUT_MS] 上界。
     *
     * 超时后**不撤销队列条目**：命令已经写进 shell，它的退出帧终将结算它；`abandon` 的语义
     * 是「命令没进 shell」，在这里用它会让下一条命令拿到别人的退出码与输出。超时只做三件事：
     * 置 [suspectStalled]、在转录里留一行提示、把超时结果返回给调用方（不抛穿）。
     * 恢复路径只有 [stop] → [start]（面板的「重启会话」）。
     *
     * 调用方被取消同样不破坏配对：条目留在 [execQueue] 里吃掉自己的帧。
     */
    suspend fun exec(command: String): SessionResult = execLock.withLock {
        val proc = process
        if (_state.value !is SessionState.Running || proc == null) {
            return@withLock SessionResult("会话未在运行。", null)
        }
        val lines = commandLines(command)
        if (lines.isEmpty()) return@withLock SessionResult("", 0)
        // 一条命令一个退出帧是配对的前提：多行会让容器打出 N 个帧，多余的帧会结算别人的命令。
        if (lines.size > 1) return@withLock SessionResult("一条命令只能一行，请逐条发送。", null)
        val line = lines.single()

        val entry = execQueue.enqueue()
        // 转录里回显一行，让日志读起来像终端；命令输出紧随其后（顺序由这里保证）。
        appendLog("$ ${PROJECTS_ROOT}> $line\n")
        _busy.value = true
        try {
            synchronized(writeLock) {
                proc.outputStream.write((line + "\n").toByteArray())
                proc.outputStream.flush()
            }
        } catch (e: Throwable) {
            // 命令没写成（或写一半就炸）：条目必须撤销，否则它会吃掉下一条的帧。
            execQueue.abandon(entry)
            _busy.value = execQueue.isBusy()
            return@withLock SessionResult("写入会话失败：${e.message}", null)
        }
        val result = withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            // 必须先等帧再取输出：outputOf 是快照，写进构造参数会在命令刚下发时就求值（拿到空串）。
            val code = entry.done.await()
            SessionResult(execQueue.outputOf(entry).trimEnd(), code)
        }
        _busy.value = execQueue.isBusy()
        if (result == null) {
            _suspectStalled.value = true
            appendLog("[kair] 命令超过 ${EXEC_TIMEOUT_MS / 1000}s 未返回退出帧；会话可能已卡住，请重启会话。\n")
            return@withLock SessionResult(
                "命令超时：${EXEC_TIMEOUT_MS / 1000}s 内没有收到退出帧。会话可能已卡住，请重启会话。",
                null,
            )
        }
        result
    }

    // ---- 启动 ------------------------------------------------------------------

    /** 启动三元组构造（PTY 通道复用同一份，见 D2-6）。 */
    internal val launchFactory = SessionLaunchFactory(proot, ::scriptDir)

    /** 起进程的活交给它；失败文案留在本类（内核中文文本的登记点，见 DocsConsistencyTest）。 */
    private val processLauncher = SessionProcessLauncher(launchFactory, spawnProcess) { label, path, detail ->
        _state.value = SessionState.Error(
            if (path == null) detail ?: "无法启动会话。" else "无法启动 $label（$path）：${detail ?: "未知原因"}",
        )
    }

    /** rootfs 同级 tmp 目录：提权脚本与 proot 临时文件都落这里。 */
    private fun scriptDir(): File =
        manager.rootfsDir.parentFile!!.resolve("tmp").apply { mkdirs() }

    // ---- 停止 ------------------------------------------------------------------

    /** 真正的停止流程；由 [stop] / 启动失败路径共用，**不得**在持有 [lifecycleLock] 外调用。 */
    private suspend fun stopInternal() = withContext(Dispatchers.IO) {
        val proc = process
        if (proc == null) {
            _state.value = SessionState.Stopped
            return@withContext
        }
        _state.value = SessionState.Stopping
        // 1) 尽力让容器内循环脚本走正常退出路径（挂载不卸，跨会话常驻）。
        runCatching {
            synchronized(writeLock) {
                proc.outputStream.write("$STOP_TOKEN\n".toByteArray())
                proc.outputStream.flush()
            }
        }
        // 2) 短等：正常路径下 proot 会收尾。常驻服务还活着时等不到就走强杀。
        val exited = runCatching { proc.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
        // 3) 强杀整棵进程树：proot + 容器内全部后代（含常驻服务）。
        //    proot 侧 `--kill-on-exit` 会连 tracee 一起收掉。
        if (!exited) proc.killTree()
        reader?.join(1000)
        releaseProcess()
        _state.value = SessionState.Stopped
    }

    /** 释放本次启动占用的进程句柄与待决命令，**不改状态**（由调用方决定置 Stopped 还是 Error）。 */
    private fun releaseProcess() {
        process = null
        reader = null
        activeMounts = emptyList()
        execQueue.completeAll(null)
        _busy.value = false
        _suspectStalled.value = false
    }

    // ---- 输出 ------------------------------------------------------------------

    /**
     * 收输出：整条流按行切分，命中帧的行**只**驱动状态机、不进日志（保持转录干净），
     * 其余行同时进日志（面板转录）与当前命令缓冲（[exec] 的返回值）。
     */
    private fun startReader(proc: Process, gen: Long) {
        reader = Thread {
            val line = StringBuilder()
            try {
                proc.inputStream.bufferedReader().use { br ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = br.read(buf)
                        if (n < 0) break
                        for (i in 0 until n) {
                            val c = buf[i]
                            if (c == '\n') {
                                handleLine(line.toString(), gen)
                                line.setLength(0)
                            } else {
                                line.append(c)
                            }
                        }
                    }
                }
                if (line.isNotEmpty()) handleLine(line.toString(), gen)
            } catch (_: Exception) {
                // 管道断裂（进程被杀）属正常路径，已收到的输出为准
            } finally {
                onStreamClosed(gen)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleLine(text: String, gen: Long) {
        if (!startGate.isCurrent(gen)) return // 旧代残留输出：不进转录，也不结算新会话的队列
        when (val f = frame(text)) {
            is Frame.Ready -> startGate.ready(gen)
            is Frame.Exit -> {
                execQueue.completeHead(f.code)
                _busy.value = execQueue.isBusy()
                // 卡住的那条终于结算了（或队列已清空）：疑似卡住标记复位。
                if (!_busy.value) _suspectStalled.value = false
            }
            null -> {
                appendLog(text + "\n")
                // 输出行归给正在跑的那条命令（队首）；没有命令在跑时只进转录。
                execQueue.appendOutput(text)
            }
        }
    }

    private fun onStreamClosed(gen: Long) {
        if (!startGate.isCurrent(gen)) return // 旧代迟到的 EOF：不得改新会话的状态
        // 播视频 3~4s 后「会话已退出」而 sender 还在发包：须分清 proot 真死还是管道先断。
        System.err.println("[kair-session] eof gen=$gen live=${process?.isAlive} exit=${runCatching { process?.exitValue() }.getOrNull()}")
        val reason = "会话进程已退出。可能是 rootfs / su 异常，或容器内进程被系统杀掉。\n" + tailOfLog()
        // 与 start() 发布 Running 互斥：谁先落锁谁决定终态（竞态说明见 start() 的 Ready 分支）。
        // 代次要**在锁内复核**：外面那次只是快速丢弃，换代恰好落在两次检查之间时靠这一行兜住。
        synchronized(stateLock) {
            if (!startGate.isCurrent(gen)) return
            val cur = _state.value
            if (cur is SessionState.Running || cur is SessionState.Starting) _state.value = SessionState.Error(reason)
            releaseProcess() // 释放也必须在锁内：出去再释放时新启动可能已换代起好进程，那一行就会清掉新会话
        }
        startGate.closed(gen) { reason } // 启动期断流：让 start() 拿到 Closed 而不是「已就绪」，绝不发布 Running
    }

    private fun appendLog(text: String) = transcript.append(text)

    /** 宿主侧一句提示（音频接收端启动失败等）写进同一份转录：用户与 agent 都能看见。 */
    internal fun noteHostMessage(text: String) = appendLog(text)

    /** 出错时给一段日志尾巴，帮助使用者判断到底卡在哪一步（如 `mount ... failed`）。 */
    private fun tailOfLog(): String = transcript.tail(600)

    /** 帧类型。[Ready] 挂载就绪；[Exit] 一条命令结束。 */
    internal sealed interface Frame {
        data object Ready : Frame
        data class Exit(val code: Int) : Frame
    }

    companion object {
        /** 就绪帧：挂载 + proot 启动成功，可以下命令了。 */
        internal const val READY_TOKEN = "__KAIR_READY__"

        /** 停止帧：让容器内循环脚本正常退出。 */
        internal const val STOP_TOKEN = "__KAIR_STOP__"

        /** 命令结束帧的前后缀：`__KAIR_RC_<code>__`。 */
        internal const val RC_PREFIX = "__KAIR_RC_"
        internal const val RC_SUFFIX = "__"

        /** 转录上限（字符）：超出按行裁剪。 */
        internal const val LOG_LIMIT = 120_000

        private const val START_TIMEOUT_SECONDS = 30L
        private const val GRACEFUL_STOP_SECONDS = 3L

        /**
         * 单条命令的上界（毫秒）。
         *
         * 取 120s：与 `SseIdleTimeoutMs` 同量级（那里已论证过「链路卡住」与「正常但慢」的
         * 分界），短了会误杀终端里正常的长命令（`apt-get install` / 编译），长了卡死窗口太久。
         * 这**不是**命令本身的超时（`container_shell` 工具走一次性进程，另有 timeout）；它只回答
         * 「会话 shell 还活着吗」。超时不撤队列条目，见 [exec]。
         */
        internal const val EXEC_TIMEOUT_MS = 120_000L

        /** 解析一行输出是否为帧；普通输出返回 null（实现见 [parseSessionFrame]）。 */
        internal fun frame(line: String): Frame? = parseSessionFrame(line)

        /** 容器内循环脚本；实现见 [sessionEntryScript]。 */
        internal fun entryScript(): String = sessionEntryScript()
    }
}
