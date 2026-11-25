package dev.kair.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserController
import dev.kair.app.core.browser.BrowserMode
import dev.kair.app.core.browser.BrowserNetworkLine
import dev.kair.app.core.browser.BrowserPageInfo
import dev.kair.app.core.browser.BrowserPanelState
import dev.kair.app.core.browser.BrowserScreenshot
import dev.kair.app.core.browser.BrowserSnapshot
import dev.kair.app.core.browser.isAllowedBrowserNavigation
import dev.kair.app.core.browser.normalizeBrowserUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * WebView 控制器：把「浏览器工具」与 Compose 面板接到同一个常驻 WebView 上。
 *
 * 所有 WebView 操作都切到主线程，并用 [gate] 串行化 —— 一次动作里要等 JS 回调，
 * 期间主线程仍会被别的工具/面板调用进去，没有闸门就会两个脚本同时改 DOM。
 *
 * 页面数据全部经 [state] 暴露；Console 走 `WebChromeClient`，网络走注入脚本
 * （fetch / XHR 带状态码），主文档失败走 `WebViewClient`。重复事件在写入处去重。
 *
 * 窗口归属（前台面板 / 后台悬浮窗）与截图路由在 [windows]（[BrowserWindowCoordinator]）。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AndroidBrowserController(
    private val appContext: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BrowserController {

    private val _state = MutableStateFlow(BrowserPanelState())
    override val state: StateFlow<BrowserPanelState> = _state.asStateFlow()

    private val main = Dispatchers.Main.immediate
    private val handler = Handler(Looper.getMainLooper())
    private val gate = Mutex()

    private var webView: WebView? = null
    private var pageFinished: CompletableDeferred<Boolean>? = null

    /** 渲染进程崩溃后要恢复的地址；重建 WebView 时用一次就清。 */
    private var pendingRestoreUrl: String? = null

    /**
     * 窗口归属与截图路由：渲染统一在常驻小窗（[BrowserWindowHost]）里，
     * 面板只做控制台，截图固定走小窗。
     */
    internal val windows = BrowserWindowCoordinator(
        appContext = appContext,
        currentView = { webView },
    )

    @Volatile
    private var destroyed = false

    override suspend fun open(url: String): BrowserPageInfo = withContext(main) {
        requireUsable()
        openInternal(url)
    }

    /** 用户自己发起的导航（面板 / 小窗地址栏）：共用加载逻辑，调用方负责切到 HUMAN。 */
    internal suspend fun navigateFromUser(url: String): BrowserPageInfo = withContext(main) { openInternal(url) }

    private suspend fun openInternal(url: String): BrowserPageInfo {
        val normalized = normalizeBrowserUrl(url)
            ?: throw BrowserActionException(
                "Only http(s) URLs are allowed. To view a local file, start a static server " +
                    "(for example `python3 -m http.server 8000`) and open `http://127.0.0.1:8000/<path>`.",
            )
        return gate.withLock {
            _state.update { it.copy(busy = true) }
            try {
                // 上一次导航的 console / network 不属于这个页面；不清掉会让人把旧报错误判成新问题。
                _state.update { it.copy(console = emptyList(), network = emptyList()) }
                pendingRestoreUrl = null
                val loaded = loadAndWait(ensureView(), normalized)
                // onPageFinished 只代表主文档解析完；SPA 首帧 / 异步渲染还要一点时间。
                if (loaded) delay(OPEN_SETTLE_MS)
                _state.recordAction("browser_open", normalized, loaded, clock())
                BrowserPageInfo(normalized, _state.value.title, loaded)
            } catch (t: Throwable) {
                _state.recordAction("browser_open", normalized, false, clock())
                throw t
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    override suspend fun snapshot(): BrowserSnapshot = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock {
            _state.update { it.copy(busy = true) }
            try {
                val page = parseSnapshot(evaluate(BrowserScripts.SNAPSHOT))
                _state.recordAction("browser_snapshot", "${page.elements.size} elements", true, clock())
                page
            } catch (t: Throwable) {
                _state.recordAction("browser_snapshot", "snapshot", false, clock())
                throw t
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    override suspend fun click(ref: String): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock { pageAction("browser_click", ref, BrowserScripts.click(validateRef(ref))) }
    }

    override suspend fun tap(x: Float, y: Float): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock {
            _state.update { it.copy(busy = true) }
            try {
                val message = performBrowserTap(ensureView(), x, y) { evaluate(BROWSER_SCALE_SCRIPT) }
                _state.recordAction("browser_tap", "($x, $y)", true, clock())
                message
            } catch (t: Throwable) {
                _state.recordAction("browser_tap", "($x, $y)", false, clock())
                throw t
            } finally {
                _state.update { it.copy(busy = false) }
                delay(ACTION_SETTLE_MS)
            }
        }
    }

    override suspend fun type(ref: String, text: String): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock { pageAction("browser_type", ref, BrowserScripts.type(validateRef(ref), text)) }
    }

    override suspend fun press(key: String): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock {
            val safeKey = key.trim().take(24)
            if (safeKey.isEmpty()) throw BrowserActionException("Key must not be empty.")
            pageAction("browser_press", safeKey, BrowserScripts.press(safeKey))
        }
    }

    override suspend fun scroll(direction: String, amount: Int?): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock {
            val dir = direction.trim().lowercase()
            if (dir !in setOf("up", "down", "top", "bottom")) {
                throw BrowserActionException("Direction must be up, down, top, or bottom.")
            }
            val px = (amount ?: 600).coerceIn(1, 5000)
            pageAction("browser_scroll", dir, BrowserScripts.scroll(dir, px))
        }
    }

    override suspend fun eval(expression: String): String = withContext(main) {
        requireUsable()
        requirePage()
        gate.withLock {
            val result = parseActionResult(evaluate(BrowserScripts.eval(expression)))
            if (!result.ok) throw BrowserActionException(result.error.ifBlank { "The expression failed." })
            result.message
        }
    }

    override suspend fun screenshot(): BrowserScreenshot = withContext(main) {
        requireUsable()
        gate.withLock {
            // 抓帧期间 UI 禁用关闭 / 接管：销毁窗口会让 PixelCopy 抓空。
            _state.update { it.copy(capturing = true) }
            try {
                windows.withVisibleCapture { frame ->
                    val view = ensureView()
                    // 等窗口真正画出这一帧再抓：贴边滑回 / 刚显示时都会截到黑屏。
                    awaitRenderedFrame(view, frame.window)
                    captureViewportPng(view, frame)
                }
            } finally {
                _state.update { it.copy(capturing = false) }
            }
        }
    }

    override fun setMode(mode: BrowserMode) {
        _state.update { it.copy(mode = mode) }
    }

    override fun goBack() {
        handler.post {
            val view = webView ?: return@post
            if (view.canGoBack()) view.goBack()
        }
    }

    override fun reload() {
        handler.post { webView?.reload() }
    }

    override fun clearConsole() {
        _state.update { it.copy(console = emptyList()) }
    }

    override fun clearNetwork() {
        _state.update { it.copy(network = emptyList()) }
    }

    /** 插件卸载时释放：销毁 WebView、清空状态。调用后本实例不可再用。 */
    internal fun release() {
        destroyed = true
        // 先同步清空状态：UI 可能正拿着本实例组合，下一个重组就会换成新控制器。
        _state.value = BrowserPanelState()
        handler.post { teardownWebView() }
    }

    /**
     * 用户主动关闭当前页面：销毁 WebView 并清空状态（悬浮小窗随之消失）。
     * 控制器本身仍可用，下一次 `browser_open` 会重建 WebView。
     */
    override fun close() {
        if (_state.value.capturing) return
        handler.post {
            teardownWebView()
            _state.value = BrowserPanelState()
        }
    }

    private fun teardownWebView() {
        pageFinished?.complete(false)
        pageFinished = null
        pendingRestoreUrl = null
        // 先从常驻小窗摘掉，再销毁：窗口里的悬空引用会跟着 Dialog 一起泄漏。
        windows.onViewDestroyed()
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    // ---- WebView 装配 ------------------------------------------------------

    private fun ensureView(): WebView {
        if (destroyed) throw BrowserActionException("The browser has been closed.")
        webView?.let { return it }
        val view = createBrowserWebView(appContext, Client(), Chrome(), Bridge(), BRIDGE_NAME)
        webView = view
        // 渲染进程崩溃后重建：新 WebView 是 about:blank，不恢复就是一块黑屏。
        pendingRestoreUrl?.let { url ->
            pendingRestoreUrl = null
            view.loadUrl(url)
        }
        // 重建的 WebView 还没挂在任何宿主里；挂到当前窗口，截图 / 面板才看得到它。
        windows.onViewCreated(view)
        return view
    }

    private suspend fun loadAndWait(view: WebView, url: String): Boolean {
        val signal = CompletableDeferred<Boolean>()
        pageFinished?.complete(false)
        pageFinished = signal
        _state.update { it.copy(url = url, title = "", loading = true, progress = 0) }
        view.loadUrl(url)
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { signal.await() } ?: false
        if (pageFinished === signal) pageFinished = null
        return loaded
    }

    private suspend fun pageAction(tool: String, detail: String, script: String): String {
        _state.update { it.copy(busy = true) }
        return try {
            val result = parseActionResult(evaluate(script))
            if (!result.ok) throw BrowserActionException(result.error.ifBlank { "The page action failed." })
            _state.recordAction(tool, detail, true, clock())
            result.message.ifBlank { "Done." }
        } catch (t: Throwable) {
            _state.recordAction(tool, detail, false, clock())
            throw t
        } finally {
            _state.update { it.copy(busy = false) }
            delay(ACTION_SETTLE_MS)
        }
    }

    private suspend fun evaluate(script: String): String? {
        val view = ensureView()
        // evaluateJavascript 没有「一定回调」的保证：渲染进程崩溃、页面死循环、beforeunload
        // 卡住时回调永远不会来。没有这道闸门，工具协程会一直挂到用户手动停止整轮。
        val raw = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }
        }
        return raw ?: throw BrowserActionException(
            "The page did not respond within ${EVAL_TIMEOUT_MS / 1000}s. Reload it and try again.",
        )
    }

    // ---- 状态写入 ----------------------------------------------------------

    private fun requireUsable() {
        if (destroyed) throw BrowserActionException("The browser has been closed.")
        if (_state.value.mode == BrowserMode.HUMAN) {
            throw BrowserActionException("The user has taken over the browser. Wait until control is handed back.")
        }
    }

    private fun requirePage() {
        if (_state.value.url.isBlank()) throw BrowserActionException("No page is open. Call browser_open first.")
    }

    // ---- 页面事件 ----------------------------------------------------------

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            _state.update { it.copy(url = url ?: it.url, loading = true, progress = 0) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            _state.update { it.copy(canGoBack = view.canGoBack()) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            _state.update {
                it.copy(
                    url = url ?: it.url,
                    title = view.title.orEmpty(),
                    loading = false,
                    progress = 100,
                    canGoBack = view.canGoBack(),
                )
            }
            val pageUrl = url.orEmpty()
            if (
                pageUrl.isNotBlank() &&
                pageUrl != "about:blank" &&
                _state.value.network.none { it.url == pageUrl && it.status != null }
            ) {
                _state.recordNetwork(BrowserNetworkLine("GET", pageUrl, 200, null, at = clock()))
            }
            view.evaluateJavascript(BrowserScripts.COLLECT, null)
            pageFinished?.complete(true)
            pageFinished = null
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            val url = request.url.toString()
            _state.recordNetwork(
                BrowserNetworkLine(
                    method = request.method,
                    url = url,
                    error = error.description.toString(),
                    at = clock(),
                ),
            )
            if (request.isForMainFrame) {
                _state.addConsole("error", "Page failed: ${error.description}", clock())
                _state.update { it.copy(loading = false) }
                pageFinished?.complete(false)
                pageFinished = null
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse,
        ) {
            val url = request.url.toString()
            _state.recordNetwork(
                BrowserNetworkLine(
                    method = request.method,
                    url = url,
                    status = response.statusCode,
                    at = clock(),
                ),
            )
            if (request.isForMainFrame) {
                _state.addConsole("error", "Page returned HTTP ${response.statusCode}", clock())
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isAllowedBrowserNavigation(request.url.toString())

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            handler.post {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (webView === view) webView = null
                // 重建后的 WebView 不会自动回到原页面；不记地址就是一块黑屏。
                pendingRestoreUrl = _state.value.url.ifBlank { null }
                _state.addConsole("error", "The page renderer crashed; browser restarted.", clock())
                _state.update {
                    it.copy(
                        loading = false,
                        progress = 0,
                        busy = false,
                        rendererGeneration = it.rendererGeneration + 1,
                    )
                }
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            _state.addConsole(consoleLevelOf(message.messageLevel()), message.message(), clock())
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _state.update { it.copy(progress = newProgress, loading = newProgress < 100) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            _state.update { it.copy(title = title.orEmpty()) }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun post(kind: String, payload: String) {
            handler.post { _state.onBrowserBridgeEvent(kind, payload, clock()) }
        }
    }

    private fun validateRef(ref: String): String {
        if (!REF_PATTERN.matches(ref)) {
            throw BrowserActionException("Invalid element ref. Call browser_snapshot and use its refs.")
        }
        return ref
    }

    private companion object {
        const val BRIDGE_NAME = "KairBrowser"
        const val LOAD_TIMEOUT_MS = 20_000L
        const val OPEN_SETTLE_MS = 400L
        const val EVAL_TIMEOUT_MS = 10_000L
        const val ACTION_SETTLE_MS = 150L
        val REF_PATTERN = Regex("^e\\d{1,3}$")
    }
}
