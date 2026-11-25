package dev.kair.app.core.tools

import dev.kair.app.core.model.Part
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具参数的**严格**判据（U61）：宽松解析能接受、但端点严格解析会拒收的输入
 * （未加引号的占位符）不得执行工具，必须回一条可纠正的错误结果。
 */
class ToolRegistryArgsTest {

    private class ProbeTool : Tool {
        override val name = "probe"
        override val description = "probe"
        override val parameters = buildJsonObject { }
        var calls = 0

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            calls++
            return ToolResult("ok")
        }
    }

    private fun call(arguments: String) = Part.ToolCall("c1", "probe", arguments)
    private fun ctx() = ToolContext(File("."), "s")

    @Test
    fun `lenient-only arguments are rejected without executing the tool`() = runBlocking {
        val tool = ProbeTool()
        val registry = ToolRegistry().register(tool)

        val result = registry.execute(call("""{"timeout": <longcat_arg_value>}"""), ctx())

        assertTrue("非法参数必须报错：${result.content}", result.isError)
        assertEquals("工具不得执行", 0, tool.calls)
        assertTrue("要告诉模型怎么纠正：${result.content}", result.content.contains("not executed"))
        assertTrue("带上原文便于模型定位：${result.content}", result.content.contains("<longcat_arg_value>"))
    }

    @Test
    fun `strict valid arguments still execute`() = runBlocking {
        val tool = ProbeTool()
        val registry = ToolRegistry().register(tool)

        val result = registry.execute(call("""{"timeout": 30}"""), ctx())

        assertFalse(result.isError)
        assertEquals(1, tool.calls)
    }

    @Test
    fun `blank arguments keep the empty object semantics`() = runBlocking {
        val tool = ProbeTool()
        val registry = ToolRegistry().register(tool)

        val result = registry.execute(call("  "), ctx())

        assertFalse(result.isError)
        assertEquals(1, tool.calls)
    }

    @Test
    fun `strict grammar rejects common malformed shapes`() {
        val invalid = listOf(
            """{"a": NaN}""" to "NaN 不是 JSON 值",
            """{"a": 1,}""" to "尾逗号",
            """{'a': 1}""" to "单引号",
            """{"a": 01}""" to "前导零",
            """{"a": "\q"}""" to "非法转义",
            "{\"a\": 1} trailing" to "尾随内容",
        )
        invalid.forEach { (raw, why) ->
            assertTrue("$why 必须判非法：$raw", parseToolArguments(raw) == null)
        }
    }

    @Test
    fun `strict grammar accepts escapes and nested values`() {
        val valid = """{"a":"\u0041","b":[1,true,null],"c":-1.5e3}"""
        assertTrue("合法结构不得误杀：$valid", parseToolArguments(valid) != null)
    }

    @Test
    fun `a json array is not a valid arguments object`() = runBlocking {
        val tool = ProbeTool()
        val registry = ToolRegistry().register(tool)

        val result = registry.execute(call("[1,2]"), ctx())

        assertTrue(result.isError)
        assertEquals(0, tool.calls)
    }

    @Test
    fun `pathological nesting is rejected instead of overflowing the stack`() {
        // 参数来自模型（不可信输入）。游标是递归下降，没有深度门就会抛 StackOverflowError ——
        // 它是 Error 不是 Exception，三个调用点都不接；落在 HistorySanitizer 上会让已持久化的
        // 脏历史每次装配都炸，会话永久打不开。必须判非法（返回 null），不是抛出去。
        val deep = """{"a":""" + "[".repeat(50_000) + "1" + "]".repeat(50_000) + "}"
        assertNull("病态嵌套必须判非法，不得溢栈", parseToolArguments(deep))

        // 门限之内仍要放行：别为了挡病态输入把正常的嵌套参数误杀。
        val ok = """{"a":""" + "[".repeat(64) + "1" + "]".repeat(64) + "}"
        assertTrue("正常嵌套不得误杀", parseToolArguments(ok) != null)
    }
}
