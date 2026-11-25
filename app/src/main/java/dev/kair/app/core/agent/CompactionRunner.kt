package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * 压缩的对外控制面（U58）：回合循环只吃这一个契约。
 *
 * 为什么合成一个接口：`compactIfNeeded` / `compactOnOverflow` 与「模型主动请求」
 * 是同一条压缩路径的三个入口，共享同一份待压缩标记；拆成几条独立 lambda 会让
 * 「谁能置位、谁负责清」散落在宿主里（`AGENTS.md` §10.4）。
 */
internal interface CompactionControl {
    /** 回合内每步开头调用：按阈值触发，或消费「模型主动请求」标记后强制压缩。 */
    suspend fun compactIfNeeded(): Boolean

    /** 端点溢出救援：按 [CompactionTrigger.CONTEXT_OVERFLOW] 压缩（允许策略走有损兜底）。 */
    suspend fun compactOnOverflow(strategy: ContextStrategy): Boolean

    /**
     * 模型侧「下一次请求之前压缩」的请求（`compact_context` 工具的唯一出口）。
     *
     * - 归属：**会话级**（随 [CompactionRunner] 实例，跟着会话对象生死），不是进程级单例；
     * - 生命周期：会话创建即有；清除时机：回合内首步消费、手动压缩、压缩提交；
     * - 并发：工具协程置位、回合循环消费，两者不在同一协程 —— [AtomicBoolean] 足够。
     */
    fun requestCompaction()
}

/**
 * 上下文压缩的执行器（A5 第二步拆出）。
 *
 * 与 [TurnExecutor] 同一个套路：**不持有 [AgentSession]**，只吃一个显式的依赖面 [Host]。
 * 这样单测可以拿假 host 直接驱动压缩决策与失败降级，不必起一个真会话；也不必修
 * 把会话的私有成员降成 `internal`。
 *
 * 语义与搬迁前逐字一致 —— 这里只搬位置，不改行为。
 */
internal class CompactionRunner(private val host: Host) : CompactionControl {

    /** 「模型已请求压缩」标记；归属 / 生命周期 / 清除 / 并发语义见 [CompactionControl.requestCompaction]。 */
    private val pendingRequest = AtomicBoolean(false)

    override fun requestCompaction() {
        pendingRequest.set(true)
    }

    /**
     * 依赖面：全是会话已有的能力，**没有新增写入点**。
     *
     * 状态写入（`transition`）通过 [markCompacting] / [clearCompacting] 交回会话 ——
     * 状态只能由会话的 `transition()` 写，执行器不碰 `TurnState`。
     */
    internal data class Host(
        val sessionId: String,
        val workspace: File,
        /** 当前装配产物：固定前缀估算的提示与工具 schema 必须出自同一次装配（U42）。 */
        val assembly: () -> AgentAssembly,
        val compactionSettings: () -> CompactionSettings,
        val contextStrategy: () -> ContextStrategy?,
        /**
         * **现取**窗口，只服务救援内部的 `overBudget` 复核（问的是「按当前设置重算，降级后仍超预算吗」）。
         * 溢出**判定**用的是另一份 —— 本发请求的快照窗口（`TurnRunner` 读 `outcome.inputs.contextWindow`，U42）；
         * 两者语义不同，别合并成一个来源：判定问「那一发是不是被窗口顶死了」，这里问「现在值不值得压」。
         */
        val contextWindow: () -> Int,
        /**
         * 摘要请求的目标模型与端点（U113）：设置项「压缩模型」优先，空 / 已删除时与本会话同源。
         * 只有摘要这一发用它 —— 压缩阈值与超窗判定仍按主请求的窗口算（溢出说的是主请求）。
         */
        val target: () -> CompactionTarget,
        val hooks: HookBus,
        /** 当前历史（只读）：压缩不回写历史之外的任何状态。 */
        val messages: () -> List<ChatMessage>,
        /** 整份重写历史；返回 false = 这一轮已被放弃、拒绝改写（U77），此时不许再报「已压缩」。 */
        val replaceHistory: (List<ChatMessage>) -> Boolean,
        /**
         * 自救链第一步的**纯内存**候选：返回「更早的图片已降级为占位」的历史。
         *
         * 刻意**不落盘、不广播**：只有本次压缩真的提交（[replaceHistory]）时才随历史
         * 一起生效。早先的实现是在 [run] 开头直接改写历史，于是「摘要失败 / 切点找不到 /
         * 被取消」这些**没有压缩成功**的路径也会留下降级结果 —— 用户看到历史图片凭空
         * 只剩一张，附件本体还被随后的 GC 删掉。语义见 [run]。
         */
        val demotedMessages: () -> List<ChatMessage>,
        val markCompacting: () -> Unit,
        val clearCompacting: () -> Unit,
        val onEvent: (AgentEvent) -> Unit,
        val turnCount: () -> Int,
        val diagnostics: Diagnostics,
        /** 压缩成功后的效果回执（`tokensBefore` / `tokensAfter`），由会话补 seq。 */
        val onCompacted: (tokensBefore: Int, tokensAfter: Int) -> Unit,
    )

    /**
     * 生效的压缩设置：把「保留原文 token」收敛到「窗口 − 预留」之内
     * （见 [Compaction.effectiveKeepRecentTokens]），避免小窗口下切点找不到、
     * 压缩静默失效。
     */
    fun effectiveSettings(): CompactionSettings {
        val settings = host.compactionSettings()
        return settings.copy(
            keepRecentTokens = Compaction.effectiveKeepRecentTokens(
                keepRecentTokens = settings.keepRecentTokens,
                contextWindow = host.contextWindow(),
                reserveTokens = settings.reserveTokens,
            ),
        )
    }

    /** 固定前缀（system prompt + 工具 schema）的启发式估算，口径与 [Compaction.estimateTokens] 一致。 */
    fun fixedPrefixTokens(): Int {
        // 提示与工具面取自同一次装配（U42）：各取一次会在切模型后估出一个不存在的前缀。
        val built = host.assembly()
        val schema = built.tools
            .joinToString("\n") { "${it.name}\n${it.description}\n${it.parameters}" }
        return Compaction.estimateTextTokens(built.settings.systemPrompt) + Compaction.estimateTextTokens(schema)
    }

    /** Compact when the estimated context crosses the threshold, or when the model asked for it. */
    override suspend fun compactIfNeeded(): Boolean {
        // 先消费标记：即使下面因为「没策略 / 关开关」不压，标记也不能留到下一步反复触发。
        val requested = pendingRequest.getAndSet(false)
        val strategy = host.contextStrategy() ?: return false
        val settings = effectiveSettings()
        if (!settings.enabled) return false
        if (!requested) {
            // system prompt 与工具 schema 同样占用端点窗口；漏算会让压缩延后到真超窗。
            val tokens = Compaction.estimateContextTokens(host.messages()) + fixedPrefixTokens()
            if (!strategy.shouldCompact(tokens, host.contextWindow(), settings)) return false
        }
        return run(strategy, settings)
    }

    /** 端点溢出救援：按 [CompactionTrigger.CONTEXT_OVERFLOW] 压缩（允许策略走有损兜底）。 */
    override suspend fun compactOnOverflow(strategy: ContextStrategy): Boolean =
        run(strategy, effectiveSettings(), CompactionTrigger.CONTEXT_OVERFLOW)

    /** 用户手按压缩（输入区圆环入口）：忽略阈值直接压一次；模型主动请求标记一并清掉。 */
    suspend fun compactNow(strategy: ContextStrategy): Boolean = run(strategy, effectiveSettings())

    /** 摘要结果：正文 + 失败成因（成功时为 null）。失败时正文就是机械检查点。 */
    private data class Summary(val text: String, val failure: String?)

    /**
     * 一次压缩尝试。**只有真的提交了历史改写才产生副作用**（负责人 2026-10-31 定）。
     *
     * 关键在第一步：图片降级只是**内存里的候选**，不落盘。于是下面每一条提前返回
     * （切点找不到 / 摘要请求失败 / 被取消）都让历史保持原样 ——
     * 用户不会看到「压缩没成功，历史图片却只剩一张」。
     *
     * 摘要失败仍会退化为机械检查点（否则上下文停在超窗状态，会话永久卡死），但落盘的
     * 后缀用**原历史**，图片不降级；只有在机械检查点之后仍超预算时才做最后一道降级，
     * 并单独记一条诊断（那种情况下不降级就必然反复溢出）。见 [commit]。
     */
    suspend fun run(
        strategy: ContextStrategy,
        settings: CompactionSettings,
        /**
         * 本次压缩的原因。只有 [CompactionTrigger.CONTEXT_OVERFLOW]（端点已拒答）在
         * 「内核独立确认仍超预算」时才允许策略走有损兜底。
         */
        trigger: CompactionTrigger = CompactionTrigger.PRESSURE,
    ): Boolean {
        // 开始压缩即清掉「模型主动请求」标记：无论本次成败，同一条请求都不应再触发第二次
        // （手动压缩与溢出救援也走这里，手动压完之后残留的标记同样必须失效）。
        pendingRequest.set(false)
        val original = host.messages()
        // 自救链第一步（对齐 dsh「先剪枝 → 再测量 → 最后才摘要」）：把「更早的图片」
        // 降级为占位，**只算在内存里**。这一步不写盘，所以下面的提前返回都不留痕迹。
        val demoted = host.demotedMessages()

        // 口径必须与 [compactIfNeeded] 的入口判定**逐项一致**（含 system prompt 与工具
        // schema 的固定前缀）。少算这一段会让「降级后」看起来比实际小：入口判超、这里判不超，
        // 真正的溢出信号就被降级成 PRESSURE、拿不到有损兜底，而真实请求仍超窗 ——
        // 于是「溢出 → 切分 → 仍溢出」反复空转，最后被端点拒答。
        val contextTokens = Compaction.estimateContextTokens(demoted) + fixedPrefixTokens()
        val overBudget = Compaction.shouldCompact(contextTokens, host.contextWindow(), settings)
        // 这里**没有**「降级后已回到预算内就不压缩」的短路（负责人 2026-10-31 决定去掉）：
        // 判定进入压缩就压到底，只留这一条规则。`overBudget` 只用于判断溢出信号可不可信。
        // 溢出信号也可能来自与上下文无关的错误（连接中断等）。只有内核独立确认仍超预算时
        // 才把它升级为真·上下文溢出，避免把无关错误当溢出、白白丢掉历史。
        val effective =
            if (trigger == CompactionTrigger.CONTEXT_OVERFLOW && overBudget) CompactionTrigger.CONTEXT_OVERFLOW
            else CompactionTrigger.PRESSURE
        // 切点找不到（历史太短 / 全是不成轮的片段）：同样不落盘，历史原样。
        val plan = strategy.plan(demoted, settings, effective) ?: return false

        host.markCompacting()
        try {
            val summary = summarize(strategy, settings, plan)
            // 本次摘要成功 = 上一次的失败已不再成立，清掉过期条目（覆盖式语义）。
            if (summary.failure == null) host.diagnostics.clearCompactionFailure(host.sessionId)
            else host.diagnostics.recordCompactionFailure(host.sessionId, summary.failure)
            return commit(strategy, settings, plan, original, summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 走到这里说明摘要已成功，失败的是**重写历史本身**（落盘 I/O 等）。历史保持
            // 原样是正确降级，但不能沉默：没有诊断，用户只会看到上下文反复溢出而不知为何。
            host.diagnostics.recordCompactionFailure(
                host.sessionId,
                "${e.javaClass.simpleName}: ${e.message ?: "压缩失败"}",
            )
            return false
        } finally {
            host.clearCompacting()
        }
    }

    /**
     * 发摘要请求；失败退化为机械检查点（不调模型、按策略已算好的切点硬切）。
     *
     * **四类入口的唯一收口**：自动阈值 / 模型主动请求（`compactIfNeeded`）、用户手动
     * （`compactNow`）、端点溢出救援（`compactOnOverflow`）都走 [run] 到这里，所以
     * 「压缩模型」只在这里取一次 —— 没有任何一条路径会漏用主模型。
     *
     * 摘要失败**不能**静默回滚：历史停在原地 = 上下文仍超窗，下一回合再次溢出、再次失败，
     * 会话永久卡在这条路径上。所以这里把异常收成「机械检查点 + 失败成因」，由调用方决定
     * 怎么落盘（失败成因进 Diagnostics）。
     */
    private suspend fun summarize(
        strategy: ContextStrategy,
        settings: CompactionSettings,
        plan: CompactionPlan,
    ): Summary {
        // 「压缩模型」可以指向另一个提供商：模型与端点必须成对取自同一个解析结果，
        // 不能拿主请求的 config 去发压缩模型的请求。
        val target = host.target()
        // 摘要请求也必须带上插件注入的请求头（如鉴权），否则第三方必需头会让压缩永久失败。
        val config = target.config.copy(headers = host.hooks.runHeaders(target.config.headers))
        return try {
            Summary(
                text = strategy.summarize(
                    model = target.model,
                    config = config,
                    plan = plan,
                    customInstructions = null,
                    reserveTokens = settings.reserveTokens,
                    sessionId = host.sessionId,
                ),
                failure = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Summary(Compaction.MECHANICAL_CHECKPOINT, "${e.javaClass.simpleName}: ${e.message ?: "摘要请求失败"}")
        }
    }

    /**
     * 提交一次压缩：写历史、必要时再降一次、广播效果。
     *
     * 落盘的后缀分两种：
     *  · 摘要成功 → `plan.kept`（基于降级后的历史算出，天然带着图片降级）；
     *  · 摘要失败 → **原历史**的同长度后缀，图片一张不动。
     * `demoteOlderImages` 只改 parts、不增删消息，所以两份后缀长度一致、位置一一对应
     * （`plan.kept` 永远是历史的后缀，`takeLast` 取到的是同一批消息）。
     */
    private fun commit(
        strategy: ContextStrategy,
        settings: CompactionSettings,
        plan: CompactionPlan,
        original: List<ChatMessage>,
        summary: Summary,
    ): Boolean {
        val summaryMessage = ChatMessage(
            role = Role.USER,
            isCompaction = true,
            parts = listOf(Part.Text(strategy.wrapSummary(plan, summary.text))),
        )
        val kept = if (summary.failure == null) plan.kept else original.takeLast(plan.kept.size)
        // 被保留消息上的 usage 是「压缩前」测得的，已不对应当前（重写后的）前缀；
        // 保留它会让 estimateContextTokens 一直读到压缩前的大值（251k→251k）并反复
        // 触发压缩。usage 不进入请求字节，清掉不影响缓存前缀。
        var newHistory = listOf(summaryMessage) + Compaction.dropStaleUsage(kept)
        // 拒绝改写 = 这一轮已被强制放弃（U77）：历史保持原样，**也不许报「已压缩」** ——
        // 否则界面会弹一次什么都没发生的成功，插件还会照着重建索引。
        if (!host.replaceHistory(newHistory)) return false
        // 机械检查点保留了原文（含图片），极端情况下仍可能超预算 —— 那就会陷入
        // 「溢出 → 机械切分 → 仍溢出」的循环。作为最后一道保险再降一次，
        // 并单独记一条诊断，与「摘要失败」区分开。
        if (summary.failure != null) {
            val stillOver = Compaction.shouldCompact(
                Compaction.estimateContextTokens(newHistory) + fixedPrefixTokens(),
                host.contextWindow(),
                settings,
            )
            if (stillOver) {
                newHistory = listOf(summaryMessage) + Compaction.dropStaleUsage(plan.kept)
                if (!host.replaceHistory(newHistory)) return false
                host.diagnostics.recordCompactionFailure(
                    host.sessionId,
                    "${summary.failure}；机械检查点后仍超预算，已降级历史图片",
                )
            }
        }
        // 压缩已重写前缀，顺手让系统提示贡献点重新读取外部状态（如记忆插件的
        // .kair/memory.md）——段落缓存不把这类外部状态当输入，不主动清就会一直
        // 沿用压缩前的旧段落。此处的缓存失效是「免费」的：历史段本就被重写，且只清本工作区作用域（U19②）。
        SystemPromptBuilder.invalidate(host.workspace.path)
        // 「压缩前」按**原始**历史估算：用户关心的是省下多少，拿降级后的小值只会
        // 让效果看起来比实际差。
        val tokensBefore = Compaction.estimateContextTokens(original)
        val tokensAfter = Compaction.estimateContextTokens(newHistory)
        // 通知插件历史被重写：此后前缀必然失效，插件可借机重建自己的本地索引。
        host.onEvent(
            AgentEvent.Compacted(
                sessionId = host.sessionId,
                workspace = host.workspace.path,
                turnCount = host.turnCount(),
                tokensBefore = tokensBefore,
                tokensAfter = tokensAfter,
            ),
        )
        // 记录本次压缩效果，供 UI 提示用户「上下文已压缩」。newHistory 里失真 usage 已被
        // 清掉，estimateContextTokens 只能靠启发式估算，得到的就是压缩后的真实大小。
        host.onCompacted(tokensBefore, tokensAfter)
        return true
    }
}
