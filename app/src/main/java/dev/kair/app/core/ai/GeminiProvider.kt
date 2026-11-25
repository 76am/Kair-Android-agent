package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Gemini **原生**传输层（`:streamGenerateContent`），由内置插件注册。
 *
 * 与 OpenAI 系的最大差异：
 * - 路径与鉴权：`{base}/v1beta/models/{model}:streamGenerateContent?alt=sse` + `x-goog-api-key`；
 * - 请求体是 protobuf 风格的 `contents[].parts[]`，工具定义包在 `functionDeclarations` 里，
 *   schema 以完整 JSON Schema 走 `parametersJsonSchema`（legacy `parameters` 只接受
 *   OpenAPI 3.0 子集，会吞掉 `additionalProperties` 等约束）；
 * - 流式响应每帧是一份完整 `GenerateContentResponse`，推理以 `parts[].thought = true` 区分，
 *   工具调用以完整 `functionCall`（非增量参数）出现；
 * - 同模型多轮必须把上一轮 `thoughtSignature` 原样回灌（text / functionCall 均可携带），
 *   换模型后不再回灌，否则 Gemini 会拒绝请求。
 */
class GeminiProvider : ProviderTransport {

    override val id: String = TransportIds.GEMINI_GENERATE

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
        val url = streamUrl(config.baseUrl, request.model)
        val body = buildBody(request)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            // 交给共享拦截器按会话记录真实请求，供「请求诊断」面板导出。
            .tag(CaptureTag::class.java, CaptureTag(request.sessionId))
        if (config.apiKey.isNotBlank()) builder.header("x-goog-api-key", config.apiKey)
        config.headers.forEach { (k, v) -> builder.header(k, v) }

        val call = HttpClient.client.newCall(builder.build())
        cancelHttpCallWhenCancelled(call)

        val response = try {
            call.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(StreamEvent.Failure(localFailureMessage(e), retryable = true))
            return@flow
        }

        if (!response.isSuccessful) {
            val err = response.body?.string().orEmpty()
            emit(
                StreamEvent.Failure(
                    "HTTP ${response.code}: ${err.take(800)}",
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                ),
            )
            return@flow
        }

        val source = response.body?.source() ?: run {
            emit(StreamEvent.Failure(EMPTY_BODY_MESSAGE, retryable = true))
            return@flow
        }

        val decoder = GeminiDecoder()
        val idle = AtomicBoolean(false)
        try {
            coroutineScope {
                val activity = newActivityClock()
                val watchdog = startSseWatchdog(source, activity) {
                    idle.set(true)
                    source.close()
                }
                try {
                    parseSse(source) { activity.set(System.currentTimeMillis()) }.collect { sse ->
                        if (sse.data == "[DONE]") return@collect
                        decoder.accept(sse.data).forEach { emit(it) }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            emit(
                StreamEvent.Failure(
                    if (idle.get()) stalledUpstreamMessage() else localFailureMessage(e),
                    retryable = if (idle.get()) false else decoder.retryableOnAbort(),
                ),
            )
            return@flow
        }

        if (idle.get()) {
            emit(StreamEvent.Failure(stalledUpstreamMessage(), retryable = false))
            return@flow
        }
        decoder.finish().forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    private fun streamUrl(baseUrl: String, model: String): String {
        val root = baseUrl.trimEnd('/')
        val base = if (root.endsWith("/v1beta")) root else "$root/v1beta"
        val name = model.removePrefix("models/")
        return "$base/models/$name:streamGenerateContent?alt=sse"
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", request.systemPrompt) } }
            }
        }
        put("contents", buildContents(request.messages, request))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { t ->
                            addJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                // Gemini 的 `parametersJsonSchema` 接受完整 JSON Schema
                                // （anyOf / oneOf / const / additionalProperties 都能保留），
                                // 不再裁剪成 legacy `parameters` 的 OpenAPI 子集。
                                put("parametersJsonSchema", t.parameters)
                            }
                        }
                    }
                }
            }
        }
        val config = buildJsonObject {
            request.options.temperature?.let { put("temperature", it) }
            request.options.maxTokens?.let { put("maxOutputTokens", it) }
            request.options.topP?.let { put("topP", it) }
            request.options.seed?.let { put("seed", it) }
            request.options.reasoningEffort?.let { effort -> putThinking(this, request.model, effort) }
        }
        if (config.isNotEmpty()) put("generationConfig", config)
    }

    /**
     * Gemini 的推理开关在 `generationConfig.thinkingConfig`：
     * `includeThoughts` 让服务端把推理作为 `thought=true` 的 part 回传；
     * Gemini 3 用 `thinkingLevel`，2.5 按型号给预算，更早的模型没有可调预算则不发。
     */
    private fun putThinking(config: kotlinx.serialization.json.JsonObjectBuilder, model: String, effort: String) {
        val level = effort.lowercase()
        val disabled = level == "off" || level == "none"

        if (model.contains("gemini-3", ignoreCase = true)) {
            config.putJsonObject("thinkingConfig") {
                // Gemini 3 不支持完全关闭思考；off 降到最低档。
                put(
                    "thinkingLevel",
                    when (level) {
                        "minimal", "low" -> "LOW"
                        "medium" -> "MEDIUM"
                        else -> "HIGH"
                    },
                )
                put("includeThoughts", true)
            }
            return
        }

        if (model.contains("gemini-2.5", ignoreCase = true) || model.contains("thinking", ignoreCase = true)) {
            config.putJsonObject("thinkingConfig") {
                if (!disabled) put("includeThoughts", true)
                put("thinkingBudget", if (disabled) 0 else geminiBudget(model, level))
            }
        }
        // 其它 Gemini 模型不支持思考参数：整个 thinkingConfig 不发送。
    }

    /** 与 pi-ai / DSH 的预算表一致；未知型号用 -1（动态预算）。 */
    private fun geminiBudget(model: String, level: String): Int {
        level.toIntOrNull()?.let { return it }
        val tiers = when {
            model.contains("2.5-pro", ignoreCase = true) ->
                mapOf("minimal" to 128, "low" to 2048, "medium" to 8192, "high" to 32768)
            model.contains("2.5-flash-lite", ignoreCase = true) ->
                mapOf("minimal" to 512, "low" to 2048, "medium" to 8192, "high" to 24576)
            model.contains("2.5-flash", ignoreCase = true) ->
                mapOf("minimal" to 128, "low" to 2048, "medium" to 8192, "high" to 24576)
            else -> return -1
        }
        return tiers[level] ?: tiers.getValue("high")
    }

    /**
     * 会话历史 → `contents`。相邻同角色消息合并成一条（工具结果与紧随的 user 图片
     * 都属于 user 回合）；推理正文与 `thoughtSignature` 只在**同一来源端点**
     * （providerId + host + model，U76）时原样带上（Gemini 3 多轮工具调用依赖它）。
     */
    private fun buildContents(messages: List<ChatMessage>, request: ChatRequest): JsonArray {
        val turns = ArrayList<Pair<String, MutableList<JsonElement>>>()
        fun add(role: String, part: JsonElement) {
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second.add(part)
            else turns.add(role to mutableListOf(part))
        }

        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> Unit // carried by `systemInstruction`
                Role.USER -> m.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> if (p.text.isNotEmpty()) {
                            add("user", buildJsonObject { put("text", p.text) })
                        }
                        is Part.Image -> {
                            // Gemini 原生只接受内联字节或 Files API 的 fileUri，不能直接抓远程 URL。
                            val base64 = p.base64
                            if (!base64.isNullOrBlank()) {
                                add(
                                    "user",
                                    buildJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", p.mediaType)
                                            put("data", base64)
                                        }
                                    },
                                )
                            }
                        }
                        else -> Unit
                    }
                }
                Role.ASSISTANT -> {
                    // 签名只对产出它的端点有效（U76 三元组）：换端点后回灌旧签名会被 Gemini 拒绝。
                    val sameOrigin = m.replaysThinkingTo(request)
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Text -> {
                                val sig = p.signature.takeIf { sameOrigin }
                                if (p.text.isNotEmpty() || sig != null) {
                                    add(
                                        "model",
                                        buildJsonObject {
                                            put("text", p.text)
                                            if (sig != null) put("thoughtSignature", sig)
                                        },
                                    )
                                }
                            }
                            is Part.Thinking -> {
                                // Gemini 3 要求 thinking part 原样带回（thought: true +
                                // thoughtSignature）；换端点后签名无效，只留文本会改变推理，
                                // 故与正文 / 工具调用一致：非同来源直接不回放。
                                if (sameOrigin) {
                                    val sig = p.signature
                                    if (p.thinking.isNotEmpty() || sig != null) {
                                        add(
                                            "model",
                                            buildJsonObject {
                                                put("thought", true)
                                                put("text", p.thinking)
                                                if (sig != null) put("thoughtSignature", sig)
                                            },
                                        )
                                    }
                                }
                            }
                            is Part.ToolCall -> add(
                                "model",
                                buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", p.name)
                                        put("args", parseArgs(p.arguments))
                                        if (p.id.isNotBlank()) put("id", p.id)
                                    }
                                    if (sameOrigin) p.signature?.let { put("thoughtSignature", it) }
                                },
                            )
                            else -> Unit
                        }
                    }
                }
                Role.TOOL_RESULT -> m.toolResults.forEach { r ->
                    add(
                        "user",
                        buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", r.toolName)
                                if (r.toolCallId.isNotBlank()) put("id", r.toolCallId)
                                putJsonObject("response") { put("result", r.content) }
                            }
                        },
                    )
                }
            }
        }

        return buildJsonArray {
            turns.forEach { (role, parts) ->
                addJsonObject {
                    put("role", role)
                    put("parts", JsonArray(parts))
                }
            }
        }
    }

    private fun parseArgs(raw: String): JsonElement =
        if (raw.isBlank()) buildJsonObject { } else try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            buildJsonObject { }
        }
}

/** Gemini SSE（每帧一份完整的 `GenerateContentResponse`）→ `StreamEvent` 的纯解码器。 */
internal class GeminiDecoder {

    private val acc = LinkedHashMap<String, GeminiToolAcc>()
    private val toolOrder = ArrayList<String>()
    private val nonce = UUID.randomUUID().toString().take(8)

    private var textId: String? = null

    /** 当前正文段累积的 `thoughtSignature`（可能出现在任意增量上）。 */
    private var textSignature: String? = null
    private var reasoningId: String? = null

    /** 当前推理段累积的 `thoughtSignature`（可能出现在任意增量上）。 */
    private var reasoningSignature: String? = null
    private var usage: Usage? = null
    private var finishReason: String? = null
    private var failed = false
    private var sawText = false
    private var sawReasoning = false
    private var textSeq = 0
    private var reasoningSeq = 0

    fun accept(data: String): List<StreamEvent> {
        val root = try {
            Json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        root["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.intOrNull
            val message = err["message"]?.jsonPrimitive?.strOrNull() ?: "Gemini error"
            failed = true
            return listOf(StreamEvent.Failure(if (code == null) message else "HTTP $code: $message"))
        }
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.strOrNull()?.let { reason ->
            failed = true
            return listOf(StreamEvent.Failure("Gemini blocked the prompt ($reason)", retryable = false))
        }

        root["usageMetadata"]?.jsonObject?.let { usage = parseGeminiUsage(it) }

        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        candidate["finishReason"]?.jsonPrimitive?.strOrNull()?.let { finishReason = it }
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return emptyList()

        val out = ArrayList<StreamEvent>(parts.size)
        parts.forEach { el ->
            val part = el as? JsonObject ?: return@forEach
            val call = part["functionCall"]?.jsonObject
            val thought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
            val text = part["text"]?.jsonPrimitive?.strOrNull()
            val signature = part["thoughtSignature"]?.jsonPrimitive?.strOrNull()

            when {
                call != null -> toolCall(call, signature, out)
                thought && !text.isNullOrEmpty() -> reasoning(text, signature, out)
                !text.isNullOrEmpty() -> bodyText(text, signature, out)
                // text 是空串但带签名：保留一个空正文段（pi-ai 同样处理）。
                text != null -> applyTextSignature(signature, out)
            }
        }
        return out
    }

    private fun bodyText(text: String, signature: String?, out: MutableList<StreamEvent>) {
        closeReasoning(out)
        if (!signature.isNullOrEmpty()) textSignature = signature
        val id = textId ?: "gemini-t${textSeq++}".also {
            textId = it
            out.add(StreamEvent.TextStart(it))
        }
        sawText = true
        out.add(StreamEvent.TextDelta(text))
    }

    /**
     * 签名可能挂在一个没有可见文本的 part 上。Gemini 3 要求原样带回，
     * 因此为它保留一个空正文段（不产出 TextDelta）。
     */
    private fun applyTextSignature(signature: String?, out: MutableList<StreamEvent>) {
        if (signature.isNullOrEmpty()) return
        textSignature = signature
        sawText = true
        if (textId == null) {
            val id = "gemini-t${textSeq++}"
            textId = id
            out.add(StreamEvent.TextStart(id))
        }
    }

    private fun reasoning(text: String, signature: String?, out: MutableList<StreamEvent>) {
        closeText(out)
        if (!signature.isNullOrEmpty()) reasoningSignature = signature
        val id = reasoningId ?: "gemini-r${reasoningSeq++}".also {
            reasoningId = it
            out.add(StreamEvent.ReasoningStart(it, "REASONING_TEXT"))
        }
        sawReasoning = true
        out.add(StreamEvent.ReasoningDelta(text))
    }

    private fun toolCall(call: JsonObject, signature: String?, out: MutableList<StreamEvent>) {
        val name = call["name"]?.jsonPrimitive?.strOrNull() ?: return
        val id = call["id"]?.jsonPrimitive?.strOrNull() ?: "gemini_${nonce}_${acc.size}"
        if (acc.containsKey(id)) return
        closeText(out)
        closeReasoning(out)

        val index = acc.size
        val args = (call["args"] as? JsonObject)?.toString() ?: "{}"
        acc[id] = GeminiToolAcc(index, id, name, StringBuilder(args))
        toolOrder.add(id)
        out.add(StreamEvent.ToolCallStarted(index, id, name, signature))
        out.add(StreamEvent.ToolCallArgsDelta(index, args))
    }

    fun retryableOnAbort(): Boolean = !sawText && !sawReasoning && acc.isEmpty() && usage == null

    fun finish(): List<StreamEvent> {
        if (failed) return emptyList()
        val out = ArrayList<StreamEvent>()
        closeText(out)
        closeReasoning(out)
        if (!sawText && !sawReasoning && acc.isEmpty()) {
            val reason = finishReason
            val blocked = reason != null && reason !in setOf("STOP", "MAX_TOKENS")
            out.add(
                if (blocked) {
                    StreamEvent.Failure("Gemini stopped the response ($reason)", retryable = false)
                } else {
                    StreamEvent.Failure(emptyStopMessage(), retryable = true)
                },
            )
            return out
        }
        usage?.let { out.add(StreamEvent.UsageEvent(it)) }
        if (toolOrder.isNotEmpty()) {
            out.add(
                StreamEvent.ToolCallsCompleted(
                    toolOrder.mapNotNull { acc[it] }
                        .sortedBy { it.index }
                        .map { ToolCallData(it.id, it.name, it.args.toString().ifBlank { "{}" }) },
                ),
            )
        }
        val stop = when {
            acc.isNotEmpty() -> StopReason.TOOL_USE
            finishReason == "MAX_TOKENS" -> StopReason.LENGTH
            else -> StopReason.STOP
        }
        out.add(StreamEvent.Completed(stop))
        return out
    }

    private fun closeText(out: MutableList<StreamEvent>) {
        textId?.let { out.add(StreamEvent.TextEnd(it, textSignature)) }
        textId = null
        textSignature = null
    }

    private fun closeReasoning(out: MutableList<StreamEvent>) {
        reasoningId?.let { out.add(StreamEvent.ReasoningEnd(it, reasoningSignature)) }
        reasoningId = null
        reasoningSignature = null
    }

    private data class GeminiToolAcc(
        val index: Int,
        val id: String,
        val name: String,
        val args: StringBuilder,
    )
}

/**
 * `promptTokenCount` 含缓存命中部分，拆成互不重叠的 input / cacheRead；
 * 与 pi-ai 一致，`thoughtsTokenCount` 也计入 output（推理同样是输出计费 / 上下文占用），
 * 另单独留一份 `reasoning` 供展示。
 */
internal fun parseGeminiUsage(u: JsonObject): Usage {
    val prompt = u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val cached = u["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val output = u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val thoughts = u["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    val cacheRead = cached.coerceIn(0, prompt)
    return Usage(input = prompt - cacheRead, output = output + thoughts, cacheRead = cacheRead, reasoning = thoughts)
}
