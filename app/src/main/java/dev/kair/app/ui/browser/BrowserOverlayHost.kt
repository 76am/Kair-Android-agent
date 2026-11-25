package dev.kair.app.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 常驻小窗是否在屏上。`KeepAlivePlugin` 把它并进前台服务的起停条件：
 * 窗口在后台可见期间进程不能被冻结，否则 agent 的动作与截图都会断。
 *
 * 放在这里而不是 `AppContainer`：这是浏览器图层自己的存在状态，容器不该为每个 UI
 * 细节长一个字段（`AppContainer` 有行数守卫）。
 */
internal object BrowserOverlayPresence {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        _visible.value = visible
    }
}

/**
 * 常驻悬浮小窗的 Compose 接线：窗口随页面存在，前后台都不重建。
 *
 * 这里只做三件事：
 * - 把窗口的 captureHost 绑进 `BrowserWindowCoordinator`（离开组合时解绑）；
 * - 持续收集页面状态：有页面就建窗 / 刷新，没有页面就销毁；
 * - 跟随生命周期更新 `appForeground` —— 降级窗（无悬浮权限）只在前台可见，
 *   且**不能**依赖重组（Compose 在 ON_STOP 后暂停 frame clock），必须挂生命周期观察者。
 *
 * 注意 Composition 在后台会暂停，但主线程的协程收集仍在跑，所以「后台打开页面」
 * 也能把窗口补出来（有权限时）。
 */
@Composable
internal fun BrowserOverlayHost(controller: AndroidBrowserController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val host = BrowserWindowHostState(
            activityContext = context,
            appContext = context.applicationContext,
            controller = controller,
            lifecycle = lifecycle,
        )
        host.start()
        onDispose { host.dispose() }
    }
}

/** 生命周期 + 页面状态的接线本体；写成普通类，避免把时序交给重组。 */
private class BrowserWindowHostState(
    activityContext: android.content.Context,
    appContext: android.content.Context,
    private val controller: AndroidBrowserController,
    private val lifecycle: Lifecycle,
) : LifecycleEventObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = BrowserWindowHost(activityContext, appContext, controller) { visible ->
        BrowserOverlayPresence.set(visible && !controller.windows.appForeground)
    }

    fun start() {
        lifecycle.addObserver(this)
        // 宿主重组（Activity 重建）时协调器可能沿用旧值：按当前生命周期兜底一次。
        controller.windows.appForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        controller.windows.attachWindowHost(window)
        scope.launch {
            controller.state.collect { state ->
                window.sync(state)
                updatePresence()
            }
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                controller.windows.appForeground = true
                window.onAppForeground(true)
                updatePresence()
            }
            Lifecycle.Event.ON_STOP -> {
                controller.windows.appForeground = false
                window.onAppForeground(false)
                updatePresence()
            }
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }

    fun dispose() {
        scope.cancel()
        window.hide()
        controller.windows.attachWindowHost(null)
        BrowserOverlayPresence.set(false)
        lifecycle.removeObserver(this)
    }

    /** 只有「窗口在后台可见」才需要前台服务；前台本来就活着，不必白挂一条通知。 */
    private fun updatePresence() {
        BrowserOverlayPresence.set(window.isShowing && !controller.windows.appForeground)
    }
}
