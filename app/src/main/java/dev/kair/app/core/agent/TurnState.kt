package dev.kair.app.core.agent

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 会话运行状态（ADR 0004 / `docs/tasks/archive/P2-1-会话状态机.md`）。
 *
 * A1 之前这件事由四个量交叉表达：`_status`（IDLE / RUNNING）、`_stopping`、
 * `_compacting`、`pendingRuns`。它们必须随时一致，却没有任何东西在守 ——
 * 置位与复位错一行就会永久卡在「正在停止」（界面转圈顶掉停止键、发送永久禁用）。
 *
 * 现在只有一个封闭状态，且只能经 [AgentSession.transition] 写入。`Stopping` 只能从
 * [Running] 进入：**「空闲且正在停止」这个组合在类型上不存在** —— 旧 bug 不是
 * 「顺序写对了」，而是写不出来。
 */
sealed interface TurnState {
    /** 没有任何工作在跑，可以立即开新回合。 */
    data object Idle : TurnState

    /**
     * 非回合的串行动作在跑（如删除消息）：它必须与回合共用 `runGate`，
     * 对外算「忙」（`isBusy`），但不算「回合在跑」（`isRunning`）。
     */
    data object Busy : TurnState

    /**
     * 一个回合（或手动压缩）正在跑。
     *
     * [compacting] 是回合内的一个**相位**，不是并列状态：压缩只由 `compactIfNeeded`
     * （在 `runLoop` 内）与 `compactNow`（`launchRun` 内）触发，两者都在回合里。
     * 拆成独立状态会让「Running 且 Compacting」这类非法组合重新变得可表达。
     */
    data class Running(val compacting: Boolean = false) : TurnState

    /**
     * 取消信号已投递、收尾（杀进程 / 修复工具配对 / 落盘）仍在进行。
     *
     * 只能从 [Running] 进入；对外的 `status` 投影仍是 RUNNING（ADR 0003：
     * 停止期间对外表现为忙，界面把停止键换成转圈）。
     */
    data object Stopping : TurnState
}

/**
 * 转移表：[AgentSession.transition] 据此校验唯一写入。
 *
 * 只列「能到达的状态」；同状态视为合法（幂等），于是重复 `abort()` 不需要特判，
 * 而 `Idle → Stopping` 这类旧 bug 的形态会被直接拒绝。
 */
internal fun TurnState.canTransitionTo(next: TurnState): Boolean = when (this) {
    TurnState.Idle ->
        next == TurnState.Idle || next == TurnState.Busy || next == TurnState.Running()
    TurnState.Busy ->
        next == TurnState.Busy || next == TurnState.Idle || next == TurnState.Running()
    is TurnState.Running ->
        next is TurnState.Running || next == TurnState.Stopping || next == TurnState.Idle
    TurnState.Stopping ->
        next == TurnState.Stopping || next == TurnState.Idle
}

/**
 * 停止收尾的硬上界（U57）：点了停止之后，最多等这么久就必须回到 [TurnState.Idle]。
 *
 * 为什么必须有上界：取消是**协作式**的。`AgentSession.abort()` 只能取消协程，打不断
 * 阻塞在网络前置阶段（DNS 解析 / TCP 连接）的调用 —— 弱网下那个调用可能十几分钟不返回，
 * 用户看到的是永久转圈、只能杀 App。ADR 0003 要求「正常路径等收尾结束才 Idle」，这条
 * 上界**不推翻**它：正常收尾（毫秒级到几秒）远小于本值，只有真的卡死时才会触发。
 *
 * 20 秒是用户容忍度，不是实现指标。**不许为了「少触发」把它抬高** —— 正常收尾经常
 * 超过它，说明主修（网络前置阶段的取消 / 上界，见 `core/ai/PreConnect.kt`）没做好，
 * 该去修那段，而不是把止损线往后挪。
 */
const val StopHardLimitMs = 20_000L

/**
 * 只读投影：值始终由 [source] 现算，不持有第二份状态。
 *
 * 存在的唯一理由：既有用例（`AgentSessionInterruptTest` 的断言不得改写，ADR 0003 的
 * 结论不可动）用 `.value` 读 `status` / `stopping`。写入点仍只有
 * [AgentSession.transition]，这里没有任何可写的副本。
 */
internal class ProjectedStateFlow<T>(
    private val source: StateFlow<TurnState>,
    private val project: (TurnState) -> T,
) : StateFlow<T> {
    override val value: T get() = project(source.value)

    override val replayCache: List<T> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        // distinctUntilChanged：多个 TurnState 可能投影成同一个值（如 Running 的两个压缩
        // 相位对 `status` 都是 RUNNING），去掉重复值时才符合 StateFlow 的「只发不同值」契约。
        source.map(project).distinctUntilChanged().collect(collector)
        // StateFlow 的 collect 契约是不返回；走到这里说明上游实现被换掉了，是编程错误。
        error("StateFlow must not complete")
    }
}
