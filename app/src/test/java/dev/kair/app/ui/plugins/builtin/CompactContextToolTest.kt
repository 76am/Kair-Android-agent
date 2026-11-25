package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `compact_context` 工具语义（U58 ①）。
 *
 * 锁四条：开启时**只置位一次**且返回普通结果；关闭自动压缩时返回**错误说明**且不置位
 * （模型不能误以为已经压过）；宿主没注入时默认 no-op；声明本身是只读、可并行。
 */
class CompactContextToolTest {

    private fun ctx(onRequest: () -> Unit = {}): ToolContext =
        ToolContext(workspace = File("."), sessionId = "s1").apply { requestCompact = onRequest }

    @Test
    fun `开启时只置位一次并返回普通结果`() = runBlocking {
        var calls = 0
        val tool = CompactContextTool { true }

        val result = tool.execute(JsonObject(emptyMap()), ctx { calls++ })

        assertFalse("正常请求不是错误结果", result.isError)
        assertEquals("只置位一次", 1, calls)
    }

    @Test
    fun `关闭自动压缩时返回错误且不置位`() = runBlocking {
        var calls = 0
        val tool = CompactContextTool { false }

        val result = tool.execute(JsonObject(emptyMap()), ctx { calls++ })

        assertTrue("必须让模型看出没有压过", result.isError)
        assertTrue("错误说明要说清是设置里关掉的", result.content.contains("disabled"))
        assertEquals("不置位，避免下一步偷偷压", 0, calls)
    }

    @Test
    fun `宿主未注入时默认 requestCompact 是 no-op`() = runBlocking {
        val tool = CompactContextTool { true }

        val result = tool.execute(JsonObject(emptyMap()), ToolContext(workspace = File("."), sessionId = "s1"))

        assertFalse("没有注入也不能抛异常", result.isError)
    }

    @Test
    fun `声明只读、可并行、不需要审批`() {
        val tool = CompactContextTool { true }

        assertEquals("compact_context", tool.name)
        assertEquals(ToolCategory.READ, tool.category)
        assertTrue(tool.isParallelSafe)
        assertFalse("只读子智能体不该拿到它（压缩会改写历史）", tool.readOnly)
        assertFalse(tool.requiresApproval)
    }
}
