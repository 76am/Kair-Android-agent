package dev.kair.app.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.webkit.WebView
import dev.kair.app.app.encodePng
import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserScreenshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** PixelCopy 等窗口帧的回调；超时按「这一帧没拿到」处理。 */
private const val PIXEL_COPY_TIMEOUT_MS = 3_000L

/** 空帧重试间隔（约两帧）。 */
private const val FRAME_RETRY_MS = 32L

/**
 * 空帧重试上限（~2s）。常驻小窗正常首帧就非空；超时仍拿不到可信帧就明确报错，
 * 绝不把黑图当截图返回。
 */
private const val FRAME_TIMEOUT_MS = 2_000L

/** 黑帧采样的网格边长；只判断「整片同色」，不遍历全图（主线程代价要可忽略）。 */
private const val BLANK_SAMPLE_GRID = 16

/** 等窗口真正画出一帧的上限；超时交给 PixelCopy 的可见性检查报错，不无限挂起。 */
private const val RENDER_FRAME_TIMEOUT_MS = 600L

/**
 * 截图前等到「窗口真的画过一帧」。调用方保证在主线程。
 *
 * 固定毫秒不够：窗口刚显示、贴边滑回、WebView 刚搬进窗口时，渲染器这一帧可能还没
 * 提交，`PixelCopy` 会拷到上一帧（整片黑或只有 DOM）。
 * 这里按真实信号等：视图 post（布局完成）→ 窗口遍历中的一次 draw →
 * 下一帧的 Choreographer 回调（确保刚 draw 的内容已经 present）。
 */
internal suspend fun awaitRenderedFrame(view: View, window: Window) {
    withTimeoutOrNull(RENDER_FRAME_TIMEOUT_MS) {
        suspendCancellableCoroutine<Unit> { cont ->
            view.post { if (cont.isActive) cont.resume(Unit) }
        }
        // 强制走一次遍历；窗口不可见时不会真的 draw，由下面的超时兜底。
        view.invalidate()
        withTimeoutOrNull(RENDER_FRAME_TIMEOUT_MS / 2) { awaitWindowDraw(window) }
        withTimeoutOrNull(RENDER_FRAME_TIMEOUT_MS / 6) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }
}

/** 窗口宿主完成一次 draw 遍历；窗口尚未显示时不会回调，由外层超时兜底。 */
private suspend fun awaitWindowDraw(window: Window): Unit = suspendCancellableCoroutine { cont ->
    val decor = window.decorView
    val observer = decor.viewTreeObserver
    var done = false
    val listener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            if (done) return
            done = true
            // 系统禁止在 onDraw 回调里 removeOnDrawListener（直接抛 IllegalStateException）；
            // 也不能在这里同步 resume（后续 PixelCopy 会跑在 draw 遍历中）。统一 post 到遍历之后。
            decor.post {
                if (observer.isAlive) observer.removeOnDrawListener(this)
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
    observer.addOnDrawListener(listener)
    // 超时 / 取消时监听器还挂在窗口上会跟着窗口一直累积，必须摘掉。
    cont.invokeOnCancellation {
        done = true
        if (observer.isAlive) observer.removeOnDrawListener(listener)
    }
}

/**
 * 把 WebView 当前视口画成 PNG。调用方保证在主线程，且视图已有尺寸。
 *
 * **为什么不用 `view.draw`**：WebView 的页面内容（尤其 `<canvas>` / WebGL / video）是硬件
 * 合成层，软件 Canvas 录不到，只会得到空白或黑块。这里只走 `PixelCopy` 从小窗的
 * `Window` 抓帧。
 *
 * **不退回软件绘制**：拿不到可信帧就抛错 —— 假截图会被模型当成真实的页面状态。
 *
 * 编码走 [encodePng]：`Bitmap.CompressFormat` 全仓只允许出现在 `BitmapImageScaler.kt`
 * （`ImageScalingGuardTest`）。
 */
internal suspend fun captureViewportPng(view: WebView, frame: CaptureFrame): BrowserScreenshot {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) {
        throw BrowserActionException(
            "The browser view has no size yet. Open the browser window once, then retry.",
        )
    }
    val power = view.context.getSystemService(PowerManager::class.java)
    if (power?.isInteractive == false) {
        throw BrowserActionException(
            "The screen is off, so the rendered page cannot be captured. " +
                "Turn the screen on and retry.",
        )
    }
    val window = frame.window
    if (!view.isAttachedToWindow || window.decorView.windowVisibility != View.VISIBLE) {
        throw BrowserActionException(
            "The browser floating window is not visible, so its rendered frame cannot be " +
                "captured. Bring Kair to the foreground and retry.",
        )
    }
    val location = IntArray(2)
    view.getLocationInWindow(location)
    // 贴边窗口在抓帧前已被滑回完整可见；这里仍按窗口边界裁一次，防止布局竞态。
    val visibleWidth = minOf(width, window.decorView.width - location[0])
    val visibleHeight = minOf(height, window.decorView.height - location[1])
    if (visibleWidth <= 0 || visibleHeight <= 0) {
        throw BrowserActionException(
            "The browser view is off screen (x=${location[0]}, y=${location[1]}). " +
                "Bring the floating window back on screen and retry.",
        )
    }
    val bitmap = Bitmap.createBitmap(visibleWidth, visibleHeight, Bitmap.Config.ARGB_8888)
    val rect = Rect(
        location[0],
        location[1],
        location[0] + visibleWidth,
        location[1] + visibleHeight,
    )
    val png = copyFramesPng(window, rect, bitmap)
    bitmap.recycle()
    if (png == null) {
        // 通常是窗口还没完成合成（刚建窗 / 刚滑回）或系统隐藏了悬浮窗。
        throw BrowserActionException(
            "The browser window's frame could not be read after retrying for 2 seconds. " +
                "The system may be hiding the floating window or the page is not composited yet. " +
                "Bring Kair to the foreground and retry.",
        )
    }
    return BrowserScreenshot(
        png = png,
        width = visibleWidth,
        height = visibleHeight,
        complete = visibleWidth == width && visibleHeight == height,
    )
}

/**
 * 按帧重试抓取窗口内容；拿不到可信帧时返回 null。
 * 拷贝成功也可能只是整片黑 / 透明的未合成帧（暗色页面还会骗过判空），
 * 所以每次都要采样确认；用尽仍空返回 null —— 绝不把黑图当截图返回。
 */
private suspend fun copyFramesPng(window: Window, rect: Rect, bitmap: Bitmap): ByteArray? {
    val deadline = SystemClock.uptimeMillis() + FRAME_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        if (copyWindowFrame(window, rect, bitmap) && !isBlankFrame(bitmap)) {
            return encodePng(bitmap)
        }
        delay(FRAME_RETRY_MS)
    }
    return null
}

/** 向窗口要一帧硬件合成结果；超时 / 窗口未加速 / 系统拒绝时返回 false。 */
private suspend fun copyWindowFrame(window: Window, rect: Rect, bitmap: Bitmap): Boolean =
    withTimeoutOrNull(PIXEL_COPY_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            // PixelCopy 对「未硬件加速 / 窗口已销毁」是同步抛异常（不是回调错误码），
            // 悬浮窗在个别 ROM 上会踩到；统一折成 false，让上层给可读错误。
            runCatching {
                PixelCopy.request(
                    window,
                    rect,
                    bitmap,
                    { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    } ?: false

/** 取宿主 Activity：降级窗（无悬浮权限）需要 Activity 作为窗口 token。 */
internal fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * 按 16×16 网格采样判断这一帧是不是「还没把页面合成上去」。
 * 只有整片纯黑 / 纯透明才算空帧 —— 纯白页面（about:blank）是真实内容，不能误判。
 */
private fun isBlankFrame(bitmap: Bitmap): Boolean {
    val stepX = (bitmap.width / BLANK_SAMPLE_GRID).coerceAtLeast(1)
    val stepY = (bitmap.height / BLANK_SAMPLE_GRID).coerceAtLeast(1)
    val samples = ArrayList<Int>(BLANK_SAMPLE_GRID * BLANK_SAMPLE_GRID)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            samples.add(bitmap.getPixel(x, y))
            x += stepX
        }
        y += stepY
    }
    return isBlankSample(samples.toIntArray())
}

/** 纯逻辑部分：采样像素是否全为同一种「空」色（黑 / 透明）。 */
internal fun isBlankSample(samples: IntArray): Boolean {
    if (samples.isEmpty()) return false
    val first = samples[0]
    if (first != Color.BLACK && first != Color.TRANSPARENT) return false
    return samples.all { it == first }
}
