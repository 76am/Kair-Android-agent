package dev.kair.app.ui.plugins.builtin

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.util.Log
import android.view.Choreographer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kair.app.R
import dev.kair.app.core.desktop.DesktopGestures
import dev.kair.app.core.desktop.DesktopResolution
import dev.kair.app.core.desktop.DesktopRunner
import dev.kair.app.core.desktop.DstRect
import dev.kair.app.core.desktop.PointerOut
import dev.kair.app.core.desktop.ScaleMode
import dev.kair.app.core.desktop.fitRect
import dev.kair.app.core.desktop.overlayAlpha
import dev.kair.app.core.desktop.toFramebuffer
import dev.kair.app.ui.screens.LocalPanelClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 桌面画面（U86 §4 F）：共享帧缓冲读循环 + 悬浮胶囊 + 软键盘条。
 *
 * - 画面读循环的生命周期**只等于面板**（它是视图通道，不是桌面进程；桌面进程归
 *   [DesktopRunner]，离开面板不停桌面 —— 任务书 §3.1）；重进面板即重读帧缓冲。
 * - 输入写 [DesktopRunner] 持有的容器内常驻 `xdotool -`（x11vnc 已删除）。
 * - 胶囊 ✕ = **关闭本面板**（回上一页），不是停桌面 —— 停桌面只有菜单里那一条。
 * - 键盘弹出**只遮画面**，不改 Xvfb 分辨率、不 resize（沿用 U85 教训）。
 * - 首帧前显示「正在连接…」，**不黑屏**。
 *
 * 悬浮件（胶囊 / 键盘条）在 `DesktopChrome.kt`：本文件只留画面与手势，改交互先看那一份。
 */
@Composable
internal fun DesktopScreen(
    runner: DesktopRunner,
    srcW: Int,
    srcH: Int,
    resolution: DesktopResolution,
    onSelectResolution: (DesktopResolution) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val onClose = LocalPanelClose.current
    // 横屏 + 真全屏（U89 §D）：进面板隐藏状态栏与导航栏，边缘滑动可临时呼出；离开时恢复。
    // 与横屏请求同一个 DisposableEffect：两者都属于「面板期间的窗口姿态」。
    val previousOrientation = remember { (context as? Activity)?.requestedOrientation }
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
                // 全应用本来就是 edge-to-edge（MainActivity 的 enableEdgeToEdge）—— 退出面板要恢复的是
                // 「不 fit system windows」，而不是 true；写成 true 会让之后所有页面都退回系统栏内布局。
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
            (context as? Activity)?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val clientScope = rememberCoroutineScope()
    // 重连只由「用户点画面上的重新连接」驱动（不自动）：读循环死了以后手势事件会被静默丢弃，
    // 用户看到的是「画面好好的但点不动」——必须给一个明确的手动出口。
    var reconnectTick by remember { mutableIntStateOf(0) }
    // 「重启桌面」不需要这个钩子：restart 会把 state 打到 Idle → Starting → Running，
    // DesktopPanel 的 when 分支来回切，DesktopScreen 天然卸载重进、读循环重建。
    val framebufferFile = runner.sharedFramebufferFile()
    if (framebufferFile == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.desktop_failed, "container session is gone"),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        return
    }
    val viewSession = remember(framebufferFile, clientScope, reconnectTick) {
        DesktopViewSession(framebufferFile, clientScope, runner)
    }
    LaunchedEffect(viewSession) { viewSession.start() }
    DisposableEffect(viewSession) { onDispose { viewSession.close() } }
    val viewState by viewSession.state.collectAsState()

    val bitmap = remember(srcW, srcH) { Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888) }
    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(viewSession, bitmap) {
        viewSession.frames.collect { rects ->
            // 整帧矩形列表一次应用（不逐像素过 Flow，任务书 §4 B）
            for (r in rects) bitmap.setPixels(r.pixels, 0, r.w, r.x, r.y, r.w, r.h)
            frameTick++
        }
    }

    VsyncTicker(viewSession)

    var scaleMode by remember { mutableStateOf(ScaleMode.Fit) }
    var showKeyboard by remember { mutableStateOf(false) }
    var lastTouchMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var capsuleAlpha by remember { mutableFloatStateOf(1f) }
    // fps 上屏（U89 F二）：数每秒上屏了几帧。空闲时为 0（没变化不推帧，不是卡死）。
    var fps by remember { mutableIntStateOf(0) }
    // 本地光标位置（屏幕坐标 px）。Xvfb 用 -nocursor，指针不再进帧缓冲，由这里画。
    var cursorPos by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        var last = frameTick
        while (true) {
            delay(1_000)
            val now = frameTick
            fps = now - last
            last = now
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            capsuleAlpha = overlayAlpha(System.currentTimeMillis() - lastTouchMs)
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            val dst = fitRect(srcW, srcH, constraints.maxWidth, constraints.maxHeight, scaleMode)
            val density = LocalDensity.current.density
            val dstSize = DpSize((dst.w / density).dp, (dst.h / density).dp)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset(dst.x, dst.y) }
                    .size(dstSize),
            )
            // 手势层：屏幕坐标 → framebuffer 坐标（黑边上的点不发事件，任务书 §4 C）。
            DesktopGestureLayer(
                modifier = Modifier.matchParentSize(),
                dst = dst,
                srcW = srcW,
                srcH = srcH,
                view = viewSession,
                onTouch = { lastTouchMs = System.currentTimeMillis() },
                onCursor = { cursorPos = it },
            )
            // 本地光标（Xvfb 用 -nocursor，指针不再进帧缓冲）。
            // 必须由 App 画：否则要么没光标，要么一圈“触摸→xdotool→X 重绘→读帧”回来，
            // 实测会一顿一顿（齿轮不顿，因为齿轮不经过这条回路）。
            CursorOverlay(position = cursorPos, modifier = Modifier.matchParentSize())
            // 首帧提示 + 读循环失败遮罩（体量守卫：从 DesktopScreen 拆出，主组合只留布局）。
            DesktopFrameOverlay(
                viewState = viewState,
                frameTick = frameTick,
                onReconnect = { reconnectTick++ },
            )
            DesktopCapsule(
                modifier = Modifier.align(Alignment.TopEnd),
                alpha = capsuleAlpha,
                scaleMode = scaleMode,
                srcW = srcW,
                srcH = srcH,
                fps = fps,
                viewState = viewState,
                resolution = resolution,
                onSelectResolution = onSelectResolution,
                onToggleKeyboard = { showKeyboard = !showKeyboard },
                onToggleScale = { scaleMode = if (scaleMode == ScaleMode.Fit) ScaleMode.Actual else ScaleMode.Fit },
                onClose = onClose,
                onRestart = { runner.restart() },
                onStop = { runner.stop() },
                onRemoveEnv = { runner.removeDesktopEnvironment() },
            )
            if (showKeyboard) {
                DesktopKeyboardBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onKey = viewSession::key,
                    onText = viewSession::text,
                )
            }
        }
    }
}

/**
 * 画面通道的「现在怎么了」：首帧提示 + 读循环失败遮罩。
 *
 * 出错必须**在画面上说出来**：画面会定格在最后一帧，看起来「一切正常」，
 * 但此刻输入已无处可去 —— 真机上就是「画面好好的，可点哪都没反应」。
 */
@Composable
private fun DesktopFrameOverlay(
    viewState: DesktopViewState,
    frameTick: Int,
    onReconnect: () -> Unit,
) {
    if (frameTick == 0) {
        // 首帧前不许黑屏（任务书 §4 F）。
        val hint = when (viewState) {
            DesktopViewState.Connecting -> R.string.desktop_connecting
            DesktopViewState.Connected -> R.string.desktop_waiting_frame
            else -> null
        }
        if (hint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    val errorMsg = (viewState as? DesktopViewState.Error)?.message ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (frameTick > 0) {
                    stringResource(R.string.desktop_link_lost)
                } else {
                    // 这是画面通道的失败，与「桌面进程起不来」是两件事，文案要分开。
                    stringResource(R.string.desktop_frame_failed, errorMsg)
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (frameTick > 0 && errorMsg.isNotBlank()) {
                Text(
                    errorMsg,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onReconnect) {
                Text(stringResource(R.string.desktop_reconnect))
            }
        }
    }
}

/**
 * 垂直同步节拍（2026-11-14）：每个 vsync 给读循环一拍，让推帧节奏与上屏对齐。
 *
 * 用 [Choreographer] 而不是 `withFrameNanos` —— 后者会强制 Compose 每帧重绘，
 * 空闲时白白烧电；`postFrameCallback` 只回调，不请求绘制。
 * 屏幕 120 Hz 时预算 8.33 ms/帧：装得下就稳定 120，装不下自然落回 60（都是 vsync 整数分频）。
 */
@Composable
private fun VsyncTicker(view: DesktopViewSession) {
    LaunchedEffect(view) {
        val choreographer = Choreographer.getInstance()
        while (true) {
            suspendCancellableCoroutine<Long> { cont ->
                choreographer.postFrameCallback { frameTimeNanos -> cont.resume(frameTimeNanos) }
            }
            view.onVsync()
        }
    }
}

/** 手势层：把 Compose 指针采样喂给纯函数归约器（§4 D），产出的事件按视口映射后发输入通道。 */
@Composable
private fun DesktopGestureLayer(
    modifier: Modifier,
    dst: DstRect,
    srcW: Int,
    srcH: Int,
    view: DesktopViewSession,
    onTouch: () -> Unit,
    /** 光标位置变化（屏幕坐标 px）；UI 据此画本地光标（Xvfb -nocursor，指针不进帧缓冲）。 */
    onCursor: (Offset) -> Unit,
) {
    // `pointerInput(Unit)` + rememberUpdatedState：dst/srcW/srcH/view 每次重组都是新对象，
    // 一旦把它们当 key，值有抖动就会取消正在进行的触摸手势（手势被掐断 = 一条事件都不发）。
    val dstState = rememberUpdatedState(dst)
    val srcWState = rememberUpdatedState(srcW)
    val srcHState = rememberUpdatedState(srcH)
    val sessionState = rememberUpdatedState(view)
    val onCursorState = rememberUpdatedState(onCursor)
    val haptic = LocalHapticFeedback.current
    // 归约器提到重组外：光标位置要跨手势、跨重组保持（放 pointerInput 块里会随手势结束重建）。
    val densityValue = LocalDensity.current.density
    val gestures = remember(densityValue) {
        DesktopGestures(
            slopPx = 12f * densityValue,
            tapMaxMovePx = 10f * densityValue,
            wheelStepPx = 30f * densityValue,
        )
    }
    // 视口/光标初始化：面板一进入就把指针放到画面中心（不然要等第一次滑动才出现）。
    LaunchedEffect(dst) {
        gestures.viewportX = dst.x.toFloat()
        gestures.viewportY = dst.y.toFloat()
        gestures.viewportW = dst.w.toFloat()
        gestures.viewportH = dst.h.toFloat()
        if (gestures.cursorPosition == null) {
            gestures.placeCursor(dst.x + dst.w / 2f, dst.y + dst.h / 2f)
            gestures.cursorPosition?.let { onCursorState.value(Offset(it.first, it.second)) }
        }
    }
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                val g = gestures

                // 归约结果（屏幕坐标）统一在这里转 framebuffer 再发：主循环与长按计时器共用一份。
                fun emit(out: List<PointerOut>) {
                    val d = dstState.value
                    for (e in out) {
                        val fb = toFramebuffer(e.x.toFloat(), e.y.toFloat(), d, srcWState.value, srcHState.value)
                        Log.d(GESTURE_TAG, "ev ${e.x},${e.y} mask=${e.mask} fb=$fb")
                        if (fb != null) sessionState.value.pointer(fb.first, fb.second, e.mask)
                    }
                    // 本地光标跟手指走（不依赖 X 重绘后再读帧回来，否则会一顿一顿）。
                    g.cursorPosition?.let { onCursorState.value(Offset(it.first, it.second)) }
                }
                // 长按计时器要并行于指针事件循环，所以得有一个真正的 scope。
                coroutineScope {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        // 视口矩形用来把光标钳在画面内、未就绪时算初始中心；缩放切换会改 dst，所以每段手势同步。
                        g.viewportX = dstState.value.x.toFloat()
                        g.viewportY = dstState.value.y.toFloat()
                        g.viewportW = dstState.value.w.toFloat()
                        g.viewportH = dstState.value.h.toFloat()
                        onTouch()
                        Log.d(GESTURE_TAG, "down ${first.position} dst=${dstState.value}")
                        g.down(first.id.value, first.position.x, first.position.y, first.uptimeMillis)
                        // 长按：到点仍在 Pending（没滑过）就把左键按在光标处，之后滑动即拖拽。
                        // 归约器自己没有时钟，这一拍只能由宿主给；震动是「已经按住」的唯一反馈 ——
                        // 没有它，用户根本不知道拖拽已经生效，只会以为点了没反应。
                        val hold = launch {
                            delay(g.longPressMs)
                            val out = g.hold()
                            if (out.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                emit(out)
                            }
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                onTouch()
                                for (change in event.changes) {
                                    val id = change.id.value
                                    val p = change.position
                                    val t = change.uptimeMillis
                                    val out = when {
                                        change.changedToDown() -> g.down(id, p.x, p.y, t)
                                        change.changedToUp() -> g.up(id, p.x, p.y, t)
                                        change.positionChanged() -> g.move(id, p.x, p.y, t)
                                        else -> emptyList()
                                    }
                                    emit(out)
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                        } finally {
                            hold.cancel()
                        }
                        g.reset()
                    }
                }
            },
    )
}

private const val GESTURE_TAG = "KairDesktop"
