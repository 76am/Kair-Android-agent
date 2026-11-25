package dev.kair.app.ui.components.markdown

/**
 * 行内解析：把一段文字切成带标志位的小片段。
 *
 * 支持 `**粗**`、`*斜*` / `_斜_`、`~~删~~`、`` `码` ``、`[文](链接)` 与反斜杠转义；
 * 允许叠加（如 `**粗 `码`**` → 一个 bold+code 片段）。认不出的标记原样留在正文里。
 */
internal fun parseInline(source: String): List<MdSpan> {
    if (source.isEmpty()) return emptyList()
    val spans = mutableListOf<MdSpan>()
    parseInlineInto(source, MdSpan(text = ""), spans)
    return spans.filter { it.text.isNotEmpty() }
}

private fun parseInlineInto(source: String, base: MdSpan, out: MutableList<MdSpan>) {
    val plain = StringBuilder()
    fun flush() = flushPlain(plain, base, out)

    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' && i + 1 < source.length -> {
                plain.append(source[i + 1])
                i += 2
            }
            c == '`' -> {
                val end = source.indexOf('`', i + 1)
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    out += base.copy(text = source.substring(i + 1, end).trim(), code = true)
                    i = end + 1
                }
            }
            source.startsWith("***", i) -> {
                val end = source.indexOf("***", i + 3)
                if (end < 0) {
                    plain.append("***")
                    i += 3
                } else {
                    flush()
                    parseInlineInto(source.substring(i + 3, end), base.copy(bold = true, italic = true), out)
                    i = end + 3
                }
            }
            source.startsWith("**", i) -> {
                val end = source.indexOf("**", i + 2)
                if (end < 0) {
                    plain.append("**")
                    i += 2
                } else {
                    flush()
                    parseInlineInto(source.substring(i + 2, end), base.copy(bold = true), out)
                    i = end + 2
                }
            }
            source.startsWith("~~", i) -> {
                val end = source.indexOf("~~", i + 2)
                if (end < 0) {
                    plain.append("~~")
                    i += 2
                } else {
                    flush()
                    parseInlineInto(source.substring(i + 2, end), base.copy(strike = true), out)
                    i = end + 2
                }
            }
            c == '*' -> {
                val end = italicEnd(source, i, '*')
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    parseInlineInto(source.substring(i + 1, end), base.copy(italic = true), out)
                    i = end + 1
                }
            }
            c == '_' && italicOpening(source, i) -> {
                val end = italicEnd(source, i, '_')
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    parseInlineInto(source.substring(i + 1, end), base.copy(italic = true), out)
                    i = end + 1
                }
            }
            c == '[' -> {
                val end = inlineLink(source, i, base, out, plain)
                if (end < 0) {
                    plain.append(c)
                    i++
                } else {
                    i = end
                }
            }
            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flush()
}

private data class LinkMatch(
    val label: String,
    val href: String,
    /** 右括号之后的下标。 */
    val end: Int,
)

/**
 * 链接的收口：把 [plain] 先落成一个普通片段，再把链接文字按新样式递归解析。
 * 返回右括号之后的下标；不是链接时返回 -1（调用方把 `[` 当普通字符）。
 */
private fun inlineLink(
    source: String,
    start: Int,
    base: MdSpan,
    out: MutableList<MdSpan>,
    plain: StringBuilder,
): Int {
    val link = linkAt(source, start) ?: return -1
    flushPlain(plain, base, out)
    parseInlineInto(link.label, base.copy(href = link.href), out)
    return link.end
}

private fun flushPlain(plain: StringBuilder, base: MdSpan, out: MutableList<MdSpan>) {
    if (plain.isEmpty()) return
    out += base.copy(text = plain.toString())
    plain.clear()
}

private fun linkAt(source: String, start: Int): LinkMatch? {
    val close = source.indexOf(']', start + 1)
    if (close <= start + 1 || close + 1 >= source.length || source[close + 1] != '(') return null
    val paren = source.indexOf(')', close + 2)
    if (paren <= close + 2) return null
    return LinkMatch(source.substring(start + 1, close), source.substring(close + 2, paren), paren + 1)
}

/**
 * 找斜体收尾。`_` 只在 ASCII 词字符旁边算「词内」：`foo_bar_baz` 里的下划线不是斜体，
 * 但中文没有空格，`_斜体_后续` 必须能收尾（2026-11-21 立）。
 * 开头与收尾两侧都不许贴空白（`2 * 3` 不该吃掉乘号）。
 */
private fun italicEnd(source: String, start: Int, marker: Char): Int {
    if (start + 1 >= source.length || source[start + 1].isWhitespace()) return -1
    var i = start + 1
    while (i < source.length) {
        if (source[i] == marker && !source[i - 1].isWhitespace()) {
            if (marker != '_' || i + 1 >= source.length || !isAsciiWord(source[i + 1])) return i
        }
        i++
    }
    return -1
}

private fun italicOpening(source: String, start: Int): Boolean {
    if (start + 1 >= source.length || source[start + 1].isWhitespace()) return false
    if (start == 0) return true
    return !isAsciiWord(source[start - 1])
}

private fun isAsciiWord(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
