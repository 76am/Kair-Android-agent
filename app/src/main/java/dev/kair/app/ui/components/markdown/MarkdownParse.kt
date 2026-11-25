package dev.kair.app.ui.components.markdown

/**
 * 块级解析入口。流式期间每帧都会对当前段重跑一次：纯扫描、不建正则树，
 * 已闭合块的对象保持不变，Compose 侧按内容跳过重组与行内解析。
 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    if (source.isBlank()) return emptyList()
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    return MdParser(normalized.split('\n')).parse()
}

private class MdParser(private val lines: List<String>) {
    private var index = 0

    fun parse(): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        while (index < lines.size) {
            val line = lines[index]
            val fence = fenceAt(line)
            val heading = headingAt(line)
            val marker = listMarker(line)
            when {
                line.isBlank() -> index++
                fence != null -> blocks += fencedCode(fence)
                heading != null -> blocks += heading(heading)
                isRule(line) -> {
                    blocks += MdBlock.Rule
                    index++
                }
                isTableStart(index) -> blocks += table()
                marker != null -> blocks += list(marker)
                line.trimStart().startsWith(">") -> blocks += quote()
                else -> blocks += paragraph()
            }
        }
        return blocks
    }

    private fun fencedCode(fence: Fence): MdBlock.Code {
        val language = fence.info.substringBefore(' ').substringBefore('\t').trim()
        index++
        val body = mutableListOf<String>()
        var closed = false
        while (index < lines.size) {
            if (isFenceEnd(lines[index], fence)) {
                closed = true
                index++
                break
            }
            body += lines[index]
            index++
        }
        return MdBlock.Code(language, body.joinToString("\n"), closed)
    }

    private fun heading(heading: Pair<Int, String>): MdBlock.Heading {
        index++
        return MdBlock.Heading(heading.first, heading.second)
    }

    private fun table(): MdBlock.Table {
        val start = index
        val header = cells(lines[index])
        val aligns = cells(lines[index + 1]).map(::alignmentOf)
        index += 2
        val rows = mutableListOf<List<String>>()
        while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|')) {
            val row = cells(lines[index]).toMutableList()
            while (row.size < header.size) row += ""
            rows += row.take(header.size)
            index++
        }
        val source = lines.subList(start, index).joinToString("\n").trimEnd()
        return MdBlock.Table(header, aligns, rows, source, open = index >= lines.size)
    }

    private fun list(first: ListMarker): MdBlock {
        val items = mutableListOf<String>()
        while (index < lines.size) {
            val marker = listMarker(lines[index]) ?: break
            // 有序 / 无序换了就收口：同一条正文里两段列表各自渲染，比混在一个块里清楚。
            if (marker.ordered != first.ordered) break
            val item = StringBuilder(marker.text)
            index++
            // 续行：缩进比条目更深、又不是新条目 —— 并进当前条目（LLM 常把长句折行）。
            while (index < lines.size && isContinuation(index, first.indent)) {
                item.append('\n').append(lines[index].trim())
                index++
            }
            items += item.toString()
        }
        return if (first.ordered) {
            MdBlock.OrderedList(first.number, items)
        } else {
            MdBlock.BulletList(items)
        }
    }

    private fun quote(): MdBlock.Quote {
        val body = mutableListOf<String>()
        while (index < lines.size && lines[index].trimStart().startsWith(">")) {
            body += lines[index].trimStart().removePrefix(">").removePrefix(" ")
            index++
        }
        return MdBlock.Quote(body.joinToString("\n").trim())
    }

    private fun paragraph(): MdBlock.Paragraph {
        val body = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank() && !startsBlock(index)) {
            body += lines[index].trimEnd()
            index++
        }
        // 防御：首行就被判成「新块」时也要吃掉一行，避免空段落让外层原地打转。
        if (body.isEmpty()) {
            body += lines[index]
            index++
        }
        return MdBlock.Paragraph(body.joinToString("\n").trim())
    }

    private fun isContinuation(at: Int, indent: Int): Boolean {
        val line = lines[at]
        return line.isNotBlank() &&
            listMarker(line) == null &&
            line.takeWhile { it == ' ' }.length > indent
    }

    private fun startsBlock(at: Int): Boolean {
        val line = lines[at]
        return fenceAt(line) != null ||
            headingAt(line) != null ||
            isRule(line) ||
            isTableStart(at) ||
            listMarker(line) != null ||
            line.trimStart().startsWith(">")
    }

    private fun isTableStart(at: Int): Boolean {
        if (at + 1 >= lines.size) return false
        val head = lines[at]
        val delimiter = lines[at + 1]
        if (!head.contains('|') || !delimiter.contains('-')) return false
        if (delimiter.any { it !in "|-: \t" }) return false
        val headCells = cells(head)
        val delimiterCells = cells(delimiter)
        return headCells.isNotEmpty() &&
            delimiterCells.size == headCells.size &&
            delimiterCells.all { it.matches(DelimiterCell) }
    }
}

private val DelimiterCell = Regex(":?-+:?")

private val ClosingHashes = Regex("\\s+#+$")

private data class Fence(
    val char: Char,
    val count: Int,
    val info: String,
)

private data class ListMarker(
    val indent: Int,
    val ordered: Boolean,
    val number: Int,
    val text: String,
)

private fun fenceAt(line: String): Fence? {
    val trimmed = line.trimStart()
    val char = when {
        trimmed.startsWith("```") -> '`'
        trimmed.startsWith("~~~") -> '~'
        else -> return null
    }
    var count = 0
    while (count < trimmed.length && trimmed[count] == char) count++
    return Fence(char, count, trimmed.substring(count).trim())
}

private fun isFenceEnd(line: String, fence: Fence): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= fence.count && trimmed.all { it == fence.char }
}

private fun headingAt(line: String): Pair<Int, String>? {
    val trimmed = line.trimStart()
    var level = 0
    while (level < trimmed.length && trimmed[level] == '#') level++
    if (level !in 1..6 || level >= trimmed.length || trimmed[level] != ' ') return null
    // 只去掉「空格 + 收尾 #」的关闭式写法；`## C#` 里的 # 是正文，不能剥。
    val text = trimmed.substring(level + 1).trim().replace(ClosingHashes, "")
    return level to text
}

private fun isRule(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 3) return false
    val char = trimmed.first()
    if (char != '-' && char != '*' && char != '_') return false
    return trimmed.all { it == char || it == ' ' } && trimmed.count { it == char } >= 3
}

private fun listMarker(line: String): ListMarker? {
    val indent = line.takeWhile { it == ' ' }.length
    val rest = line.substring(indent)
    if (rest.length < 2) return null
    val marker = rest.first()
    if (marker.isDigit()) {
        val dot = rest.indexOfFirst { it == '.' || it == ')' }
        if (dot <= 0 || dot + 1 >= rest.length || rest[dot + 1] != ' ') return null
        val number = rest.substring(0, dot).toIntOrNull() ?: return null
        return ListMarker(indent, ordered = true, number = number, text = rest.substring(dot + 2).trimEnd())
    }
    if (marker != '-' && marker != '*' && marker != '+') return null
    if (rest[1] != ' ') return null
    return ListMarker(indent, ordered = false, number = 1, text = rest.substring(2).trimEnd())
}

private fun cells(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|")) text = text.dropLast(1)
    if (text.isBlank()) return emptyList()
    return text.split('|').map { it.trim() }
}

private fun alignmentOf(cell: String): MdAlign {
    val trimmed = cell.trim()
    return when {
        trimmed.startsWith(":") && trimmed.endsWith(":") -> MdAlign.Center
        trimmed.endsWith(":") -> MdAlign.End
        else -> MdAlign.Start
    }
}
