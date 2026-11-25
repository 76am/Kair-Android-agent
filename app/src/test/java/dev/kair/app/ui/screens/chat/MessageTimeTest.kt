package dev.kair.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

/** 构造「上海时区下」的某个时刻，便于把边界写清楚。 */
private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
    Calendar.getInstance(shanghai).apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

class MessageTimeTest {

    @Test
    fun `same day shows only the time`() {
        val now = at(2024, 3, 5, 18, 0)
        assertEquals("15:30", formatWhoTime(at(2024, 3, 5, 15, 30, 8), now, shanghai))
    }

    @Test
    fun `either end of the same day stays time-only`() {
        val now = at(2024, 3, 5, 23, 59, 59)
        assertEquals("00:01", formatWhoTime(at(2024, 3, 5, 0, 1, 9), now, shanghai))
        assertEquals("23:58", formatWhoTime(at(2024, 3, 5, 23, 58, 0), now, shanghai))
    }

    @Test
    fun `crossing a day boundary adds month and day`() {
        val now = at(2024, 3, 5, 0, 1)
        assertEquals("3月4日 23:59", formatWhoTime(at(2024, 3, 4, 23, 59, 59), now, shanghai))
    }

    @Test
    fun `earlier the same year adds month and day but not the year`() {
        val now = at(2024, 12, 31, 23, 0)
        assertEquals("1月1日 08:05", formatWhoTime(at(2024, 1, 1, 8, 5, 0), now, shanghai))
    }

    @Test
    fun `a different year adds the year`() {
        val now = at(2025, 1, 1, 0, 0)
        assertEquals("2024年12月31日 23:59", formatWhoTime(at(2024, 12, 31, 23, 59, 59), now, shanghai))
    }

    @Test
    fun `today is judged by the day boundary of the given zone`() {
        val sent = at(2024, 3, 4, 23, 30, 0) // 上海 3/4 23:30 = UTC 3/4 15:30
        val now = at(2024, 3, 5, 10, 0) // 上海 3/5 10:00 = UTC 3/5 02:00
        assertEquals("3月4日 23:30", formatWhoTime(sent, now, shanghai))
        assertEquals("3月4日 15:30", formatWhoTime(sent, now, TimeZone.getTimeZone("UTC")))
    }

    /**
     * U98 的口径：秒不再上屏。这条用例钉的是「不是漏了补零，是刻意没写秒」——
     * 有人日后为了「更精确」把 `HH:mm:ss` 加回去，深色转录的标签行会再次被撑满。
     */
    @Test
    fun `seconds never reach the who line`() {
        val now = at(2024, 3, 5, 18, 0)
        listOf(
            formatWhoTime(at(2024, 3, 5, 15, 30, 8), now, shanghai),
            formatWhoTime(at(2024, 3, 4, 15, 30, 8), now, shanghai),
            formatWhoTime(at(2023, 3, 4, 15, 30, 8), now, shanghai),
        ).forEach { assertTrue("必须止于 `HH:mm`，实际：$it", Regex("""\d{2}:\d{2}$""").containsMatchIn(it)) }
    }
}
