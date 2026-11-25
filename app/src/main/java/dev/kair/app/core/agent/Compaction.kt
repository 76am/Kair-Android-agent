package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.Json
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.retryingStream
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

/**
 * Context compaction helpers, ported from pi's `coding-agent/src/core/compaction/`.
 *
 * Pure helpers plus one LLM round-trip that turns the older part of a transcript
 * into a structured checkpoint summary. The summary is replayed as a user turn
 * wrapped in the same tags pi uses, so provider payloads stay contract-compatible.
 *
 * 契约类型 [CompactionSettings] / [CompactionPlan] 与 [ContextStrategy] 接口见
 * `ContextStrategy.kt`；本文件只放纯逻辑，以及把它们接到契约上的内置实现
 * [SummarizingContextStrategy]。
 */

object Compaction {

    // ---- prompts (verbatim from pi; model input contract, kept in English) ----

    const val COMPACTION_SUMMARY_PREFIX =
        "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
    const val COMPACTION_SUMMARY_SUFFIX = "\n</summary>"

    const val SUMMARIZATION_SYSTEM_PROMPT = """You are a context summarization assistant. Your task is to read a conversation between a user and an AI assistant, then produce a structured summary following the exact format specified.

Do NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."""

    const val SUMMARIZATION_PROMPT = """The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.

Use this EXACT format:

## Goal
[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned by user]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Current work]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [Ordered list of what should happen next]

## Critical Context
- [Any data, examples, or references needed to continue]
- [Or "(none)" if not applicable]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

    const val UPDATE_SUMMARIZATION_PROMPT = """The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.

Update the existing structured summary with new information. RULES:
- PRESERVE all existing information from the previous summary
- ADD new progress, decisions, and context from the new messages
- UPDATE the Progress section: move items from "In Progress" to "Done" when completed
- UPDATE "Next Steps" based on what was accomplished
- PRESERVE exact file paths, function names, and error messages
- If something is no longer relevant, you may remove it

Use this EXACT format:

## Goal
[Preserve existing goals, add new ones if the task expanded]

## Constraints & Preferences
- [Preserve existing, add new ones discovered]

## Progress
### Done
- [x] [Include previously done items AND newly completed items]

### In Progress
- [ ] [Current work - update based on progress]

### Blocked
- [Current blockers - remove if resolved]

## Key Decisions
- **[Decision]**: [Brief rationale] (preserve all previous, add new)

## Next Steps
1. [Update based on current state]

## Critical Context
- [Preserve important context, add new if needed]

Keep each section concise. Preserve exact file paths, function names, and error messages."""

    // ---- token estimation (wide-char + image aware) ----

    fun calculateContextTokens(usage: Usage): Int =
        if (usage.totalTokens > 0) usage.totalTokens else usage.input + usage.output + usage.cacheRead + usage.cacheWrite

    /**
     * 图片 token 的固定估计值。
     *
     * 真实占用由视觉编码器按像素决定（如 Claude 为 `⌈w/28⌉×⌈h/28⌉`，一张 1000×2500 的截图
     * ≈3.2k），但 [Part.Image] 只存 base64 / url，拿不到尺寸。取行业常用的宽裕固定值
     * （Hermes 用 1500）。关键是**不要再把它当 0**——按文本估算是旧实现的漏算。
     */
    private const val IMAGE_TOKEN_ESTIMATE = 1500

    /**
     * 被降级的历史图片替换成的文本占位符。随历史回灌给模型，属模型输入契约，用英文。
     * 与 Claude Code `stripImagesFromMessages` 的 `[image]` 一致。
     */
    private const val IMAGE_PLACEHOLDER = "[image]"

    /**
     * 单段文本的 token 估算。
     *
     * pi 的 chars/4 只对英文成立：CJK（中日韩）约 1 token/字，按 chars/4 会**低估约 4 倍**。
     * 长中文会话里这足以让「历史整体大小」失真到压缩切点找不到（见 [planCompaction]），
     * 于是「该压缩」判定成立、压缩却静默失效。这里按字符分类累加：CJK / 全角计 1，其余计 1/4，
     * 纯 ASCII 的结果与 chars/4 完全一致。
     */
    private fun textTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var tokens = 0.0
        for (ch in text) tokens += if (isWideChar(ch)) 1.0 else 0.25
        return ceil(tokens).toInt()
    }

    /** 文本口径的估算，供固定前缀（系统提示 / 工具 schema）与本文件的单消息估算复用。 */
    fun estimateTextTokens(text: String): Int = textTokens(text)

    /** CJK 汉字 / 假名 / 谚文与全角形式：按 ~1 token/字计。 */
    private fun isWideChar(ch: Char): Boolean {
        val c = ch.code
        return c in 0x2E80..0x9FFF ||
            // CJK 部首扩展 + 汉字
            c in 0xAC00..0xD7AF ||
            // 谚文音节
            c in 0xF900..0xFAFF ||
            // CJK 兼容汉字
            c in 0xFF00..0xFFEF         // 全角 / 半角形式
    }

    /**
     * Estimate tokens for one message. 文本按 [textTokens] 的宽字符口径，图片部件按
     * [IMAGE_TOKEN_ESTIMATE] **单独计入**——旧实现只按文本估算，会把图片当 0。
     */
    fun estimateTokens(message: ChatMessage): Int {
        // 被中止的回合不会按原样发出：装配时它的 parts 被投影成一条中断提示
        // （见 AgentSession.modelVisibleHistory）。原文（未完成的 thinking / tool_call /
        // 图片）不占请求字节，若照常计入会让压缩提前触发。
        if (message.role == Role.ASSISTANT && message.stopReason == StopReason.ABORTED) {
            return textTokens(AgentSession.INTERRUPTED_NOTICE)
        }
        var tokens = 0
        when (message.role) {
            Role.SYSTEM, Role.USER -> tokens = textTokens(message.text)
            Role.ASSISTANT -> for (part in message.parts) {
                when (part) {
                    is Part.Text -> tokens += textTokens(part.text)
                    // 思考默认随请求回传（见 SavedModel.replayThinking），要计入体积；
                    // 签名（Anthropic / Responses）同样占请求字节，一并计入。
                    // 用户关闭回传时会略微高估 —— 符合「只高估不低估」的估算原则。
                    is Part.Thinking -> tokens += textTokens(part.thinking + (part.signature ?: ""))
                    is Part.ToolCall -> tokens += textTokens(part.name) + textTokens(part.arguments)
                    else -> Unit
                }
            }
            Role.TOOL_RESULT -> for (r in message.toolResults) tokens += textTokens(r.content)
        }
        tokens += message.images.sumOf { imageTokens(it) }
        return tokens
    }

    /**
     * 单张图片的 token 估算。带附件引用（[Part.Image.ref]）且已知像素尺寸时，按视觉编码器
     * 口径（`⌈w/28⌉×⌈h/28⌉`，与 Claude 一致）计入；否则退回固定值 [IMAGE_TOKEN_ESTIMATE]。
     * 取两者较大值，保证「只高估不低估」——低估会让压缩迟迟不触发。
     */
    private fun imageTokens(image: Part.Image): Int {
        val w = image.ref?.width ?: 0
        val h = image.ref?.height ?: 0
        val byPixels = if (w > 0 && h > 0) ceil(w / 28.0).toInt() * ceil(h / 28.0).toInt() else 0
        return maxOf(byPixels, IMAGE_TOKEN_ESTIMATE)
    }

    /**
     * 纯启发式估算（宽字符 + 图片口径），不信任任何 usage；只在拿不到可信任 usage 时兜底。
     */
    fun estimateHeuristic(messages: List<ChatMessage>): Int = messages.sumOf { estimateTokens(it) }

    /**
     * 压缩后**保留**的消息：清掉其上「在压缩前测得」的 usage。
     *
     * usage 记的是「生成这条消息时整段上下文有多大」。压缩会重写前缀，被保留消息的 usage
     * 仍指向压缩前的大前缀，继续信任它就会一直读到旧的大值（典型症状：压缩后仍显示
     * 251k→251k，并让 [shouldCompact] 反复命中）。因此在压缩发生时直接清掉这些失真值。
     *
     * usage 不进入请求字节（见 [RequestAssembler] 的分段 canonical 与 provider 的
     * `toWire`），清掉**不改变**缓存前缀；之后新回合的 assistant 会带回针对当前前缀测得的
     * 真实 usage，[estimateContextTokens] 又会重新信任它。
     */
    fun dropStaleUsage(kept: List<ChatMessage>): List<ChatMessage> =
        kept.map { if (it.usage == null) it else it.copy(usage = null) }

    /**
     * Estimate context tokens from the transcript, trusting the newest assistant usage
     * and estimating only the messages that follow it.
     *
     * usage 是对「当时整段上下文」的实测，最接近真实占用，只在拿不到时回退 chars/4。
     * **不再**因为历史里存在压缩检查点（[ChatMessage.isCompaction]）就整体回退启发式 ——
     * 检查点常年停在历史头部，其后会累积大量新的、有效的 usage；整体回退会把它们全部丢掉、
     * 长期低估上下文（圆环显示远小于实际前缀），让压缩迟迟不触发。压缩自身的「旧 usage
     * 失真」问题改由 [dropStaleUsage] 在压缩时把失真值清掉来消除，而不是在这里靠位置猜测。
     */
    fun estimateContextTokens(messages: List<ChatMessage>): Int {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val usage = m.usage
            if (m.role == Role.ASSISTANT &&
                usage != null &&
                calculateContextTokens(usage) > 0 &&
                m.stopReason != StopReason.ERROR &&
                m.stopReason != StopReason.ABORTED
            ) {
                var trailing = 0
                for (j in i + 1 until messages.size) trailing += estimateTokens(messages[j])
                return calculateContextTokens(usage) + trailing
            }
        }
        return estimateHeuristic(messages)
    }

    fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean {
        if (!settings.enabled) return false
        return contextTokens > contextWindow - settings.reserveTokens
    }

    /**
     * 生效的「保留原文 token」预算：上限是「窗口 − 预留」。
     *
     * 否则小窗口模型（如 32k 窗口 + 16k 预留 → 触发线 16k）上，默认的
     * [CompactionSettings.keepRecentTokens]（20k）会比触发线还大，[findCutPoint]
     * 从尾部永远累不到预算，cut 退回第一个 user 消息 → [planCompaction] 返回 null，
     * 于是「该压缩」判定成立却压不动，压缩静默失效。
     */
    fun effectiveKeepRecentTokens(keepRecentTokens: Int, contextWindow: Int, reserveTokens: Int): Int =
        keepRecentTokens.coerceAtMost((contextWindow - reserveTokens).coerceAtLeast(0))

    // ---- cut point detection ----

    /**
     * Index of the first message to keep.
     *
     * 常规路径：从尾部累到 [keepRecentTokens] 后**向前**吸附到最近的 user 回合起点，
     * 保证 tool_call / tool_result 配对不被拆、转录以 user 开头。
     *
     * 预算下限：向前吸附可能正好吸到一个**刚发的新回合**上（模型在新回合开头主动压缩
     * 就是这种情形），把保留量从 31k 吃成几 k。保留量不足预算时退到**包含累加点的那个
     * 回合起点**——只会多留、不会少留。
     *
     * 兜底路径：单个 user 回合本身就超过保留预算的 2 倍时（真机一次 94 步、31 分钟），
     * 整段保留等于「压不动」—— 切点退到该回合内部的**步骤边界**（assistant 消息起点）。
     * 只切在 assistant 之前，工具结果永远跟着它的调用留在同一侧；摘要本身是 user 角色，
     * 转录仍以 user 开头。2 倍是刻意留的滞后：正常回合不被切碎，只有真的失控才动刀。
     * 这一步不再以 `cutIndex > 0` 为前提：整段只有一个超大回合时同样要能切，
     * 否则 [planCompaction] 直接返回 null，压缩静默失效。
     */
    fun findCutPoint(messages: List<ChatMessage>, keepRecentTokens: Int): Int {
        val userCuts = messages.indices.filter { messages[it].role == Role.USER }
        if (userCuts.isEmpty()) return 0

        var accumulated = 0
        var cutIndex = userCuts.first()
        var reachedAt = -1
        for (i in messages.indices.reversed()) {
            val tokens = estimateTokens(messages[i])
            if (tokens == 0) continue
            accumulated += tokens
            if (accumulated >= keepRecentTokens) {
                cutIndex = userCuts.firstOrNull { it >= i } ?: userCuts.last()
                reachedAt = i
                break
            }
        }
        // 预算都没累到：整段都在保留范围内，没有可压的部分。
        if (reachedAt < 0) return cutIndex

        // 用逐条启发式求和，而不是 estimateContextTokens：后者的「最新 usage 优先」记的是
        // 压缩前的整段上下文，拿来判断「保留了多少」会误判（usage 250k / 实际只留 200 tok）。
        var keptTokens = messages.subList(cutIndex, messages.size).sumOf { estimateTokens(it) }
        if (keptTokens < keepRecentTokens) {
            cutIndex = userCuts.lastOrNull { it <= reachedAt } ?: cutIndex
            keptTokens = messages.subList(cutIndex, messages.size).sumOf { estimateTokens(it) }
        }
        if (keptTokens <= keepRecentTokens * 2) return cutIndex

        // 从到达点往前找最近的步骤起点；找不到（整段没有 assistant 边界）就保持回合起点。
        for (a in minOf(reachedAt, messages.lastIndex) downTo cutIndex + 1) {
            if (messages[a].role == Role.ASSISTANT) return a
        }
        return cutIndex
    }

    /**
     * Decide what to summarize. A previous compaction checkpoint is folded into the
     * next summary (iterative update) instead of being re-summarized verbatim.
     * Returns null when there is nothing worth compacting.
     */
    fun planCompaction(messages: List<ChatMessage>, settings: CompactionSettings): CompactionPlan? {
        if (messages.isEmpty()) return null
        val head = messages.first()
        val previousSummary = if (head.isCompaction) unwrapSummary(head.text) else null
        val rest = if (previousSummary != null) messages.drop(1) else messages
        if (rest.size <= 1) return null

        val cut = findCutPoint(rest, settings.keepRecentTokens)
        if (cut <= 0) return null

        return CompactionPlan(
            summarized = rest.subList(0, cut),
            kept = rest.subList(cut, rest.size),
            previousSummary = previousSummary,
            tokensBefore = estimateContextTokens(messages),
        )
    }

    /** 逃生舱一次丢弃的 user 回合比例（对齐 Claude Code `truncateHeadForPTLRetry` 的 20%）。 */
    private const val FORCED_DROP_FRACTION = 0.2

    /**
     * 最后手段的压缩计划（对齐 Claude Code 的 `truncateHeadForPTLRetry`）。
     *
     * 常规 [planCompaction] 找不到切点（历史整体小于「保留原文」预算）却仍被判超限时，
     * 再压不掉就只能是整段卡死。这里按 user 回合切分，直接丢弃最老的约 20% 回合（至少 1 个），
     * 保留其余 —— 有损，由 [ContextStrategy.plan] 在 [CompactionTrigger.CONTEXT_OVERFLOW]
     * 时才采用。
     *
     * 按 user 回合切分是为了不拆散 tool_call / tool_result 配对，并保持 user 起头的转录。
     */
    fun forcedPlan(messages: List<ChatMessage>, settings: CompactionSettings): CompactionPlan? {
        if (messages.isEmpty()) return null
        val head = messages.first()
        val previousSummary = if (head.isCompaction) unwrapSummary(head.text) else null
        val rest = if (previousSummary != null) messages.drop(1) else messages

        val roundStarts = rest.indices.filter { rest[it].role == Role.USER }
        if (roundStarts.size < 2) return null
        val dropCount = maxOf(1, (roundStarts.size * FORCED_DROP_FRACTION).toInt())
        val cut = roundStarts.getOrNull(dropCount) ?: return null

        return CompactionPlan(
            summarized = rest.subList(0, cut),
            kept = rest.subList(cut, rest.size),
            previousSummary = previousSummary,
            tokensBefore = estimateContextTokens(messages),
        )
    }

    /**
     * 图片占位文本。[path] 可推导时写成 `[image: <path>]` —— 模型据此还能用 `read_image`
     * 重新读回图片（对齐 dsh：省略占位携带只读路径），否则退化为 `[image]`。
     */
    fun imagePlaceholder(path: String?): String =
        if (path.isNullOrBlank()) IMAGE_PLACEHOLDER else "[image: $path]"

    /**
     * 只保留最近一条带图消息里的图片，更早的历史图片降级为文本占位符
     * （对齐 Claude Code `stripImagesFromMessages`）。
     *
     * 图片一旦被模型看过，再把它当原始字节留在转录里、每回合重发，既烧 token，也可能让端点
     * 直接拒掉请求。由 [AgentSession] 在**压缩/溢出自救时**调用一次并持久化：此刻前缀本就要
     * 重写，降级不额外破坏缓存；若改成每回合滑窗降级，会让中段图片反复变化，每回合打掉一次
     * 前缀缓存（dsh 特意为此记录「缓存复用止于第一张被修改的消息」）。
     *
     * [pathHint] 按消息下标给出该图片的来源路径（如 `read_image` 读的磁盘文件），
     * 供 [imagePlaceholder] 决定占位文本；用户直接附加的图片没有来源路径。
     *
     * 幂等：只有存在「更早的图片」时才改写，否则逐字节返回原列表（缓存安全）。
     */
    fun demoteOlderImages(
        messages: List<ChatMessage>,
        pathHint: (Int) -> String? = { null },
    ): List<ChatMessage> {
        val newestImageAt = messages.indexOfLast { it.images.isNotEmpty() }
        if (newestImageAt < 0) return messages
        var changed = false
        val result = messages.mapIndexed { i, m ->
            if (i == newestImageAt || m.images.isEmpty()) m
            else {
                changed = true
                val placeholder = imagePlaceholder(pathHint(i))
                m.copy(parts = m.parts.map { p -> if (p is Part.Image) Part.Text(placeholder) else p })
            }
        }
        return if (changed) result else messages
    }

    /**
     * 摘要模型不可用时的**机械检查点**正文：不调模型、不依赖运行期状态，因此逐字节确定。
     *
     * 为什么必须有：摘要失败若只是「本次压缩作废」，上下文仍超窗，下一回合再次溢出、
     * 再次失败 —— 会话永久卡在这条路径上（见 `AgentSessionLoopTest.failedSummaryStillRewritesHistory`）。
     * 宁可丢掉**已被压缩计划抛弃**的那部分原文（有损，但换来一条能继续走下去的历史），
     * 也不能让历史停在原地。丢了什么、为什么丢，由 `Diagnostics` 对用户可见。
     *
     * 面向模型，用英文（`AGENTS.md` §4）。
     */
    const val MECHANICAL_CHECKPOINT: String =
        "The earlier turns of this conversation were dropped to stay within the context window " +
            "because the summarization request failed. Their content is no longer available here. " +
            "Work from the workspace state and the recent turns below; re-read files if you need details."

    fun wrapSummary(summary: String): String = COMPACTION_SUMMARY_PREFIX + summary + COMPACTION_SUMMARY_SUFFIX

    fun unwrapSummary(text: String): String {
        var body = text
        if (body.startsWith(COMPACTION_SUMMARY_PREFIX)) body = body.removePrefix(COMPACTION_SUMMARY_PREFIX)
        // 文件清单被追加在收尾标签**之后**（`wrapSummary` + `formatFileOperations`）。
        // 用 `endsWith` 判断会漏掉这种检查点，清单与 `</summary>` 会一起被塞进下一轮的
        // `<previous-summary>`；这里按收尾标签**首次**出现的位置截断。
        val end = body.indexOf(COMPACTION_SUMMARY_SUFFIX)
        if (end >= 0) body = body.substring(0, end)
        return body
    }

    // ---- serialization ----

    private const val TOOL_RESULT_MAX_CHARS = 2000

    private fun truncateForSummary(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n[... ${text.length - maxChars} more characters truncated]"
    }

    /** Serialize messages to text so the model reads a digest instead of continuing a conversation. */
    fun serializeConversation(messages: List<ChatMessage>): String {
        val parts = ArrayList<String>()
        for (m in messages) {
            when (m.role) {
                Role.SYSTEM, Role.USER -> {
                    if (m.text.isNotEmpty()) parts.add("${m.summaryLabel()}: ${m.text}")
                    // 图片本体不进摘要请求（对齐 Claude Code：几兆截图重发给模型只为换一句
                    // 「用户发了张图」是纯浪费）；只留占位符，让摘要知道这里曾有过图。
                    if (m.images.isNotEmpty()) parts.add("[User attached an image]")
                }
                Role.ASSISTANT -> {
                    if (m.text.isNotEmpty()) parts.add("[Assistant]: ${m.text}")
                    val calls = m.toolCalls
                    if (calls.isNotEmpty()) {
                        parts.add("[Assistant tool calls]: " + calls.joinToString("; ") { "${it.name}(${it.arguments})" })
                    }
                }
                Role.TOOL_RESULT -> for (r in m.toolResults) {
                    if (r.content.isNotEmpty()) parts.add("[Tool result]: ${truncateForSummary(r.content, TOOL_RESULT_MAX_CHARS)}")
                }
            }
        }
        return parts.joinToString("\n\n")
    }

    // ---- file operation tracking (pi: computeFileLists / formatFileOperations) ----

    private class FileOps {
        val read = LinkedHashSet<String>()
        val written = LinkedHashSet<String>()
        val edited = LinkedHashSet<String>()
    }

    private fun collectFileOps(messages: List<ChatMessage>): FileOps {
        val ops = FileOps()
        for (m in messages) {
            if (m.role != Role.ASSISTANT) continue
            for (call in m.toolCalls) {
                val path = toolCallPath(call.arguments) ?: continue
                when (call.name) {
                    "read" -> ops.read.add(path)
                    "write" -> ops.written.add(path)
                    "edit" -> ops.edited.add(path)
                }
            }
        }
        return ops
    }

    private fun toolCallPath(arguments: String): String? = runCatching {
        Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun formatFileOperations(summarized: List<ChatMessage>): String {
        val ops = collectFileOps(summarized)
        val modified = (ops.edited + ops.written).sorted()
        val readOnly = ops.read.filterNot { it in modified }.sorted()
        val sections = ArrayList<String>()
        if (readOnly.isNotEmpty()) sections.add("<read-files>\n${readOnly.joinToString("\n")}\n</read-files>")
        if (modified.isNotEmpty()) sections.add("<modified-files>\n${modified.joinToString("\n")}\n</modified-files>")
        if (sections.isEmpty()) return ""
        return "\n\n" + sections.joinToString("\n\n")
    }

    // ---- summarization LLM call ----

    /** Build the single-user-turn prompt that asks the model for a checkpoint summary. */
    fun buildSummaryPrompt(summarized: List<ChatMessage>, previousSummary: String?, customInstructions: String?): String {
        val conversationText = serializeConversation(summarized)
        var prompt = "<conversation>\n$conversationText\n</conversation>\n\n"
        if (previousSummary != null) prompt += "<previous-summary>\n$previousSummary\n</previous-summary>\n\n"
        var base = if (previousSummary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT
        if (!customInstructions.isNullOrBlank()) base = "$base\n\nAdditional focus: $customInstructions"
        return prompt + base
    }

    /**
     * Run the summarization completion and return its text. Cache writes are disabled
     * for these one-off calls (pi: `cacheRetention: "none"`).
     */
    suspend fun generateSummary(
        model: String,
        config: ProviderConfig,
        summarized: List<ChatMessage>,
        previousSummary: String?,
        customInstructions: String?,
        reserveTokens: Int,
        sessionId: String?,
    ): String {
        val maxTokens = (reserveTokens * 0.8).toInt().coerceAtLeast(512)
        val request = ChatRequest(
            model = model,
            systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
            messages = listOf(
                ChatMessage(
                    role = Role.USER,
                    parts = listOf(Part.Text(buildSummaryPrompt(summarized, previousSummary, customInstructions))),
                ),
            ),
            tools = emptyList(),
            options = SamplingOptions(maxTokens = maxTokens),
            sessionId = sessionId,
            cache = false,
        )
        val text = StringBuilder()
        var failure: String? = null
        // 与主请求一致：瞬时网络抖动 / 429 / 5xx 走同一套重试，避免一次可恢复故障
        // 直接变成压缩失败（压缩是超窗时唯一的自救手段）。
        retryingStream { ProviderTransports.stream(request, config) }.collect { ev ->
            when (ev) {
                is StreamEvent.TextDelta -> text.append(ev.text)
                is StreamEvent.Failure -> failure = ev.message
                else -> Unit
            }
        }
        failure?.let { throw IllegalStateException("Summarization failed: $it") }
        val summary = text.toString().trim()
        if (summary.isEmpty()) throw IllegalStateException("Summarization produced an empty summary")
        return summary
    }
}

/**
 * 内置的摘要式压缩策略：把 [Compaction] 的纯逻辑接到 [ContextStrategy] 契约上。
 * 由内置插件 `dev.kair.plugin.context` 注册后生效；未注册时内核不压缩。
 * 换一种算法（不同切点 / 摘要格式 / 本地小模型）只需替换插件，无需改内核。
 */
object SummarizingContextStrategy : ContextStrategy {

    override val id = "dev.kair.context.summarizing"

    override fun shouldCompact(
        contextTokens: Int,
        contextWindow: Int,
        settings: CompactionSettings,
    ): Boolean = Compaction.shouldCompact(contextTokens, contextWindow, settings)

    /**
     * 常规切点找不到（历史整体还小于「保留原文」预算、却仍被判超限）时，只有
     * [CompactionTrigger.CONTEXT_OVERFLOW] —— 端点已拒答、且内核独立确认仍超预算 ——
     * 才退到 [forcedPlan] 的有损兜底。正常压力压缩不丢数据。
     */
    override fun plan(
        messages: List<ChatMessage>,
        settings: CompactionSettings,
        trigger: CompactionTrigger,
    ): CompactionPlan? =
        Compaction.planCompaction(messages, settings)
            ?: if (trigger == CompactionTrigger.CONTEXT_OVERFLOW) Compaction.forcedPlan(messages, settings) else null

    override suspend fun summarize(
        model: String,
        config: ProviderConfig,
        plan: CompactionPlan,
        customInstructions: String?,
        reserveTokens: Int,
        sessionId: String?,
    ): String = Compaction.generateSummary(
        model = model,
        config = config,
        summarized = plan.summarized,
        previousSummary = plan.previousSummary,
        customInstructions = customInstructions,
        reserveTokens = reserveTokens,
        sessionId = sessionId,
    )

    override fun wrapSummary(plan: CompactionPlan, summary: String): String =
        Compaction.wrapSummary(summary) + Compaction.formatFileOperations(plan.summarized)
}
