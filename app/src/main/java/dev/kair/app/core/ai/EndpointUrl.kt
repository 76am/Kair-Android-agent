package dev.kair.app.core.ai

/**
 * 取 baseUrl 的主机名（去 scheme / userinfo / 端口 / 路径 / query，统一小写）。
 *
 * 端点身份判定的**唯一归一化点**：LongCat 图片上限（[isLongCatHost]）、思考回传的来源键
 * （U76，`TurnExecutor` 落库与装配共用同一份归一化结果）、`EchoRequired` 显式表都走这里。
 * 各调用点自己再写一份 substring 链，就会出现「同一端点两种写法」的漏判。
 */
internal fun hostOf(baseUrl: String): String = baseUrl.trim()
    .substringAfter("://")
    .substringBefore("/")
    .substringBefore("?")
    .substringAfterLast('@')
    .substringBefore(':')
    .lowercase()

/** 从用户粘贴的地址识别出的协议，以及归一化后的 API 根地址。 */
internal data class InferredEndpoint(
    val transportId: String,
    val baseUrl: String,
)

/**
 * 识别「直接粘贴完整接口地址」的情况，并把地址收回成传输层期望的 API 根。
 *
 * 用户常从服务商文档复制 `.../v1/chat/completions` 这类完整端点，而各传输层会
 * 自己拼接端点后缀（见 [OpenAiProvider] / [OpenAiResponsesProvider] /
 * [AnthropicProvider]）。这里做确定性归一化，省掉「先切协议才敢填地址」的门槛：
 * - `/chat/completions` → Chat Completions
 * - `/responses` → Responses
 * - `/messages`（含 Anthropic 的 `/v1/messages`）→ Anthropic Messages
 * - `anthropic.com` 主机或 `/anthropic` 路径 → Anthropic Messages
 *
 * 返回 null 表示「看不出协议」：调用方保留用户当前选择，不擅自改写地址。
 */
internal fun inferEndpoint(raw: String): InferredEndpoint? {
    val url = raw.trim().trimEnd('/')
    if (url.isEmpty()) return null
    val lower = url.lowercase()

    val endpointSuffix = when {
        lower.endsWith("/chat/completions") -> "/chat/completions" to TransportIds.OPENAI_CHAT
        lower.endsWith("/responses") -> "/responses" to TransportIds.OPENAI_RESPONSES
        lower.endsWith("/messages") -> "/messages" to TransportIds.ANTHROPIC_MESSAGES
        else -> null
    }
    if (endpointSuffix != null) {
        val (suffix, transportId) = endpointSuffix
        var base = url.dropLast(suffix.length).trimEnd('/')
        // Anthropic 传输层自己拼 `/v1/messages`，根地址要把多出来的 `/v1` 一并收回。
        if (transportId == TransportIds.ANTHROPIC_MESSAGES && base.lowercase().endsWith("/v1")) {
            base = base.dropLast("/v1".length)
        }
        return InferredEndpoint(transportId, base)
    }

    // 没有完整端点时，用主机 / 路径特征兜底（只认无歧义的入口）。
    val host = lower.substringAfter("://", "").substringBefore('/')
    val isAnthropic = host == "api.anthropic.com" ||
        host.endsWith(".anthropic.com") ||
        lower.endsWith("/anthropic")
    if (!isAnthropic) return null

    val base = if (lower.endsWith("/v1")) url.dropLast("/v1".length) else url
    return InferredEndpoint(TransportIds.ANTHROPIC_MESSAGES, base.trimEnd('/'))
}
