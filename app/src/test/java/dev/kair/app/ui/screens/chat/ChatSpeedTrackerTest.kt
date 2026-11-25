package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.ui.components.formatSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码速度口径（U111）：从消息快照还原实时值与单发 stats。
 *
 * 纯 Kotlin，时钟由用例注入；覆盖等待首字 / 实时窗口 / 静默清零 / usage 校准 /
 * 错误与中止不记 stats / 下一发重置 / 回合聚合。
 */
class ChatSpeedTrackerTest {

    private var now = 0L
    private val tracker = ChatSpeedTracker { now }

    private fun assistant(
        id: String,
        text: String,
        thinking: String = "",
        usage: Usage? = null,
        stop: String? = null,
    ) = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        parts = buildList {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking))
            add(Part.Text(text))
        },
        usage = usage,
        stopReason = stop,
    )

    @Test
    fun waiting_then_streaming_then_usage_calibrated_stats() {
        now = 0
        tracker.onMessages(listOf(assistant("a1", "")), now)
        assertEquals(SpeedPhase.WAITING, tracker.phase)
        assertNull(tracker.liveTps)

        now = 100
        tracker.onMessages(listOf(assistant("a1", "hello")), now)
        assertEquals(SpeedPhase.STREAMING, tracker.phase)
        assertNull("单个样本没有窗口", tracker.liveTps)

        now = 800
        tracker.onMessages(listOf(assistant("a1", "hello world!!!")), now)
        // 14 个 ASCII ≈ ceil(14/4)=4 token；窗口 700ms 内从 2 → 4。
        assertEquals(2.857, tracker.liveTps ?: 0.0, 0.01)

        now = 1_000
        tracker.onMessages(
            listOf(assistant("a1", "hello world!!!", usage = Usage(output = 10), stop = StopReason.STOP)),
            now,
        )
        val stats = tracker.stats.getValue("a1")
        assertEquals(10, stats.outputTokens)
        assertEquals(900L, stats.decodeMs)
        assertEquals(100L, stats.firstTokenMs)
        assertFalse(stats.estimated)
        assertEquals(SpeedPhase.IDLE, tracker.phase)
        assertNull(tracker.liveTps)
    }

    @Test
    fun silence_clears_live_value_but_keeps_stats_until_finish() {
        now = 0
        tracker.onMessages(listOf(assistant("a1", "")), now)
        now = 100
        tracker.onMessages(listOf(assistant("a1", "hello")), now)
        now = 600
        tracker.onMessages(listOf(assistant("a1", "hello world!!!")), now)
        assertTrue((tracker.liveTps ?: 0.0) > 0.0)

        now = 2_500
        tracker.onTick(now)
        assertNull("静默超过 1.5s 不再显示陈旧读数", tracker.liveTps)
        assertEquals(SpeedPhase.STREAMING, tracker.phase)

        now = 2_600
        tracker.onMessages(
            listOf(assistant("a1", "hello world!!!", usage = Usage(output = 4), stop = StopReason.STOP)),
            now,
        )
        assertEquals(SpeedPhase.IDLE, tracker.phase)
    }

    @Test
    fun error_and_aborted_responses_do_not_record_stats() {
        now = 0
        tracker.onMessages(listOf(assistant("a1", "")), now)
        now = 100
        tracker.onMessages(listOf(assistant("a1", "partial")), now)
        now = 900
        tracker.onMessages(listOf(assistant("a1", "partial", stop = StopReason.ABORTED)), now)
        assertTrue(tracker.stats.isEmpty())

        now = 1_000
        tracker.onMessages(listOf(assistant("a2", "")), now)
        now = 1_100
        tracker.onMessages(listOf(assistant("a2", "boom")), now)
        now = 1_900
        tracker.onMessages(listOf(assistant("a2", "boom", stop = StopReason.ERROR)), now)
        assertTrue(tracker.stats.isEmpty())
    }

    @Test
    fun next_response_resets_live_and_accumulates_stats() {
        now = 0
        tracker.onMessages(listOf(assistant("a1", "")), now)
        now = 100
        tracker.onMessages(listOf(assistant("a1", "hello")), now)
        now = 900
        tracker.onMessages(
            listOf(assistant("a1", "hello", usage = Usage(output = 4), stop = StopReason.TOOL_USE)),
            now,
        )

        now = 1_000
        tracker.onMessages(
            listOf(
                assistant("a1", "hello", usage = Usage(output = 4), stop = StopReason.TOOL_USE),
                assistant("a2", ""),
            ),
            now,
        )
        assertEquals(SpeedPhase.WAITING, tracker.phase)
        now = 1_200
        tracker.onMessages(
            listOf(
                assistant("a1", "hello", usage = Usage(output = 4), stop = StopReason.TOOL_USE),
                assistant("a2", "world"),
            ),
            now,
        )
        assertEquals(SpeedPhase.STREAMING, tracker.phase)
        assertEquals(1, tracker.stats.size)

        now = 2_000
        tracker.onMessages(
            listOf(
                assistant("a1", "hello", usage = Usage(output = 4), stop = StopReason.TOOL_USE),
                assistant("a2", "world", usage = Usage(output = 6), stop = StopReason.STOP),
            ),
            now,
        )
        assertEquals(2, tracker.stats.size)
        assertEquals(4 + 6, tracker.stats.values.sumOf { it.outputTokens })
    }

    @Test
    fun estimated_stats_are_marked_when_usage_is_missing() {
        now = 0
        tracker.onMessages(listOf(assistant("a1", "")), now)
        now = 100
        tracker.onMessages(listOf(assistant("a1", "hello")), now)
        now = 700
        tracker.onMessages(listOf(assistant("a1", "hello world!!!")), now)
        now = 800
        tracker.onMessages(listOf(assistant("a1", "hello world!!!", stop = StopReason.STOP)), now)
        val stats = tracker.stats.getValue("a1")
        assertTrue(stats.estimated)
        assertTrue(stats.outputTokens > 0)
    }

    @Test
    fun speed_format_keeps_one_decimal_only_below_ten() {
        assertEquals("28", formatSpeed(28.4))
        assertEquals("9.6", formatSpeed(9.6))
        assertEquals("<1", formatSpeed(0.4))
        assertEquals("0", formatSpeed(0.0))
    }
}
