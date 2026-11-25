package dev.kair.app.ui.plugins.builtin

import android.util.Log
import dev.kair.app.core.desktop.DesktopRect
import dev.kair.app.core.desktop.DesktopRunner
import dev.kair.app.core.desktop.XwdFramebufferReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 面板作用域的画面视图：共享帧缓冲读循环 + 输入转发。
 *
 * 生命周期只等于面板 —— 面板卸载即 [close] 取消读循环；桌面进程归 [DesktopRunner]，
 * 离开面板不停桌面。画面出错时把原因写进 [state]，由画面上的遮罩显示并提供重连。
 *
 * 从 `DesktopScreen.kt` 拆出来是体量守卫（单文件 500 行）：那边已经贴着上限，
 * 这段又是「状态 + IO」自成一块，单独成文件后画面布局与协议/生命周期各改各的。
 */
internal class DesktopViewSession(
    private val file: File,
    private val scope: CoroutineScope,
    private val runner: DesktopRunner,
) {
    private val _state = MutableStateFlow<DesktopViewState>(DesktopViewState.Connecting)
    val state: StateFlow<DesktopViewState> = _state.asStateFlow()

    /** `replay = 1`：首帧可能在 collector 订阅之前就到达；丢掉首帧等于永久黑屏。 */
    private val _frames = MutableSharedFlow<List<DesktopRect>>(
        replay = 1,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<List<DesktopRect>> = _frames.asSharedFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val vsync = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        _state.value = DesktopViewState.Connecting
        job = XwdFramebufferReader(
            file = file,
            scope = scope,
            wake = wake,
            vsync = vsync,
            // core 不许依赖 android.util.Log（架构守卫），日志口由 UI 注入。
            log = { Log.d(VIEW_TAG, it) },
            onError = { message -> _state.value = DesktopViewState.Error(message) },
            onFrame = { rects ->
                _frames.tryEmit(rects)
                if (_state.value !is DesktopViewState.Error) _state.value = DesktopViewState.Connected
            },
        ).start()
    }

    fun pointer(x: Int, y: Int, mask: Int) {
        // 输入本身也是「画面可能变了」的信号：读循环据此从空闲退避里立刻醒一次。
        wake.trySend(Unit)
        runner.pointer(x, y, mask)
    }

    fun key(keysym: Int, down: Boolean) {
        wake.trySend(Unit)
        runner.key(keysym, down)
    }

    /** 非 Latin-1 文本（中文等）：宿主会先起一个 `xdotool type` 进程（见 DesktopInputChannel）。 */
    fun text(value: String) {
        wake.trySend(Unit)
        runner.text(value)
    }

    fun onVsync() {
        vsync.trySend(Unit)
    }

    fun close() {
        job?.cancel()
        job = null
    }
}

private const val VIEW_TAG = "KairDesktopView"
