package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 内置与第三方传输层共享的稳定 id。提供方定义与配置只引用这些字符串（或插件
 * 自定义的 id），内核不做枚举硬分支。
 */
object TransportIds {
    /** OpenAI Chat Completions 及其所有兼容端点。 */
    const val OPENAI_CHAT = "openai-chat"

    /** OpenAI Responses API（`/v1/responses`，事件流带 `item_id`）。 */
    const val OPENAI_RESPONSES = "openai-responses"

    /** Anthropic Messages API。 */
    const val ANTHROPIC_MESSAGES = "anthropic-messages"

    /** Google Gemini 原生 `:streamGenerateContent` API。 */
    const val GEMINI_GENERATE = "gemini-generate"
}

/** A known provider endpoint template. */
data class ProviderDef(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    /** 传输层 id，指向某个已注册的 [ProviderTransport]。 */
    val transportId: String,
    val needsKey: Boolean = true,
)

/** Concrete credentials + endpoint chosen by the user. */
data class ProviderConfig(
    val providerId: String,
    /** 传输层 id，决定用哪个 [ProviderTransport] 发请求。 */
    val transportId: String,
    val baseUrl: String,
    val apiKey: String,
    val headers: Map<String, String> = emptyMap(),
)

/** A tool advertised to the model. */
data class LlmToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * 请求尾部的可选参数：温度、输出上限、思考强度与高级采样参数。全部默认 null = 不发送。
 *
 * **缓存安全**：这些字段只进请求尾部（`RequestAssembler` 的 VOLATILE 段），不参与
 * 缓存前缀，所以用户随时调整它们都不会破坏此前累积的前缀缓存。
 *
 * 采样参数（惩罚 / top_p / seed）只有 OpenAI 兼容协议支持，Anthropic 传输层忽略它们。
 */
data class SamplingOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /**
     * 思考强度**实际要发送的值**（由模型配置的思考等级映射给出）；null = 不发送。
     *
     * `off` / `none` / `disabled` 表示显式关闭思考：OpenAI 兼容传输层据此发
     * `thinking: {"type": "disabled"}`（而非 `reasoning_effort`，后者会被 LongCat 这类端点静默忽略）。
     */
    val reasoningEffort: String? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topP: Double? = null,
    val seed: Int? = null,
)

data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<LlmToolSpec>,
    /** 请求尾部参数；见 [SamplingOptions]。 */
    val options: SamplingOptions = SamplingOptions(),
    /** Session id used as OpenAI's `prompt_cache_key` so turns land on the same cache shard. */
    val sessionId: String? = null,
    /** When false the request opts out of prompt caching (used for one-off summaries). */
    val cache: Boolean = true,
    /**
     * 本发请求的**目标端点标识**（U76）：提供商目录 id 与归一化主机名
     * （去 scheme / userinfo / 端口 / 路径 / query、统一小写）。
     *
     * 与 [ChatMessage.providerId] / [ChatMessage.providerHost] 配对，构成思考回传的来源键；
     * 判据只在 [replaysThinkingTo] 一处，四个传输层与前缀摘要都调它。
     * null = 本发请求未声明来源（如压缩摘要请求），历史思考一律不回传。
     */
    val providerId: String? = null,
    val providerHost: String? = null,
)

/**
 * Streaming events emitted while a completion is produced.
 *
 * `@Serializable` 是为了测试侧「录制 → JSONL → 回放」：真实流量的每一帧落盘成一行，
 * 回放时不连网、不花钱，回归解码器与 `AgentSession` 的组装结果。序列化只发生在测试
 * 与诊断路径，**不进入请求字节**，因此不影响缓存前缀。
 *
 * 正文 / 推理的 Start / End 是**新增的可选边界事件**：传输层能给出稳定 part id 时
 * 就发，老代码只处理 `TextDelta` / `ReasoningDelta` 仍然工作（内核按「当前打开的段」
 * 追加）。`TextEnd` / `ToolCallStarted` 只增加了带默认值的可选字段，并保留旧构造器，
 * 旧插件不受影响。
 */
@Serializable
sealed interface StreamEvent {
    /** 一段正文开始；[id] 由传输层生成（OpenAI `text-0`、Anthropic content block index）。 */
    @Serializable
    @SerialName("textStart")
    data class TextStart(val id: String) : StreamEvent

    @Serializable
    @SerialName("textDelta")
    data class TextDelta(val text: String) : StreamEvent

    /** 一段正文结束（被工具调用打断、或流正常收尾）。 */
    @Serializable
    @SerialName("textEnd")
    data class TextEnd(
        val id: String,
        /** Gemini 的 `thoughtSignature`；其它协议为 null。 */
        val signature: String? = null,
    ) : StreamEvent {
        // 保留旧的 1 参构造器，避免已编译插件链接失败。
        constructor(id: String) : this(id, null)
    }

    /**
     * 一段推理开始。[reasoningType] 区分原始推理文本与摘要
     * （`REASONING_TEXT` / `SUMMARY_TEXT`）；未知时为 null。
     */
    @Serializable
    @SerialName("reasoningStart")
    data class ReasoningStart(val id: String, val reasoningType: String? = null) : StreamEvent

    @Serializable
    @SerialName("reasoningDelta")
    data class ReasoningDelta(val text: String) : StreamEvent

    /**
     * 一段推理结束。[signature] 是服务端要求回传的思考签名（Anthropic `signature` /
     * Responses reasoning item JSON / Gemini `thoughtSignature`），[redacted] 表示
     * Anthropic 的 `redacted_thinking`（内容被隐去，[signature] 即 `data`）。
     */
    @Serializable
    @SerialName("reasoningEnd")
    data class ReasoningEnd(
        val id: String,
        val signature: String? = null,
        val redacted: Boolean = false,
    ) : StreamEvent {
        // 保留旧的 1 参构造器，避免已编译插件链接失败。
        constructor(id: String) : this(id, null, false)
    }

    @Serializable
    @SerialName("toolCallStarted")
    data class ToolCallStarted(
        val index: Int,
        val id: String,
        val name: String,
        /** Gemini 的 `thoughtSignature`；其它协议为 null。 */
        val signature: String? = null,
    ) : StreamEvent {
        // 保留旧的 3 参构造器，避免已编译插件链接失败。
        constructor(index: Int, id: String, name: String) : this(index, id, name, null)
    }

    @Serializable
    @SerialName("toolCallArgsDelta")
    data class ToolCallArgsDelta(val index: Int, val delta: String) : StreamEvent

    @Serializable
    @SerialName("usageEvent")
    data class UsageEvent(val usage: Usage) : StreamEvent

    /** Final tool calls in call order, with fully accumulated arguments. */
    @Serializable
    @SerialName("toolCallsCompleted")
    data class ToolCallsCompleted(val calls: List<ToolCallData>) : StreamEvent

    @Serializable
    @SerialName("completed")
    data class Completed(val stopReason: String?) : StreamEvent

    /**
     * 本回合失败。
     *
     * [retryable] / [retryAfterMs] 供传输层顺手标注：内核会据此在**尚未产出任何可见输出**
     * 时自动重发同一请求（退避 + 上限见内核传输层约定）。两者都可省：
     * - 不标注（`null`）时，内核按错误文本判定（HTTP 408/409/429/5xx、过载 / 限流
     *   关键字、空响应视为瞬时；鉴权 / 参数 / 配额类不重试）；
     * - 标注 [retryable] `false` 表示「重试无意义」，内核不再尝试。
     *
     * [retryAfterMs] 对应服务端 `Retry-After`（仅秒数形式），作为退避下限。
     */
    @Serializable
    @SerialName("failure")
    data class Failure(
        val message: String,
        val retryable: Boolean? = null,
        val retryAfterMs: Long? = null,
    ) : StreamEvent
}

@Serializable
data class ToolCallData(val id: String, val name: String, val arguments: String)

/**
 * Provider 传输层契约：把一次请求变成流式事件。实现由插件提供（内置传输层插件
 * 及社区的 `.kplug` 代码插件均可）；内核只按 [ProviderConfig.transportId] 解析，
 * 不再有枚举分支。
 */
interface ProviderTransport {
    /** 稳定标识，与 [TransportIds] 中的常量或插件自定义 id 对应。 */
    val id: String

    /**
     * 该端点单张图片允许的最大总像素数（宽 × 高）；`null` = 未声明上限，内核不缩放。
     *
     * 这是**端点能力**，不是内核常量：由传输层实现按 [config] 声明，内核只在图片
     * 入库前按它等比缩放，不把任何具体上限写死进会话模型或请求装配。
     *
     * 默认实现返回 `null` —— 旧插件不实现也语义正确、链接不失败（AGENTS §2.1）。
     */
    fun maxImagePixels(config: ProviderConfig): Long? = null

    fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent>
}

/**
 * 传输层注册表，由内核持有、插件填充。解析不到 id 时降级为一个
 * [StreamEvent.Failure] 流，而不是抛异常或硬编码回退到某个实现。
 */
object ProviderTransports {

    private val registry = LinkedHashMap<String, ProviderTransport>()

    /** 注册或替换一个传输层；空 id 返回 false。 */
    fun register(transport: ProviderTransport): Boolean {
        if (transport.id.isBlank()) return false
        registry[transport.id] = transport
        return true
    }

    fun unregister(id: String): Boolean = registry.remove(id) != null

    fun byId(id: String): ProviderTransport? = registry[id]

    val ids: List<String> get() = registry.keys.toList()

    /** 按配置解析并调用传输层；未注册的 id 降级为一个 Failure 流。 */
    fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> {
        val transport = registry[config.transportId]
            ?: return flowOf(StreamEvent.Failure("No transport registered for '${config.transportId}'"))
        return transport.stream(request, config)
    }
}
