package dev.kair.app.core.desktop

import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.ContainerSessionHost
import dev.kair.app.core.proot.Mount
import dev.kair.app.core.proot.ProotCommandRunner
import dev.kair.app.core.proot.SessionState
import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** 桌面通道对界面的状态面。 */
internal sealed interface DesktopState {
    /** [installed] = 探测到桌面包已全装：面板应显示「启动桌面」而不是「安装并启动」。 */
    data class Idle(val installed: Boolean = false) : DesktopState

    /**
     * 正在探测桌面包是否已装（面板刚进入的瞬间）。
     *
     * 没有这个态就只能靠 [Idle] 的默认值顶一下 —— 探测回来之前 UI 已按「未安装」画完，
     * 于是先闪一下「安装并启动」再跳成「启动桌面」（真机反馈 2026-11-14）。
     * 它只在「未知」时出现：已装（Idle.installed=true）不重进探测，避免反复闪。
     */
    data object Checking : DesktopState

    /** 正在下载依赖：[fetchedBytes] 是 apt 缓存与索引目录的当前字节数（安装进度的来源）。 */
    data class Installing(val fetchedBytes: Long) : DesktopState
    data object Starting : DesktopState

    /**
     * 容器会话未就绪（不存在或不在 [SessionState.Running]）。
     *
     * 与 [DesktopRunner] 的启动守卫同源（都读 `ContainerSessionHost.sessionReady`）：
     * 面板据此直接给中文引导而不是摆一个点下去必失败的「启动桌面」。
     */
    data object NoSession : DesktopState
    data class Running(val width: Int, val height: Int) : DesktopState
    data class Error(val message: String) : DesktopState

    /**
     * 容器会话中断导致桌面被收掉。带的是会话状态名（英文枚举），
     * 文案由 UI 层拼 —— core 不持有面向用户的中文（AGENTS.md §4）。
     */
    data class SessionLost(val sessionState: String) : DesktopState
}

/**
 * 图形桌面的容器侧持有者（U86 §3.2 / §4 E）：与终端 PTY **并列**的长驻通道，
 * 生命周期归容器会话层，**不被面板显隐驱动** —— 面板销毁只断开画面视图，桌面照跑；
 * 唯一停桌面的入口是显式 [stop]（任务书 §3.1 的五条离开路径一条都不停）。
 *
 * 归属（AGENTS 10.5）：应用级容器能力，随内置插件实例创建；
 * 生命周期：容器会话 Running 才能 [start]，会话停止时自动 [stop]（rootfs 与挂载都没了）；
 * 清除：[stop] / [removeDesktopEnvironment]；并发：[lock] 串行启停。
 *
 * 画面：App 直接读 Xvfb `-fbdir` 的帧缓冲；输入：App 写容器内常驻的 `xdotool -`
 * （2026-11-14 起 x11vnc 与 RFB 客户端已删除）。
 */
internal class DesktopRunner(
    private val sessionHost: ContainerSessionHost,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<DesktopState>(DesktopState.Idle())
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    private val lock = kotlinx.coroutines.sync.Mutex()
    private var process: Process? = null
    private var sessionWatch: Job? = null
    private val outputTail = StringBuilder()

    /** 输入通道（容器内常驻 `xdotool -`）—— 管道本身在 `DesktopInputChannel`。 */
    private val input = DesktopInputChannel(scope) { message ->
        _state.value = DesktopState.Error(message)
    }

    /**
     * 最近一次 [start] 收到的**屏幕像素尺寸**。
     *
     * [restart] 复用它，不收参数 —— 曾经 `restart(srcW, srcH)` 收到的是帧缓冲尺寸
     * （1544×720），`desktopFramebufferSize` 又缩一次 2/3 → 1029×480，再重启继续缩
     * （真机 2026-11-14：分辨率在两个值之间跳）。尺寸只在 start 收一次，重启一律复用。
     */
    private var screenSize: Pair<Int, Int>? = null

    /** 最近一次选定的输出分辨率档位；[restart] 与换档重启都复用它（U89 §F）。 */
    private var resolution = DesktopResolution.DEFAULT

    /** 启动桌面：探测依赖 →（缺则全量安装）→ 写脚本 → 起进程 → 等帧缓冲就绪。 */
    fun start(
        screenWidthPx: Int,
        screenHeightPx: Int,
        resolution: DesktopResolution = DesktopResolution.DEFAULT,
    ) {
        screenSize = screenWidthPx to screenHeightPx
        this.resolution = resolution
        scope.launch { startInternal(screenWidthPx, screenHeightPx, resolution) }
    }

    /** 显式停止：killTree 连 Xvfb / jwm 一起收（proot `--kill-on-exit` 兜底）。 */
    fun stop() {
        scope.launch { stopInternal() }
    }

    /**
     * 停止后重新启动（胶囊菜单「重启桌面」）。屏幕尺寸与分辨率档位复用 [start] 的那次 ——
     * **不收尺寸参数**：调用方手里只有帧缓冲尺寸，传进来就会再缩一次 2/3（真机 1544→1029）。
     */
    fun restart() {
        val (w, h) = screenSize ?: return // 没 start 过就没有可复用的尺寸（菜单只在 Running 时出现，防御）
        scope.launch {
            stopInternal()
            startInternal(w, h, resolution)
        }
    }

    /**
     * 切换输出分辨率档位（U89 §F）：Xvfb 的 screen 只能在启动时给，所以换档 = 停 → 起。
     *
     * 屏幕像素尺寸复用 [start] 那次；还没启动过就只记下档位，下次 [start] 生效。
     * 这是**会重启桌面**的动作（正在运行的程序退出），UI 必须在选择前说明。
     */
    fun setResolution(resolution: DesktopResolution) {
        if (this.resolution == resolution) return
        this.resolution = resolution
        val (w, h) = screenSize ?: return
        scope.launch {
            stopInternal()
            startInternal(w, h, resolution)
        }
    }

    /**
     * 指针事件（framebuffer 坐标 + RFB mask 语义：1=左、2=右、8=滚轮上、16=滚轮下）。
     * UI 主线程调用；只入队，真正的管道写发生在 IO 协程里。
     */
    fun pointer(x: Int, y: Int, mask: Int) = input.pointer(x, y, mask)

    /** 键盘事件（X keysym）；桌面未运行时静默丢弃。 */
    fun key(keysym: Int, down: Boolean) = input.key(keysym, down)

    /** 非 Latin-1 文本（中文等）；桌面未运行时静默丢弃。 */
    fun text(value: String) = input.text(value)

    /**
     * 面板进入时探测一次桌面包是否已装（真机反馈：装过以后每次进来还是「安装并启动」，
     * 点下去其实不装、直接连上）。只改 [DesktopState.Idle]，探测期间用户点了启动就放弃写回。
     *
     * 2026-11-14：探测期间必须先切到 [DesktopState.Checking]，否则 UI 拿默认值
     * `Idle(installed=false)` 先画一帧「安装并启动」，探测回来再跳成「启动桌面」—— 真实的可见闪烁。
     */
    fun refreshInstallState() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val current = _state.value
                if (current !is DesktopState.Idle || process != null) return@withContext
                // 已经确认装过：重进面板不再探测，否则每次都要闪一下「检测中」。
                if (current.installed) return@withContext
                val session = sessionHost.session.value ?: return@withContext
                if (session.state.value !is SessionState.Running) return@withContext
                _state.value = DesktopState.Checking
                val (exit, _) = runDesktopOneShot(session, session.prootRunner, desktopProbeCommand(DESKTOP_PACKAGES))
                // 探测期间可能已经进入 Starting / Installing，只在自己的检测态写回。
                if (_state.value is DesktopState.Checking) {
                    _state.value = DesktopState.Idle(installed = exit == 0)
                }
            }
        }
    }

    /** 移除桌面环境（apt remove 全量组件）；先停桌面。 */
    fun removeDesktopEnvironment() {
        scope.launch {
            stopInternal()
            if (!sessionHost.sessionReady.value) {
                _state.value = DesktopState.NoSession
                return@launch
            }
            val session = sessionHost.session.value
            if (session == null) {
                _state.value = DesktopState.NoSession
                return@launch
            }
            _state.value = DesktopState.Starting
            val runner = session.prootRunner
            val out = runDesktopOneShot(session, runner, desktopRemoveEnvironmentCommand(DESKTOP_PACKAGES))
            _state.value = if (out.first == 0) {
                DesktopState.Idle(installed = false)
            } else {
                DesktopState.Error("apt remove exit=${out.first}")
            }
        }
    }

    // ---- 启动流程 ----

    private suspend fun startInternal(
        screenWidthPx: Int,
        screenHeightPx: Int,
        resolution: DesktopResolution,
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (process != null) return@withContext // 已在运行：面板重进只重建画面读循环，不重复起桌面
            val session = sessionHost.session.value
            if (!sessionHost.sessionReady.value || session == null) {
                _state.value = DesktopState.NoSession
                return@withContext
            }
            val runner = session.prootRunner
            val missing = probeMissingPackages(session) ?: return@withContext
            if (missing.isNotEmpty()) {
                if (!installPackages(runner, session.mountedMounts, missing)) return@withContext
            }
            val (w, h) = desktopFramebufferSize(screenWidthPx, screenHeightPx, resolution)
            if (launchDesktop(runner, session.mountedMounts, w, h) == null) {
                return@withContext
            }
            if (!awaitReady()) {
                val died = process?.isAlive == false
                killProcess()
                _state.value = DesktopState.Error(
                    if (died) {
                        "desktop process exited before the framebuffer was ready: ${tailOfOutput()}"
                    } else {
                        "shared framebuffer not ready in ${READY_TIMEOUT_MS / 1000}s: ${tailOfOutput()}"
                    },
                )
                return@withContext
            }
            // 输入通道最后起：xdotool 需要 X server 已经就绪。
            if (!input.start(runner, session.mountedMounts)) {
                killProcess()
                return@withContext
            }
            _state.value = DesktopState.Running(w, h)
            watchSession(session)
        }
    }

    /** 逐个 `dpkg-query`；返回缺失清单。走独立一次性进程，不污染共享终端转录。 */
    private suspend fun probeMissingPackages(session: ContainerSession): List<String>? {
        val runner = session.prootRunner
        val missing = mutableListOf<String>()
        for (pkg in DESKTOP_PACKAGES) {
            val (exit, _) = runDesktopOneShot(
                runner,
                session.mountedMounts,
                "dpkg-query -W -f='${'$'}{Status}' $pkg 2>/dev/null | grep -q 'install ok installed'",
            )
            if (exit == -1) {
                _state.value = DesktopState.Error("probe interrupted: $pkg")
                return null
            }
            if (exit != 0) missing.add(pkg)
        }
        return missing
    }

    /**
     * 两阶段 apt：`--download-only` 出字节进度（轮询 cache + lists 的增量），
     * `--no-download` 用包名从缓存离线装（依赖顺序交给 apt，不用 dpkg -i glob）。
     */
    private suspend fun installPackages(runner: ProotCommandRunner, mounts: List<Mount>, missing: List<String>): Boolean {
        // 安装峰值 = 基础 rootfs + apt lists + .deb 缓存 + 解包后的包；设备空间不足时
        // dpkg 会在最后几个大包上报错，且错误尾部只看到「Errors were encountered」。
        // 先预检，给出「需要多少 / 当前多少」，而不是让 apt 装到一半失败。
        desktopSpaceError(runner.rootfsDir.usableSpace)?.let { reason ->
            _state.value = DesktopState.Error(reason)
            return false
        }
        _state.value = DesktopState.Installing(0)
        val cacheDir = File(runner.rootfsDir, APT_CACHE_DIR.removePrefix("/"))
        val listsDir = File(runner.rootfsDir, "var/lib/apt/lists")
        val baseline = directoryBytes(cacheDir) + directoryBytes(listsDir)
        val poll = scope.launch(Dispatchers.IO) {
            while (true) {
                val current = directoryBytes(cacheDir) + directoryBytes(listsDir)
                _state.value = DesktopState.Installing((current - baseline).coerceAtLeast(0))
                delay(PROGRESS_POLL_MS)
            }
        }
        val fetch = runDesktopOneShot(runner, mounts, desktopFetchCommand(missing))
        val added = if (fetch.first == 0) runDesktopOneShot(runner, mounts, desktopAddCommand(missing)) else -1 to ""
        poll.cancel()
        // 完整输出落容器 /tmp，面板只显示尾部；真机排查时 `cat /tmp/kair-apt-install.log` 即可。
        runCatching {
            File(runner.rootfsDir, "tmp/kair-apt-install.log").writeText(
                "=== apt fetch exit=${fetch.first} ===\n${fetch.second}\n" +
                    "=== apt install exit=${added.first} ===\n${added.second}\n",
            )
        }
        if (fetch.first != 0) return failInstall("apt fetch exit=${fetch.first}", fetch.second)
        if (added.first != 0) return failInstall("apt install exit=${added.first}", added.second)
        // 装完清 .deb 缓存（约 91 MB）；lists 保留，开发工具 / 后续 apt 不再重新拉 40 MB 索引。
        runCatching { runDesktopOneShot(runner, mounts, "apt-get clean >/dev/null 2>&1 || true") }
        return true
    }

    /** 安装失败统一出口：面板只放过滤后的错误行（完整输出在容器 `/tmp/kair-apt-install.log`）。 */
    private fun failInstall(label: String, output: String): Boolean {
        _state.value = DesktopState.Error("$label（完整日志 /tmp/kair-apt-install.log）：${aptFailureExcerpt(output)}")
        return false
    }

    /** 写启动脚本与容器侧 setup / 菜单脚本进 rootfs，起一条**独立**的 proot 长跑进程持有桌面。 */
    private fun launchDesktop(runner: ProotCommandRunner, mounts: List<Mount>, w: Int, h: Int): Process? {
        _state.value = DesktopState.Starting
        outputTail.setLength(0)
        val scripts = listOf(
            "usr/local/bin/kair-desktop-setup.sh" to desktopSetupScript(DESKTOP_PACKAGES),
            "usr/local/bin/kair-desktop-menu.sh" to desktopMenuScript(),
            "tmp/$SCRIPT_NAME" to desktopScript(w, h),
        )
        runCatching {
            for ((path, content) in scripts) {
                val file = File(runner.rootfsDir, path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                // 只有 /usr/local/bin 里的两份会被启动脚本按路径直接执行。
                if (path.startsWith("usr/local/bin/")) file.setExecutable(true, false)
            }
        }.onFailure {
            _state.value = DesktopState.Error("cannot write desktop scripts: ${it.message}")
            return null
        }
        val proc = runCatching {
            ProcessBuilder(runner.buildDesktopArgs(mounts, "/tmp/$SCRIPT_NAME"))
                .directory(runner.rootfsDir.parentFile)
                .apply {
                    environment().clear()
                    environment().putAll(runner.buildEnv())
                }
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            _state.value = DesktopState.Error("cannot start desktop process: ${it.message}")
            return null
        }
        process = proc
        drainOutput(proc)
        return proc
    }

    /**
     * 就绪判据：Xvfb 已把完整一帧写进 `/tmp/Xvfb_screen0`（xwd 头 + `bytes_per_line*height`）。
     * 同时要求**本进程还活着** —— 只看文件会在旧进程残留 / 启动即崩时假绿。
     */
    private suspend fun awaitReady(): Boolean {
        val framebuffer = sharedFramebufferFile()
        val xSocket = xSocketFile()
        var waited = 0L
        while (waited < READY_TIMEOUT_MS) {
            val proc = process ?: return false
            if (!proc.isAlive) return false
            // 只看帧缓冲长度会抢跑：Xvfb 用 mmap 落 fb，文件在 server 初始化早期就被
            // ftruncate 到全尺寸，而它真正 listen 要等 llvmpipe/LLVM 加载完（154 MB）。
            // 抢跑的后果是输入通道在 X 还没 listen 时起 xdotool，直接
            // 「Failed creating new xdo instance」（真机 2026-11-14 复现）。
            // socket 出现才代表 :1 能连，两个条件同时满足才算就绪。
            val socketReady = xSocket != null && xSocket.exists()
            if (socketReady && framebuffer != null && isXwdFramebufferReady(framebuffer)) return true
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return false
    }

    /**
     * 共享帧缓冲文件（U89 §F）：Xvfb 唯一画面来源（2026-11-14 起无条件开启）。
     * 容器 `/tmp` 在 proot 下就是 `<rootfs>/tmp`（baseArgs 没有 bind /tmp）。
     */
    internal fun sharedFramebufferFile(): File? {
        val session = sessionHost.session.value ?: return null
        return File(session.prootRunner.rootfsDir, "tmp/$SHARED_FRAMEBUFFER_FILE_NAME")
    }

    /**
     * X server 的 unix socket（`<rootfs>/tmp/.X11-unix/X1`）。
     *
     * Xvfb 在**开始 listen** 时才创建它 —— 这个文件出现才代表 `:1` 能连上。
     * 帧缓冲文件不行：mmap 时就被 ftruncate 到全尺寸，比 listen 早得多（真机 2026-11-14）。
     */
    private fun xSocketFile(): File? {
        val session = sessionHost.session.value ?: return null
        return File(session.prootRunner.rootfsDir, "tmp/.X11-unix/X1")
    }

    /** 容器会话一停，桌面必然跟着停（rootfs 与挂载都没了）—— 这条不属于「离开面板」。 */
    private fun watchSession(session: ContainerSession) {
        sessionWatch?.cancel()
        sessionWatch = scope.launch {
            session.state.collect { s ->
                if (s !is SessionState.Running && process != null) {
                    // 收桌面必须留痕：此前一律置成 Idle(installed=true)，面板看起来
                    // 就是「还没启动桌面」，用户只能看到「桌面闪退」而没有任何解释。
                    // 2026-11-15 真机：Activity 重建 -> killTree -> 会话进程退出 -> 这里收桌面。
                    System.err.println("[kair-desktop] session=$s -> stopping desktop")
                    stopInternal(DesktopState.SessionLost(s.toString()))
                }
            }
        }
    }

    // ---- 停止 ----

    /**
     * 停止桌面。`terminal` 非空表示**不是用户主动停的**（会话中断、重启连带），
     * 此时状态直接用它，否则面板会假装「从未启动」，
     * 用户看到的就是无声无息的「闪退」。
     */
    private suspend fun stopInternal(terminal: DesktopState? = null) = lock.withLock {
        withContext(Dispatchers.IO) {
            sessionWatch?.cancel()
            sessionWatch = null
            killProcess()
            // 能跑起来就说明包装过了；下次进面板直接显示「启动桌面」。
            _state.value = terminal ?: DesktopState.Idle(installed = true)
        }
    }

    private fun killProcess() {
        // 输入进程是独立 proot，不是桌面进程的子进程；必须先单独收掉。
        input.stop()
        val proc = process ?: return
        process = null
        System.err.println("[kair-desktop] killProcess proc=${proc.toString().take(60)}")
        val exited = runCatching { proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) runCatching { proc.killTree() }
    }

    private fun drainOutput(proc: Process) {
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(outputTail) {
                        outputTail.append(line).append('\n')
                        if (outputTail.length > OUTPUT_TAIL_LIMIT) outputTail.deleteRange(0, outputTail.length - OUTPUT_TAIL_LIMIT - 1)
                    }
                }
            } catch (_: Exception) {
                // 管道断裂（进程被杀）属正常路径
            }
        }.apply { isDaemon = true; start() }
    }

    /** 取满 1000 字符：启动脚本出错时会刷一大段，400 字符会把前面的关键报错吃掉。 */
    private fun tailOfOutput(): String = synchronized(outputTail) { outputTail.toString().takeLast(1000) }

    companion object {
        /** 全量桌面组件（Ubuntu 22.04 jammy；jwm/pcmanfm 在 universe）。 */
        internal val DESKTOP_PACKAGES = listOf(
            "xvfb",
            "jwm",
            "xterm",
            "scrot",
            // Alpine 的 mesa-dri-gallium 在 Ubuntu 叫 libgl1-mesa-dri（含 llvmpipe）。
            "libgl1-mesa-dri",
            "pcmanfm",
            // 包名映射：font-wqy-zenhei → fonts-wqy-zenhei，font-dejavu → fonts-dejavu-core。
            "fonts-wqy-zenhei",
            "fonts-dejavu-core",
            "hicolor-icon-theme",
            "adwaita-icon-theme",
            "xdotool",
            // 2026-11-14：dbus + gvfs 只为 PCManFM 的 trash://。没有 session bus 时 libfm 拿不到
            // gvfsd-trash，侧栏只能把 /trash 当普通目录，Move to Trash 会落到界面看不到的
            // ~/.local/share/Trash。装上后「系统回收站」才有还原 / 清空，两个回收站才不混淆。
            "dbus",
            "gvfs",
        )

        internal const val SCRIPT_NAME = "kair-desktop.sh"

        /** 就绪窗要给 Xvfb 冷启动栅栏（最多 10s）留出余量；栅栏自己超时会 exit 1 提前失败。 */
        internal const val READY_TIMEOUT_MS = 25_000L
        private const val READY_POLL_MS = 400L
        private const val PROGRESS_POLL_MS = 500L
        private const val OUTPUT_TAIL_LIMIT = 2_000

        /** 依赖体积读数（Phase 0，x86_64）：`.deb` 下载约 91 MB，索引约 40 MB；
         *  安装后约 451 MiB；但安装峰值（lists + cache + 解包）约 1 GB，必须按峰值预检。 */
        const val DOWNLOAD_HINT_MB = 130
        const val INSTALLED_HINT_MB = 460
        const val INSTALL_SPACE_REQUIRED_MB = 1000L
    }
}
