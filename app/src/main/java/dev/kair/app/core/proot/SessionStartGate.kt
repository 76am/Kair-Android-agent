package dev.kair.app.core.proot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 一次启动尝试的结论（U44）。**必须能区分**「挂载就绪」与「启动期断流」。 */
internal sealed interface StartOutcome {
    data object Ready : StartOutcome

    /** 进程在 READY 之前就没了（rootfs / su 异常、被系统杀）；[reason] 是给用户看的原因。 */
    data class Closed(val reason: String) : StartOutcome
}

/**
 * 启动闸门：把「READY 到达 / 流提前结束 / 旧代迟到回调」收敛成一个**可区分的结果**。
 *
 * U44 之前 `ContainerSession` 用一个 `CompletableDeferred<Unit>` 表示就绪，而
 * `onStreamClosed` 无条件完成它 —— 进程在 READY 前断流时 `start()` 误以为成功，
 * 把刚写的 `Error` 覆盖成 `Running`（面板显示「运行中」而进程已死）。
 *
 * 代次：每次 [begin] 递增；读线程的每个回调都带自己的代次，旧代迟到回调一律丢弃
 * （否则上一次会话的进程退出会把新会话打成 `Error`）。
 *
 * 抽成独立类的另一个原因是体量守卫（`ContainerSession.kt` 登记值只准减不准增）。
 */
internal class SessionStartGate(private val timeoutMs: Long) {

    private class Attempt(val generation: Long, val outcome: CompletableDeferred<StartOutcome>)

    private var generation = 0L
    private var current: Attempt? = null

    /** 开始一次启动，返回本次代次（读线程回调必须带着它）。 */
    fun begin(): Long = synchronized(this) {
        generation += 1
        current = Attempt(generation, CompletableDeferred())
        generation
    }

    /** [gen] 是否仍是当前代；旧代回调据此直接丢弃。 */
    fun isCurrent(gen: Long): Boolean = synchronized(this) { gen == generation }

    /** 等本次启动的结论；超过 [timeoutMs] 返回 null（调用方按「启动超时」处理）。 */
    suspend fun await(gen: Long): StartOutcome? {
        val attempt = synchronized(this) { current?.takeIf { it.generation == gen } } ?: return null
        return withTimeoutOrNull(timeoutMs) { attempt.outcome.await() }
    }

    /** 收到 READY 帧；已结束的尝试不受影响（`CompletableDeferred` 只认第一次）。 */
    fun ready(gen: Long) = complete(gen, StartOutcome.Ready)

    /** 启动期断流；[reason] 只在确实是当前代时才求值。 */
    fun closed(gen: Long, reason: () -> String) {
        val attempt = attemptOf(gen) ?: return
        attempt.outcome.complete(StartOutcome.Closed(reason()))
    }

    private fun complete(gen: Long, outcome: StartOutcome) {
        attemptOf(gen)?.outcome?.complete(outcome)
    }

    private fun attemptOf(gen: Long): Attempt? = synchronized(this) {
        current?.takeIf { it.generation == gen }
    }
}
