package dev.kair.app.ui.screens.chat

import dev.kair.app.core.agent.Compaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 工具参数 / 思考文本的**纯文本**推导（U107 从 `ToolCallViews.kt` 拆出，压回 500 行软上限）。
 *
 * 2026-11-19 简化：**不按键名挑值、不逐键铺开、不重排结构** —— 参数保持模型下发的形状，
 * 只做一件事：参数收齐后按 JSON 语义还原 `\uXXXX` 转义（见 [toolArgumentText]）。
 * 参数每帧都在变、每帧都会调到这里，所以任何一步都不许抛异常，也不该做重活
 * （见 [MaxSummaryChars] / [TextTailWindow] 的预算说明）。
 */

/**
 * 折叠态那一行的字符预算（U105）。
 *
 * 这一行只有几十字符宽，超出部分本来就看不见；**上限不是为了好看，是为了不卡死**：
 * 写文件时参数是半截 JSON（`{"path":…,"content":"…`），在闭合前会一路长到几十万字符。
 * 把那整段交给 `maxLines = 1` 的 Text，每帧都要重新排版一次，主线程被压住，界面
 * 会一直停到参数吐完才恢复 —— 用户看到的就是「工具卡很久不渲染，写完才出来」。
 */
private const val MaxSummaryChars = 160

/**
 * 展开区 / 思考区的显示窗口（U108）：只保留**最近**这么多字符，从头部截断。
 *
 * 流式内容是「从下往上长」的，用户盯的是最新几行；保留开头等于把正在写的内容挡在
 * 视口之外。截断留在头部并补省略号，尾部永远完整 —— 与折叠行取尾部是同一个方向。
 */
internal const val TextTailWindow = 5_000

/**
 * 用量文案（U100）：`847 tok` / `2.1k tok` / `12k tok`。
 *
 * 为什么不是行数：12 行可能是 12 个数字，也可能是 12 行 JSON —— 它答不了「这次调用多贵」。
 * 三位以上按千进位，一位小数，整数时省掉 `.0`（`2k` 比 `2.0k` 少一个字符，在这个宽度上算数）。
 */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 100_000 -> {
        val tenths = Math.round(tokens / 100.0).toInt()
        val whole = tenths / 10
        val frac = tenths % 10
        if (frac == 0) "${whole}k" else "$whole.${frac}k"
    }
    else -> "${tokens / 1_000}k"
}

/**
 * 工具卡头行右侧的用量（U105 第四轮）：**运行中也报，而且只增不减**。
 * 口径 = 参数（模型正在写的 JSON）+ 输出（工具回的结果）：出结果前只算参数，写文件时数字
 * 跟着参数涨；出结果后把输出加上去，不会从 `2.6k tok` 掉回 `1 tok`（那样像出错）。
 * 执行阶段（`bash`）数字会停住：`CommandRunner.run` 只在结束时回整段输出，没有可计量的增量。
 */
internal fun toolUsageText(arguments: String, body: String): String? {
    val args = if (arguments.isNotBlank()) Compaction.estimateTextTokens(arguments) else 0
    val out = if (body.isNotBlank()) Compaction.estimateTextTokens(body) else 0
    val total = args + out
    return if (total == 0) null else "${formatTokenCount(total)} tok"
}

/**
 * 折叠态的一行摘要（U98 / U108）：取**最新**的 [MaxSummaryChars] 个字符，压平换行。
 *
 * 流式时这一行就是 ticker：新字符从右进、旧字符向左滑出（见 `TraceBlock` 的 detail）。
 * 所以取尾部而不是头部 —— 头部在一回合里永远不变，看不出「它还在写」。
 * 也用于思考块：折叠态给半句思路，比什么都不给更能判断要不要点开。
 *
 * 任何输入都不抛异常（每帧都可能被调），也不解析 JSON。
 */
internal fun recentTailSummary(raw: String): String {
    if (raw.isBlank()) return ""
    // 先取尾部再压平：半截 JSON 可能几十万字符，正则和 Text 排版都只该看到尾端一小段。
    val flat = raw.takeLast(MaxSummaryChars * 2)
        .replace('\n', ' ')
        .replace(WhitespaceRun, " ")
        .trim()
    if (flat.isEmpty() || flat == "{}" || flat == "null") return ""
    return if (flat.length <= MaxSummaryChars) flat else "…" + flat.takeLast(MaxSummaryChars - 1)
}

/**
 * 展开态 IN 区可显示的**参数**（U100 / U108，2026-11 补转义还原）。
 *
 * 模型下发的 `arguments` 里中文可能被写成 JSON 的 `\uXXXX` 转义序列（LongCat 等端点如此），
 * 原文直出就是 `\u4e0a\u62a4\u6728` 这样一串码，用户读不了。这不是乱码、也不是编码没解：
 * SSE 外层解析已经剥了一层，剩下的转义属于**参数文本自身**，得按 JSON 语义还原。
 *
 * 两种情况分开处理：
 * - **已闭合**且能解析成 JSON 对象：解析后重新编码，字符串值里的转义就此还原；结构、键序
 *   不变，只把 `\uXXXX` 变成字符。解析失败（模型吐了坏 JSON）仍原文直出，不让显示层报错。
 * - **还在写**（未闭合）：绝不做 JSON 解析 —— 本函数每帧都会被调，几十万字符的解析会把
 *   主线程压住（见 [MaxSummaryChars] 的教训）。只还原尾部窗口内的 `\uXXXX`；头部反正
 *   `TailText` 不显示，保持原样。
 *
 * 空串 / `{}` / `null` 视为「没有可展开的」。
 */
internal fun toolArgumentText(raw: String): String {
    if (raw.isBlank()) return ""
    if (raw.length <= 8) {
        val bare = raw.trim()
        if (bare == "{}" || bare == "null") return ""
    }
    val trimmed = raw.trim()
    // 形状判断 O(1)：未闭合直接跳过解析，这是流式期间每帧都要走的快路径。
    if (trimmed.length > 8 && trimmed.first() == '{' && trimmed.last() == '}') {
        val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        if (parsed is JsonObject) return parsed.toString()
    }
    if (raw.length > TextTailWindow) {
        return raw.substring(0, raw.length - TextTailWindow) +
            unescapeJsonUnicode(raw.takeLast(TextTailWindow))
    }
    return unescapeJsonUnicode(raw)
}

/**
 * 只还原 `\uXXXX` 这一种转义；`\\uXXXX`（反斜杠自身被转义、属于字面量）保持不动。
 *
 * 流式期间唯一能安全做的轻量还原：不做 JSON 解析，代价与文本长度线性。不含 `\u` 时
 * 直接返回原串，连扫描都省掉。
 */
private fun unescapeJsonUnicode(text: String): String {
    if (!text.contains("\\u")) return text
    return UnicodeEscape.replace(text) { m -> m.groupValues[1].toInt(16).toChar().toString() }
}

private val UnicodeEscape = Regex("""(?<!\\)\\u([0-9a-fA-F]{4})""")

/**
 * 只保留最近 [TextTailWindow] 个字符；被截断时在头部补省略号。
 *
 * 为什么要避开低代理位：按 UTF-16 下标硬切会把一个 emoji 劈成半个字符，排版时
 * 变成替换符 —— 退一格并不改变「看最新内容」的语义。
 */
internal fun tailText(raw: String): String {
    if (raw.length <= TextTailWindow) return raw
    var start = raw.length - TextTailWindow
    if (Character.isLowSurrogate(raw[start])) start++
    return "…" + raw.substring(start)
}

private val WhitespaceRun = Regex("\\s+")
