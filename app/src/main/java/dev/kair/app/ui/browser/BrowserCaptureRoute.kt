package dev.kair.app.ui.browser

/** 截图从哪个窗口抓帧：渲染统一在常驻小窗里，只剩这一个来源。 */
internal enum class BrowserCaptureWindow { WINDOW }

/**
 * 一次截图的路由结果。[window] 为 null 表示当前截不了，[error] 是面向模型的原因。
 *
 * 做成纯数据而不是直接抛异常：UI 侧的「现在为什么截不了」只有一处判定，
 * JVM 单测能覆盖熄屏 / 无页面 / 未预热 / 后台无权限四种输入。
 */
internal data class BrowserCaptureRoute(
    val window: BrowserCaptureWindow?,
    val error: String? = null,
) {
    val available: Boolean get() = window != null
}

/**
 * 选择截图的窗口来源（纯函数）。
 *
 * 窗口是**常驻**的（有页面就有一扇小窗），截图不再 show / hide，所以判定只看
 * 「现在这扇窗能不能读到一帧合成内容」：
 *
 * 1. 屏幕熄灭 → 不可截：读不到合成帧，不能返回假图；
 * 2. 还没有页面 → 让模型先 `browser_open`；
 * 3. 窗口还没画出第一帧（刚建窗 / Activity 重建）→ 明确报「还在渲染」，不返回黑图；
 * 4. 后台且没有「显示在其他应用上层」→ 降级窗只在 App 前台可见，报权限错误；
 * 5. 其余情况 → 抓常驻小窗。
 */
internal fun resolveBrowserCaptureRoute(
    screenOn: Boolean,
    hasPage: Boolean,
    windowReady: Boolean,
    foreground: Boolean,
    overlayPermission: Boolean,
): BrowserCaptureRoute = when {
    !screenOn -> BrowserCaptureRoute(
        window = null,
        error = "The screen is off, so the rendered page cannot be captured. " +
            "Turn the screen on and retry.",
    )
    !hasPage -> BrowserCaptureRoute(
        window = null,
        error = "No page is open. Call browser_open first.",
    )
    !windowReady -> BrowserCaptureRoute(
        window = null,
        error = "The browser window is still rendering its first frame. Retry in a moment.",
    )
    !foreground && !overlayPermission -> BrowserCaptureRoute(
        window = null,
        error = "Kair is in the background and does not have the \"display over other apps\" " +
            "permission, so the floating window is not visible. Grant it in Settings - Plugins - " +
            "Browser, then retry.",
    )
    else -> BrowserCaptureRoute(BrowserCaptureWindow.WINDOW)
}
