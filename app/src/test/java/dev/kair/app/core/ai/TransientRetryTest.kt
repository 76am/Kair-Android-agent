package dev.kair.app.core.ai

import dev.kair.app.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输层瞬时失败自动重试：只重发「能确定是瞬时」且「尚未产出任何可见输出」的失败，
 * 退避 1s → 2s 封顶 8s，并尊重服务端 `Retry-After`。
 */
class TransientRetryTest {

    private class Scripted(private val scripts: List<List<StreamEvent>>) {
        var calls = 0
            private set

        /** 第 n 次尝试取第 n 段脚本；脚本用尽后重复最后一段（便于验证「到顶就停」）。 */
        fun next(): Flow<StreamEvent> {
            val script = scripts[minOf(calls, scripts.lastIndex)]
            calls++
            return script.asFlow()
        }
    }

    private fun runRetry(
        scripts: List<List<StreamEvent>>,
        attempts: Int = TransientMaxAttempts,
    ): Triple<List<StreamEvent>, Int, List<Long>> {
        val scripted = Scripted(scripts)
        val delays = mutableListOf<Long>()
        val events = runBlocking {
            retryingStream(
                attempts = attempts,
                jitter = { 0 },
                delayMs = { delays += it },
                attempt = { scripted.next() },
            ).toList()
        }
        return Triple(events, scripted.calls, delays)
    }

    @Test
    fun `retries a transient failure that arrived before any output`() {
        val (events, calls, delays) = runRetry(
            listOf(
                listOf(StreamEvent.Failure("HTTP 503: upstream busy")),
                listOf(StreamEvent.TextDelta("hi"), StreamEvent.Completed("stop")),
            ),
        )
        assertEquals(listOf(StreamEvent.TextDelta("hi"), StreamEvent.Completed("stop")), events)
        assertEquals(2, calls)
        assertEquals(listOf(TransientInitialBackoffMs), delays)
    }

    @Test
    fun `stops retrying once output has been streamed`() {
        // 已经流出 token：重发会造成重复输出 / 重复计费，宁可把失败报给用户。
        val (events, calls, delays) = runRetry(
            listOf(listOf(StreamEvent.TextDelta("half"), StreamEvent.Failure("HTTP 503: upstream busy"))),
        )
        assertEquals(listOf(StreamEvent.TextDelta("half"), StreamEvent.Failure("HTTP 503: upstream busy")), events)
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `gives up after the attempt cap`() {
        val (events, calls, delays) = runRetry(listOf(listOf(StreamEvent.Failure("HTTP 500: boom"))))
        assertEquals(listOf(StreamEvent.Failure("HTTP 500: boom")), events)
        assertEquals(TransientMaxAttempts, calls)
        // 首次 + 2 次重试 = 两次退避（1s → 2s）。
        assertEquals(listOf(TransientInitialBackoffMs, 2 * TransientInitialBackoffMs), delays)
    }

    @Test
    fun `does not retry a non transient http error`() {
        val (events, calls, _) = runRetry(listOf(listOf(StreamEvent.Failure("HTTP 401: invalid api key"))))
        assertEquals(listOf(StreamEvent.Failure("HTTP 401: invalid api key")), events)
        assertEquals(1, calls)
    }

    @Test
    fun `rate limit honours retry-after over the default backoff`() {
        val (_, calls, delays) = runRetry(
            listOf(
                listOf(StreamEvent.Failure("HTTP 429: slow down", retryAfterMs = 5_000)),
                listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed("stop")),
            ),
        )
        assertEquals(2, calls)
        assertEquals(listOf(5_000L), delays)
    }

    @Test
    fun `quota exhaustion is not retried even when it arrives as 429`() {
        val (events, calls, _) = runRetry(listOf(listOf(StreamEvent.Failure("HTTP 429: insufficient_quota"))))
        assertEquals(listOf(StreamEvent.Failure("HTTP 429: insufficient_quota")), events)
        assertEquals(1, calls)
    }

    @Test
    fun `a stalled upstream is never retried`() {
        // 看门狗已等满 120 秒，且无法区分「链路卡住」与「模型长时间思考」：重试只是再等一轮。
        val (_, calls, delays) = runRetry(listOf(listOf(StreamEvent.Failure(stalledUpstreamMessage(), retryable = false))))
        assertEquals(1, calls)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `unmarked failures fall back to the text heuristic`() {
        // 传输层没标注时：空响应与流内过载错误可重试，无法归类的一律不重试。
        assertTrue(StreamEvent.Failure(emptyStopMessage()).isTransient())
        assertTrue(StreamEvent.Failure(EMPTY_BODY_MESSAGE).isTransient())
        assertTrue(StreamEvent.Failure("Overloaded").isTransient())
        assertFalse(StreamEvent.Failure("connection reset by peer").isTransient())
        assertFalse(StreamEvent.Failure("No transport registered for 'x'").isTransient())
    }

    @Test
    fun `an explicit mark wins over the text heuristic`() {
        assertFalse(StreamEvent.Failure("HTTP 503: x", retryable = false).isTransient())
        assertTrue(StreamEvent.Failure("HTTP 400: x", retryable = true).isTransient())
    }

    @Test
    fun `backoff grows exponentially and respects the caps`() {
        assertEquals(1_000L, backoffMs(0, null))
        assertEquals(2_000L, backoffMs(1, null))
        assertEquals(TransientMaxBackoffMs, backoffMs(9, null))
        assertEquals(5_000L, backoffMs(0, 5_000))
        assertEquals(TransientMaxRetryAfterMs, backoffMs(0, 60_000))
        assertEquals(2_000L, backoffMs(1, 1_000))
    }

    @Test
    fun `retry-after only accepts the seconds form`() {
        assertEquals(3_000L, parseRetryAfterMs("3"))
        assertEquals(null, parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(null, parseRetryAfterMs("0"))
        assertEquals(null, parseRetryAfterMs(null))
    }

    @Test
    fun `usage and completion events alone do not block a retry`() {
        // 只有「被用户看到的内容」才算产出；用量 / 完成事件不算，而且失败尝试的
        // 用量事件必须被丢弃，不能上报。
        val (events, calls, _) = runRetry(
            listOf(
                listOf(StreamEvent.UsageEvent(Usage(input = 1)), StreamEvent.Failure("HTTP 500: x")),
                listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed("stop"), StreamEvent.UsageEvent(Usage(input = 2))),
            ),
        )
        assertEquals(2, calls)
        assertEquals(
            listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed("stop"), StreamEvent.UsageEvent(Usage(input = 2))),
            events,
        )
    }
}
