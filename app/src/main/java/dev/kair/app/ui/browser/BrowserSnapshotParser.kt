package dev.kair.app.ui.browser

import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserElement
import dev.kair.app.core.browser.BrowserSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 页面动作脚本的返回。错误信息面向模型（英文）。 */
internal data class BrowserActionResult(
    val ok: Boolean,
    val message: String,
    val error: String,
)

/**
 * `evaluateJavascript` 的返回值是**字符串字面量**（脚本里的 JSON.stringify 再被 WebView
 * 包一层引号），所以要解两层：先取字符串，再解析 JSON 对象。
 */
private fun decodePayload(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") {
        throw BrowserActionException("The page did not return any data. Call browser_snapshot again.")
    }
    return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }
        .getOrElse { throw BrowserActionException("Could not read the page response.") }
}

internal fun parseSnapshot(raw: String?): BrowserSnapshot {
    val payload = decodePayload(raw)
    val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }
        .getOrElse { throw BrowserActionException("The page snapshot is not valid JSON.") }
    val elements = obj["elements"]?.jsonArray?.mapNotNull { item ->
        val el = item.jsonObject
        val ref = el["ref"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        BrowserElement(
            ref = ref,
            role = el["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = el["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            value = el["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            checked = el["checked"]?.jsonPrimitive?.booleanOrNull,
            disabled = el["disabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }.orEmpty()
    return BrowserSnapshot(
        url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        elements = elements,
        text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

internal fun parseActionResult(raw: String?): BrowserActionResult {
    val payload = decodePayload(raw)
    val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }
        .getOrElse { throw BrowserActionException("The page action returned invalid JSON.") }
    return BrowserActionResult(
        ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
        message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}
