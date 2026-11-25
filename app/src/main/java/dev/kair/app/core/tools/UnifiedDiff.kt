package dev.kair.app.core.tools

/**
 * `edit` 工具的统一 diff 生成（**面向模型**，英文；确定性——同一处编辑在任何时刻
 * 都产出同一份文本，因此可安全进入工具回灌与上下文）。
 *
 * 逐块生成 hunk：先把每个编辑块内部的公共前 / 后缀行裁掉，只保留真正的增删行，
 * 再各带 [unifiedDiff] 的上下文行。**不做全文 LCS**：编辑块本身通常很小，前后缀
 * 裁剪已足够表达「改了什么」，且避免大文件上的 O(行数²) 开销。
 */

/** 一处已定位的编辑：替换区间在原文件中的起始行 + 原始/替换文本。 */
internal data class EditHunk(
    /** 被替换区间起始行（0 起；只用到行号，忽略行内偏移）。 */
    val startLine: Int,
    val oldText: String,
    val newText: String,
)

/** 计算 [charOffset] 位于 [text] 的第几行（0 起）。 */
internal fun lineIndexOf(text: String, charOffset: Int): Int {
    var line = 0
    val end = charOffset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') line++
    return line
}

/**
 * 生成标准 unified diff 文本；无实际变更的 hunk 会被跳过。
 *
 * 编辑块自身的公共前 / 后缀行**必须作为上下文行原样输出**：它们虽然两侧相同，
 * 但在 hunk 体内承担定位作用，裁掉会让 hunk 体少行、而 `after` 窗口仍从
 * `startLine + oldLines.size` 起算，上下文就错位到编辑块之后，读者看到的新增行会
 * 落在错误位置。[commonBorder] 只用来定位真正增删的区间，不用来删行。
 */
internal fun unifiedDiff(path: String, original: String, hunks: List<EditHunk>, context: Int = 3): String {
    val lines = original.split('\n')
    val out = StringBuilder()
    out.append("--- a/").append(path).append('\n')
    out.append("+++ b/").append(path).append('\n')
    for (h in hunks) {
        val oldLines = splitLines(h.oldText)
        val newLines = splitLines(h.newText)
        val (prefix, suffix) = commonBorder(oldLines, newLines)
        val changed = prefix < oldLines.size - suffix || prefix < newLines.size - suffix
        if (!changed) continue
        val beforeFrom = maxOf(0, h.startLine - context)
        val before = lines.subList(beforeFrom, minOf(h.startLine, lines.size))
        val afterFrom = minOf(h.startLine + oldLines.size, lines.size)
        val after = lines.subList(afterFrom, minOf(afterFrom + context, lines.size))
        out.append("@@ -").append(beforeFrom + 1).append(',').append(before.size + oldLines.size + after.size)
            .append(" +").append(beforeFrom + 1).append(',').append(before.size + newLines.size + after.size)
            .append(" @@\n")
        fun context(line: String) {
            out.append(' ').append(line).append('\n')
        }
        before.forEach(::context)
        oldLines.subList(0, prefix).forEach(::context)
        oldLines.subList(prefix, oldLines.size - suffix).forEach { out.append('-').append(it).append('\n') }
        newLines.subList(prefix, newLines.size - suffix).forEach { out.append('+').append(it).append('\n') }
        if (suffix > 0) oldLines.subList(oldLines.size - suffix, oldLines.size).forEach(::context)
        after.forEach(::context)
    }
    return out.toString().trimEnd('\n')
}

/** 两侧行序列的公共前缀 / 后缀行数（后缀不含已归入前缀的部分）。 */
private fun commonBorder(oldLines: List<String>, newLines: List<String>): Pair<Int, Int> {
    val maxPrefix = minOf(oldLines.size, newLines.size)
    var prefix = 0
    while (prefix < maxPrefix && oldLines[prefix] == newLines[prefix]) prefix++
    var suffix = 0
    while (suffix < maxPrefix - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) {
        suffix++
    }
    return prefix to suffix
}
