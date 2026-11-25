package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.LlmToolSpec
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.ai.replaysThinkingTo
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import java.security.MessageDigest

/**
 * 缓存前缀分段。稳定前缀按 [SegmentKind] 声明的顺序装配，易变尾部排在其后，
 * 见 `docs/架构与内核边界.md` 第 6 节「缓存稳定前缀」契约。
 */
enum class SegmentKind {
    /** system prompt：段落顺序固定、内容确定。 */
    SYSTEM,

    /** 工具列表：确定顺序。 */
    TOOLS,

    /** 历史回合：只追加，绝不改写。 */
    HISTORY,

    /** 请求尾部：温度 / 最大输出 / 缓存开关等，不进入缓存前缀。 */
    VOLATILE,
    ;

    /**
     * 这一段会不会随**会话历史**增长 —— 只有历史段会：它按回合数与消息体量线性变长，
     * 长会话可达数百 KB，是唯一一个规模足以压垮 Compose 排版的段。
     *
     * 另三段不随历史增长，上界由「配置 + 各贡献点自己的产出」决定：系统提示 = 段落数
     * （贡献点可以读外部状态，例如记忆插件读工作区的 `.kair/memory.md`；那份文件变长，
     * 这一段就变长 —— 但它读的不是会话消息，长度由文件自己决定）、
     * 工具列表 = 注册工具及其 schema、请求尾部 = 参数量。
     * **放开的是「展示截断」，不是内存保证**：这三段的 canonical 本就整段按会话长期持有，
     * 点开详情时多物化一份的上界 ≤ canonical。
     *
     * 展示预算一律经 [displayBudget] 取，别在面板 / 观测器里各写一份 `kind == HISTORY`。
     */
    val growsWithSession: Boolean
        get() = this == HISTORY

    /**
     * 按段型取某份展示预算：会随回合增长的历史段用 [budget]（截尾），另三段整段给出。
     *
     * 面板的两处预算（详情页整段 `RequestSegment.displayLimit`、「本次新增」引文
     * `CacheInspector`）都走这里，「只有历史段要截」这条规则只写一遍 ——
     * 别在面板 / 观测器里各补一份 `kind == HISTORY`。
     */
    fun displayBudget(budget: Int): Int = if (growsWithSession) budget else UNLIMITED_DISPLAY
}

/** 「不截尾」：有界段整段给出，不用另开一套分支表达「没有限制」。 */
private const val UNLIMITED_DISPLAY = Int.MAX_VALUE

/**
 * 单个前缀分段的观测快照。
 *
 * [canonical] 是该段「与线上表示一致」的确定性文本，只由真正发给模型的内容决定，
 * **不含**消息 id、时间戳等非确定性字段，因此相同的逻辑状态必然得到相同字节。
 * 它用于计算大小、摘要，以及与上一回合做前缀包含判断。
 *
 * **内存约束**：长会话的历史段与 canonical 一样大（可达数百 KB）。因此
 * - [readable] 按需生成（面板真正打开时才物化），历史段只保留 [DISPLAY_LIMIT] 的尾部，
 *   有界的那三段（系统提示 / 工具列表 / 请求尾部）整段给出，见 [SegmentKind.displayBudget]；
 * - [bytes] / [digest] 由同一份字节数组一次算出，避免为二者各分配一份等长数组；
 * - [preview] 先截断再替换换行，避免复制整段文本。
 *
 * 缓存面板只在用户点开详情时才需要可读文本，若在装配期就复制一份，多会话并存时
 * 会长期持有「会话数 × 历史长度」的额外文本 —— 低内存机型打开详情即可 OOM。
 */
class RequestSegment(
    val kind: SegmentKind,
    /** 面向用户展示的中文标签。 */
    val label: String,
    val canonical: String,
    /** 是否属于稳定前缀（易变尾部为 false）。 */
    val stable: Boolean,
    /**
     * 可读渲染的工厂（与 [canonical] 承载同一段内容，用换行 / 缩进替代 `\u0000` 分隔符）。
     * 为 null 时直接使用 [canonical]。
     */
    private val readableRenderer: (() -> String)? = null,
) {
    /** 详情页排版预算：随回合增长的段截尾，有界的段整段给出（见 [SegmentKind.displayBudget]）。 */
    private val displayLimit: Int
        get() = kind.displayBudget(DISPLAY_LIMIT)

    /** 面向人的可读渲染，供面板展示「这段到底往前缀里塞了什么」。 */
    val readable: String by lazy(LazyThreadSafetyMode.NONE) {
        val full = readableRenderer?.invoke() ?: canonical
        val limit = displayLimit
        if (full.length <= limit) {
            full
        } else {
            "…（已省略前 ${full.length - limit} 字符）\n" + full.takeLast(limit)
        }
    }

    /**
     * 原始前缀的尾部（与缓存前缀的规范化文本逐字一致，只把控制字符转义成可见形式）。
     *
     * 这里的文本是**Kair 内部用于计量前缀的规范化投影**，不是 HTTP 报文：真正发出去的是
     * JSON 里结构化的 `messages` / `parts`，`\0` / `\1` / `\2` 分隔符绝不进入请求、模型也看不见。
     * [readable] 是给人看的渲染（会加「思考」之类的标签与换行），核对「字节口径算了什么」
     * 必须看这一份。分隔符转义后肉眼可见，长度仍受该段的展示预算（[SegmentKind.displayBudget]）约束。
     */
    val raw: String by lazy(LazyThreadSafetyMode.NONE) {
        val limit = displayLimit
        val from = maxOf(0, canonical.length - limit)
        val head = if (from > 0) "…（已省略前 $from 字符）\n" else ""
        head + escapeControls(canonical.substring(from)).take(limit)
    }

    /** 单次 UTF-8 编码同时得到字节数与指纹。 */
    private val meta: SegmentMeta by lazy(LazyThreadSafetyMode.NONE) {
        val utf8 = canonical.toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(utf8)
        SegmentMeta(utf8.size, sha.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    val bytes: Int get() = meta.bytes
    val digest: String get() = meta.digest

    /** 面板展示用的一行内容摘要。 */
    val preview: String = canonical.take(PREVIEW_LIMIT).replace('\n', ' ').trim()

    private data class SegmentMeta(val bytes: Int, val digest: String)

    companion object {
        /**
         * 历史段详情页最多排版的字符数：它随回合数线性增长（可达数百 KB），
         * 超长文本会显著加重 Compose 排版与内存 —— 另三段有界，不套用这个预算。
         */
        const val DISPLAY_LIMIT = 8_000

        private const val PREVIEW_LIMIT = 72
    }
}

/**
 * 一次请求装配的产出：既给出 [request]，也给出可观测的 [segments]。
 * 这是「缓存稳定前缀」契约在代码里的落点。
 */
data class RequestAssembly(
    val request: ChatRequest,
    val segments: List<RequestSegment>,
) {
    /** 稳定前缀的总字节数。 */
    val prefixBytes: Int get() = segments.filter { it.stable }.sumOf { it.bytes }

    /** 含易变尾部的总字节数。 */
    val totalBytes: Int get() = segments.sumOf { it.bytes }

    fun segment(kind: SegmentKind): RequestSegment? = segments.firstOrNull { it.kind == kind }
}

/** 某分段相对同会话上一回合的变化。 */
enum class SegmentChange {
    /** 首见于本次装配，没有上一回合可比。 */
    NEW,

    /** 字节完全一致。 */
    UNCHANGED,

    /** 上一回合的内容仍是本次的前缀 —— 缓存安全。 */
    APPENDED,

    /** 前缀被改写 —— 缓存失效。 */
    REWRITTEN,

    /** 非稳定段，不参与前缀判断。 */
    NOT_APPLICABLE,
}

/**
 * 把会话状态装配成一次请求，并按「稳定前缀 / 易变尾部」显式分段。
 *
 * 内核的请求装配器属于不可插件化的四件套之一（见 `AGENTS.md` 第 2 节）：
 * 它保证所有插件产出的上下文都经过同一条缓存契约。
 */
object RequestAssembler {

    /**
     * 工具段序列化结果的缓存：工具列表逐回合完全一致时，复用上次算好的 canonical /
     * readable，免去每回合对每个工具重复 `parameters.toString()` 的开销（工具 schema
     * 往往不小）。键是**列表内容**（[LlmToolSpec] 是 data class，比较即等价比较），
     * 因此工具列表一变就重新序列化，缓存不会让内容失真。容量有界，避免随会话数膨胀。
     */
    private const val TOOL_TEXT_CACHE_CAPACITY = 4
    private val toolTextCache =
        object : LinkedHashMap<List<LlmToolSpec>, ToolsText>(TOOL_TEXT_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<List<LlmToolSpec>, ToolsText>,
            ): Boolean = size > TOOL_TEXT_CACHE_CAPACITY
        }

    private data class ToolsText(val canonical: String, val readable: String)

    @Synchronized
    private fun toolsText(tools: List<LlmToolSpec>): ToolsText =
        toolTextCache.getOrPut(tools) { ToolsText(canonicalTools(tools), readableTools(tools)) }

    fun assemble(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<LlmToolSpec>,
        options: SamplingOptions = SamplingOptions(),
        sessionId: String? = null,
        cache: Boolean = true,
        /**
         * 本发请求的目标端点（U76）：提供商目录 id 与**已归一化**的主机名
         * （由调用方经 `hostOf(baseUrl)` 取得，装配期不再自己解析地址）。
         * 两者进 [ChatRequest]，供思考回传的来源判据使用；null = 未声明来源，历史思考不回传。
         *
         * 端点身份在这一发里**只归一化一次**：落库侧（`TurnExecutor.snapshot`）直接取
         * `request.providerId` / `request.providerHost`，不再解析一次地址 —— 两处各算一次
         * 就会出现「自己发的请求认不出自己刚落的思考」这种漏判。
         */
        providerId: String? = null,
        providerHost: String? = null,
    ): RequestAssembly {
        // 注入提示统一在这里加 wire 标记：四个 provider 共用同一份 request.messages，
        // 因此不必逐家改；canonical 也必须用同一份，缓存检查才等于实际发送字节。
        val wireMessages = messages.map { it.wireSystemNote() }
        val request = ChatRequest(
            model = model,
            systemPrompt = systemPrompt,
            messages = wireMessages,
            tools = tools,
            options = options,
            sessionId = sessionId,
            cache = cache,
            providerId = providerId,
            providerHost = providerHost,
        )
        val toolsText = toolsText(tools)
        val segments = listOf(
            RequestSegment(
                SegmentKind.SYSTEM, "系统提示", systemPrompt, stable = true,
            ),
            RequestSegment(
                SegmentKind.TOOLS, "工具列表", toolsText.canonical, stable = true,
                readableRenderer = { toolsText.readable },
            ),
            RequestSegment(
                SegmentKind.HISTORY, "历史回合", canonicalMessages(wireMessages, request), stable = true,
                readableRenderer = { readableMessages(wireMessages, request) },
            ),
            RequestSegment(
                SegmentKind.VOLATILE,
                "请求尾部",
                canonicalOptions(options, cache),
                stable = false,
            ),
        )
        return RequestAssembly(request, segments)
    }
}

/** 比较同会话相邻两次装配：判断该段是保持、追加，还是被改写。 */
fun diffSegment(previous: RequestSegment?, current: RequestSegment): SegmentChange {
    if (!current.stable) return SegmentChange.NOT_APPLICABLE
    if (previous == null) return SegmentChange.NEW
    if (previous.canonical == current.canonical) return SegmentChange.UNCHANGED
    if (current.canonical.startsWith(previous.canonical)) return SegmentChange.APPENDED
    return SegmentChange.REWRITTEN
}

/**
 * 工具段的确定性文本。顺序即装配顺序；`parameters` 由 `JsonObject` 承载，
 * 其底层是插入有序的 map，构造确定则输出确定。
 */
private fun canonicalTools(tools: List<LlmToolSpec>): String = buildString {
    tools.forEach { t ->
        append(t.name).append('\u0000')
        append(t.description).append('\u0000')
        append(t.parameters.toString())
        append('\u0001')
    }
}

/**
 * 历史回合的确定性文本，镜像 provider 实际发出的字段（role / 文本 / 工具调用 / 工具结果）。
 * 消息 id、时间戳、用量不参与前缀字节；`thoughtSignature` 只在 Gemini 回灌时进入请求体，
 * 这里一并计入，保证摘要与实际字节一致。
 *
 * 思考内容与传输层同口径：只有**同一来源端点**（providerId + host + model，U76）产出的思考
 * 才会回传，判据统一走 [replaysThinkingTo] —— 摘要与真实请求必须用同一个判断，
 * 否则缓存命中率观测会失真。
 */
private fun canonicalMessages(messages: List<ChatMessage>, request: ChatRequest): String = buildString {
    messages.forEach { m ->
        append(m.role.name).append('\u0000')
        val replayThinking = m.replaysThinkingTo(request)
        m.parts.forEach { p ->
            when (p) {
                is Part.Text -> append('t').append('\u0000').append(p.text)
                    .append('\u0000').append(p.signature.orEmpty())
                is Part.ToolCall -> append('c').append('\u0000')
                    .append(p.id).append('\u0000').append(p.name).append('\u0000').append(p.arguments)
                    .append('\u0000').append(p.signature.orEmpty())
                is Part.ToolResult -> append('r').append('\u0000')
                    .append(p.toolCallId).append('\u0000').append(p.toolName).append('\u0000')
                    .append(p.content).append('\u0000').append(if (p.isError) '1' else '0')
                // 图片：本体过大，前缀只记「类型 + url / 附件摘要」，仍具确定性。
                // 引用式图片（本体在附件存储）直接用内容寻址的 sha256，与是否已水合无关，
                // 前缀因此不受「请求期水合」影响。
                is Part.Image -> append('i').append('\u0000')
                    .append(p.mediaType).append('\u0000')
                    .append(p.url ?: p.ref?.sha256 ?: "sha256:" + shortDigest(p.base64.orEmpty()))
                is Part.Thinking -> if (replayThinking) {
                    append('h').append('\u0000').append(p.thinking)
                        .append('\u0000').append(p.signature.orEmpty())
                        .append('\u0000').append(if (p.redacted) '1' else '0')
                }
            }
            append('\u0001')
        }
        append('\u0002')
    }
}

/** 请求尾部的确定性文本；这些参数排在消息之后，不属于缓存前缀。 */
private fun canonicalOptions(options: SamplingOptions, cache: Boolean): String = buildString {
    append("temperature=").append(options.temperature ?: "").append('\u0000')
    append("max_tokens=").append(options.maxTokens ?: "").append('\u0000')
    append("reasoning_effort=").append(options.reasoningEffort ?: "").append('\u0000')
    append("frequency_penalty=").append(options.frequencyPenalty ?: "").append('\u0000')
    append("presence_penalty=").append(options.presencePenalty ?: "").append('\u0000')
    append("top_p=").append(options.topP ?: "").append('\u0000')
    append("seed=").append(options.seed ?: "").append('\u0000')
    append("cache=").append(cache)
}

/**
 * 工具段的可读渲染：逐个工具列出「名字 / 描述 / 参数 schema」。
 * 与 [canonicalTools] 内容一一对应，只是把分隔符换成换行，供人阅读。
 */
private fun readableTools(tools: List<LlmToolSpec>): String = buildString {
    tools.forEach { t ->
        append("● ").append(t.name).append('\n')
        append(t.description).append('\n')
        append(t.parameters.toString()).append("\n\n")
    }
}

/**
 * 历史回合的可读渲染：逐条消息列出角色与各部件（文本 / 思考 / 工具调用 / 工具结果）。
 * 思考只在同一来源端点时回传（与 [canonicalMessages] 一致），这里把「回传了什么」直接展示出来。
 */
private fun readableMessages(messages: List<ChatMessage>, request: ChatRequest): String = buildString {
    messages.forEach { m ->
        append("【").append(m.role.name).append("】\n")
        val replayThinking = m.replaysThinkingTo(request)
        m.parts.forEach { p ->
            when (p) {
                is Part.Text -> append(p.text).append('\n')
                is Part.ToolCall -> append("→ 调用 ").append(p.name).append(' ')
                    .append(p.arguments).append('\n')
                is Part.ToolResult -> append("← 结果 ").append(p.toolName)
                    .append(if (p.isError) "（错误）" else "").append(' ')
                    .append(p.content).append('\n')
                is Part.Image -> append("图片 ").append(p.mediaType)
                    .append(
                        when {
                            p.url != null -> " " + p.url
                            p.ref != null -> " ref(${p.ref?.sha256?.take(8)})"
                            else -> " base64(${(p.base64 ?: "").length} 字符)"
                        },
                    )
                    .append('\n')
                is Part.Thinking -> if (replayThinking) {
                    if (p.redacted) {
                        append("思考（服务端已隐去，原样回传）").append('\n')
                    } else {
                        append("思考 ").append(p.thinking).append('\n')
                    }
                }
            }
        }
        append('\n')
    }
}

/** 取 SHA-256 前 8 字节作为内容摘要，足以发现前缀变化。 */
private fun shortDigest(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * 把控制字符转义成可见形式，供面板核对「原始前缀」。
 *
 * `\u0000`（字段分隔）、`\u0001`（部件分隔）、`\u0002`（消息分隔）是缓存前缀的约定分隔符，
 * 直接渲染要么不可见、要么是方块「乱码」；转义后既能逐字核对，又不会让人误以为是脏数据。
 */
internal fun escapeControls(text: String): String = buildString(text.length) {
    text.forEach { c ->
        when (c) {
            '\u0000' -> append("\\0")
            '\u0001' -> append("\\1")
            '\u0002' -> append("\\2")
            else -> if (c < ' ' && c != '\n' && c != '\r' && c != '\t') {
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                append(c)
            }
        }
    }
}
