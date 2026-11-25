package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * assembleSelectableText：「选取文字」弹窗里的全文拼装（标签由调用方注入，故可纯函数单测）。
 * 工具卡内已取消内联选区，工具输入 / 输出必须完整拼进这里（不截断），否则没有别处能选中复制。
 */
class SelectableTextTest {

    private val thinkingLabel = "思考过程"
    private val callFormat = "调用 %1\$s"
    private val inputLabel = "输入"
    private val outputLabel = "输出"

    private fun assemble(msg: ChatMessage, matched: List<Part.ToolResult?>) =
        assembleSelectableText(msg, matched, thinkingLabel, callFormat, inputLabel, outputLabel)

    @Test
    fun `thinking comes before body text`() {
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.Thinking("先想一想"), Part.Text("再回答")),
        )

        assertEquals("思考过程\n先想一想\n\n再回答", assemble(msg, emptyList()))
    }

    @Test
    fun `tool call includes full input and matched output`() {
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.ToolCall("c1", "bash", "{\"command\":\"ls\"}")),
        )
        val matched = listOf(Part.ToolResult("c1", "bash", "a.txt\nb.txt"))

        val text = assemble(msg, matched)
        assertTrue(text.contains("调用 bash"))
        assertTrue(text.contains("输入\n{\"command\":\"ls\"}"))
        assertTrue(text.contains("输出\na.txt\nb.txt"))
    }

    @Test
    fun `pending call omits output section`() {
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.ToolCall("c1", "bash", "{}")),
        )

        val text = assemble(msg, listOf(null))
        assertTrue(text.contains("调用 bash"))
        assertTrue(text.contains("输入\n{}"))
        assertFalse(text.contains(outputLabel))
    }

    @Test
    fun `long tool output is not truncated`() {
        val long = "x".repeat(5000)
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.ToolCall("c1", "read", "{}")),
        )
        val matched = listOf(Part.ToolResult("c1", "read", long))

        assertTrue(assemble(msg, matched).contains(long))
    }

    @Test
    fun `empty message yields empty string`() {
        val msg = ChatMessage(role = Role.ASSISTANT, parts = emptyList())
        assertEquals("", assemble(msg, emptyList()))
    }
}
