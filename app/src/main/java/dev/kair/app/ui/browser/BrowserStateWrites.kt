package dev.kair.app.ui.browser

import android.webkit.ConsoleMessage
import dev.kair.app.core.browser.BrowserActionLine
import dev.kair.app.core.browser.BrowserConsoleLine
import dev.kair.app.core.browser.BrowserNetworkLine
import dev.kair.app.core.browser.BrowserPanelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 面板状态的三类写入（Console / Network / Action）与页面 bridge 事件解析。
 *
 * 从 `AndroidBrowserController` 拆出来的原因是单文件 500 行守卫；这里全是
 * `MutableStateFlow<BrowserPanelState>` 的扩展，不持有 WebView，JVM 侧也能直接构造验证。
 */

internal const val BROWSER_MAX_LINES = 200
internal const val BROWSER_MAX_CONSOLE_TEXT = 2000
internal const val BROWSER_NETWORK_DEDUP_MS = 500L

internal fun MutableStateFlow<BrowserPanelState>.addConsole(level: String, message: String, at: Long) {
    val text = message.trim().take(BROWSER_MAX_CONSOLE_TEXT)
    if (text.isEmpty()) return
    update { current ->
        val last = current.console.lastOrNull()
        if (last != null && last.level == level && last.message == text) {
            current
        } else {
            current.copy(
                console = (current.console + BrowserConsoleLine(level, text, at)).takeLast(BROWSER_MAX_LINES),
            )
        }
    }
}

internal fun MutableStateFlow<BrowserPanelState>.recordNetwork(line: BrowserNetworkLine) {
    update { current ->
        val last = current.network.lastOrNull()
        // 同一请求会被 onReceivedHttpError 与页面脚本各报一次：只合并**紧邻且同时刻**的重复，
        // 有间隔的重试（用户 / Agent 再点一次）必须留下第二条。
        val same = last != null &&
            last.method == line.method &&
            last.url == line.url &&
            last.status == line.status &&
            last.error == line.error &&
            line.at - last.at < BROWSER_NETWORK_DEDUP_MS
        if (same) {
            current
        } else {
            current.copy(network = (current.network + line).takeLast(BROWSER_MAX_LINES))
        }
    }
}

internal fun MutableStateFlow<BrowserPanelState>.recordAction(tool: String, detail: String, ok: Boolean, at: Long) {
    update { current ->
        current.copy(actions = (current.actions + BrowserActionLine(tool, detail, ok, at)).takeLast(BROWSER_MAX_LINES))
    }
}

/** 页面 bridge 事件（console / network）；payload 是页面提供的 JSON，解析失败直接丢弃。 */
internal fun MutableStateFlow<BrowserPanelState>.onBrowserBridgeEvent(kind: String, payload: String, at: Long) {
    val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
    when (kind) {
        "console" -> addConsole(
            obj["level"]?.jsonPrimitive?.contentOrNull ?: "log",
            obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            at,
        )

        "network" -> {
            val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (url.isBlank()) return
            recordNetwork(
                BrowserNetworkLine(
                    method = obj["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
                    url = url,
                    status = obj["status"]?.jsonPrimitive?.intOrNull,
                    durationMs = obj["durationMs"]?.jsonPrimitive?.intOrNull?.toLong(),
                    error = obj["error"]?.jsonPrimitive?.contentOrNull,
                    at = at,
                ),
            )
        }
    }
}

/** ConsoleMessage 级别 → 面板里的短标签。 */
internal fun consoleLevelOf(level: ConsoleMessage.MessageLevel): String = when (level) {
    ConsoleMessage.MessageLevel.ERROR -> "error"
    ConsoleMessage.MessageLevel.WARNING -> "warn"
    ConsoleMessage.MessageLevel.DEBUG -> "debug"
    ConsoleMessage.MessageLevel.TIP -> "info"
    else -> "log"
}
