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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
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
 * OpenAI Chat Completions transport. Also drives every OpenAI-compatible
 * endpoint (OpenRouter, DeepSeek, Groq, xAI, Gemini's OpenAI endpoint, Ollama, ...).
 * 由内置插件注册。
 */
class OpenAiProvider : ProviderTransport {

    override val id: String = TransportIds.OPENAI_CHAT

    /**
     * LongCat 端点声明单图总像素上限（真机 400：
     * `Image total pixels exceeds maximum allowed size (5242880px)`）。
     * 端点属性放在传输层实现里，其它 OpenAI 兼容端点保持未声明（内核不缩放）。
     *
     * 判定看**真正在调的那个端点**：`providerId` 只是目录条目 —— 走「自定义」填 LongCat
     * 地址、或把预设条目的 baseUrl 改成代理，都是同一个端点。只认 `providerId` 会让
     * 内核以为「这端点没有上限」，超限图逐字节原样发出去，后果是功能静默不生效。
     */
    override fun maxImagePixels(config: ProviderConfig): Long? =
        if (config.providerId == LONGCAT_PROVIDER_ID || isLongCatHost(config.baseUrl)) {
            LONGCAT_MAX_IMAGE_PIXELS
        } else {
            null
        }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
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
            // 取消是控制流，不是网络错误：吞掉它会变成一次「失败」（甚至被判定为可重试）。
            throw e
        } catch (e: Exception) {
            // call.cancel()（取消协程）也会让 execute 抛 IOException，这里必须先区分开。
            currentCoroutineContext().ensureActive()
            // 连接期异常（DNS / 连接 / TLS / 读超时）都是瞬时的：标注可重试。
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
        // 端点不回 tool_call id 时的占位后缀：每次请求一个 nonce，避免各回合的
        // "call_N" 互相重复（结果按 id 关联，重复会把老回合的输出顶掉）。
        val idNonce = java.util.UUID.randomUUID().toString().take(8)
        var stopReason: String? = null
        var usage: Usage? = null
        var sawText = false
        var sawReasoning = false
        // 段落边界：正文 / 推理各用稳定 part id 标记，交给 AgentSession 定位。
        // Chat Completions 没有官方 part id，这里用「类型 + 序号」合成，保证同一流内唯一。
        var textPartId: String? = null
        var reasoningPartId: String? = null

        /** 推理来源字段名，随 ReasoningEnd 上报，下一回合据此原样回传。 */
        var reasoningSignature: String? = null
        var textSeq = 0
        var reasoningSeq = 0
        val idle = AtomicBoolean(false)

        try {
            coroutineScope {
                // 空闲看门狗：SSE 读取是阻塞的，只有关掉底层 socket 才能中断读循环。
                // 超时后置位 idle 并关 source，读会立刻返回 null 或抛错，二者都在下方统一判定。
                val activity = newActivityClock()
                val watchdog = startSseWatchdog(source, activity) {
                    idle.set(true)
                    source.close()
                }
                try {
                    parseSse(source) { activity.set(System.currentTimeMillis()) }.collect { sse ->
                        if (sse.data == "[DONE]") return@collect
                        val root = try {
                            Json.parseToJsonElement(sse.data).jsonObject
                        } catch (_: Exception) {
                            return@collect
                        }
                        root["usage"]?.let { u ->
                            if (u !is kotlinx.serialization.json.JsonNull) usage = parseOpenAiUsage(u.jsonObject)
                        }
                        val choices = root["choices"] as? JsonArray ?: return@collect
                        val choice = choices.firstOrNull()?.jsonObject ?: return@collect
                        choice["finish_reason"]?.let { fr ->
                            if (fr !is kotlinx.serialization.json.JsonNull) stopReason = fr.jsonPrimitive.content
                        }
                        val delta = choice["delta"]?.jsonObject ?: return@collect

                        delta["content"]?.let { c ->
                            if (c !is kotlinx.serialization.json.JsonNull && c.jsonPrimitive.content.isNotEmpty()) {
                                sawText = true
                                // 正文开始前若推理还开着，先收尾推理段（reasoning → content 的边界）。
                                reasoningPartId?.let {
                                    emit(StreamEvent.ReasoningEnd(it, reasoningSignature))
                                    reasoningPartId = null
                                }
                                if (textPartId == null) {
                                    val id = "text-${textSeq++}"
                                    textPartId = id
                                    emit(StreamEvent.TextStart(id))
                                }
                                emit(StreamEvent.TextDelta(c.jsonPrimitive.content))
                            }
                        }
                        // 两种字段名都见得到：DeepSeek / 多数中转站用 reasoning_content，
                        // 少数兼容端点用 reasoning。记下来源字段名，下一轮原样写回。
                        val reasoningField = when {
                            delta["reasoning_content"] != null -> "reasoning_content"
                            delta["reasoning"] != null -> "reasoning"
                            else -> null
                        }
                        reasoningField?.let { field ->
                            val r = delta[field]!!
                            if (r !is kotlinx.serialization.json.JsonNull && r.jsonPrimitive.content.isNotEmpty()) {
                                sawReasoning = true
                                reasoningSignature = field
                                textPartId?.let { textId0 ->
                                    // 推理通常先于正文；若正文已开却又来了推理，先收尾正文段。
                                    emit(StreamEvent.TextEnd(textId0)); textPartId = null
                                }
                                reasoningPartId ?: "reasoning-${reasoningSeq++}".also {
                                    reasoningPartId = it
                                    emit(StreamEvent.ReasoningStart(it, "REASONING_TEXT"))
                                }
                                emit(StreamEvent.ReasoningDelta(r.jsonPrimitive.content))
                            }
                        }
                        (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                            val tc = el.jsonObject
                            val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val fn = tc["function"]?.jsonObject
                            val existing = acc[index]
                            val id = tc["id"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
                            val name = fn?.get("name")?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
                            val argDelta = fn?.get("arguments")?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }

                            if (existing == null) {
                                if (acc.isEmpty()) {
                                    // 工具调用打断正文 / 推理：先发结束边界，段落在转录里才有明确收口。
                                    reasoningPartId?.let {
                                        emit(StreamEvent.ReasoningEnd(it, reasoningSignature))
                                        reasoningPartId = null
                                    }
                                    textPartId?.let { emit(StreamEvent.TextEnd(it)); textPartId = null }
                                }
                                val a = ToolAcc(id = id ?: "call_${index}_$idNonce", name = name ?: "")
                                acc[index] = a
                                emit(StreamEvent.ToolCallStarted(index, a.id, a.name))
                            }
                            val a = acc[index]!!
                            if (id != null) a.id = id
                            if (name != null) a.name = name
                            if (!argDelta.isNullOrEmpty()) {
                                a.args.append(argDelta)
                                emit(StreamEvent.ToolCallArgsDelta(index, argDelta))
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
            // 同上：用户停止（call.cancel / 协程取消）不是「流错误」，不能当失败上报。
            currentCoroutineContext().ensureActive()
            // 流中途断开：**本次尝试是否已经产出可见内容**决定能否重发 —— 一个 token 都没
            // 流出去时重发是安全的（多半是切网 / 中转站掐断），已流出则重发会重复输出。
            emit(
                StreamEvent.Failure(
                    if (idle.get()) stalledUpstreamMessage() else localFailureMessage(e),
                    retryable = if (idle.get()) false else !sawText && !sawReasoning && acc.isEmpty(),
                ),
            )
            return@flow
        }

        // 链路空闲超时：不当作正常结束，也不把可能半截的工具调用送去执行。重试也救不了
        // —— 看门狗无法区分「链路卡住」与「模型长时间思考」，重试只是再等一轮 120 秒。
        if (idle.get()) {
            emit(StreamEvent.Failure(stalledUpstreamMessage(), retryable = false))
            return@flow
        }
        // 流结束却既无结束原因也无任何内容：多为服务端吞错后直接关流，报错而非
        // 静默 STOP（静默 STOP 会被当成「模型回复为空」进入下一回合）。这类失败是瞬时的，
        // 交由内核在没有产出的情况下自动重发；它**不再**被当作上下文溢出。
        if (stopReason == null && !sawText && !sawReasoning && acc.isEmpty()) {
            emit(StreamEvent.Failure(emptyStopMessage(), retryable = true))
            return@flow
        }

        // 流正常结束：把仍开着的正文 / 推理段收口，再上报用量与终态。
        reasoningPartId?.let { emit(StreamEvent.ReasoningEnd(it, reasoningSignature)) }
        textPartId?.let { emit(StreamEvent.TextEnd(it)) }
        usage?.let { emit(StreamEvent.UsageEvent(it)) }
        if (acc.isNotEmpty()) {
            emit(
                StreamEvent.ToolCallsCompleted(
                    acc.entries.sortedBy { it.key }.map { ToolCallData(it.value.id, it.value.name, it.value.args.toString()) },
                ),
            )
        }
        emit(StreamEvent.Completed(mapStopReason(stopReason)))
    }.flowOn(Dispatchers.IO)

    internal fun buildBody(request: ChatRequest, config: ProviderConfig): JsonObject = buildJsonObject {
        put("model", request.model)
        // OpenAI caches prompts automatically; a stable key keeps turns on the same cache shard
        // and raises the hit rate. Only sent to OpenAI itself (pi: `prompt_cache_key`).
        val sessionId = request.sessionId
        if (request.cache && sessionId != null && config.baseUrl.contains("api.openai.com")) {
            put("prompt_cache_key", sessionId.take(64))
        }
        putJsonArray("messages") {
            if (request.systemPrompt.isNotBlank()) {
                addJsonObject { put("role", "system"); put("content", request.systemPrompt) }
            }
            request.messages.forEach { add(toWire(it, request, config)) }
        }
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        // 请求尾部参数（全部 null = 不发送）：OpenAI 兼容协议认这一整组，包括
        // 推理模型的 reasoning_effort 与高级采样参数（惩罚 / top_p / seed）。
        val options = request.options
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("max_tokens", it) }
        // 思考开关：`off` / `none` / `disabled` 表示显式关闭思考。LongCat 这类兼容端点
        // 会静默忽略任何 `reasoning_effort`，只有 `thinking: {"type": "disabled"}` 能真正关掉。
        val effort = options.reasoningEffort
        if (effort == "off" || effort == "none" || effort == "disabled") {
            putJsonObject("thinking") { put("type", "disabled") }
        } else {
            effort?.let { put("reasoning_effort", it) }
        }
        options.frequencyPenalty?.let { put("frequency_penalty", it) }
        options.presencePenalty?.let { put("presence_penalty", it) }
        options.topP?.let { put("top_p", it) }
        options.seed?.let { put("seed", it) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        }
                    }
                }
            }
        }
    }

    private fun toWire(m: ChatMessage, request: ChatRequest, config: ProviderConfig): JsonObject = when (m.role) {
        Role.SYSTEM -> buildJsonObject { put("role", "system"); put("content", m.text) }
        Role.USER -> {
            val images = m.images
            // 纯文本消息保持字符串内容（与既有字节一致 → 缓存安全）；有图片才升级为
            // 多模态数组（text + image_url），仅当模型勾选「图像模型」时才会走到这里。
            if (images.isEmpty()) {
                buildJsonObject { put("role", "user"); put("content", m.text) }
            } else {
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (m.text.isNotBlank()) addJsonObject { put("type", "text"); put("text", m.text) }
                        images.forEach { img ->
                            if (img.base64 != null || img.url != null) {
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", img.dataUrl) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Role.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            put("content", m.text)
            // 思考回传：签名里记的是产出它的字段名（reasoning_content / reasoning）。
            // 只对产出它的端点回放（providerId + host + model，U76）—— 换端点后签名无效，传输层不猜。
            val replay = m.parts.filterIsInstance<Part.Thinking>().takeIf { m.replaysThinkingTo(request) }
            val field = replay?.firstNotNullOfOrNull { it.signature?.takeIf(::isReasoningField) }
                ?: "reasoning_content"
            val thinking = replay.orEmpty()
                .filterNot { it.redacted }
                .joinToString("\n") { it.thinking }
            if (thinking.isNotEmpty()) {
                put(field, thinking)
            } else if (echoesReasoningContent(request, config)) {
                // DeepSeek：带 tools 的请求必须回传 reasoning_content，缺失即 400。
                // 空串同样合法，用来兜住「思考被关闭 / 旧会话没有思考」的回合。
                put("reasoning_content", "")
            }
            if (m.toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    m.toolCalls.forEach { tc ->
                        addJsonObject {
                            put("id", tc.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tc.name)
                                put("arguments", tc.arguments.ifBlank { "{}" })
                            }
                        }
                    }
                }
            }
        }
        Role.TOOL_RESULT -> {
            val r = m.toolResults.firstOrNull()
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", r?.toolCallId ?: "")
                put("content", r?.content ?: m.text)
            }
        }
    }

    /** 只有这两种字段名可安全回放；其余（如 Gemini 的 thoughtSignature）不猜。 */
    private fun isReasoningField(name: String): Boolean =
        name == "reasoning_content" || name == "reasoning"

    private fun mapStopReason(raw: String?): String = when (raw) {
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.LENGTH
        else -> StopReason.STOP
    }

    private data class ToolAcc(var id: String, var name: String, val args: StringBuilder = StringBuilder())
}

/**
 * 解析 OpenAI 兼容响应里的 usage，含缓存命中 token。
 *
 * 命中 token 有两个来源：
 * - OpenAI / OpenRouter：`usage.prompt_tokens_details.cached_tokens`
 * - DeepSeek：直出 `usage.prompt_cache_hit_tokens`（与前者同值）
 *
 * 两家的 `prompt_tokens` 都已**包含**命中的部分，因此这里把命中量从 `input`
 * 里拆出来放进 `cacheRead`，使 input 与 cacheRead 互不重叠——这样
 * `cacheHitRate = cacheRead / (input + cacheRead + cacheWrite)` 才等于
 * 「命中 token / 本次全部输入 token」，与 DeepSeek Harness 的口径一致。
 * （早期实现只读 prompt_tokens，导致 cacheRead 恒为 0、命中率恒 0%。）
 */
internal fun parseOpenAiUsage(u: JsonObject): Usage {
    val promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val output = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
    val cached = u["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
        ?: u["prompt_tokens_details"]?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.intOrNull
        ?: 0
    val cacheRead = cached.coerceIn(0, promptTokens)
    return Usage(input = promptTokens - cacheRead, output = output, cacheRead = cacheRead)
}

/** Shared mutable accumulator type used by both providers. */
internal typealias JsonObj = JsonObject

internal fun JsonPrimitive.strOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

private const val LONGCAT_PROVIDER_ID = "longcat"
private const val LONGCAT_HOST_SUFFIX = "longcat.chat"
private const val LONGCAT_MAX_IMAGE_PIXELS = 5_242_880L

/** 只认主机名本身或其子域，避免路径 / query / 后缀拼接里出现字样就误判为 LongCat。 */
private fun isLongCatHost(baseUrl: String): Boolean {
    val host = hostOf(baseUrl)
    return host == LONGCAT_HOST_SUFFIX || host.endsWith(".$LONGCAT_HOST_SUFFIX")
}

/**
 * 「缺 `reasoning_content` 会 400」的端点白名单：**默认不补**（U76）。
 *
 * 键是**精确匹配**的主机名（经 [hostOf] 归一化），值是需要补空串的 model 集合，
 * 空集 = 该 host 全部模型。加一家只加一行，不改判断逻辑。
 *
 * 为什么不再用 `contains("deepseek")`：那是子串判定 —— 中转地址里带字样、模型名里带字样
 * 都会命中，于是「切到别家」与「留在官方」得到不对称的兜底（任务文档 §1 场景 2 / 3）。
 * 2026-11-12 实测：`deepseek-flash` 与 `LongCat-2.5` 带 tools 且完全不给该字段都是 200，
 * 所以当代模型一个都不需要；这张表只为老模型（`deepseek-chat` / `deepseek-reasoner`）
 * 留一个不动逻辑就能加回的位置。
 */
private val EchoRequired: Map<String, Set<String>> = mapOf(
    "api.deepseek.com" to setOf("deepseek-chat", "deepseek-reasoner"),
)

/** 该端点是否要求「没有思考也要补一个空串」；表里没有 = 不补。 */
private fun echoesReasoningContent(request: ChatRequest, config: ProviderConfig): Boolean =
    EchoRequired[hostOf(config.baseUrl)]?.let { it.isEmpty() || request.model in it } ?: false
