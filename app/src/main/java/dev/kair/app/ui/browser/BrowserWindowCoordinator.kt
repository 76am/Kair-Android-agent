package dev.kair.app.ui.browser

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.Window
import android.webkit.WebView
import dev.kair.app.core.browser.BrowserActionException

/** 截图抓帧的窗口来源。渲染只有一扇常驻小窗，不再区分面板 / 悬浮窗。 */
internal data class CaptureFrame(val window: Window)

/** 截图宿主：抓帧前确保窗口完整可见（贴边时滑回），然后交出窗口。 */
internal interface CaptureHost {
    suspend fun <T> withVisibleCapture(capture: suspend (CaptureFrame) -> T): T
}

/**
 * WebView 的窗口归属与截图路由。
 *
 * 渲染统一在 [BrowserWindowHost] 的常驻小窗里：面板不再持有 WebView，也没有
 * 「面板 / 悬浮窗」双宿主切换。本类只保留三件事：
 * - 把新建的 WebView 交给窗口（渲染进程崩溃重建时靠这一步回到屏上）；
 * - 提供纯函数形式的路由结论（[resolveBrowserCaptureRoute]）；
 * - 记录 App 是否在前台（降级窗只在前后台有区别）。
 *
 * 线程：所有方法都在主线程调用（Compose / 生命周期 / 控制器都已切主线程）；
 * 跨线程读的两个状态位标 `@Volatile`，让截图路由不必回主线程确认。
 */
internal class BrowserWindowCoordinator(
    private val appContext: Context,
    private val currentView: () -> WebView?,
) {

    /** App 是否已进入前台（STARTED 及以上）；由窗口宿主在 ON_START / ON_STOP 更新。 */
    @Volatile
    var appForeground: Boolean = true

    /** 窗口是否已经画出过至少一帧；未就绪时截图返回可读错误，而不是黑图。 */
    @Volatile
    var windowReady: Boolean = false

    private var host: BrowserWindowHost? = null

    fun attachWindowHost(host: BrowserWindowHost?) {
        this.host = host
    }

    /** 控制器新建了 WebView：直接挂到当前窗口（没有窗口时由窗口显示时自己来取）。 */
    fun onViewCreated(view: WebView) {
        host?.attachView(view)
    }

    /** WebView 即将销毁 / 页面关闭：先从窗口摘掉，避免悬空引用。 */
    fun onViewDestroyed() {
        host?.detachView()
        windowReady = false
    }

    /** 窗口显示时把控制器里已有的 WebView 挂进来。 */
    fun attachCurrentView() {
        host?.attachView(currentView() ?: return)
    }

    fun route(): BrowserCaptureRoute = resolveBrowserCaptureRoute(
        screenOn = isScreenOn(),
        hasPage = currentView() != null,
        windowReady = windowReady,
        foreground = appForeground,
        overlayPermission = canDrawOverlays(),
    )

    /** 按当前路由抓帧；不可截时抛出带原因的 [BrowserActionException]。 */
    suspend fun <T> withVisibleCapture(capture: suspend (CaptureFrame) -> T): T {
        val route = route()
        if (!route.available) {
            throw BrowserActionException(route.error ?: "The browser is not available for capture right now.")
        }
        val host = host ?: throw BrowserActionException(
            "The browser window is not available. Bring Kair to the foreground and retry.",
        )
        return host.captureHost.withVisibleCapture(capture)
    }

    private fun isScreenOn(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)
}
