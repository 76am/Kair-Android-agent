package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.tools.ToolCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 动态插件工具：未声明类别时默认需要审批；声明类别后跟随审批面板的分类开关。
 */
class ScriptToolCategoryTest {

    private fun tool(category: String?, requiresApproval: Boolean = false) = ScriptTool(
        spec = ToolSpec(
            name = "demo",
            description = "demo",
            script = "demo.sh",
            category = category,
            requiresApproval = requiresApproval,
        ),
        dir = File("."),
    )

    @Test
    fun `undeclared category requires approval`() {
        val tool = tool(category = null)
        assertEquals(ToolCategory.UNSPECIFIED, tool.category)
        assertTrue(tool.requiresApproval)
    }

    @Test
    fun `declared category follows the approval panel`() {
        val tool = tool(category = "write")
        assertEquals(ToolCategory.WRITE, tool.category)
        assertFalse(tool.requiresApproval)
    }

    @Test
    fun `category names are case-insensitive`() {
        assertEquals(ToolCategory.EXECUTE, tool(category = " EXECUTE ").category)
        assertEquals(ToolCategory.DELETE, tool(category = "delete").category)
    }

    @Test
    fun `explicit approval still wins over a category`() {
        assertTrue(tool(category = "read", requiresApproval = true).requiresApproval)
    }

    @Test
    fun `a misspelled category is treated as undeclared and requires approval`() {
        val tool = tool(category = "wirte")
        assertEquals(ToolCategory.UNSPECIFIED, tool.category)
        assertTrue(tool.requiresApproval)
    }
}
