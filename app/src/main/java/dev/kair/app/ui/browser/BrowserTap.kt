package dev.kair.app.ui.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import dev.kair.app.core.browser.BrowserActionException
import kotlinx.coroutines.delay

/** 按下与抬起之间的停留时间；为 0 时部分 canvas 手势识别器会把点击当抖动丢掉。 */
private const val TAP_HOLD_MS = 40L

/** CSS 像素 → View 像素的比例：`devicePixelRatio × visualViewport.scale`。 */
internal const val BROWSER_SCALE_SCRIPT =
    "window.devicePixelRatio * (window.visualViewport ? window.visualViewport.scale : 1)"

/**
 * 在视口坐标 (x, y)（CSS 像素）落一次**真实触摸**，用来点 canvas / WebGL 控件。
 *
 * `WebView.dispatchTouchEvent` 会走回原生触摸链路，页面侧拿到的是可信 touch / pointer 事件；
 * `element.click()` 只触发 DOM click，不触发 canvas 上的 pointer / touch 监听。
 *
 * [scaleProvider] 由控制器提供（在主线程执行 JS），返回当前页面的 CSS→View 像素比例。
 * 悬浮窗的透明挡板不会影响这里：事件直接派发给 WebView，不经过窗口命中测试。
 */
internal suspend fun performBrowserTap(
    view: WebView,
    x: Float,
    y: Float,
    scaleProvider: suspend () -> String?,
): String {
    if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) {
        throw BrowserActionException("Tap coordinates must be non-negative CSS pixels.")
    }
    if (view.width <= 0 || view.height <= 0) {
        throw BrowserActionException(
            "The page has no size yet. Open the browser panel once (or take a screenshot), then retry.",
        )
    }
    val scale = scaleProvider()?.trim()?.toFloatOrNull()
        ?: throw BrowserActionException(
            "Could not read the page scale factor. Reload the page and retry.",
        )
    val px = x * scale
    val py = y * scale
    if (px >= view.width || py >= view.height) {
        throw BrowserActionException(
            "Tap ($x, $y) is outside the ${view.width}x${view.height} viewport. " +
                "Use CSS pixels relative to the visible viewport.",
        )
    }
    val downTime = SystemClock.uptimeMillis()
    sendTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, px, py)
    delay(TAP_HOLD_MS)
    sendTouch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py)
    return "Tapped at ($x, $y)."
}

private fun sendTouch(
    view: WebView,
    downTime: Long,
    eventTime: Long,
    action: Int,
    x: Float,
    y: Float,
) {
    val pointer = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    val coords = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }
    val event = MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        1,
        arrayOf(pointer),
        arrayOf(coords),
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0,
    )
    try {
        view.dispatchTouchEvent(event)
    } finally {
        event.recycle()
    }
}
