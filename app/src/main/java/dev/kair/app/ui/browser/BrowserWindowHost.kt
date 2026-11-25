package dev.kair.app.ui.browser

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import dev.kair.app.R
import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserMode
import dev.kair.app.core.browser.BrowserPanelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 常驻悬浮小窗：`TYPE_APPLICATION_OVERLAY` 的 Dialog，承载**整个应用唯一的 WebView**。
 *
 * 形态：顶栏（32dp，可拖动）+ 页面区域（[BrowserWindowChrome]）。页面区宽度 = 屏宽 30%
 * （下限 96dp），高度按**当前屏幕宽高比**缩放（竖屏 ≈ 9:19.5 / 横屏 ≈ 19.5:9），
 * 与真实设备视口一致；尺寸计算见 [browserWindowSizePx]。顶栏在页面之外，
 * 截图按 WebView 位置裁 rect 时不会把顶栏截进画面。
 *
 * **为什么窗口常驻**：截图曾经每次 show / hide 并把 WebView 搬来搬去，窗口刚创建时
 * 还没合成，PixelCopy 只能拿到整块黑（真机表现为「一瞬间黑屏」）。现在窗口随页面
 * 存在，前后台都不重建；截图只等一帧、直接读，没有形态变化。
 *
 * **降级**：没有「显示在其他应用上层」时，窗口挂在 Activity 上（普通 Dialog）。
 * 前台照常悬浮渲染，退后台窗口不可见；截图路由会报权限错误。
 *
 * Agent 模式窗口不聚焦、页面盖透明挡板；用户接管后窗口可聚焦，顶栏标题换成
 * 地址输入框 —— 顶栏高度不变，避免接管时页面重排。
 */
internal class BrowserWindowHost(
    private val activityContext: Context,
    private val appContext: Context,
    private val controller: AndroidBrowserController,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {

    private val windows = controller.windows
    private val metrics = appContext.resources.displayMetrics
    private val density = metrics.density

    private var dialog: Dialog? = null
    private var overlayType = false
    private var showing = false
    private var collapsed = false
    private var collapsedLeft = false
    private var lastMode: BrowserMode? = null
    private var scope: CoroutineScope? = null
    private var attachedView: WebView? = null

    private var x = Int.MIN_VALUE
    private var y = Int.MIN_VALUE
    private var pageWidth = 0
    private var pageHeight = 0

    /** 拖动监听要跨事件保存按下点，不能每帧新建。 */
    private val drag by lazy { Drag() }

    /** 视图装配延迟到第一次显示：此时页面尺寸已经算好。 */
    private val chrome: BrowserWindowChrome by lazy {
        BrowserWindowChrome(
            context = appContext,
            onTouchBar = { view, event -> drag.onTouch(view, event) },
            onClose = { if (!controller.state.value.capturing) controller.close() },
            onExpand = { expand() },
            onSubmitUrl = ::submitUrl,
        )
    }

    val isShowing: Boolean get() = showing

    /**
     * 截图宿主：窗口常驻、已合成，抓帧前最多把贴边的小窗滑回完整可见（纯位移）。
     * 等一帧由调用方负责（`controller.screenshot → awaitRenderedFrame`）。
     */
    val captureHost = object : CaptureHost {
        override suspend fun <T> withVisibleCapture(capture: suspend (CaptureFrame) -> T): T {
            if (!showing) {
                throw BrowserActionException(
                    "The browser floating window is not available. Bring Kair to the foreground and retry.",
                )
            }
            expand(animate = false)
            val window = dialog?.window ?: throw BrowserActionException(
                "The browser floating window is not available. Bring Kair to the foreground and retry.",
            )
            return capture(CaptureFrame(window))
        }
    }

    /** 页面状态变化：有页面就显示 / 刷新，没有页面就销毁窗口。 */
    fun sync(state: BrowserPanelState) {
        if (state.url.isBlank()) {
            hide()
            return
        }
        if (!showing) {
            show(state)
            return
        }
        maybeUpgradeWindowType()
        render(state)
    }

    /** 前后台切换：overlay 窗口不受影响；降级窗只能在前台存在。 */
    fun onAppForeground(foreground: Boolean) {
        val state = controller.state.value
        if (state.url.isBlank()) return
        if (Settings.canDrawOverlays(appContext)) return
        if (foreground) sync(state) else hide()
    }

    /** 把控制器里的 WebView 挂进窗口（新建 / 重建后由协调器调用）。 */
    fun attachView(view: WebView) {
        attachedView = view
        val frame = chrome.pageFrame
        if (view.parent === frame) return
        (view.parent as? ViewGroup)?.removeView(view)
        frame.removeAllViews()
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** WebView 即将销毁：先从窗口中摘掉，避免悬空引用。 */
    fun detachView() {
        val view = attachedView
        attachedView = null
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
    }

    // ---- 显隐 --------------------------------------------------------------

    private fun show(state: BrowserPanelState): Boolean {
        if (showing) return true
        val wantOverlay = Settings.canDrawOverlays(appContext)
        // 降级窗依赖 Activity token：后台或没有 Activity 时不出窗，截图给明确错误。
        if (!wantOverlay && (!windows.appForeground || activityContext.findActivity() == null)) {
            return false
        }
        val base = if (wantOverlay) appContext else activityContext
        val dialog = createDialog(base, wantOverlay) ?: return false
        applyWindowLayout(dialog)
        dialog.replaceContent(ensureRoot())
        return try {
            dialog.show()
            showing = true
            overlayType = wantOverlay
            collapsed = false
            windows.windowReady = false
            windows.attachCurrentView()
            render(state)
            onVisibilityChanged(true)
            markReadyAfterDraw(dialog.window)
            true
        } catch (t: Throwable) {
            // 权限被撤销 / ROM 拒绝：不出窗口，截图走明确错误，不崩溃。
            showing = false
            windows.windowReady = false
            runCatching { dialog.dismiss() }
            this.dialog = null
            onVisibilityChanged(false)
            false
        }
    }

    /** 页面关闭 / 宿主销毁：交还 WebView 并移除窗口。 */
    fun hide() {
        if (!showing) return
        showing = false
        collapsed = false
        windows.windowReady = false
        scope?.cancel()
        scope = null
        runCatching { dialog?.dismiss() }
        dialog = null
        overlayType = false
        detachView()
        lastMode = null
        onVisibilityChanged(false)
    }

    /** 用户从设置页授权后：把降级窗换成 overlay 窗口（一次性重建，之后前后台都在）。 */
    private fun maybeUpgradeWindowType() {
        if (!showing || overlayType) return
        if (!Settings.canDrawOverlays(appContext)) return
        val state = controller.state.value
        hide()
        show(state)
    }

    /** 窗口画出第一帧后标记就绪；未就绪时截图返回「还在渲染」，绝不返回黑图。 */
    private fun markReadyAfterDraw(window: Window?) {
        val decor = window?.decorView ?: return
        val observer = decor.viewTreeObserver
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                decor.post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                    if (showing) windows.windowReady = true
                }
            }
        }
        observer.addOnDrawListener(listener)
        decor.invalidate()
    }

    private fun render(state: BrowserPanelState) {
        if (!showing) return
        chrome.render(state)
        syncViewportSize()
        if (lastMode != state.mode) applyMode(state.mode)
    }

    // ---- 窗口 --------------------------------------------------------------

    private fun Dialog.replaceContent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        setContentView(view)
    }

    private fun createDialog(context: Context, overlay: Boolean): Dialog? {
        val dialog = Dialog(ContextThemeWrapper(context, android.R.style.Theme_Translucent_NoTitleBar))
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val window = dialog.window ?: return null
        if (overlay) window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        this.dialog = dialog
        return dialog
    }

    private fun ensureRoot(): FrameLayout {
        val root = chrome.root
        chrome.setPageHeight(pageHeight)
        return root
    }

    // ---- 尺寸 / 位置 --------------------------------------------------------

    private fun applyWindowLayout(dialog: Dialog) {
        val window = dialog.window ?: return
        val size = pageSizePx()
        pageWidth = size.first
        pageHeight = size.second
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(pageWidth, pageHeight + dp(TOP_BAR_HEIGHT_DP))
        if (x == Int.MIN_VALUE) x = (metrics.widthPixels - pageWidth - dp(16)).coerceAtLeast(0)
        if (y == Int.MIN_VALUE) y = dp(10)
        x = x.coerceIn(0, (metrics.widthPixels - pageWidth).coerceAtLeast(0))
        y = y.coerceIn(dp(10), maxY())
        moveTo(x, y)
    }

    /** 页面区域尺寸：跟当前屏幕的宽高比走（竖屏 9:19.5 / 横屏 19.5:9），见 [browserWindowSizePx]。 */
    private fun pageSizePx(): Pair<Int, Int> =
        // 下限只兜底异常屏幕：0.30 屏宽在常见手机上约 108dp 宽，下限必须低于它才不会
        // 把「缩小一半」吃掉（旧值 160 / 220 会顶住宽度，实际只缩到 ~0.4 屏宽）。
        browserWindowSizePx(metrics.widthPixels, metrics.heightPixels, dp(96), dp(96))

    /** 屏幕旋转 / 分屏后视口变了：按新尺寸重排小窗，位置夹回屏幕内。 */
    private fun syncViewportSize() {
        val dialog = dialog ?: return
        val size = pageSizePx()
        if (size.first == pageWidth && size.second == pageHeight) return
        pageWidth = size.first
        pageHeight = size.second
        chrome.setPageHeight(pageHeight)
        applyWindowLayout(dialog)
    }

    private fun maxY(): Int =
        (metrics.heightPixels - pageHeight - dp(TOP_BAR_HEIGHT_DP) - dp(24)).coerceAtLeast(dp(10))

    private fun moveTo(newX: Int, newY: Int) {
        x = newX
        y = newY
        val window = dialog?.window ?: return
        val attrs = window.attributes
        attrs.x = newX
        attrs.y = newY
        window.setAttributes(attrs)
    }

    private fun animateTo(targetX: Int, targetY: Int) {
        if (x == targetX && y == targetY) return
        val fromX = x
        val fromY = y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                moveTo(
                    (fromX + (targetX - fromX) * fraction).toInt(),
                    (fromY + (targetY - fromY) * fraction).toInt(),
                )
            }
            start()
        }
    }

    // ---- 贴边 ---------------------------------------------------------------

    /** 截图前 / 点把手：把贴边的小窗滑回完整可见（默认 160ms，抓帧时立即到位）。 */
    fun expand(animate: Boolean = true) {
        if (!collapsed) return
        collapsed = false
        chrome.hideEdgeHandle()
        val targetX = if (collapsedLeft) 0 else (metrics.widthPixels - pageWidth).coerceAtLeast(0)
        val targetY = y.coerceIn(dp(10), maxY())
        if (animate) animateTo(targetX, targetY) else moveTo(targetX, targetY)
    }

    private fun collapse(toRight: Boolean) {
        collapsed = true
        collapsedLeft = !toRight
        chrome.showEdgeHandle(toRight)
        val targetX = if (toRight) {
            metrics.widthPixels - dp(EDGE_HANDLE_WIDTH_DP)
        } else {
            -(pageWidth - dp(EDGE_HANDLE_WIDTH_DP))
        }
        animateTo(targetX, y)
    }

    /** 松手后的去向：拖出屏幕大半 → 贴边半隐；否则吸到最近的边、完整可见。 */
    private fun settleAfterDrag() {
        val center = x + pageWidth / 2
        when {
            center > metrics.widthPixels - dp(24) -> collapse(toRight = true)
            center < dp(24) -> collapse(toRight = false)
            else -> {
                collapsed = false
                chrome.hideEdgeHandle()
                val targetX = if (center < metrics.widthPixels / 2) 0 else metrics.widthPixels - pageWidth
                animateTo(targetX, y.coerceIn(dp(10), maxY()))
            }
        }
    }

    /** 按 touch slop 区分点击与拖动；拖动移动整个窗口。 */
    private inner class Drag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(appContext).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(target: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = x
                    startY = y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) moveTo((startX + dx).toInt(), (startY + dy).toInt())
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) settleAfterDrag()
                    return true
                }
            }
            return false
        }
    }

    // ---- 模式 / 地址栏 ------------------------------------------------------

    /** 可聚焦 = WebView 收触摸与输入法；Agent 模式下窗口不抢焦点，挡板吃掉页面触摸。 */
    private fun applyMode(mode: BrowserMode) {
        lastMode = mode
        val window = dialog?.window ?: return
        val human = mode == BrowserMode.HUMAN
        chrome.setHuman(human, controller.state.value.url)
        if (human) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            hideIme()
        }
        // addFlags / clearFlags 只改本地 attributes，个别 ROM 不会主动重下发；
        // 显式 setAttributes 触发 updateViewLayout，确保「可聚焦 / 可输入」真的生效。
        window.setAttributes(window.attributes)
    }

    /** 悬浮窗地址栏导航：和面板地址栏一样走 `navigateFromUser`（HUMAN 模式允许）。 */
    private fun submitUrl() {
        val target = chrome.urlText()
        if (target.isEmpty()) return
        val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also {
            scope = it
        }
        scope.launch {
            val opened = runCatching { controller.navigateFromUser(target) }.isSuccess
            if (opened) {
                hideIme()
            } else {
                Toast.makeText(appContext, R.string.browser_url_invalid, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideIme() {
        val view = dialog?.window?.decorView ?: return
        appContext.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
