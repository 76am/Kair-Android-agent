package dev.kair.app.core.tools

/**
 * Shared truncation utilities for tool outputs, ported from pi's
 * `packages/coding-agent/src/core/tools/truncate.ts`.
 *
 * Two independent limits apply; whichever is hit first wins:
 * - Line limit (default 2000 lines)
 * - Byte limit (default 50KB)
 */
object Truncation {
    const val DEFAULT_MAX_LINES = 2000
    const val DEFAULT_MAX_BYTES = 50 * 1024
    const val GREP_MAX_LINE_LENGTH = 500

    fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }
}

data class TruncationResult(
    val content: String,
    val truncated: Boolean,
    val truncatedBy: String?, // "lines" | "bytes" | null
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    val lastLinePartial: Boolean,
    val firstLineExceedsLimit: Boolean,
    val maxLines: Int,
    val maxBytes: Int,
)

fun truncateHead(
    content: String,
    maxLines: Int = Truncation.DEFAULT_MAX_LINES,
    maxBytes: Int = Truncation.DEFAULT_MAX_BYTES,
): TruncationResult {
    val totalBytes = content.toByteArray(Charsets.UTF_8).size
    val lines = splitLines(content)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(content, false, null, totalLines, totalBytes, totalLines, totalBytes, false, false, maxLines, maxBytes)
    }

    val firstLineBytes = lines[0].toByteArray(Charsets.UTF_8).size
    if (firstLineBytes > maxBytes) {
        return TruncationResult("", true, "bytes", totalLines, totalBytes, 0, 0, false, true, maxLines, maxBytes)
    }

    val out = ArrayList<String>()
    var outputBytes = 0
    var truncatedBy = "lines"
    var i = 0
    while (i < lines.size && i < maxLines) {
        val line = lines[i]
        val lineBytes = line.toByteArray(Charsets.UTF_8).size + if (i > 0) 1 else 0
        if (outputBytes + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            break
        }
        out.add(line)
        outputBytes += lineBytes
        i++
    }
    if (out.size >= maxLines && outputBytes <= maxBytes) truncatedBy = "lines"

    val outputContent = out.joinToString("\n")
    return TruncationResult(
        content = outputContent,
        truncated = true,
        truncatedBy = truncatedBy,
        totalLines = totalLines,
        totalBytes = totalBytes,
        outputLines = out.size,
        outputBytes = outputContent.toByteArray(Charsets.UTF_8).size,
        lastLinePartial = false,
        firstLineExceedsLimit = false,
        maxLines = maxLines,
        maxBytes = maxBytes,
    )
}

fun truncateTail(
    content: String,
    maxLines: Int = Truncation.DEFAULT_MAX_LINES,
    maxBytes: Int = Truncation.DEFAULT_MAX_BYTES,
): TruncationResult {
    val totalBytes = content.toByteArray(Charsets.UTF_8).size
    val lines = splitLines(content)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(content, false, null, totalLines, totalBytes, totalLines, totalBytes, false, false, maxLines, maxBytes)
    }

    val out = ArrayList<String>()
    var outputBytes = 0
    var truncatedBy = "lines"
    var lastLinePartial = false
    var i = lines.size - 1
    while (i >= 0 && out.size < maxLines) {
        val line = lines[i]
        val lineBytes = line.toByteArray(Charsets.UTF_8).size + if (out.isNotEmpty()) 1 else 0
        if (outputBytes + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            if (out.isEmpty()) {
                val partial = truncateStringToBytesFromEnd(line, maxBytes)
                out.add(0, partial)
                outputBytes = partial.toByteArray(Charsets.UTF_8).size
                lastLinePartial = true
            }
            break
        }
        out.add(0, line)
        outputBytes += lineBytes
        i--
    }
    if (out.size >= maxLines && outputBytes <= maxBytes) truncatedBy = "lines"

    val outputContent = out.joinToString("\n")
    return TruncationResult(
        content = outputContent,
        truncated = true,
        truncatedBy = truncatedBy,
        totalLines = totalLines,
        totalBytes = totalBytes,
        outputLines = out.size,
        outputBytes = outputContent.toByteArray(Charsets.UTF_8).size,
        lastLinePartial = lastLinePartial,
        firstLineExceedsLimit = false,
        maxLines = maxLines,
        maxBytes = maxBytes,
    )
}

/** Truncate a single line to maxChars, adding a `... [truncated]` suffix (used for grep matches). */
fun truncateLine(line: String, maxChars: Int = Truncation.GREP_MAX_LINE_LENGTH): Pair<String, Boolean> =
    if (line.length <= maxChars) line to false
    else "${line.substring(0, maxChars)}... [truncated]" to true

/**
 * 按行切分，但**不制造幻影空行**：`"b\n".split('\n')` 会得到 `["b", ""]`，而那个 `""`
 * 并不对应文件里的任何一行（它只是尾随换行）。把它当行数用会让 hunk 凭空多占一行，
 * `afterFrom` 于是跳过编辑块之后的第一行真实内容，读者看到的就是「下一行凭空消失」。
 * 空串表示「删掉整块」，切出空列表。
 */
internal fun splitLines(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split("\n").toMutableList()
    if (content.endsWith("\n")) lines.removeAt(lines.size - 1)
    return lines
}

private fun truncateStringToBytesFromEnd(str: String, maxBytes: Int): String {
    val bytes = str.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return str
    var start = bytes.size - maxBytes
    while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}
