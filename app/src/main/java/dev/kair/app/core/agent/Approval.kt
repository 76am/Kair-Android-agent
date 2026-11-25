package dev.kair.app.core.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * 用户对一次工具调用的决定。
 *
 * - [ALLOW_ONCE] 只放行这一次，不记忆。
 * - [ALLOW_SESSION] 本会话内同类操作一律放行。
 * - [ALLOW_ALWAYS] 永久放行（跨会话），由宿主持久化。
 * - [DENY] 拒绝；调用不执行，理由回给模型。
 * - [TIMEOUT] 用户长时间未操作；按「未批准」结束调用，理由回给模型。
 */
enum class ApprovalDecision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY, TIMEOUT }

/** 一条待用户确认的工具调用。 */
data class ApprovalRequest(
    val id: String,
    /**
     * 放行归类键：决定「本会话 / 永久放行」记忆的范围。由审批插件按**调用内容**生成
     * （`<approvalKey>:<命令或参数>`，危险命令再加 `#danger`），因此放行粒度是「这一条
     * 命令 / 这一组参数」，而不是「这个工具」。
     */
    val ruleKey: String,
    /** 展示给用户的一句话说明。 */
    val summary: String,
    /** 原始参数（JSON 文本），供用户查看细节。 */
    val arguments: String,
    /** 发起该调用的会话 id；用于「本会话放行」的归类，也用于让 UI 只展示当前会话的请求。 */
    val sessionId: String?,
    /** 是否允许「永久放行」；危险操作由工具置 false，只能逐次 / 逐会话确认。 */
    val rememberable: Boolean,
    /**
     * 子智能体发起时的**顶层父会话 id**：审批卡片显示在这个会话的聊天页。
     * 为 null 表示请求就来自 [sessionId] 本身。
     */
    val ownerSessionId: String? = null,
    /** 子智能体发起时的来源名称（如「检索日志」），用于卡片上说明是谁在请求。 */
    val originLabel: String? = null,
)

/**
 * 审批闸门（内核服务）：挂在 `tool_call` 钩子上，用一次挂起换取用户的决定。
 *
 * 设计要点：
 * - **按会话并行**：每个展示会话（[ApprovalRequest.ownerSessionId] 优先，否则
 *   [ApprovalRequest.sessionId]）各自维护一条待批请求；不同会话可同时看到卡片。
 *   同一会话内仍按到达顺序串行，UI 一次只面对一个请求。
 * - **拒绝即阻断**：返回 [ApprovalDecision.DENY] 时调用方应构造
 *   `ToolCallOutcome(blockReason = ...)`，复用既有的「工具错误结果」语义。
 * - 放行规则分三级：本次 / 本会话（按 会话 + 归类键）/ 永久（按归类键，可持久化）。
 *   归类键由调用方（审批插件）按**调用内容**生成，闸门只按原样比对，不解释其结构。
 */
class ApprovalGate(
    initialAlwaysAllowed: Set<String> = emptySet(),
    /** 永久放行集合变化时回调，宿主据此落盘；默认不持久化。 */
    private val persistAlways: (Set<String>) -> Unit = {},
    /**
     * 等待用户决定的超时。超时按 [ApprovalDecision.TIMEOUT] 返回，让工具调用结束并把
     * 「用户未操作」回给模型 —— 否则子智能体或无人值守的会话会永久挂起。
     * 超时只覆盖「卡片已展示后的等待」，排队等前一条处理的时间不计入。
     */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /** 超时秒数（UI 提示用）；0 表示不限时。 */
    val timeoutSeconds: Long get() = timeoutMillis / 1000

    /**
     * 「挂起等待用户决定」这套机制本身在 [gate] 里 ——
     * **与提问闸门共用同一份实现**（`AGENTS.md` §10.4：一个概念只允许一个实现点）。
     * 本类只留审批独有的部分：放行规则层。
     */
    private val gate = PendingUserGate<ApprovalRequest, ApprovalDecision>(
        timeoutMillis = timeoutMillis,
        displaySessionId = { it.ownerSessionId ?: it.sessionId ?: "*" },
        idOf = { it.id },
        timeoutAnswer = { ApprovalDecision.TIMEOUT },
        // 规则判定必须在拿到会话锁之后：排队期间别处可能刚加了本会话放行规则。
        fastPath = { req ->
            synchronized(rulesLock) {
                when {
                    req.ruleKey in alwaysAllowed -> ApprovalDecision.ALLOW_ALWAYS
                    ruleKey(req.ruleKey, req.sessionId) in sessionAllowed -> ApprovalDecision.ALLOW_SESSION
                    else -> null
                }
            }
        },
        // 写入也必须留在同一把会话锁内（评审 P1-1）。否则「本会话放行」写在锁外时，
        // 下一个同会话同 ruleKey 的排队请求可能在规则落表前就跑完 fastPath ——
        // 用户明明刚点了「本会话都允许」，它却又弹一次卡片。
        postDecision = { req, decision -> applyRule(req, decision) },
    )

    /**
     * 当前待批请求，按**展示会话 id** 索引；空表示无。
     * UI 用自己会话的 id 取对应卡片，因此不同会话的请求互不遮挡。
     */
    val pending: StateFlow<Map<String, ApprovalRequest>> = gate.pending

    /** 放行规则的读写锁：规则集合会被多个会话并发访问。 */
    private val rulesLock = Any()
    private val alwaysAllowed = initialAlwaysAllowed.toMutableSet()
    private val sessionAllowed = mutableSetOf<String>()

    /** 永久放行的归类键快照。 */
    fun alwaysRules(): Set<String> = synchronized(rulesLock) { alwaysAllowed.toSet() }

    /** 本会话放行的「会话/归类键」快照。 */
    fun sessionRules(): Set<String> = synchronized(rulesLock) { sessionAllowed.toSet() }

    /** 该归类键对应的操作是否已被放行，无需再询问用户。 */
    fun isAllowed(key: String, sessionId: String?): Boolean = synchronized(rulesLock) {
        key in alwaysAllowed || ruleKey(key, sessionId) in sessionAllowed
    }

    /**
     * 请求审批并挂起，直到用户通过 [resolve] 作出决定。
     * 已被放行规则覆盖时直接返回对应决定，不挂起；
     * 同一会话已有待批请求时**排队**，前一条处理完再展示；不同会话互不阻塞。
     */
    suspend fun request(req: ApprovalRequest): ApprovalDecision = gate.await(req)

    /** 用户对 [id] 对应的待批请求作出决定；id 不匹配或无待批请求时忽略。 */
    fun resolve(id: String, decision: ApprovalDecision) = gate.resolve(id, decision)

    /**
     * 清空指定会话的放行规则；不影响其它会话。
     * 结束会话时也应调用，兑现「本会话放行在会话结束后失效」的契约。
     */
    fun clearSessionRules(sessionId: String) = synchronized(rulesLock) {
        sessionAllowed.removeAll { it.startsWith("$sessionId/") }
    }

    /**
     * 会话结束：丢弃会话级放行条目，**并让该会话未决的审批立即按 TIMEOUT 收尾**。
     *
     * 后半句是本批新增的行为：改前这里只清规则，未决的审批要等满 3 分钟超时才结束。
     * 会话都关了不会再有人作答，让工具调用立刻带着「未批准」返回更符合 `AGENTS.md` §7。
     */
    fun endSession(sessionId: String) {
        clearSessionRules(sessionId)
        gate.endSession(sessionId)
    }

    /** 清空永久放行规则并持久化。 */
    fun clearAlwaysRules() {
        synchronized(rulesLock) { alwaysAllowed.clear() }
        persistAlways(emptySet())
    }

    private fun applyRule(req: ApprovalRequest, decision: ApprovalDecision) = synchronized(rulesLock) {
        when {
            // 不可永久放行的操作即使被要求永久放行，也只降级为「本会话」。
            decision == ApprovalDecision.ALLOW_ALWAYS && !req.rememberable ->
                sessionAllowed.add(ruleKey(req.ruleKey, req.sessionId))

            decision == ApprovalDecision.ALLOW_ALWAYS -> {
                alwaysAllowed.add(req.ruleKey)
                persistAlways(alwaysAllowed.toSet())
            }

            decision == ApprovalDecision.ALLOW_SESSION ->
                sessionAllowed.add(ruleKey(req.ruleKey, req.sessionId))

            else -> Unit
        }
    }

    private fun ruleKey(toolName: String, sessionId: String?): String =
        "${sessionId ?: "*"}/$toolName"

    companion object {
        /** 默认审批超时：3 分钟。足够用户看到卡片并决定，又不会让子智能体永久挂起。 */
        const val DEFAULT_TIMEOUT_MILLIS = 3 * 60 * 1000L
    }
}
