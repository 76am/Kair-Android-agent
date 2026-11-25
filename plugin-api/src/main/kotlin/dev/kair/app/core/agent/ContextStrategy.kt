package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage

/**
 * 压缩参数，由设置驱动。属于内核与策略共享的配置契约，不是某个实现的私有细节。
 */
data class CompactionSettings(
    val enabled: Boolean = true,
    /** 预留给模型自身输出的 token；压缩在窗口填满前触发。 */
    val reserveTokens: Int = 16384,
    /** 压缩后大致保留多少近期 token 原文。 */
    val keepRecentTokens: Int = 20000,
)

/** 一次压缩的计划：哪些消息被摘要、哪些原样保留。 */
data class CompactionPlan(
    val summarized: List<ChatMessage>,
    val kept: List<ChatMessage>,
    val previousSummary: String?,
    val tokensBefore: Int,
)

/**
 * 本次请求压缩的原因（对齐 dsh 的 `CompactionTrigger`）。
 *
 * 内核在两种时机询问策略：
 * - [PRESSURE]：常规压力，上下文逼近窗口上限（[shouldCompact] 命中）；
 * - [CONTEXT_OVERFLOW]：上下文溢出，端点已经拒答，且内核独立确认仍超预算。
 *
 * 策略据此选择更激进、更有损但能自救的计划（如 [Compaction.forcedPlan]）。把「原因」
 * 放进契约而不是藏在某个实现内部，是为了让替换策略也能决定「溢出时允许丢多少」。
 */
enum class CompactionTrigger {
    PRESSURE,
    CONTEXT_OVERFLOW,
}

/**
 * 上下文/压缩策略契约。内核只认这个接口，实现由插件提供。
 *
 * 它决定「何时压缩、如何摘要」：内核在每轮请求前询问 [shouldCompact]，
 * 需要时依次调用 [plan] 制定计划、[summarize] 生成摘要、[wrapSummary] 组装检查点正文。
 *
 * 内核只持有「当前策略」这一个引用（单例能力，后注册者覆盖先注册者；
 * 卸载时按提供者历史栈回滚）。没有插件提供策略时，内核降级为**不压缩**——
 * 长会话可能触发溢出，但不会被阻断。
 * 契约见 `docs/架构与内核边界.md` 第 5.1 节、`docs/插件接口契约.md`。
 */
interface ContextStrategy {

    /** 稳定标识，如 `dev.kair.context.summarizing`。 */
    val id: String

    /** 估算上下文是否已接近窗口上限（窗口减去 [CompactionSettings.reserveTokens]）。 */
    fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean

    /**
     * 制定压缩计划；没有可压缩的内容时返回 null。[trigger] 说明本次为何要压缩。
     *
     * **契约**：返回的 [CompactionPlan.kept] 必须是 [messages] 的连续**尾部后缀**。内核在摘要
     * 失败时按 `original.takeLast(plan.kept.size)` 回取同长度的原历史（保住原图），下标对齐
     * 依赖这一前缀关系；若 `kept` 不是尾部后缀，失败路径会把不属于保留段的消息带回来。
     */
    fun plan(
        messages: List<ChatMessage>,
        settings: CompactionSettings,
        trigger: CompactionTrigger,
    ): CompactionPlan?

    /** 用一次单轮补全生成摘要正文；实现自行负责 prompt 与结果解析。 */
    suspend fun summarize(
        model: String,
        config: ProviderConfig,
        plan: CompactionPlan,
        customInstructions: String?,
        reserveTokens: Int,
        sessionId: String?,
    ): String

    /** 把摘要与相关文件清单包装成检查点消息的正文。 */
    fun wrapSummary(plan: CompactionPlan, summary: String): String
}
