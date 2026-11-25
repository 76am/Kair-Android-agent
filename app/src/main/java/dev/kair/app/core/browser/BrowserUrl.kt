package dev.kair.app.core.browser

import java.net.URI

private val allowedSchemes = setOf("http", "https")

/**
 * 把用户 / 模型给的地址归一成可加载的 http(s) URL；不合法返回 null。
 *
 * 只做**语法**判断，不做主机白名单：手机连 PC 上的 dev server（`192.168.x.x`）是主场景，
 * 按回环地址限制反而把真实用法挡掉。危险的是 scheme（`file:` / `content:` /
 * `javascript:` / `data:`），不是主机名。
 *
 * 没写 scheme 时补 `http://`（本地 dev server 几乎都是明文）；写了 `://` 却仍不是
 * http(s) 的一律拒绝，绝不「纠正」成 http。
 */
fun normalizeBrowserUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in allowedSchemes) return null
    if (uri.host.isNullOrBlank()) return null
    // 带凭据的 URL 会把密码写进会话与工具输出；P1 不需要，直接拒绝。
    if (uri.userInfo != null) return null
    return uri.toString()
}

/** 顶层导航是否允许留在 WebView 里。非 http(s) 一律交给宿主拦截。 */
fun isAllowedBrowserNavigation(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()
}
