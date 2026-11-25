package dev.kair.app.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * 传输层瞬时失败自动重试。
 *
 * 为什么需要：网络抖动、切数据/Wi-Fi、429 限流、5xx、以及中转站吞错后直接关流产生的
 * 「空响应」都是瞬时故障，重发同一请求即可恢复。主流 agent（Claude Code、Codex）与官方
 * SDK 都有这层；本仓库此前只有「明确溢出 → 压缩后重试」一条自愈路径，网络类失败直接
 * 甩给用户手动长按重试。
 *
 * 为什么放在这里：内核在传输层之上统一包一层，**所有**传输层（含第三方插件）共享同一套
 * 判定、退避与上限，不必各自实现。
 *
 * 两条硬约束，对齐 Claude Code / Codex 的既有行为：
 * 1. **只在「尚未产出任何可见输出」时重发**。一旦有 token 流出，重发意味着重复输出、
 *    重复计费、内容错位；宁可把失败报给用户（由他决定是否撤回重发）。
 * 2. **只重发能确定是瞬时的失败**（[StreamEvent.Failure.isTransient]）。鉴权、参数、
 *    模型名之类重试无意义，只浪费时间。
 *
 * 另有一条刻意**不**重试：链路空闲超时（[stalledUpstreamMessage]）。看门狗已经等满
 * [SseIdleTimeoutMs]，而它无法区分「链路卡住」与「模型长时间思考」；重试会把后者放大成
 * 「每次再等 120 秒」—— Codex 那边正是这样把「慢」变成「更慢」。
 */

/** 单回合最多尝试次数（首次 + 2 次重试）。 */
internal const val TransientMaxAttempts = 3

/** 退避基数与上限：1s → 2s，封顶 8s。 */
internal const val TransientInitialBackoffMs = 1_000L
internal const val TransientMaxBackoffMs = 8_000L

/** 即便服务端给了 `Retry-After`，也不让一次请求静默等过久。 */
internal const val TransientMaxRetryAfterMs = 30_000L

/** 传输层「响应体为空」固定文案（判定与文案的单一来源）。 */
internal const val EMPTY_BODY_MESSAGE = "Empty response body"

/**
 * 解析 `Retry-After`：只认「秒数」形式（HTTP-date 形式留空，退回默认退避）。
 */
internal fun parseRetryAfterMs(raw: String?): Long? {
    val seconds = raw?.trim()?.toLongOrNull() ?: return null
    if (seconds <= 0) return null
    return seconds * 1_000
}

/** 已经被用户看到的内容：一旦出现就不再重发。 */
internal fun StreamEvent.isVisibleOutput(): Boolean = when (this) {
    is StreamEvent.TextDelta,
    is StreamEvent.ReasoningDelta,
    is StreamEvent.ToolCallStarted,
    is StreamEvent.ToolCallArgsDelta,
    is StreamEvent.ToolCallsCompleted,
    -> true
    // Start / End 只是段落边界，本身不含用户可见内容：不能因为收到一个 TextStart
    // 就锁死重发（那时流里还没有任何 token）。它们仍会被转发，见 [retryingStream]。
    is StreamEvent.TextStart,
    is StreamEvent.TextEnd,
    is StreamEvent.ReasoningStart,
    is StreamEvent.ReasoningEnd,
    is StreamEvent.UsageEvent,
    is StreamEvent.Completed,
    is StreamEvent.Failure,
    -> false
}

/**
 * 是否值得重发。传输层标注优先（[StreamEvent.Failure.retryable]）；未标注时交给
 * [classifyError] 结构化判定，拿不准（UNKNOWN）一律不重试。
 */
internal fun StreamEvent.Failure.isTransient(): Boolean {
    retryable?.let { return it }
    if (message == emptyStopMessage() || message == EMPTY_BODY_MESSAGE) return true
    val status = errorStatusCode(message)
    return classifyError(parseErrorDetail(message), status).transient
}

/** 第 [retries] 次重试（从 0 计）的退避：指数增长，且不低于服务端 `Retry-After`。 */
internal fun backoffMs(retries: Int, retryAfterMs: Long?): Long {
    val exponential = (TransientInitialBackoffMs shl retries).coerceAtMost(TransientMaxBackoffMs)
    val server = retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(TransientMaxRetryAfterMs) ?: return exponential
    return maxOf(exponential, server)
}

/**
 * 把 [attempt] 产出的失败流包成「瞬时失败自动重发」的流：正常事件原样转发，
 * 失败则按 [isTransient] 与「是否已产出可见输出」决定重发还是上报。
 *
 * [jitter] / [delayMs] 可注入，便于单测确定化。
 */
internal fun retryingStream(
    attempts: Int = TransientMaxAttempts,
    jitter: (Long) -> Long = { Random.nextLong(0, it / 4 + 1) },
    delayMs: suspend (Long) -> Unit = { delay(it) },
    attempt: () -> Flow<StreamEvent>,
): Flow<StreamEvent> = flow {
    var retries = 0
    while (true) {
        var failure: StreamEvent.Failure? = null
        var visible = false
        // 首个可见内容之前的所有事件都先缓冲：既包括用量 / 完成 / 段落边界这类
        // 「不锁死重发」的事件，也包括 TextStart —— 失败的尝试不该上报它们，同时
        // 成功时必须按原顺序发出（先 TextStart 再 TextDelta，不能因缓冲被挪到后面）。
        val pending = mutableListOf<StreamEvent>()
        try {
            attempt().collect { ev ->
                if (failure != null) return@collect
                when {
                    ev is StreamEvent.Failure -> failure = ev
                    ev.isVisibleOutput() -> {
                        if (!visible) {
                            visible = true
                            pending.forEach { emit(it) }
                            pending.clear()
                        }
                        // 可见内容一旦流出就立即转发（用户已经看到了，收不回来），
                        // 同时也就此锁死重发。
                        emit(ev)
                    }
                    visible -> emit(ev)
                    else -> pending += ev
                }
            }
        } catch (e: CancellationException) {
            // 用户停止 / 上层取消：原样抛出，不能当作一次「可重试的传输失败」。
            throw e
        } catch (e: Exception) {
            // 第三方传输层可能直接抛异常而不是发 Failure。按「未产生可见输出 = 可重发」
            // 处理，与各内置传输层的口径一致；已产生可见输出则由下方直接上报。
            failure = StreamEvent.Failure(localFailureMessage(e), retryable = true)
        }
        val failed = failure
        if (failed == null) {
            pending.forEach { emit(it) }
            return@flow
        }
        if (visible || !failed.isTransient() || retries >= attempts - 1) {
            emit(failed)
            return@flow
        }
        val base = backoffMs(retries, failed.retryAfterMs)
        delayMs(base + jitter(base))
        retries++
    }
}
