package dev.kair.app.core

import java.io.File

/**
 * 极简 Kotlin 源码扫描器：只回答两个问题 —— 每个函数占哪几行、它是不是 `@Composable`。
 *
 * 为什么要自己写：Kotlin 没有现成的「方法区间」API。正则「找 `fun` 数到下一个 `fun`」
 * 在「接口 / 抽象方法没有体」时会一路吞到下一个类的类体（P5-2 指令 §3 坑 1）。
 *
 * 口径（`MethodSizeGuardTest` 的白名单与 `docs/代码规范.md` §4 共用）：
 * - **物理行**：起始行 = `fun` 所在行；结束行 = 函数体最后一行；中间的**空行与注释都计入**。
 *   与 `SourceSizeGuardTest` 的 `readLines().size` 同一口径，不按 LOC。
 * - 块体 `{ ... }`：结束行 = 与之配平的 `}` 所在行。
 * - 表达式体 `= ...`：结束行 = 表达式**真正结束**的那一行，含 `}.flowOn(...)` 这类链式尾部
 *   （`}` 后面还跟着 `.`，就还在表达式里）。
 * - 局部函数（函数里套 `fun`）单独计数 —— 太久同样该拆（坑 6）。
 *
 * 不做的事：不解析泛型实参、不建 AST、不认匿名函数（`fun(...)` 没有名字，无法登记）。
 * 足够回答「这个方法多长」即可。
 */
data class ScannedFunction(
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val isComposable: Boolean,
) {
    /** 物理行数，含首尾与中间的空行 / 注释。 */
    val lineCount: Int get() = endLine - startLine + 1
}

object KotlinSourceScanner {

    /**
     * 剥掉注释与字符串字面量后的源码：非代码字符抹成空格，**长度、偏移与换行都不变**。
     *
     * 给「按源码守接线」的守卫用。直接对原文做正则会被一行注释骗过 ——
     * U18 增量复核实测：在 `QueueSection` 函数体里留一句含 `visibleQueue(queue)` 的注释、
     * 把真实调用改回 `queue`，守卫两条全绿。行号不变是前提：调用方还要用
     * [scan] 返回的 `startLine` / `endLine` 去切这段文本。
     */
    fun stripped(source: String): String = mask(source).text

    /** 扫一段 Kotlin 源码，返回其中所有**有体**的函数（含局部函数）。 */
    fun scan(source: String): List<ScannedFunction> {
        val maskedSource = mask(source)
        val masked = maskedSource.text
        val lines = LineIndex(masked)
        val rawLines = source.lines()
        val result = mutableListOf<ScannedFunction>()
        var i = 0
        while (i < masked.length) {
            if (!isKeyword(masked, i, "fun")) {
                i++
                continue
            }
            val parsed = parseFunction(masked, i, lines, maskedSource.regions)
            if (parsed != null) {
                val funLine = lines.lineOf(i)
                result += ScannedFunction(
                    name = parsed.name,
                    startLine = funLine + 1,
                    endLine = parsed.endIndex.let(lines::lineOf) + 1,
                    isComposable = hasComposableAnnotation(rawLines, funLine, i - lines.startOf(funLine)),
                )
                i += 3
            } else {
                // `fun interface` 或匿名函数：跳过关键字本身，后面的方法照样要扫到。
                i += 3
            }
        }
        return result
    }

    fun scan(file: File): List<ScannedFunction> = scan(file.readText(Charsets.UTF_8))

    private class Parsed(val name: String, val endIndex: Int)

    /** mask 后的源码 + 被抹掉的区间（注释 / 字面量）。 */
    private class Masked(val text: String, val regions: List<MaskRegion>)

    /** [range] 是原串下标区间（end exclusive）；[literal] 区分字符串字面量与注释。 */
    private class MaskRegion(val range: IntRange, val literal: Boolean)

    // ------------------------------------------------------------------ 声明解析

    /**
     * 从 `fun` 关键字处解析：方法名 → 参数括号 → 签名区里找 `{` 或 `=` → 求出结束下标。
     * 找不到体（接口 / 抽象方法）返回 null。
     */
    private fun parseFunction(
        masked: String,
        funIndex: Int,
        lines: LineIndex,
        regions: List<MaskRegion>,
    ): Parsed? {
        var i = skipWhitespace(masked, funIndex + 3)
        // 坑 5：`fun interface` 不是方法（后面没有参数括号）
        if (isKeyword(masked, i, "interface")) return null

        // 坑 4：名字前可能有类型参数 `<T>` 与接收者 `List<T>.`；参数括号必须取 angle == 0 的那个。
        // 设 10 行上限，防止畸形源码把整份文件当成签名。
        val nameStartLine = lines.lineOf(funIndex)
        var angle = 0
        var lastIdent: String? = null
        while (i < masked.length) {
            val c = masked[i]
            if (lines.lineOf(i) - nameStartLine > 10) return null
            when {
                c == '<' -> {
                    angle++
                    i++
                }
                c == '>' -> {
                    // `->` 里的 `>` 不关闭类型参数
                    if (angle > 0 && masked.getOrNull(i - 1) != '-') angle--
                    i++
                }
                angle > 0 -> i++
                c == '(' -> {
                    val name = lastIdent ?: return null // 匿名函数 `fun(...)`
                    val paramsEnd = matchParen(masked, i) ?: return null
                    val bodyEnd = findBodyEnd(masked, paramsEnd + 1, lines, regions) ?: return null
                    return Parsed(name, bodyEnd)
                }
                c == '`' -> {
                    // 反引号标识符原样保留，名字取引号内的内容
                    val close = masked.indexOf('`', i + 1)
                    if (close < 0) return null
                    lastIdent = masked.substring(i + 1, close)
                    i = close + 1
                }
                isIdentPart(c) -> {
                    val start = i
                    while (i < masked.length && isIdentPart(masked[i])) i++
                    lastIdent = masked.substring(start, i)
                }
                c == '{' || c == '=' || c == ';' || c == ',' || c == ')' || c == '}' -> return null
                else -> i++
            }
        }
        return null
    }

    /**
     * 参数右括号之后，在**签名区**里找函数体的开头。
     *
     * 坑 1：接口 / 抽象方法右括号后直接换行接下一个声明 —— 只有在签名区里找到 `{` / `=`
     * 才算有体，绝不「往后找第一个 `{`」。
     * 坑 4：返回类型可跨行（允许 5 行），但遇到下一个声明关键字或 `}` 立即判为无体。
     */
    private fun findBodyEnd(
        masked: String,
        from: Int,
        lines: LineIndex,
        regions: List<MaskRegion>,
    ): Int? {
        val signatureLine = lines.lineOf(from)
        var i = from
        var parens = 0
        var angles = 0
        while (i < masked.length) {
            val c = masked[i]
            when {
                c == '(' -> parens++
                c == ')' -> parens--
                c == '<' -> angles++
                c == '>' -> if (angles > 0 && masked.getOrNull(i - 1) != '-') angles--
                parens == 0 && angles == 0 && c == '{' -> return matchBrace(masked, i) // 块体
                parens == 0 && angles == 0 && c == '=' && isSingleEquals(masked, i) ->
                    // `=` 后可能直接换行 / 接注释（表达式从下一行开始）；字符串字面量本身
                    // 在 mask 后是空格，不能当空白跳掉 —— 由 skipToExpression 区分。
                    return expressionEnd(masked, regions, skipToExpression(masked, regions, i + 1))
                c == ';' -> return null
                c == '\n' -> {
                    if (lines.lineOf(i + 1) - signatureLine > 5) return null
                    val next = skipWhitespace(masked, i + 1)
                    // `}` 是所在类的收尾；`@` 是下一个声明的注解 —— 两者都不属于「返回类型续行」
                    if (next >= masked.length || masked[next] == '}' || masked[next] == '@') return null
                    if (DECLARATION_KEYWORDS.any { isKeyword(masked, next, it) }) return null
                }
            }
            i++
        }
        return null
    }

    /**
     * 跳到表达式体的第一个 token：跳过空白与注释，但**停在字符串字面量上**
     * —— 字面量在 mask 后是空格，若一并跳过，`= "text"` 就会被当成空体。
     */
    private fun skipToExpression(masked: String, regions: List<MaskRegion>, from: Int): Int {
        var i = from
        var regionIndex = 0
        while (i < masked.length) {
            while (regionIndex < regions.size && regions[regionIndex].range.last < i) regionIndex++
            if (regionIndex < regions.size && i in regions[regionIndex].range) {
                if (regions[regionIndex].literal) return i
                i = regions[regionIndex].range.last + 1
                continue
            }
            if (masked[i].isWhitespace()) {
                i++
                continue
            }
            return i
        }
        return i
    }

    /**
     * 表达式体的结束行（坑 3）：配平括号后，遇到换行且上一非空字符不是续行操作符、
     * 下一行也不以续行符号开头，才算结束。
     * 于是 `= flow { ... }` 与 `= flow { ... }.flowOn(...)` 的结束行不同 —— 后者算到链尾。
     *
     * 字符串字面量在 [mask] 里已被抹成空格，但它**是表达式内容**：`= "text"` 这类函数体
     * 若只靠 mask 后的文本判断，会被当成空体整条跳过（`SystemPrompt.section` /
     * `KairNav.panel` 踩过）。所以这里按 [regions] 把字面量当作一个整体 token 跳过，
     * 并把它的最后一行记为内容行。
     */
    private fun expressionEnd(masked: String, regions: List<MaskRegion>, from: Int): Int? {
        var i = from
        var parens = 0
        var brackets = 0
        var braces = 0
        var lastSignificant = -1
        var regionIndex = 0
        while (i < masked.length) {
            while (regionIndex < regions.size && regions[regionIndex].range.last < i) regionIndex++
            if (regionIndex < regions.size) {
                val region = regions[regionIndex]
                if (i in region.range) {
                    // 字面量算内容（结束行取它最后一行）；注释只在计算上等同于空白
                    if (region.literal) lastSignificant = region.range.last
                    i = region.range.last + 1
                    continue
                }
            }
            val c = masked[i]
            when (c) {
                '(' -> parens++
                ')' -> parens--
                '[' -> brackets++
                ']' -> brackets--
                '{' -> braces++
                '}' -> {
                    // 已经跌出本表达式：`}` 属于外层（类体 / 函数体）
                    if (braces == 0) return lastSignificant.takeIf { it >= 0 }
                    braces--
                }
                ';' -> if (parens == 0 && brackets == 0 && braces == 0) {
                    return lastSignificant.takeIf { it >= 0 }
                }
                '\n' -> if (parens == 0 &&
                    brackets == 0 &&
                    braces == 0 &&
                    !continuesOnNextLine(masked, regions, lastSignificant, i)
                ) {
                    return lastSignificant.takeIf { it >= 0 }
                }
            }
            if (!c.isWhitespace()) lastSignificant = i
            i++
        }
        return lastSignificant.takeIf { it >= 0 }
    }

    private fun continuesOnNextLine(
        masked: String,
        regions: List<MaskRegion>,
        lastSignificant: Int,
        newline: Int,
    ): Boolean {
        // 上一行以操作符 / 开括号结尾 → 明显没写完
        if (lastSignificant >= 0 && masked[lastSignificant] in TRAILING_CONTINUATION) return true
        var j = newline + 1
        var sawLiteral = false
        while (j < masked.length) {
            val region = regions.firstOrNull { j >= it.range.first && j <= it.range.last }
            when {
                region != null -> {
                    if (region.literal) sawLiteral = true
                    j = region.range.last + 1
                }
                masked[j].isWhitespace() -> j++
                else -> break
            }
        }
        // 下一段内容是字符串字面量 → 表达式从下一行接着写（如 `= "a" +` 的续行）
        if (sawLiteral) return true
        if (j >= masked.length) return false
        if (masked[j] in LEADING_CONTINUATION) return true
        return NEXT_LINE_KEYWORDS.any { isKeyword(masked, j, it) }
    }

    // ------------------------------------------------------------------ 词法 mask

    /**
     * 坑 2：先把注释与字符串字面量全部抹成空格（保留换行与偏移），再找关键字与括号。
     * 不这么做的话，KDoc 里的 `fun foo()` 会被当成真方法，raw string 里的 `{ }` 会破坏配平。
     * 支持：`//`、可嵌套的 `/* */`、`"""..."""`、`"..."`（含转义）、`'c'`。
     *
     * 同时记下被抹掉的区间 [MaskRegion]：表达式体解析需要知道「这里原本有个字面量」
     * —— `= "text"` 这类函数体在 mask 后的文本里是空的，但它是真的表达式。
     */
    private fun mask(source: String): Masked {
        val out = source.toCharArray()
        val regions = mutableListOf<MaskRegion>()
        fun blank(from: Int, to: Int, literal: Boolean) {
            val end = minOf(to, out.size)
            for (k in from until end) {
                if (out[k] != '\n') out[k] = ' '
            }
            if (from < end) regions += MaskRegion(from until end, literal)
        }

        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                    blank(i, end, literal = false)
                    i = end
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    var depth = 1
                    var k = i + 2
                    while (k < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", k) -> { depth++; k += 2 }
                            source.startsWith("*/", k) -> { depth--; k += 2 }
                            else -> k++
                        }
                    }
                    blank(i, k, literal = false)
                    i = k
                }
                source.startsWith("\"\"\"", i) -> {
                    var k = i + 3
                    while (k < source.length && !source.startsWith("\"\"\"", k)) k++
                    k = if (k < source.length) k + 3 else source.length
                    blank(i, k, literal = true)
                    i = k
                }
                c == '"' -> {
                    var k = i + 1
                    while (k < source.length && source[k] != '\n') {
                        if (source[k] == '\\') { k += 2; continue }
                        if (source[k] == '"') { k++; break }
                        k++
                    }
                    blank(i, k, literal = true)
                    i = k
                }
                c == '\'' -> {
                    var k = i + 1
                    while (k < source.length && source[k] != '\n') {
                        if (source[k] == '\\') { k += 2; continue }
                        if (source[k] == '\'') { k++; break }
                        k++
                    }
                    blank(i, k, literal = true)
                    i = k
                }
                else -> i++
            }
        }
        return Masked(String(out), regions)
    }

    // ------------------------------------------------------------------ @Composable

    /**
     * 坑 8：从声明行往上扫连续的注解行（允许夹 1–2 个空行、允许 KDoc / 行注释），
     * 出现 `Composable` 即算；声明行自己 `fun` 之前的注解也算。
     * 用「@ + 点分限定名 + 标识符右边界」匹配，兼容 `@androidx.compose.runtime.Composable`，
     * 且不会把 `@NonRestartableComposable` 或字符串里的 `Composable` 算进去。
     */
    private fun hasComposableAnnotation(rawLines: List<String>, funLine: Int, funColumn: Int): Boolean {
        val sameLine = rawLines[funLine].substring(0, funColumn.coerceIn(0, rawLines[funLine].length))
        if (COMPOSABLE_ANNOTATION.containsMatchIn(sameLine)) return true

        var line = funLine - 1
        var blanks = 0
        while (line >= 0) {
            val text = rawLines[line].trim()
            when {
                text.isEmpty() -> {
                    blanks++
                    if (blanks > 2) return false
                }
                text.startsWith("//") ||
                    text.startsWith("/*") ||
                    text.startsWith("*") ||
                    text.endsWith("*/") -> Unit // 注释行不算断点
                text.startsWith("@") -> {
                    if (COMPOSABLE_ANNOTATION.containsMatchIn(text)) return true
                    blanks = 0
                }
                else -> return false
            }
            line--
        }
        return false
    }

    // ------------------------------------------------------------------ 小工具

    private fun matchParen(masked: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < masked.length) {
            when (masked[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun matchBrace(masked: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 坑 3：`=` 是函数体，`==` / `=>` / `<=` / `>=` / `!=` 不是。 */
    private fun isSingleEquals(masked: String, i: Int): Boolean {
        if (masked.getOrNull(i + 1) == '=') return false
        val prev = masked.getOrNull(i - 1)
        return prev == null || prev !in "=!<>"
    }

    private fun skipWhitespace(masked: String, from: Int): Int {
        var i = from
        while (i < masked.length && masked[i].isWhitespace()) i++
        return i
    }

    private fun isKeyword(masked: String, at: Int, keyword: String): Boolean {
        if (!masked.startsWith(keyword, at)) return false
        val before = masked.getOrNull(at - 1)
        val after = masked.getOrNull(at + keyword.length)
        return (before == null || !isIdentPart(before)) && (after == null || !isIdentPart(after))
    }

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** 行号 / 列号换算；行号 0 起。 */
    private class LineIndex(text: String) {
        private val starts: IntArray = buildList {
            add(0)
            text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }.toIntArray()

        fun lineOf(index: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= index) lo = mid else hi = mid - 1
            }
            return lo
        }

        fun startOf(line: Int): Int = starts[line]
    }

    private const val TRAILING_CONTINUATION = ".,+-*/%=&|?:<>([{"
    private const val LEADING_CONTINUATION = ".?:&|+-*/%=<>"
    private val NEXT_LINE_KEYWORDS = listOf("else", "as", "is", "in")

    /** 坑 4：签名区里一旦出现这些，说明「右括号之后已经没有体了」。 */
    private val DECLARATION_KEYWORDS = listOf(
        "fun", "val", "var", "override", "private", "internal", "public", "protected",
        "open", "abstract", "final", "suspend", "class", "object", "interface", "companion",
        "init", "constructor", "typealias", "inline", "enum", "data", "sealed",
    )

    /** 只认 `@` 开头的注解名；`Composable` 必须落在标识符右边界。 */
    private val COMPOSABLE_ANNOTATION = Regex("""@(?:[A-Za-z_]\w*\.)*Composable\b""")
}
