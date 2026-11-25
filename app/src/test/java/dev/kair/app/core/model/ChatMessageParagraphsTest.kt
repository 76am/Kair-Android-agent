package dev.kair.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ChatMessage.textParagraphs`（U98）。
 *
 * 存在理由：转录区改成按段渲染之后，**段落边界就是 `Part.Text` 的边界** —— 而那个边界
 * 是内核切出来的（`TurnExecutor.snapshot` 每遇到思考 / 工具调用就开新段），不是展示层猜的。
 * 所以这里要钉住三件事：不合并、不插连接符、空白段不占位。
 */
class ChatMessageParagraphsTest {

    private fun message(vararg parts: Part) = ChatMessage(
        id = "m1",
        role = Role.ASSISTANT,
        parts = parts.toList(),
        stopReason = StopReason.STOP,
    )

    @Test
    fun `a single segment is a single paragraph`() {
        assertEquals(listOf("先说这一句。"), message(Part.Text("先说这一句。")).textParagraphs)
    }

    @Test
    fun `a tool call between two segments keeps them apart`() {
        val msg = message(
            Part.Text("先查一下。"),
            Part.ToolCall(id = "c1", name = "read", arguments = """{"path":"a.kt"}"""),
            Part.Text("查到了，改这里。"),
        )
        assertEquals(listOf("先查一下。", "查到了，改这里。"), msg.textParagraphs)
        // `text` 仍是整串（复制 / 导出用），两者不是同一个东西。
        assertEquals("先查一下。查到了，改这里。", msg.text)
    }

    @Test
    fun `blank segments do not become empty paragraphs`() {
        val msg = message(
            Part.Text("有内容。"),
            Part.Text("   "),
            Part.Text(""),
            Part.Text("\n第二段\n"),
        )
        assertEquals(listOf("有内容。", "第二段"), msg.textParagraphs)
    }

    @Test
    fun `a message without text has no paragraphs`() {
        val msg = message(Part.ToolCall(id = "c1", name = "ls", arguments = "{}"))
        assertEquals(emptyList<String>(), msg.textParagraphs)
    }
}
