package dev.kair.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Conversation roles. Mirrors pi's Message roles: system / user / assistant / toolResult.
 */
@Serializable
enum class Role {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("toolResult")
    TOOL_RESULT,
}

/** Terminal stop reasons, mirroring pi-ai's StopReason. */
object StopReason {
    const val STOP = "stop"
    const val LENGTH = "length"
    const val TOOL_USE = "toolUse"
    const val ERROR = "error"
    const val ABORTED = "aborted"

    // ---- U48 回合终止可见性：内核四个静默出口的可枚举原因（只增不改，旧值不动）----

    /** 本回合步数用尽（maxSteps 到点），模型没机会收尾；用户可发送「继续」接续。 */
    const val STEP_LIMIT = "stepLimit"

    /** 空回合：模型只回了结束原因，没有正文也没有工具调用，不落盘但必须可见。 */
    const val EMPTY_RESPONSE = "emptyResponse"

    /** 会话已关闭（close / 被淘汰），区别于用户主动中止 [ABORTED]。 */
    const val SESSION_CLOSED = "sessionClosed"
}

/**
 * 附件引用（内容寻址）：图片**本体不进会话日志**，日志里只存这条引用。
 *
 * 本体由附件存储按 [sha256] 落盘；请求装配时按引用读回 base64 重新水合（内容寻址，
 * 同一引用永远水合出同一字节，故不影响缓存前缀的字节稳定）。旧会话仍内联 base64
 * （[Part.Image.ref] 为空），两种形态并存、互不影响。
 *
 * [width] / [height] 为像素尺寸，供 token 估算按真实像素计入（未知时为 0，退回固定估值）。
 */
@Serializable
data class AttachmentRef(
    val sha256: String,
    val mediaType: String = "image/png",
    val width: Int = 0,
    val height: Int = 0,
)

/** A typed piece of message content (pi's AssistantMessage content blocks). */
@Serializable
sealed interface Part {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        /**
         * Gemini 的 `thoughtSignature`：同一来源端点多轮时必须原样回灌，否则 Gemini 3
         * 的推理链会断。其它协议不读写该字段。
         */
        val signature: String? = null,
    ) : Part {
        // 保留旧的 1 参构造器，避免已编译插件链接失败。
        constructor(text: String) : this(text, null)
    }

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        /**
         * 思考签名，含义由产出它的传输层解释：Anthropic 的 `signature`、OpenAI 兼容协议的
         * 来源字段名（`reasoning_content` / `reasoning`）、Responses 的完整 reasoning item
         * JSON、Gemini 的 `thoughtSignature`。只在回传给**同一来源端点**时有效
         * （判据见 `dev.kair.app.core.ai.replaysThinkingTo`），换端点即丢弃。
         */
        val signature: String? = null,
        /**
         * Anthropic 的 `redacted_thinking`：思考被安全策略隐去，[thinking] 为空，
         * [signature] 是必须原样回传的 `data`。
         */
        val redacted: Boolean = false,
    ) : Part {
        // 保留旧的 1 参构造器，避免已编译插件链接失败。
        constructor(thinking: String) : this(thinking, null, false)
    }

    @Serializable
    @SerialName("toolCall")
    data class ToolCall(
        val id: String,
        val name: String,
        /** Raw JSON object string of the tool arguments, as streamed by the provider. */
        val arguments: String,
        /** 同 [Text.signature]：Gemini 3 工具调用多轮回灌所需的 `thoughtSignature`。 */
        val signature: String? = null,
    ) : Part {
        // 保留旧的 3 参构造器，避免已编译插件链接失败。
        constructor(id: String, name: String, arguments: String) : this(id, name, arguments, null)
    }

    @Serializable
    @SerialName("toolResult")
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: String,
        val isError: Boolean = false,
    ) : Part

    /**
     * 图片部件（多模态）：base64 数据与 URL 二选一。
     *
     * base64 与 url 都为空时该部件不产生意义，传输层会跳过 —— 但正常路径不会出现
     * 这种状态（发送方要么给本地文件编码成 base64，要么给远程 URL）。
     */
    @Serializable
    @SerialName("image")
    data class Image(
        /** base64 编码的图片数据（不含 `data:` 前缀）。 */
        val base64: String? = null,
        /** 远程或本地图片 URL。 */
        val url: String? = null,
        /** MIME 类型，如 `image/png` / `image/jpeg`。 */
        val mediaType: String = "image/png",
        /**
         * 内容寻址附件引用。图片本体存于附件存储（不进会话日志）时非空，请求装配时按
         * 引用读回 base64 水合进 [base64]；为空表示本体内联在 [base64]（旧数据）。
         */
        val ref: AttachmentRef? = null,
    ) : Part
}

/** 内联 `data:` URL（OpenAI 兼容协议发图用）；有 url 时优先 return url。 */
val Part.Image.dataUrl: String
    get() = url ?: "data:$mediaType;base64,${base64.orEmpty()}"

/** Token accounting, mirroring pi-ai's Usage. */
@Serializable
data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val reasoning: Int? = null,
) {
    val totalTokens: Int get() = input + output + cacheRead + cacheWrite
}

/** Cache hit rate over the input tokens read this turn, mirroring pi's footer metric. */
val Usage.cacheHitRate: Double?
    get() {
        val promptTokens = input + cacheRead + cacheWrite
        return if (promptTokens <= 0) null else cacheRead.toDouble() / promptTokens
    }

@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: Role,
    val parts: List<Part>,
    val model: String? = null,
    val usage: Usage? = null,
    val stopReason: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    /** True for the context-compaction checkpoint message replayed as a user turn. */
    val isCompaction: Boolean = false,
    /**
     * True for a host-injected note replayed as a user turn（如「工具集已变化」提示）。
     * 与 [isCompaction] 同源：系统生成的 user 回合，界面据此渲染成提示卡片而非用户气泡。
     *
     * 装配请求时宿主会把它的正文包成 `<system-note>…</system-note>`（角色仍是 user），
     * 插件注入的 note 自动获得该标记；持久化文本本身不含标签（wire 只存在于请求字节）。
     */
    val isSystemNote: Boolean = false,
    /**
     * 产出这条消息的**提供商目录 id**（`ProviderConfig.providerId`）与**端点主机名**（U76）。
     *
     * 两者与 [model] 一起构成「思考回传的来源键」：只有来源完全一致的历史思考才回传，
     * 判据只在 `dev.kair.app.core.ai.replaysThinkingTo` 一处。主机名是归一化后的
     * （去 scheme / userinfo / 端口 / 路径 / query、统一小写），**不是完整 baseUrl** ——
     * 存完整地址会让路径 / 端点后缀的写法差异也变成「换了一家」。
     *
     * 老会话落盘时没有这两个字段 → 反序列化为 null → 一律按 foreign 处理（不回传，保守）。
     * 这是已拍板的已知副作用：升级后历史思考不再回传，不引入迁移口径。
     */
    val providerId: String? = null,
    val providerHost: String? = null,
) {
    val text: String
        get() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    /**
     * 正文**按段**给出（U98）：每个 `Part.Text` 是一个「连续说话」的段。
     *
     * 为什么需要它：`text` 是把所有段拼成一串，专给「复制 / 判空 / 导出」用 —— 拼出来的
     * 结果没有分隔，一个回合里「先说话 → 调工具 → 接着说」在界面上会粘成一句话。
     * 而转录区要的是**两个段落**（设计稿 `.msg-agent` 是块级段落，`margin-bottom` 12px）。
     * 段边界本身是有意义的（`TurnExecutor.snapshot` 每遇到思考 / 工具就会开新段），
     * 所以这里原样切开、只丢掉空白段，不插入任何连接符。
     */
    val textParagraphs: List<String>
        get() = parts.filterIsInstance<Part.Text>().map { it.text.trim() }.filter { it.isNotEmpty() }

    val thinking: String
        get() = parts.filterIsInstance<Part.Thinking>().joinToString("") { it.thinking }

    val toolCalls: List<Part.ToolCall>
        get() = parts.filterIsInstance<Part.ToolCall>()

    val toolResults: List<Part.ToolResult>
        get() = parts.filterIsInstance<Part.ToolResult>()

    val images: List<Part.Image>
        get() = parts.filterIsInstance<Part.Image>()
}

fun newId(): String = UUID.randomUUID().toString()
