package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the context-overflow detection ported from pi-ai's overflow.ts. */
class OverflowTest {

    private fun error(message: String) = ChatMessage(
        role = Role.ASSISTANT,
        parts = listOf(Part.Text(message)),
        stopReason = StopReason.ERROR,
        errorMessage = message,
        isError = true,
    )

    private fun complete(stopReason: String, usage: Usage) = ChatMessage(
        role = Role.ASSISTANT,
        parts = listOf(Part.Text("ok")),
        stopReason = stopReason,
        usage = usage,
    )

    @Test
    fun matchesExplicitOverflowMessage() {
        assertTrue(
            OverflowDetector.isContextOverflow(
                error("prompt is too long: 267000 tokens > 200000 maximum"),
            ),
        )
    }

    @Test
    fun rateLimitAndThrottlingAreNotOverflow() {
        assertFalse(OverflowDetector.isContextOverflow(error("Rate limit exceeded, retry later")))
        assertFalse(OverflowDetector.isContextOverflow(error("Throttling error: too many requests")))
    }

    @Test
    fun silentOverflowFromUsageOverWindow() {
        val msg = complete(StopReason.STOP, Usage(input = 200_001, cacheRead = 0))
        assertTrue(OverflowDetector.isContextOverflow(msg, contextWindow = 200_000))
        assertFalse(OverflowDetector.isContextOverflow(msg))
    }

    @Test
    fun lengthStopWithNoOutputIsOverflow() {
        val msg = complete(StopReason.LENGTH, Usage(input = 199_000, output = 0))
        assertTrue(OverflowDetector.isContextOverflow(msg, contextWindow = 200_000))
    }

    @Test
    fun lengthStopWithOutputIsNotOverflow() {
        val msg = complete(StopReason.LENGTH, Usage(input = 199_000, output = 512))
        assertFalse(OverflowDetector.isContextOverflow(msg, contextWindow = 200_000))
    }

    @Test
    fun recoverableLengthBelowDesiredOutput() {
        val msg = complete(StopReason.LENGTH, Usage(input = 1000, output = 512))
        assertTrue(OverflowDetector.isRecoverableLength(msg, desiredMaxOutput = 4096))
        assertFalse(OverflowDetector.isRecoverableLength(msg, desiredMaxOutput = 256))
    }

    @Test
    fun emptyResponseIsNotOverflow() {
        // 中转站吞错后直接关流：客户端只看到「空响应」，文本里没有任何 overflow 关键字。
        // 这类失败是瞬时故障，由传输层的自动重发处理，**不应**触发压缩（压缩改的是历史，
        // 既掩盖真因，又白白牺牲缓存前缀）。
        val msg = error(emptyStopMessage())
        assertFalse(OverflowDetector.isContextOverflow(msg))
        assertFalse(OverflowDetector.isContextOverflow(msg, contextWindow = 200_000))
    }

    @Test
    fun ordinaryErrorIsNotOverflow() {
        assertFalse(OverflowDetector.isContextOverflow(error("connection reset by peer")))
    }
}
