package dev.kair.app.core.agent

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 「今天几号」system note 的 wire 文案：`2026-10-29 (Thu)`。
 *
 * 面向模型的字符串用英文（`AGENTS.md` §4）；星期取 CLDR 的英文短名。
 * 界面不直接显示这行 —— `SystemNoteCard` 用 [dateNoteDisplayText] 换回中文星期。
 *
 * 日期部分用 [LocalDate] 的 ISO 形式；时区由注入的时钟决定（见 [AgentSession]，
 * 默认取设备本地时区，不用 UTC —— 否则中国用户早 8 点前会看到昨天）。
 */
internal fun dateNoteText(date: LocalDate): String =
    "$date (${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)})"

/**
 * wire 英文化之前的日期 note 文案，仅用于判重与 UI 展示：`2026-10-29（周四）`。
 *
 * 旧会话里的这条不迁移、不改写；新写入一律用 [dateNoteText]。
 */
internal fun legacyDateNoteText(date: LocalDate): String =
    "$date（${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)}）"

private val DateNoteWirePattern = Regex("""^(\d{4}-\d{2}-\d{2}) \([A-Za-z]{3,4}\)$""")

/**
 * 界面上把英文 wire 文案本地化成中文星期；非日期 note 原样返回。
 *
 * 旧会话里已有中文格式的日期 note，不匹配这里，原样显示（不做迁移改写）。
 */
internal fun dateNoteDisplayText(text: String): String {
    val iso = DateNoteWirePattern.find(text)?.groupValues?.get(1) ?: return text
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return text
    return legacyDateNoteText(date)
}
