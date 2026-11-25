package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.model.dataUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenAI **Responses API**（`POST {baseUrl}/responses`）传输层，由内置插件注册。
 *
 * 与 Chat Completions 的差异都收敛在事件解码里：正文 / 推理是带稳定 `item_id`
 * 的 output item，工具调用用 `call_id` 关联结果，因此可以直接映射到
 * `TextStart(id)` / `ReasoningStart(id, type)`，不需要合成 part id。
 *
 * 请求侧用 `store = false`：Kair 自己持有历史，不依赖服务端会话状态；
 * 推理内容不回灌（Responses 不返回加密推理块时无法重放），只回灌正文与工具调用。
 */
class OpenAiResponsesProvider : ProviderTransport {

    override val id: String = TransportIds.OPENAI_RESPONSES

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
        val url = config.baseUrl.trimEnd('/') + "/responses"
        val body = buildBody(request, config)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            // 交给共享拦截器按会话记录真实请求，供「请求诊断」面板导出。
            .tag(CaptureTag::class.java, CaptureTag(request.sessionId))
        if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${config.apiKey}")
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

        val decoder = ResponsesDecoder()
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
                        decoder.accept(sse.event, sse.data).forEach { emit(it) }
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

    internal fun buildBody(request: ChatRequest, config: ProviderConfig): JsonObject = buildJsonObject {
        put("model", request.model)
        if (request.systemPrompt.isNotBlank()) put("instructions", request.systemPrompt)
        put("input", buildJsonArray { addResponsesInput(request.messages, this, request) })
        put("stream", true)
        // 不依赖服务端会话状态：历史由 Kair 自己持有并逐轮回灌。
        put("store", false)

        val options = request.options
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("max_output_tokens", it) }
        options.topP?.let { put("top_p", it) }
        // Responses 只有推理模型认 reasoning；summary=auto 让服务端回推理摘要，
        // 对应 ReasoningStart(..., "SUMMARY_TEXT")。
        options.reasoningEffort?.let {
            putJsonObject("reasoning") {
                put("effort", it)
                put("summary", "auto")
            }
            // store=false 时必须显式索取加密推理内容，否则 reasoning item 无法在
            // 下一轮回灌（服务端会以「缺少 encrypted_content」拒绝）。
            putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
        }
        val cacheKey = request.sessionId
        if (request.cache && cacheKey != null && config.baseUrl.contains("api.openai.com")) {
            put("prompt_cache_key", cacheKey.take(64))
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
        }
    }

    /**
     * Responses 的 input 是**扁平 item 列表**：正文 / 图片是 message item，
     * 工具调用与工具结果各自是独立 item（不是挂在 message 下面）。
     */
    private fun addResponsesInput(messages: List<ChatMessage>, arr: JsonArrayBuilder, request: ChatRequest) {
        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> Unit // carried by `instructions`
                Role.USER -> {
                    val content = buildJsonArray {
                        m.parts.forEach { p ->
                            when (p) {
                                is Part.Text -> if (p.text.isNotEmpty()) {
                                    addJsonObject {
                                        put("type", "input_text")
                                        put("text", p.text)
                                    }
                                }
                                is Part.Image -> addJsonObject {
                                    put("type", "input_image")
                                    put("image_url", p.dataUrl)
                                }
                                else -> Unit
                            }
                        }
                    }
                    if (content.isNotEmpty()) {
                        arr.addJsonObject {
                            put("role", "user")
                            put("content", content)
                        }
                    }
                }
                Role.ASSISTANT -> {
                    // 思考回灌：signature 里存的正是服务端给的 reasoning item（含
                    // encrypted_content），原样放回 input 即可，只对产出它的端点有效。
                    if (m.replaysThinkingTo(request)) {
                        m.parts.filterIsInstance<Part.Thinking>().forEach { t ->
                            val item = t.signature?.let {
                                runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                            }
                            if (item != null && item["type"]?.jsonPrimitive?.content == "reasoning") {
                                arr.add(item)
                            }
                        }
                    }
                    if (m.text.isNotEmpty()) {
                        arr.addJsonObject {
                            put("role", "assistant")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "output_text")
                                    put("text", m.text)
                                }
                            }
                        }
                    }
                    m.toolCalls.forEach { tc ->
                        arr.addJsonObject {
                            put("type", "function_call")
                            put("call_id", tc.id)
                            put("name", tc.name)
                            put("arguments", tc.arguments.ifBlank { "{}" })
                        }
                    }
                }
                Role.TOOL_RESULT -> m.toolResults.forEach { r ->
                    arr.addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", r.toolCallId)
                        put("output", r.content)
                    }
                }
            }
        }
    }
}

/**
 * Responses SSE → `StreamEvent` 的纯解码器（不碰网络，便于用真实帧做单测）。
 *
 * 事件名既能从 SSE 的 `event:` 行取，也能从 JSON 的 `type` 字段取（两者都有，互为兜底）。
 */
internal class ResponsesDecoder {

    private val acc = LinkedHashMap<Int, ResponsesToolAcc>()
    private val indexByItem = HashMap<String, Int>()

    private var textId: String? = null
    private var reasoningId: String? = null
    private var reasoningType: String? = null
    private var usage: Usage? = null
    private var stopDetail: String? = null
    private var failed = false
    private var sawText = false
    private var sawReasoning = false
    private var nextToolIndex = 0

    fun accept(event: String?, data: String): List<StreamEvent> {
        val root = try {
            Json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val type = event?.takeIf { it.isNotBlank() }
            ?: root["type"]?.jsonPrimitive?.strOrNull()
            ?: return emptyList()

        val out = ArrayList<StreamEvent>(2)
        when (type) {
            "response.output_text.delta" -> {
                val id = root["item_id"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                val delta = root["delta"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                if (delta.isEmpty()) return emptyList()
                closeReasoning(out)
                if (textId != id) {
                    closeText(out)
                    textId = id
                    out.add(StreamEvent.TextStart(id))
                }
                sawText = true
                out.add(StreamEvent.TextDelta(delta))
            }
            "response.output_text.done" -> closeText(out)
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                val id = root["item_id"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                val delta = root["delta"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                if (delta.isEmpty()) return emptyList()
                val kind = if (type.contains("summary")) "SUMMARY_TEXT" else "REASONING_TEXT"
                closeText(out)
                if (reasoningId != id || reasoningType != kind) {
                    closeReasoning(out)
                    reasoningId = id
                    reasoningType = kind
                    out.add(StreamEvent.ReasoningStart(id, kind))
                }
                sawReasoning = true
                out.add(StreamEvent.ReasoningDelta(delta))
            }
            "response.reasoning_summary_text.done",
            "response.reasoning_text.done",
            -> closeReasoning(out)
            "response.output_item.added" -> itemAdded(root, out)
            "response.output_item.done" -> itemDone(root, out)
            "response.function_call_arguments.delta" -> {
                val itemId = root["item_id"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                val delta = root["delta"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                if (delta.isEmpty()) return emptyList()
                val index = indexByItem[itemId] ?: return emptyList()
                acc[index]?.args?.append(delta)
                out.add(StreamEvent.ToolCallArgsDelta(index, delta))
            }
            "response.function_call_arguments.done" -> {
                val itemId = root["item_id"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                val args = root["arguments"]?.jsonPrimitive?.strOrNull() ?: return emptyList()
                val index = indexByItem[itemId] ?: return emptyList()
                val a = acc[index] ?: return emptyList()
                if (a.args.isEmpty() && args.isNotEmpty()) {
                    a.args.append(args)
                    out.add(StreamEvent.ToolCallArgsDelta(index, args))
                }
            }
            "response.completed", "response.incomplete" -> completed(root, out)
            "response.failed" -> {
                val resp = root["response"]?.jsonObject
                val err = resp?.get("error")?.jsonObject
                val message = err?.get("message")?.jsonPrimitive?.strOrNull()
                    ?: err?.get("code")?.jsonPrimitive?.strOrNull()
                    ?: "Responses request failed"
                failed = true
                closeText(out)
                closeReasoning(out)
                out.add(StreamEvent.Failure(message, retryable = false))
            }
            "error" -> {
                val code = root["code"]?.jsonPrimitive?.strOrNull()
                val message = root["message"]?.jsonPrimitive?.strOrNull() ?: "Responses stream error"
                failed = true
                out.add(StreamEvent.Failure(if (code == null) message else "$code: $message"))
            }
        }
        return out
    }

    private fun itemAdded(root: JsonObject, out: MutableList<StreamEvent>) {
        val item = root["item"]?.jsonObject ?: return
        val itemId = item["id"]?.jsonPrimitive?.strOrNull()
        when (item["type"]?.jsonPrimitive?.strOrNull()) {
            "reasoning" -> {
                // reasoning item 往往先于 summary/text 事件出现；真正的段落边界由
                // reasoning_*_text.delta 决定，这里只负责在工具/正文切换时收口。
                closeText(out)
            }
            "message" -> closeReasoning(out)
            "function_call" -> {
                closeText(out)
                closeReasoning(out)
                val index = nextToolIndex++
                if (itemId != null) indexByItem[itemId] = index
                val callId = item["call_id"]?.jsonPrimitive?.strOrNull()
                    ?: itemId
                    ?: "call_$index"
                val name = item["name"]?.jsonPrimitive?.strOrNull().orEmpty()
                val a = ResponsesToolAcc(callId, name)
                acc[index] = a
                out.add(StreamEvent.ToolCallStarted(index, callId, name))
                val initialArgs = item["arguments"]?.jsonPrimitive?.strOrNull()
                if (!initialArgs.isNullOrEmpty()) {
                    a.args.append(initialArgs)
                    out.add(StreamEvent.ToolCallArgsDelta(index, initialArgs))
                }
            }
        }
    }

    private fun itemDone(root: JsonObject, out: MutableList<StreamEvent>) {
        val item = root["item"]?.jsonObject ?: return
        val itemId = item["id"]?.jsonPrimitive?.strOrNull()
        when (item["type"]?.jsonPrimitive?.strOrNull()) {
            "message" -> if (itemId != null && itemId == textId) closeText(out)
            "reasoning" -> if (itemId != null) {
                // reasoning item 的完整内容（含 encrypted_content）只在 done 事件里齐全。
                // 摘要段可能已先收口；这里再补一条带签名的事件，AgentSession 会把它
                // 落到同一个段上，下一回合据此原样回灌。
                out.add(StreamEvent.ReasoningEnd(itemId, item.toString()))
            }
            "function_call" -> {
                val index = itemId?.let { indexByItem[it] } ?: return
                val a = acc[index] ?: return
                val name = item["name"]?.jsonPrimitive?.strOrNull()
                if (!name.isNullOrBlank()) a.name = name
                val args = item["arguments"]?.jsonPrimitive?.strOrNull()
                if (!args.isNullOrEmpty() && a.args.isEmpty()) {
                    a.args.append(args)
                    out.add(StreamEvent.ToolCallArgsDelta(index, args))
                }
            }
        }
    }

    private fun completed(root: JsonObject, out: MutableList<StreamEvent>) {
        val resp = root["response"]?.jsonObject ?: root
        resp["usage"]?.jsonObject?.let { usage = parseResponsesUsage(it) }
        stopDetail = resp["incomplete_details"]?.jsonObject?.get("reason")?.jsonPrimitive?.strOrNull()
        closeText(out)
        closeReasoning(out)
    }

    /** 流被异常中断时是否可安全重发：一个可见事件都没产出才允许。 */
    fun retryableOnAbort(): Boolean = !sawText && !sawReasoning && acc.isEmpty() && usage == null

    fun finish(): List<StreamEvent> {
        if (failed) return emptyList()
        val out = ArrayList<StreamEvent>()
        closeText(out)
        closeReasoning(out)
        if (!sawText && !sawReasoning && acc.isEmpty()) {
            out.add(StreamEvent.Failure(emptyStopMessage(), retryable = true))
            return out
        }
        usage?.let { out.add(StreamEvent.UsageEvent(it)) }
        if (acc.isNotEmpty()) {
            out.add(
                StreamEvent.ToolCallsCompleted(
                    acc.entries.sortedBy { it.key }.map {
                        ToolCallData(it.value.id, it.value.name, it.value.args.toString().ifBlank { "{}" })
                    },
                ),
            )
        }
        val stop = when {
            acc.isNotEmpty() -> StopReason.TOOL_USE
            stopDetail == "max_output_tokens" -> StopReason.LENGTH
            else -> StopReason.STOP
        }
        out.add(StreamEvent.Completed(stop))
        return out
    }

    private fun closeText(out: MutableList<StreamEvent>) {
        textId?.let { out.add(StreamEvent.TextEnd(it)) }
        textId = null
    }

    private fun closeReasoning(out: MutableList<StreamEvent>) {
        reasoningId?.let { out.add(StreamEvent.ReasoningEnd(it)) }
        reasoningId = null
        reasoningType = null
    }

    private data class ResponsesToolAcc(
        val id: String,
        var name: String,
        val args: StringBuilder = StringBuilder(),
    )
}

/** Responses usage：`input_tokens` 含缓存命中部分，这里拆成互不重叠的 input / cacheRead。 */
internal fun parseResponsesUsage(u: JsonObject): Usage {
    val promptTokens = u["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val output = u["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cached = u["input_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0
    val cacheRead = cached.coerceIn(0, promptTokens)
    val reasoning = u["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
    return Usage(input = promptTokens - cacheRead, output = output, cacheRead = cacheRead, reasoning = reasoning)
}
