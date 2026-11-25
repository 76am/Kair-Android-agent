package dev.kair.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `KotlinSourceScanner` 自己的测试：P5-2 指令 §3 的 8 个坑，每条至少钉一条用例。
 *
 * 一个假绿的守卫比没有守卫更危险 —— 如果解析器把方法区间算错，`MethodSizeGuardTest`
 * 会「全绿」地放过超长函数，而我们以为它已经守住了。
 */
class KotlinSourceScannerTest {

    @Test
    fun `interface and abstract methods without bodies are skipped`() {
        // 坑 1：右括号后直接换行。若实现「往后找第一个 {」，会把下一个类的类体算成方法体。
        val source = sourceOf(
            "interface Plugin {",
            "    fun activate(ctx: PluginContext)",
            "}",
            "",
            "abstract class Base {",
            "    abstract fun run()",
            "}",
            "",
            "class Real : Base() {",
            "    override fun run() {",
            "        doWork()",
            "    }",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source)
        assertEquals(listOf("run"), functions.map { it.name })
        assertEquals(10, functions.single().startLine)
        assertEquals(12, functions.single().endLine)
    }

    @Test
    fun `comments and string literals neither produce functions nor break brace matching`() {
        // 坑 2：KDoc 里的 fun / raw string 里的花括号 / 嵌套块注释，都不能参与解析。
        val source = sourceOf(
            "/**",
            " * 说明：fun fakeInKdoc() 不是方法。",
            " */",
            "class Demo {",
            "    // fun fakeInLineComment() { 也不是",
            "    /* 外层 /* 嵌套 */ { 仍然 } 是注释 */",
            "    fun real() {",
            "        val raw = \"\"\"fun fakeInRaw() { }\n { 配平的 } 也不算\"\"\"",
            "        val quoted = \"a\\\"b{ fun fakeInString()\"",
            "        val brace = '}'",
            "        check(raw.length + quoted.length + brace.code > 0)",
            "    }",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source)
        assertEquals(listOf("real"), functions.map { it.name })
        assertEquals(7, functions.single().startLine)
        assertEquals(13, functions.single().endLine)
    }

    @Test
    fun `expression bodies end at the last line of the chain`() {
        // 坑 3：`= flow { ... }` 与 `= flow { ... }.flowOn(...)` 的结束行不同 —— 后者算到链尾，
        // 这就是 P5-2 交付说明里要写明的口径。
        val source = sourceOf(
            "fun bare() = flow {",
            "    emit(1)",
            "}",
            "",
            "fun chained() = flow {",
            "    emit(1)",
            "}",
            "    .flowOn(dispatcher)",
            "",
            "// 等号在行尾、表达式从下一行开始：不能把开头那个换行当成「表达式已结束」",
            "fun leadingNewline() =",
            "    flow {",
            "        emit(1)",
            "    }",
            "",
            "fun next() = 1",
        )
        val functions = KotlinSourceScanner.scan(source).associateBy { it.name }
        assertEquals(3, functions.getValue("bare").lineCount)
        assertEquals(3, functions.getValue("bare").endLine)
        assertEquals(4, functions.getValue("chained").lineCount)
        assertEquals(8, functions.getValue("chained").endLine)
        assertEquals(4, functions.getValue("leadingNewline").lineCount)
        assertEquals(14, functions.getValue("leadingNewline").endLine)
        assertEquals(1, functions.getValue("next").lineCount)
    }

    @Test
    fun `multi-line signatures resolve to the right body`() {
        // 坑 4：参数括号配平；返回类型可跨行，但遇到下一个声明关键字立刻判为无体。
        val source = sourceOf(
            "fun wide(",
            "    a: Int,",
            "    b: List<Int>,",
            "): Map<String,",
            "    Int> {",
            "    return emptyMap()",
            "}",
            "",
            "fun bodiless(",
            "    a: Int,",
            "): Map<String,",
            "    Int>",
            "",
            "override fun after() {",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source)
        assertEquals(listOf("wide", "after"), functions.map { it.name })
        assertEquals(7, functions.first().endLine)
        assertEquals(14, functions.last().startLine)
    }

    @Test
    fun `fun interface is not a method`() {
        // 坑 5：`fun interface` 后面没有参数括号，不是方法。
        val source = sourceOf(
            "fun interface OnEvent {",
            "    fun onEvent(id: String)",
            "    fun withDefault(): Int = 1",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source)
        assertTrue(functions.none { it.name == "interface" })
        // onEvent 没有体 → 跳过；默认实现有体 → 必须继续扫到（证明 `fun interface` 没有吞掉后续声明）
        assertEquals(listOf("withDefault"), functions.map { it.name })
    }

    @Test
    fun `local functions are counted separately from their host`() {
        // 坑 6：函数里套 fun —— 两条都要出现，外层包含内层的行。
        val source = sourceOf(
            "fun host() {",
            "    fun helper() {",
            "        step()",
            "    }",
            "    helper()",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source)
        assertEquals(setOf("host", "helper"), functions.map { it.name }.toSet())
        assertEquals(6, functions.single { it.name == "host" }.lineCount)
        assertEquals(3, functions.single { it.name == "helper" }.lineCount)
    }

    @Test
    fun `same-name overloads are both returned`() {
        // 坑 7：白名单 key 只能取方法名，所以重载必须两条都扫到（守卫再按名字取最大值）。
        val source = sourceOf(
            "class Overloads {",
            "    fun foo() = 1",
            "    fun foo(x: Int) = x",
            "}",
        )
        val foos = KotlinSourceScanner.scan(source).filter { it.name == "foo" }
        assertEquals(2, foos.size)
        assertEquals(listOf(2, 3), foos.map { it.startLine })
    }

    @Test
    fun `composable annotation is detected around the declaration`() {
        // 坑 8：上一行 / 全限定名 / 夹空行 / 同行注解都要认；NonRestartable 与 KDoc 提及不能误判。
        val source = sourceOf(
            "@Composable",
            "fun plain() { }",
            "",
            "@androidx.compose.runtime.Composable",
            "fun qualified() { }",
            "",
            "@Composable",
            "",
            "fun afterBlankLine() { }",
            "",
            "@NonRestartableComposable",
            "fun notComposable() { }",
            "",
            "/**",
            " * 这里提到 @Composable 只是文档。",
            " */",
            "fun documented() { }",
            "",
            "class Inline {",
            "    @Composable fun sameLine() { }",
            "}",
            "",
            "@Composable",
            "private fun withModifier() { }",
        )
        val byName = KotlinSourceScanner.scan(source).associateBy { it.name }
        assertTrue(byName.getValue("plain").isComposable)
        assertTrue(byName.getValue("qualified").isComposable)
        assertTrue(byName.getValue("afterBlankLine").isComposable)
        assertFalse(byName.getValue("notComposable").isComposable)
        assertFalse(byName.getValue("documented").isComposable)
        assertTrue(byName.getValue("sameLine").isComposable)
        assertTrue(byName.getValue("withModifier").isComposable)
    }

    @Test
    fun `string literal expression bodies are counted`() {
        // 回归：mask 把字符串抹成空格后，`= "text"` 曾被当成空体整条跳过
        // （`SystemPrompt.section` / `KairNav.panel` 两处真实函数因此漏扫，总数少了 2）。
        val source = sourceOf(
            "object Routes {",
            "    fun chat(id: String) = \"chat/\$id\"",
            "",
            "    override fun section(ctx: Ctx): String =",
            "        \"text with \${ctx.id}\"",
            "",
            "    fun raw() =",
            "        \"\"\"line1",
            "        line2\"\"\"",
            "}",
        )
        val functions = KotlinSourceScanner.scan(source).associateBy { it.name }
        assertEquals(setOf("chat", "section", "raw"), functions.keys)
        assertEquals(1, functions.getValue("chat").lineCount)
        assertEquals(4, functions.getValue("section").startLine)
        assertEquals(5, functions.getValue("section").endLine)
        assertEquals(7, functions.getValue("raw").startLine)
        assertEquals(9, functions.getValue("raw").endLine)
    }

    @Test
    fun `line count is physical lines including blanks and comments`() {
        val source = sourceOf(
            "fun padded() {",
            "    first()",
            "",
            "    // 注释行也计入",
            "    second()",
            "}",
        )
        assertEquals(6, KotlinSourceScanner.scan(source).single().lineCount)
    }

    @Test
    fun `fun-like identifiers are not functions`() {
        val source = sourceOf(
            "val funny = 1",
            "fun functionName() = funny",
        )
        assertEquals(listOf("functionName"), KotlinSourceScanner.scan(source).map { it.name })
    }

    /** 用「按行拼接」而不是 raw string：用例里要嵌入 `\"\"\"`，raw string 会打架。 */
    private fun sourceOf(vararg lines: String): String = lines.joinToString("\n")
}
