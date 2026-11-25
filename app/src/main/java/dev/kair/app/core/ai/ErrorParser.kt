package dev.kair.app.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 传输层错误的结构化解析与分类。
 *
 * 为什么需要：各厂商 / 中转站的错误体形状完全不同（OpenAI `error.message`、Anthropic
 * `error.message`、Google `error.status`、部分中转站裸 `message` 或纯文本），此前只按
 * 错误文本做关键字匹配，既容易误判（`HTTP 429: insufficient_quota` 被当成可重试），
 * 也无法统一展示。这里统一「取详情 → 分类」两步：分类只由 [TransientRetry] 消费，
 * 决定是否自动重发；展示则只给状态码 + 服务端原文（见 [describeError]）。
 */

/** 错误分类；[transient] 决定内核是否自动重发同一请求。 */
internal enum class ErrorClassification {
    RATE_LIMIT,
    SERVER_ERROR,
    TRANSIENT_HTTP,
    OVERLOADED,
    CAPACITY,
    TIMEOUT,

    /** 配额 / 余额耗尽：常常以 429 出现，但重试无意义。 */
    QUOTA,
    AUTH,
    PARAM,
    UNKNOWN;

    val transient: Boolean
        get() = when (this) {
            RATE_LIMIT, SERVER_ERROR, TRANSIENT_HTTP, OVERLOADED, CAPACITY, TIMEOUT -> true
            QUOTA, AUTH, PARAM, UNKNOWN -> false
        }
}

/** 明确不属于瞬时、重试无意义的错误文本标记（优先于 HTTP 状态码判定）。 */
private val NON_TRANSIENT_MARKERS = listOf(
    "invalid_request_error",
    "invalid_request",
    "authentication_error",
    "permission_error",
    "not_found_error",
    "invalid_api_key",
    "model_not_found",
    "insufficient_quota", // OpenAI 明确：配额耗尽时 429 不可重试
)

private val HTTP_STATUS = Regex("""\bHTTP\s+(\d{3})\b""")

/**
 * 从传输层拼出的错误文本里取 HTTP 状态码。
 *
 * 内置传输层的格式固定为 `HTTP {code}: {body}`；第三方插件若不按此格式上报，
 * 就退化为纯文本分类，不影响可用性。
 */
internal fun errorStatusCode(message: String): Int? =
    HTTP_STATUS.find(message)?.groupValues?.get(1)?.toIntOrNull()

/**
 * 递归提取错误详情：厂商把真正给用户看的那句话埋在不同层级里，逐层找第一个
 * `error` / `detail` / `message` / `description` 即可覆盖主流格式。
 */
internal fun parseErrorDetail(element: JsonElement?): String {
    if (element == null) return ""
    return when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.firstOrNull()?.let(::parseErrorDetail) ?: ""
        is JsonObject -> {
            val keys = listOf("error", "detail", "message", "description")
            val nested = keys.firstNotNullOfOrNull { key -> element[key]?.let(::parseErrorDetail) }
            // 走到这里说明没有可识别的字段；保留原文以便排查（截断避免整页日志）。
            nested?.takeIf { it.isNotBlank() } ?: element.toString().take(800)
        }
    }
}

/**
 * 从原始错误文本里提取详情：能解析成 JSON 就递归取字段，否则原样返回（裸字符串 /
 * 中转站纯文本）。
 */
internal fun parseErrorDetail(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
    return try {
        parseErrorDetail(Json.parseToJsonElement(trimmed))
    } catch (_: Exception) {
        trimmed
    }
}

/**
 * 分类错误。[detail] 是 [parseErrorDetail] 的结果，[statusCode] 来自错误文本。
 *
 * 判定顺序刻意是「非瞬态标记 → HTTP 状态码 → 瞬态文本标记」：
 * `HTTP 429: insufficient_quota` 必须归为 [ErrorClassification.QUOTA]，
 * 而不是因为 429 就重试。
 */
internal fun classifyError(detail: String, statusCode: Int?): ErrorClassification {
    NON_TRANSIENT_MARKERS.firstOrNull { detail.contains(it, ignoreCase = true) }?.let { marker ->
        return when (marker) {
            "insufficient_quota" -> ErrorClassification.QUOTA
            "invalid_api_key", "authentication_error", "permission_error" -> ErrorClassification.AUTH
            else -> ErrorClassification.PARAM
        }
    }
    when (statusCode) {
        401, 403 -> return ErrorClassification.AUTH
        400, 402, 404, 422 -> return ErrorClassification.PARAM
        408, 409 -> return ErrorClassification.TRANSIENT_HTTP
        429 -> return ErrorClassification.RATE_LIMIT
        in 500..599 -> return ErrorClassification.SERVER_ERROR
    }
    return when {
        detail.contains("overloaded", ignoreCase = true) -> ErrorClassification.OVERLOADED
        detail.contains("rate limit", ignoreCase = true) ||
            detail.contains("rate_limit", ignoreCase = true) ||
            detail.contains("too many requests", ignoreCase = true) -> ErrorClassification.RATE_LIMIT
        detail.contains("capacity", ignoreCase = true) -> ErrorClassification.CAPACITY
        detail.contains("timed out", ignoreCase = true) ||
            detail.contains("timeout", ignoreCase = true) -> ErrorClassification.TIMEOUT
        detail.contains("service unavailable", ignoreCase = true) ||
            detail.contains("temporarily unavailable", ignoreCase = true) -> ErrorClassification.SERVER_ERROR
        else -> ErrorClassification.UNKNOWN
    }
}

/**
 * 面向用户展示的错误文本：**不翻译分类**，只给 HTTP 状态码 + 服务端原文。
 *
 * 为什么不再给「请求参数有误」「配额已用尽」这类文案：分类只能服务「是否自动重试」，
 * 一旦拿来翻译就一定在边界上猜错 —— 402 余额不足被说成参数有误就是典型。原文是唯一
 * 能回答「到底哪里错了」的东西。
 *
 * 本地传输异常（没有 HTTP 状态码）在源头已由 [localFailureMessage] 按异常类型换成中文，
 * 这里只做透传 —— 服务端原文与插件自带的说明都不翻译。
 *
 * 分类仍然保留，只由 [classifyError] 提供给 [TransientRetry] 判定重试，不进入转录。
 */
internal fun describeError(detail: String, statusCode: Int?): String {
    val text = detail.trim().ifBlank { "未知错误" }
    // 内置传输层已拼成 `HTTP {code}: {body}`，原文本身完整，不要再重复一行状态码。
    val body = if (statusCode == null || text.startsWith("HTTP $statusCode")) text else "HTTP $statusCode\n$text"
    return body.takeIf { it.length <= ERROR_DISPLAY_LIMIT } ?: (body.take(ERROR_DISPLAY_LIMIT) + TRUNCATED_HINT)
}

/**
 * 转录里最多展示这么多字符。
 *
 * 正常 API 错误只有几百字符，完全放得下；但网关 / 中转站可能返回整页 HTML，
 * 原样写进历史会在下一回合被重新发给模型、白白吃掉上下文，所以留一个远高于
 * 真实错误的上限。完整响应原文仍可从「请求诊断」面板导出。
 */
private const val ERROR_DISPLAY_LIMIT = 500

private const val TRUNCATED_HINT = "…（已截断，完整原文见「请求诊断」的导出）"
