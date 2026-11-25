package dev.kair.app.app

import dev.kair.app.core.subagent.SubagentType
import dev.kair.app.core.tools.ReadImageToolName
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `browser` 子智能体的工具面（以及三种类型的解析）。
 *
 * 判据落在纯函数 [subagentToolFilter] 上：它同时是 `SessionLifecycle.createSubAgentSession`
 * 的过滤器，所以这里绿了就等于装配出来的 schema 与系统提示一致（U42 的同一份工具列表）。
 */
class SubagentToolFilterTest {

    private class FakeTool(override val name: String, override val readOnly: Boolean = false) : Tool {
        override val description = name
        override val parameters = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("")
    }

    private val tools = listOf(
        FakeTool("read", readOnly = true),
        FakeTool("ls", readOnly = true),
        FakeTool("write"),
        FakeTool("browser_open"),
        FakeTool("read_image", readOnly = true),
        FakeTool("subagent"),
    )

    /** 模拟 `SessionAssembly.toolEnabled`：`read_image` 只对视觉模型可见。 */
    private fun visible(type: SubagentType, vision: Boolean = true): List<String> =
        tools
            .filter(subagentToolFilter(type) { tool -> tool.name != ReadImageToolName || vision })
            .map { it.name }

    @Test
    fun `browser keeps browser tools plus read and read image`() {
        assertEquals(
            listOf("read", "browser_open", "read_image"),
            visible(SubagentType.BROWSER),
        )
    }

    @Test
    fun `browser drops read image for a non vision model`() {
        assertEquals(
            listOf("read", "browser_open"),
            visible(SubagentType.BROWSER, vision = false),
        )
    }

    @Test
    fun `search stays read only and coding inherits every visible tool`() {
        assertEquals(listOf("read", "ls", "read_image"), visible(SubagentType.SEARCH))
        assertEquals(tools.map { it.name }, visible(SubagentType.CODING))
    }

    @Test
    fun `type parsing accepts browser and falls back to coding`() {
        assertEquals(SubagentType.BROWSER, SubagentType.from(" Browser "))
        assertEquals(SubagentType.SEARCH, SubagentType.from("search"))
        assertEquals(SubagentType.CODING, SubagentType.from("coding"))
        assertEquals(SubagentType.CODING, SubagentType.from("unknown"))
        assertEquals(SubagentType.CODING, SubagentType.from(null))
    }
}
