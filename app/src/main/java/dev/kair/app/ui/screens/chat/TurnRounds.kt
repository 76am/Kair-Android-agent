package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason

/**
 * 回合切分（设计稿 v2「折叠规则」的落点）。
 *
 * 一轮 = 用户发令的那条消息，到下一段已经收口的 agent 输出为止；中间的
 * 「思考 + 工具调用 + 中间说明」折进折叠卡，**最终回答始终在折叠区外**。
 * 回合内部的 user 消息（工具回图、回合中改道的引导）不切回合，见 [keepsTurnOpen]。
 * 压缩检查点与系统提示不并入回合 —— 它们是转录上的独立事件，各自成一条
 * [FeedMessage]（沿用原卡片样式）。
 *
 * 耗时口径（负责人拍板的方案 A）：回合 elapsed = 回合内最后一条消息的 timestamp
 * 减去触发它的用户消息 timestamp。零内核改动，代价是流式期间的时间只是近似 ——
 * 换来的是不给 `ChatMessage` 加 duration 字段（plugin-api ABI 不动）。
 */

/** 折叠卡里的一步：思考或工具调用。中间说明（正文段）不算步数，只随折叠体渲染。 */
internal enum class TurnStepKind { THINK, TOOL }

/**
 * 折叠头统计的一步。只喂折叠头的计数与 chips；折叠体按 [TurnStepGroup] 分组渲染
 * （[ToolCallBlock] 保持现结构被复用，不为此重造行组件）。
 */
internal data class TurnStep(
    val kind: TurnStepKind,
    /** 工具步 = 归一化后的工具名；思考步 = null。 */
    val toolName: String?,
    val failed: Boolean,
    val denied: Boolean,
)

/**
 * 折叠体内的一「步」：一条 assistant 消息，以及紧随其后的工具结果 / 工具回图。
 *
 * 步是删除与重试的单位：分隔线上的「删除这一步 / 重试这一步」作用于这一整组，
 * 删完不会留下半截工具结果。切分口径与转录顺序一致 —— 每条 assistant 起一步，
 * 其后的 TOOL_RESULT、纯图片 user 消息并入当前步；遇下一条 assistant 开新步。
 */
internal data class TurnStepGroup(
    val messages: List<ChatMessage>,
    val failed: Boolean,
) {
    /** 步的锚点：重试从这一条开始撤回（步内第一条）。 */
    val anchor: ChatMessage get() = messages.first()

    val key: String get() = anchor.id
}

/**
 * 一个回合的解码速度汇总（U111）：只累加有 stats 的 assistant 响应。
 *
 * [anchorId] 是本回合最后一条带 stats 的 assistant 消息 id —— 完成提示用它确认
 * 「这条平均确实属于刚结束的这轮」，而不是上一条回合的陈旧读数。
 */
internal data class TurnSpeed(
    val averageTps: Double,
    val outputTokens: Int,
    val decodeMs: Long,
    val firstTokenMs: Long?,
    val estimated: Boolean,
    val anchorId: String,
)

/** 一个回合的折叠视图。 */
internal data class TurnRound(
    /** 触发回合的用户消息；会话开头就出现的 agent 段为 null。 */
    val user: ChatMessage?,
    /** 回合内的消息（assistant + tool_result，以及回合中插入的 user），按转录顺序。 */
    val agent: List<ChatMessage>,
    val steps: List<TurnStep>,
    /** 展开体按步分组；顺序与转录一致。 */
    val stepGroups: List<TurnStepGroup>,
    /** 归一化工具名 → 次数，保持首次出现顺序（折叠头 chips）。 */
    val toolCounts: List<Pair<String, Int>>,
    val failCount: Int,
    val denyCount: Int,
    /** 回合耗时（毫秒）；方案 A 的 timestamp 差近似。 */
    val elapsedMs: Long,
    /** 本回合的解码速度汇总；界面打开期间没观测到 stats 时为 null。 */
    val speed: TurnSpeed?,
    /** 折叠区外的那条最终回答；运行中或没有正文时为 null。 */
    val finalAnswer: ChatMessage?,
    val running: Boolean,
) {
    /** 一步工具都没用的回合不出折叠头（设计稿：避免给简单回复加壳）。 */
    val foldable: Boolean
        get() = !running && steps.any { it.kind == TurnStepKind.TOOL }

    /**
     * 头行「已处理 N 步」与展开体的分隔线条数共用这一个口径。
     *
     * 从「思考 + 每次工具调用各算一步」改成「每条 assistant 算一步」：一步就是模型的一次
     * 决策（想了什么、说了什么、调了哪些工具）加它带回来的结果，和删除 / 重试的作用范围
     * 完全重合。旧的统计口径会让头行读 9 步、展开体只有 3 条分隔线。
     */
    val stepCount: Int get() = stepGroups.size

    /** 折进折叠体的消息（agent 段去掉 finalAnswer），顺序即转录顺序。 */
    val folded: List<ChatMessage> get() = stepGroups.flatMap { it.messages }

    /** LazyColumn key 与折叠展开状态的归属键：优先用户消息 id（回合的锚点）。 */
    val key: String get() = user?.id ?: agent.first().id
}

/** LazyColumn 的一行：独立消息（检查点 / 系统提示）或一个回合。 */
internal sealed interface FeedItem {
    val key: String
}

internal data class FeedMessage(val msg: ChatMessage) : FeedItem {
    override val key: String get() = "m:${msg.id}"
}

internal data class FeedTurn(val round: TurnRound) : FeedItem {
    override val key: String get() = "t:${round.key}"
}

/**
 * 工具名归一化（设计稿 `normTool`）：去掉 `container_` / `workspace_` 前缀，
 * 下划线换成间隔点 —— chips 上 `container_shell` 变成 `shell`、`workspace_files` 变成 `files`。
 */
internal fun normalizeToolName(name: String): String =
    name.removePrefix("container_").removePrefix("workspace_").replace('_', '·')

/**
 * 「被拒」的判据：审批闸门拒绝 / 超时时，内核把 `blockReason` 原样写成一条
 * **错误**工具结果（见 `ApprovalPlugin` 的 DENY / TIMEOUT 分支）。内核没有专门的
 * 「被拒」字段，这里按结果文本前缀识别 —— 只影响展示统计，不进任何请求字节。
 * 失败数因此要**排除**被拒：被拒不是执行失败。
 */
private val DeniedResultPrefixes = listOf("The user denied this", "Approval timed out")

internal fun isDeniedResult(result: Part.ToolResult?): Boolean =
    result != null && DeniedResultPrefixes.any { result.content.startsWith(it) }

/**
 * 工具回带的图片（read_image / 截屏等）是不是一条「假用户消息」。
 *
 * 内核让模型看见图片时复用了用户发图的形状：在工具结果之后**另追加**一条纯图片的
 * user 消息（见 `TurnRunner.persist`）。判据带「前一条是工具结果」，用来在
 * [matchToolResults] 里区分它与用户真发的纯图片消息（后者要结束上一回合的配对窗口）。
 */
internal fun isToolImageMessage(message: ChatMessage, previous: ChatMessage?): Boolean =
    message.role == Role.USER &&
        message.text.isBlank() &&
        message.images.isNotEmpty() &&
        previous?.role == Role.TOOL_RESULT

/**
 * 这条消息落进转录后，回合是否仍「开着」（模型还要继续跑）。
 *
 * 回合边界不能只看 `role == USER`：转录里插进 user 消息的地方不止用户发消息 ——
 * 工具回图（`TurnRunner.persist`）和回合中改道的引导消息（`AgentSession.steer`）
 * 都是 user 角色，却属于**当前回合内部**的事件。按角色硬切会把一个回合拦腰截断，
 * 卡片顺序与配对随之错乱。
 *
 * 所以边界改判「上一段是否已经收口」：只有工具结果、或带工具调用且未被中止 / 出错的
 * assistant 消息之后，模型才还需要继续；其余（最终回答、错误、中止、系统提示）
 * 都意味着这段已结束，下一条 user 消息才是新回合的发令枪。
 */
private fun keepsTurnOpen(message: ChatMessage): Boolean = when (message.role) {
    // 封口的尾条（内核在中断收尾时给「没有 assistant 可标」的回合最后一条工具结果
    // 标 ABORTED，见 `HistorySanitizer.sealInterruptedTail`）：这一轮已经结束，
    // 下一条用户消息必须开新回合，不能被当成回合内引导折进上一轮。
    Role.TOOL_RESULT -> message.stopReason != StopReason.ABORTED
    Role.ASSISTANT -> message.toolCalls.isNotEmpty() &&
        message.stopReason != StopReason.ABORTED &&
        message.stopReason != StopReason.ERROR
    else -> false
}

/**
 * 把消息列表切成 [FeedItem]：用户消息开回合，agent / 工具结果并入当前回合，
 * 检查点与系统提示打断回合并独立成行。
 *
 * [matched] 来自 [matchToolResults]（按回合配对的工具结果），用于逐步判失败 / 被拒。
 *
 * **运行中的回合只认「最后一轮」**，不按消息 id 去各轮里找：忙起来时新用户消息可能已经
 * 落进转录（回合中改道的引导、排队的下一条），按 id 匹配会把**上一轮已完成**的回合
 * 也标成运行中 —— 它的卡片会突然全部展开，看起来像顺序错乱。
 */
internal fun buildFeedItems(
    messages: List<ChatMessage>,
    matched: Map<String, List<Part.ToolResult?>>,
    busy: Boolean,
    responseStats: Map<String, ResponseStats> = emptyMap(),
): List<FeedItem> {
    val out = mutableListOf<FeedItem>()
    var user: ChatMessage? = null
    var agent = mutableListOf<ChatMessage>()

    fun flush(isLast: Boolean) {
        if (user == null && agent.isEmpty()) return
        out.add(FeedTurn(makeRound(user, agent, matched, running = busy && isLast, responseStats)))
        user = null
        agent = mutableListOf()
    }

    // 当前段是否还「开着」（见 keepsTurnOpen）：开着时到来的 user 消息是回合内部事件
    // （工具回图 / 回合中改道的引导），必须并入本回合，不能当新回合的发令枪。
    var open = false
    fun flushOpen() {
        flush(isLast = false)
        open = false
    }
    messages.forEachIndexed { i, m ->
        // 工具回图是「上一条工具结果的产物」，即使本回合已封口（中断收尾）也留在这一轮里；
        // 用户自己发的消息则必须开新回合。
        val toolImage = isToolImageMessage(m, messages.getOrNull(i - 1))
        when {
            m.isCompaction || m.isSystemNote -> {
                flushOpen()
                out.add(FeedMessage(m))
            }
            m.role == Role.USER && (open || toolImage) -> agent.add(m)
            m.role == Role.USER -> {
                flushOpen()
                user = m
            }
            m.role == Role.ASSISTANT || m.role == Role.TOOL_RESULT -> {
                agent.add(m)
                open = keepsTurnOpen(m)
            }
            else -> {
                flushOpen()
                out.add(FeedMessage(m))
            }
        }
    }
    flush(isLast = true)
    // 末尾是独立事件（检查点 / 系统提示）时，最后一个回合仍是「正在跑的那一轮」。
    if (busy) {
        val lastTurn = out.indexOfLast { it is FeedTurn }
        if (lastTurn >= 0) {
            val round = (out[lastTurn] as FeedTurn).round
            if (!round.running) {
                out[lastTurn] = FeedTurn(makeRound(round.user, round.agent, matched, running = true, responseStats))
            }
        }
    }
    return out
}

private fun makeRound(
    user: ChatMessage?,
    agent: List<ChatMessage>,
    matched: Map<String, List<Part.ToolResult?>>,
    running: Boolean,
    responseStats: Map<String, ResponseStats>,
): TurnRound {
    // 最终回答 = 回合**最后一条**消息，且它是已经收口的 assistant 正文；运行中不给。
    val finalAnswer = if (running) null else agent.lastOrNull()?.takeIf { it.closesTurnWithAnswer() }
    val folded = agent.filterNot { it === finalAnswer }
    val stepGroups = groupSteps(folded, matched)

    val steps = mutableListOf<TurnStep>()
    val counts = linkedMapOf<String, Int>()
    for (m in folded) {
        if (m.role != Role.ASSISTANT) continue
        if (m.thinking.isNotBlank()) steps.add(TurnStep(TurnStepKind.THINK, null, false, false))
        m.toolCalls.forEachIndexed { i, call ->
            val result = matched[m.id]?.getOrNull(i)
            val denied = isDeniedResult(result)
            val failed = result?.isError == true && !denied
            steps.add(TurnStep(TurnStepKind.TOOL, normalizeToolName(call.name), failed, denied))
            counts.merge(normalizeToolName(call.name), 1, Int::plus)
        }
    }

    val startTs = user?.timestamp ?: agent.firstOrNull()?.timestamp ?: 0L
    val endTs = agent.lastOrNull()?.timestamp ?: user?.timestamp ?: 0L
    return TurnRound(
        user = user,
        agent = agent,
        steps = steps,
        stepGroups = stepGroups,
        toolCounts = counts.entries.map { it.key to it.value },
        failCount = steps.count { it.failed },
        denyCount = steps.count { it.denied },
        elapsedMs = (endTs - startTs).coerceAtLeast(0L),
        speed = turnSpeed(agent, responseStats),
        finalAnswer = finalAnswer,
        running = running,
    )
}

/**
 * 回合平均速度：只汇总**有 stats 的 assistant 响应**，按输出 token 加权。
 *
 * 分母是解码耗时（首 token → 末 token），不是 [TurnRound.elapsedMs] ——
 * 后者含工具执行与审批等待，拿来除 token 会把速度压成一个误导性的低值。
 */
private fun turnSpeed(
    agent: List<ChatMessage>,
    responseStats: Map<String, ResponseStats>,
): TurnSpeed? {
    val rows = agent.mapNotNull { msg -> responseStats[msg.id]?.let { msg to it } }
        .filter { (_, s) -> s.outputTokens > 0 && s.decodeMs > 0 }
    if (rows.isEmpty()) return null
    val tokens = rows.sumOf { it.second.outputTokens }
    val decodeMs = rows.sumOf { it.second.decodeMs }
    if (decodeMs <= 0) return null
    return TurnSpeed(
        averageTps = tokens * 1000.0 / decodeMs,
        outputTokens = tokens,
        decodeMs = decodeMs,
        firstTokenMs = rows.first().second.firstTokenMs,
        estimated = rows.any { it.second.estimated },
        anchorId = rows.last().first.id,
    )
}

/**
 * 把折叠体的消息按「步」切开：每条 assistant 起一步，其后的工具结果 / 工具回图并入
 * 当前步。回合若从孤儿工具结果开头（压缩切点留下的），它自成一「步」，不丢渲染。
 */
private fun groupSteps(
    folded: List<ChatMessage>,
    matched: Map<String, List<Part.ToolResult?>>,
): List<TurnStepGroup> {
    val groups = mutableListOf<MutableList<ChatMessage>>()
    for (m in folded) {
        if (m.role == Role.ASSISTANT || groups.isEmpty()) groups.add(mutableListOf(m)) else groups.last().add(m)
    }
    return groups.map { msgs ->
        val assistant = msgs.firstOrNull { it.role == Role.ASSISTANT }
        val failed = assistant != null &&
            assistant.toolCalls.indices.any { i ->
                val result = matched[assistant.id]?.getOrNull(i)
                result?.isError == true && !isDeniedResult(result)
            }
        TurnStepGroup(msgs, failed)
    }
}

/**
 * 这条消息是不是「收口的最终回答」。
 *
 * 不能取「最后一条有正文的 assistant」：模型常在调工具前先写一句说明（「我先读一下主题」），
 * 后面还会跟若干条**只有工具调用、没有正文**的 assistant（read_image 就是典型）。
 * 按「有正文」回看会把中间说明拎到折叠区外，它之后的读图 / 写文件反而留在折叠体里 ——
 * 删掉这条说明，边界只会往前退一格，顺序看起来永远错位。
 *
 * 所以回答只认回合的最后一条：带正文、且不再有待执行的工具调用。出错 / 中止是例外 ——
 * 它们的正文就是这条回合的最终产出，即便还挂着没跑完的调用也要留在折叠区外。
 */
private fun ChatMessage.closesTurnWithAnswer(): Boolean {
    if (role != Role.ASSISTANT || text.isBlank()) return false
    return toolCalls.isEmpty() ||
        stopReason == StopReason.ERROR ||
        stopReason == StopReason.ABORTED
}

/**
 * 耗时文案：只报整数，按量级换单位 —— `42s` / `1m 33s` / `2h 05m`。
 *
 * 不报小数：折叠头与读秒都以秒为单位跳，`.1` 这种尾数每 100ms 抖一次，
 * 在等宽小字上像在闪；整秒读数在长回合里已经完全够用。
 *
 * 换单位的理由：长回合只报秒会变成 `3725s` 这种要人自己换算的读数；分层后
 * 秒级只报秒、分钟级带秒、小时级带两位分钟（`05`），在等宽小字里宽度也稳定。
 * 整分 / 整点省掉更小的单位（`2m` / `3h`），末尾不挂无意义的零。
 */
internal fun formatElapsed(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1_000
    val minutes = total / 60
    val seconds = total % 60
    val hours = minutes / 60
    val restMinutes = minutes % 60
    return when {
        hours > 0 -> if (restMinutes == 0L) "${hours}h" else "${hours}h ${twoDigits(restMinutes)}m"
        minutes > 0 -> if (seconds == 0L) "${minutes}m" else "${minutes}m ${twoDigits(seconds)}s"
        else -> "${seconds}s"
    }
}

/** 两位补零：`5` → `05`，让 `2h 05m` / `1m 05s` 在等宽字里不跳宽度。 */
private fun twoDigits(value: Long): String = value.toString().padStart(2, '0')
