package dev.kair.app.core.proot

import dev.kair.app.core.desktop.DesktopRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 常驻容器会话的宿主：**ProotPlugin 生产、终端插件消费**。
 *
 * 会话本身归 proot 能力所有，终端只是它的一个界面；拆成独立插件后不能再让两个插件
 * 各自创建一条长跑进程，因此由这里持有唯一实例。
 *
 * 两条通道（任务文档 §3 D3 定案）：
 * - [session]：agent 的帧协议会话（`container_shell` 那条通道，原样保留）；
 * - [pty]：用户终端的 PTY 会话（U59 新增）。
 * **放同一个 host 里**，因为两条通道共享 rootfs 与 `/projects` 挂载，启停时机必须同步 ——
 * 一条卸了挂载另一条立刻废；两个 host 只能靠约定，而约定会被下一批忘掉。
 *
 * 全局可变状态的四项说明（AGENTS 10.5）：
 * - 归属：应用级容器能力，不属于某个会话或项目；
 * - 生命周期：`ProotPlugin.activate` 创建并 [replace]；插件重激活时替换旧实例；
 *   进程重建后由插件重新创建；
 * - 清除：禁用 proot 插件时调用 [clearAndStop]（两条通道一起停）；禁用终端插件只回收 UI；
 * - 并发：[replace] / [clearAndStop] / [restart] 由 [lock] 串行；会话内部另有 lifecycleLock。
 */
class ContainerSessionHost internal constructor(
    private val scope: CoroutineScope,
    /**
     * 会话音频接收端（U89 §E · 路线 B1）：实现要用 `android.media.AudioTrack`，
     * 只能由宿主层注入；JVM / 未注入时是 [SessionAudioSink.None]（会话静音照跑）。
     *
     * 归**会话**不归桌面（审阅回执二 §3）：`.asoundrc` 是整个容器的 ALSA 默认设备，
     * 绑在桌面上会让「不开桌面 / 停桌面 / 覆盖安装」一律静默失声。
     */
    private val audioSink: SessionAudioSink = SessionAudioSink.None,
) {

    /**
     * 图形桌面持有者（U86）：与 [session] / [pty] 并列的**第三条通道**，归本 host（容器会话层）。
     *
     * 不挂插件实例：`builtinPlugins()` 每次调用都 new 一批 Plugin，禁用→启用会换实例 ——
     * 句柄挂实例上就会丢进程、「停止桌面」失效、旧桌面成孤儿（审阅 Blocker 3）。
     * 桌面不随会话状态自动重启（只有显式「停止桌面」或会话停止时才收）。
     */
    internal val desktop = DesktopRunner(this, scope)

    /** 音频接收端的生命周期观察者；换会话 / 清空时取消，避免旧会话的停止事件关掉新会话的端口。 */
    private var audioWatch: Job? = null

    private val _session = MutableStateFlow<ContainerSession?>(null)

    /** 当前帧协议会话；未创建或已清除时为 null。终端面板据此重组。 */
    val session: StateFlow<ContainerSession?> = _session.asStateFlow()

    private val _pty = MutableStateFlow<PtySession?>(null)

    /** 当前 PTY 会话；PTY 起不来时它是 [PtyState.Error]，面板据此显示失败原因。 */
    val pty: StateFlow<PtySession?> = _pty.asStateFlow()

    /**
     * 容器会话**能否承载桌面**：会话存在且已 [SessionState.Running]。
     *
     * 面板的可点性与 [DesktopRunner] 的守卫都读这一个真值。此前两处各判一套
     * （面板只判 `session == null`、runner 判 `state !is Running`），于是会话停在
     * `Stopped` 时面板照常摆出「启动桌面」，点下去只得到一句英文
     * `container session is not running`（真机 2026-11-14 反馈）。
     * 会话被替换时 `flatMapLatest` 会跟着切到新实例的状态，不会留着旧会话的 Running。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionReady: StateFlow<Boolean> = _session
        .flatMapLatest { s -> s?.state?.map { it is SessionState.Running } ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val lock = Mutex()

    private val _restarting = MutableStateFlow(false)

    /**
     * 是否处在 [restart] 的「停完还没起」窗口内。界面据此显示占位 ——
     * **这个标志不能由 [session] / [pty] 的状态推出来**：重启中途与「用户主动停掉」
     * 在状态上完全同形（PTY `Stopped` + 会话 `Stopped`），但前者要占位、后者要如实渲染。
     * 归属 [ContainerSessionHost]（重启的唯一入口就在本类），随实例消亡，不单独清除。
     */
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    /**
     * 发布新会话（两条通道一起）。旧实例先停止再发布，避免长跑进程并存；
     * 同一实例重复发布是幂等的。`nextPty` 默认沿用当前值，兼容只换帧协议会话的调用。
     */
    suspend fun replace(next: ContainerSession?, nextPty: PtySession? = _pty.value) = lock.withLock {
        val previous = _session.value
        val previousPty = _pty.value
        if (previous !== next) {
            // 先撤观察者再停旧会话：旧会话的 Stopped 事件若晚到，会把新会话刚绑好的端口关掉。
            audioWatch?.cancel()
            audioWatch = null
            previous?.stop()
            _session.value = next
            bindAudio(next)
        }
        if (previousPty !== nextPty) {
            previousPty?.stop()
            _pty.value = nextPty
        }
    }

    /** 置空并停止两条通道（禁用 proot 插件时调用）。 */
    suspend fun clearAndStop() = lock.withLock {
        val previous = _session.value
        val previousPty = _pty.value
        _session.value = null
        _pty.value = null
        previousPty?.stop()
        previous?.stop()
        bindAudio(null)
    }

    /**
     * 重启两条通道（U60 那枚「重启会话」按钮的语义）：先停后起，一次把帧协议会话
     * 与用户终端都换掉 —— 终端卡死时这是用户期望的唯一出路。
     *
     * 整个窗口用 [restarting] 罩住再复位（`finally`）：中间那段两条通道都停在过渡态，
     * 界面若照状态渲染会在网格与失败屏之间跳一下，见 `terminalScreen`。
     */
    suspend fun restart() = lock.withLock {
        val session = _session.value
        val pty = _pty.value
        _restarting.value = true
        try {
            pty?.stop()
            session?.stop()
            session?.start()
            pty?.start()
        } finally {
            _restarting.value = false
        }
    }

    /**
     * 音频接收端随会话走（审阅回执二 §3）：会话一发布就先起监听（消除「会话 Running
     * 但 5902 还没 listen」的窗口），之后只在 Running 时确认在听。
     *
     * **离开 Running 不停**（U89L）：崩溃或关桌面会把会话带回 Stopped/Error，若这时关掉
     * 5902，崩溃重启后要等会话重新 Running 才有声音 —— 真机上表现为「浏览器已经有声音了，
     * App 却收不到」（2026-11-14 实测：Chromium 崩溃重启后 5902 一直空着，aplay 直接
     * `write failed: Broken pipe`）。5902 空着没人连是零成本的；反复 close/bind 反而可能
     * 撞 TIME_WAIT。只有会话对象被清除（[clearAndStop]）才真正停。
     */
    private fun bindAudio(session: ContainerSession?) {
        // 这里必须留痕：此前失败路径只 noteHostMessage 到帧协议会话转录，
        // 用户在 PTY 终端里看不到，于是「装好了、也在播、就是没声」且零日志可查。
        System.err.println(
            "[kair-audio] bindAudio session=${session?.hashCode()} sink=${audioSink.javaClass.simpleName}",
        )
        audioWatch?.cancel()
        audioWatch = null
        if (session == null) {
            audioSink.stop()
            return
        }
        startAudio(session)
        audioWatch = scope.launch {
            // 跳过订阅时立即重放的当前值（发布时是 Stopped）：上面已经起过一次监听。
            session.state.drop(1).collect { state ->
                if (shouldStartAudio(state)) startAudio(session)
            }
        }
    }

    /** 启动失败只有这一个出口：写进会话转录，用户与 agent 都能看见（不再是静默失声）。 */
    private fun startAudio(session: ContainerSession) {
        val reason = audioSink.start()
        System.err.println("[kair-audio] startAudio -> ${reason ?: "ok"}")
        if (reason != null) {
            session.noteHostMessage("[kair] 音频接收端未启动：$reason\n")
        }
    }
}

/**
 * 会话状态变化时对音频接收端要做的唯一判断：`Running` 就确保在听，其余状态一律保持现状。
 *
 * 抽成纯函数是为了让 JVM 判据能钉住 U89L 那条语义 ——「崩溃/关桌面不得关掉 5902」，
 * 真机上的代价是「浏览器已经有声音了，App 却静默收不到」。
 */
internal fun shouldStartAudio(state: SessionState): Boolean = state is SessionState.Running
