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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** Anthropic Messages API transport（由内置插件注册）。 */
class AnthropicProvider : ProviderTransport {

    override val id: String = TransportIds.ANTHROPIC_MESSAGES

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Anthropic ephemeral prompt-cache marker. */
    private val cacheControl: JsonObject = buildJsonObject { put("type", "ephemeral") }

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
        val url = config.baseUrl.trimEnd('/') + "/v1/messages"
        val body = buildBody(request)
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            // 交给共享拦截器按会话记录真实请求，供「请求诊断」面板导出。
            .tag(CaptureTag::class.java, CaptureTag(request.sessionId))
            .header("anthropic-version", "2023-06-01")
        if (config.apiKey.isNotBlank()) builder.header("x-api-key", config.apiKey)
        config.headers.forEach { (k, v) -> builder.header(k, v) }

        val call = HttpClient.client.newCall(builder.build())
        cancelHttpCallWhenCancelled(call)

        val response = try {
            call.execute()
        } catch (e: CancellationException) {
            // 取消是控制流，不是网络错误（同 OpenAiProvider）。
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // 连接期异常都是瞬时的：标注可重试。
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

        val acc = LinkedHashMap<Int, ToolAcc>()
        // content block index → block 类型：content_block_stop 只带 index，靠它决定
        // 该发 TextEnd 还是 ReasoningEnd。
        val blockTypes = HashMap<Int, String>()
        // 思考签名：`thinking` block 的 signature 以 `signature_delta` 增量到达，
        // `redacted_thinking` 的不可读 payload 直接在 block.data 里。两者都要原样回传。
        val blockSignatures = HashMap<Int, String>()
        var inputTokens = 0
        var cacheRead = 0
        var cacheWrite = 0
        var outputTokens = 0
        var stopReason: String? = null
        var sawText = false
        var sawReasoning = false
        val idle = AtomicBoolean(false)

        try {
            coroutineScope {
                // 空闲看门狗：理由同 OpenAiProvider —— 阻塞读只能靠关底层 socket 中断。
                val activity = newActivityClock()
                val watchdog = startSseWatchdog(source, activity) {
                    idle.set(true)
                    source.close()
                }
                try {
                    parseSse(source) { activity.set(System.currentTimeMillis()) }.collect { sse ->
                        val root = try {
                            Json.parseToJsonElement(sse.data).jsonObject
                        } catch (_: Exception) {
                            return@collect
                        }
                        when (root["type"]?.jsonPrimitive?.content) {
                            "message_start" -> {
                                val u = root["message"]?.jsonObject?.get("usage")?.jsonObject
                                u?.get("input_tokens")?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                                u?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull?.let { cacheRead = it }
                                u?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull?.let { cacheWrite = it }
                            }
                            "content_block_start" -> {
                                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val block = root["content_block"]?.jsonObject
                                val type = block?.get("type")?.jsonPrimitive?.content
                                blockTypes[index] = type.orEmpty()
                                when (type) {
                                    // Anthropic 的 content block index 就是稳定的 part id：
                                    // 字符串化后交给 AgentSession 定位段落。
                                    "text" -> emit(StreamEvent.TextStart(index.toString()))
                                    "thinking" -> {
                                        // 极少数兼容端点会在 start 里就给出完整签名。
                                        block["signature"]?.jsonPrimitive?.content
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { blockSignatures[index] = it }
                                        emit(StreamEvent.ReasoningStart(index.toString(), "REASONING_TEXT"))
                                    }
                                    "redacted_thinking" -> {
                                        block["data"]?.jsonPrimitive?.content
                                            ?.let { blockSignatures[index] = it }
                                        emit(StreamEvent.ReasoningStart(index.toString(), "REDACTED"))
                                    }
                                    "tool_use" -> {
                                        val id = block["id"]?.jsonPrimitive?.content ?: "toolu_$index"
                                        val name = block["name"]?.jsonPrimitive?.content ?: ""
                                        acc[index] = ToolAcc(id, name)
                                        emit(StreamEvent.ToolCallStarted(index, id, name))
                                    }
                                }
                            }
                            "content_block_stop" -> {
                                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                when (blockTypes[index]) {
                                    "text" -> emit(StreamEvent.TextEnd(index.toString()))
                                    "thinking" -> emit(
                                        StreamEvent.ReasoningEnd(index.toString(), blockSignatures[index]),
                                    )
                                    "redacted_thinking" -> emit(
                                        StreamEvent.ReasoningEnd(
                                            index.toString(),
                                            blockSignatures[index],
                                            redacted = true,
                                        ),
                                    )
                                }
                            }
                            "content_block_delta" -> {
                                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                                val delta = root["delta"]?.jsonObject ?: return@collect
                                when (delta["type"]?.jsonPrimitive?.content) {
                                    "text_delta" -> delta["text"]?.jsonPrimitive?.content?.let {
                                        if (it.isNotEmpty()) {
                                            sawText = true
                                            emit(StreamEvent.TextDelta(it))
                                        }
                                    }
                                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.content?.let {
                                        if (it.isNotEmpty()) {
                                            sawReasoning = true
                                            emit(StreamEvent.ReasoningDelta(it))
                                        }
                                    }
                                    "signature_delta" -> delta["signature"]?.jsonPrimitive?.content?.let {
                                        blockSignatures[index] = blockSignatures[index].orEmpty() + it
                                    }
                                    "input_json_delta" -> {
                                        val partial = delta["partial_json"]?.jsonPrimitive?.content
                                        if (!partial.isNullOrEmpty()) {
                                            val a = acc.getOrPut(index) { ToolAcc("toolu_$index", "") }
                                            a.args.append(partial)
                                            emit(StreamEvent.ToolCallArgsDelta(index, partial))
                                        }
                                    }
                                }
                            }
                            "message_delta" -> {
                                root["delta"]?.jsonObject?.get("stop_reason")?.let { sr ->
                                    if (sr !is JsonNull) stopReason = sr.jsonPrimitive.content
                                }
                                root["usage"]?.jsonObject?.get("output_tokens")?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                            }
                            "error" -> {
                                // 带上 error.type（如 rate_limit_error / overloaded_error）：
                                // 错误解析器据此分类，比只看 message 更可靠。
                                val err = root["error"]?.jsonObject
                                val type = err?.get("type")?.jsonPrimitive?.content
                                val msg = err?.get("message")?.jsonPrimitive?.content ?: "Anthropic error"
                                emit(StreamEvent.Failure(if (type.isNullOrBlank()) msg else "$type: $msg"))
                            }
                        }
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
                    // 同 OpenAI 传输层：一个 token 都没流出时重发安全，已流出则不重发。
                    retryable = if (idle.get()) false else !sawText && !sawReasoning && acc.isEmpty(),
                ),
            )
            return@flow
        }

        // 链路空闲超时 / 空响应：理由同 OpenAiProvider（见那里更详细的注释）。
        if (idle.get()) {
            emit(StreamEvent.Failure(stalledUpstreamMessage(), retryable = false))
            return@flow
        }
        if (stopReason == null && !sawText && !sawReasoning && acc.isEmpty()) {
            emit(StreamEvent.Failure(emptyStopMessage(), retryable = true))
            return@flow
        }

        emit(StreamEvent.UsageEvent(Usage(input = inputTokens, output = outputTokens, cacheRead = cacheRead, cacheWrite = cacheWrite)))
        if (acc.isNotEmpty()) {
            emit(
                StreamEvent.ToolCallsCompleted(
                    acc.entries.sortedBy { it.key }.map { ToolCallData(it.value.id, it.value.name, it.value.args.toString().ifBlank { "{}" }) },
                ),
            )
        }
        emit(StreamEvent.Completed(mapStopReason(stopReason)))
    }.flowOn(Dispatchers.IO)

    internal fun buildBody(request: ChatRequest): JsonObject {
        val cache = request.cache
        return buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.options.maxTokens ?: 8192)
            if (request.systemPrompt.isNotBlank()) {
                // Cache breakpoint 1: the static system prompt.
                if (cache) {
                    putJsonArray("system") {
                        addJsonObject {
                            put("type", "text")
                            put("text", request.systemPrompt)
                            put("cache_control", cacheControl)
                        }
                    }
                } else {
                    put("system", request.systemPrompt)
                }
            }
            // Anthropic 要求 user/assistant 严格交替：工具结果 user 后紧跟一条图片 user
            // （read_image 回带图片时）需要合并成同一条 user，否则请求被拒。
            val messages = mergeAdjacentSameRole(
                buildJsonArray { addAnthropicMessages(request.messages, this, request) },
            )
            // Cache breakpoint 2: the newest conversation block, caching the whole prefix.
            put("messages", if (cache) withCacheControlOnLastMessage(messages, cacheControl) else messages)
            // Anthropic 不支持惩罚 / top_p / seed / reasoning_effort，故只映射温度与输出上限；
            // 其余尾部参数由模型配置决定是否勾选（未勾选就不会配到这里）。
            request.options.temperature?.let { put("temperature", it) }
            put("stream", true)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEachIndexed { i, t ->
                        addJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.parameters)
                            // Cache breakpoint 3: the last tool definition caches the tool schema block.
                            if (cache && i == request.tools.lastIndex) put("cache_control", cacheControl)
                        }
                    }
                }
            }
        }
    }

    /**
     * Attach `cache_control` to the last text/tool_result block of the final message,
     * mirroring pi's `addCacheControlToLastConversationMessage`. Because the transcript
     * is append-only, this makes every earlier token a cache read on the next turn.
     */
    private fun withCacheControlOnLastMessage(messages: JsonArray, cc: JsonObject): JsonArray {
        val last = messages.lastOrNull() as? JsonObject ?: return messages
        val role = last["role"]?.jsonPrimitive?.content
        if (role != "user" && role != "assistant") return messages
        val content = last["content"] as? JsonArray ?: return messages
        val lastBlock = content.lastOrNull() as? JsonObject ?: return messages
        val type = lastBlock["type"]?.jsonPrimitive?.content
        if (type != "text" && type != "tool_result" && type != "image") return messages

        val blocks = content.toMutableList()
        blocks[blocks.lastIndex] = JsonObject(lastBlock + ("cache_control" to cc))
        val patched = JsonObject(last + ("content" to JsonArray(blocks)))
        val out = messages.toMutableList()
        out[out.lastIndex] = patched
        return JsonArray(out)
    }

    /**
     * 合并相邻的同角色消息为一条（内容块顺序拼接）。Anthropic 只接受 user/assistant
     * 交替，而「工具结果 user + 图片 user」这类相邻 user 必须并成一条才合法。
     */
    private fun mergeAdjacentSameRole(messages: JsonArray): JsonArray {
        val out = ArrayList<JsonElement>()
        for (el in messages) {
            val cur = el as? JsonObject
            val prev = out.lastOrNull() as? JsonObject
            if (cur != null &&
                prev != null &&
                prev["role"]?.jsonPrimitive?.content == cur["role"]?.jsonPrimitive?.content
            ) {
                val prevContent = prev["content"] as? JsonArray ?: JsonArray(emptyList())
                val curContent = cur["content"] as? JsonArray ?: JsonArray(emptyList())
                out[out.lastIndex] = JsonObject(prev + ("content" to JsonArray(prevContent + curContent)))
            } else {
                out.add(el)
            }
        }
        return JsonArray(out)
    }

    /** Anthropic requires alternating user/assistant turns; consecutive tool results merge into one user turn. */
    private fun addAnthropicMessages(
        messages: List<ChatMessage>,
        arr: kotlinx.serialization.json.JsonArrayBuilder,
        request: ChatRequest,
    ) {
        val pendingToolResults = ArrayList<JsonElement>()

        fun flushToolResults() {
            if (pendingToolResults.isEmpty()) return
            arr.addJsonObject {
                put("role", "user")
                put("content", JsonArray(ArrayList(pendingToolResults)))
            }
            pendingToolResults.clear()
        }

        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> Unit // system prompt is carried at the top level
                Role.TOOL_RESULT -> m.toolResults.forEach { r ->
                    pendingToolResults.add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", r.toolCallId)
                            put("content", r.content)
                            if (r.isError) put("is_error", true)
                        },
                    )
                }
                Role.USER -> {
                    flushToolResults()
                    arr.addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            if (m.text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", m.text) }
                            // 多模态：base64 走 source.base64，远程 url 走 source.url。
                            m.images.forEach { img ->
                                val base64 = img.base64
                                when {
                                    !base64.isNullOrBlank() -> addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", img.mediaType)
                                            put("data", base64)
                                        }
                                    }
                                    !img.url.isNullOrBlank() -> addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "url")
                                            put("url", img.url)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Role.ASSISTANT -> {
                    flushToolResults()
                    // 思考签名只对产出它的端点有效：换模型 / 换中转后回传旧签名会被服务端拒绝。
                    val replayThinking = m.replaysThinkingTo(request)
                    arr.addJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            // thinking 必须在 text 之前（Anthropic 的原始块顺序）。
                            m.parts.forEach { p ->
                                when (p) {
                                    is Part.Thinking -> if (replayThinking) {
                                        when {
                                            p.redacted -> p.signature?.takeIf { it.isNotBlank() }?.let { data ->
                                                addJsonObject {
                                                    put("type", "redacted_thinking")
                                                    put("data", data)
                                                }
                                            }
                                            // 没有签名的思考块发回去会被拒；宁可略过。
                                            !p.signature.isNullOrBlank() -> addJsonObject {
                                                put("type", "thinking")
                                                put("thinking", p.thinking)
                                                put("signature", p.signature)
                                            }
                                        }
                                    }
                                    is Part.Text -> if (p.text.isNotEmpty()) addJsonObject {
                                        put("type", "text")
                                        put("text", p.text)
                                    }
                                    else -> Unit
                                }
                            }
                            m.toolCalls.forEach { tc ->
                                addJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", parseArgs(tc.arguments))
                                }
                            }
                        }
                    }
                }
            }
        }
        flushToolResults()
    }

    private fun parseArgs(raw: String): JsonElement =
        if (raw.isBlank()) buildJsonObject { } else try { Json.parseToJsonElement(raw) } catch (_: Exception) { buildJsonObject { } }

    private fun mapStopReason(raw: String?): String = when (raw) {
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.LENGTH
        else -> StopReason.STOP
    }

    private data class ToolAcc(val id: String, val name: String, val args: StringBuilder = StringBuilder())
}
