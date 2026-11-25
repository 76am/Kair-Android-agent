package dev.kair.app.ui.browser

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 新建 Dev Browser 的 WebView 并套用固定设置。
 *
 * 独立数据目录后缀由 `KairApplication` 在进程启动时设置（`BROWSER_DATA_DIR_SUFFIX`），
 * 这里不再重复调用，避免「只能设置一次」的 API 抛异常。
 */
internal fun createBrowserWebView(
    context: Context,
    client: WebViewClient,
    chrome: WebChromeClient,
    bridge: Any,
    bridgeName: String,
): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = userAgentString + " KairDevBrowser/1.0"
    }
    addJavascriptInterface(bridge, bridgeName)
    webViewClient = client
    webChromeClient = chrome
}
