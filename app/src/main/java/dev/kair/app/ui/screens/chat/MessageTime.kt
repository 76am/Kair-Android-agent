package dev.kair.app.ui.screens.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 转录里「谁 · 时间」那行的时间（设计稿 `.who`：`你 · 01:11`）。
 *
 * 按「离现在多远」递进，只补到能消除歧义为止：
 * - **今天**：`01:11` —— 同一天不必重复日期。
 * - **今年内**：`3月5日 01:11` —— 跨了天就必须给「日」，而「日」脱离「月」没有意义，
 *   所以月日成对出现（不是「先补日、再补月」的两级）。
 * - **跨年**：`2024年3月5日 01:11` —— 年份不同，补上年。
 *
 * 刻意不做「昨天 / 前天 / 星期X」：多一层分支就多一处出错面，`M月d日` 已无歧义。
 *
 * **为什么到分钟为止（U98 改）**：这行是等宽小字，右端还挂着 `⋯` 入口，一行就那么大。
 * 早先到秒（`2025年11月18日 01:20:00`）在深色转录里直接撑满整行、把 `⋯` 挤到边缘，
 * 与设计稿的 `你 · 01:11` 差了一大截 —— 而秒对「这是什么时候说的」没有增量信息，
 * 先后顺序由消息在列表里的位置本身就说明了。完整到秒的时间仍可从「选取文字」拿到。
 *
 * @param now 参照时间（便于单测注入）；[zone] 决定「今天 / 今年」的边界。
 */
fun formatWhoTime(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val sent = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
    val today = Calendar.getInstance(zone).apply { timeInMillis = now }
    val pattern = when {
        sameDay(sent, today) -> "HH:mm"
        sent.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "M月d日 HH:mm"
        else -> "yyyy年M月d日 HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault())
        .apply { timeZone = zone }
        .format(Date(timestamp))
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
