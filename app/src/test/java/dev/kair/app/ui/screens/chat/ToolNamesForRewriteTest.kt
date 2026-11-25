package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「撤回前是否有工具被调用」的纯函数（用于编辑 / 重试的确认弹窗）：
 * 编辑看这条消息**之后**的回合；重试看从这条消息**起**（含自身）的回合。
 */
class ToolNamesForRewriteTest {

    private var counter = 0

    private fun user(id: String, text: String = "hi") =
        ChatMessage(id = id, role = Role.USER, parts = listOf(Part.Text(text)))

    private fun assistant(id: String, vararg calls: Part.ToolCall) =
        ChatMessage(id = id, role = Role.ASSISTANT, parts = calls.toList())

    private fun call(name: String) =
        Part.ToolCall(id = "c${counter++}", name = name, arguments = "{}")

    private fun toolResult(id: String) =
        ChatMessage(id = id, role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult("c0", "bash", "out")))

    @Test
    fun `edit scope looks after the message, de-duplicated, in order`() {
        val messages = listOf(
            user("u1"),
            assistant("a1", call("write"), call("bash")),
            assistant("a2", call("bash")),
        )
        assertEquals(listOf("write", "bash"), toolNamesForRewrite(messages, "u1", includeSelf = false))
    }

    @Test
    fun `edit scope ignores tool calls before the message`() {
        val messages = listOf(
            assistant("a0", call("write")),
            user("u1"),
        )
        assertEquals(emptyList<String>(), toolNamesForRewrite(messages, "u1", includeSelf = false))
    }

    @Test
    fun `retry scope includes the retried message itself`() {
        val messages = listOf(
            user("u1"),
            assistant("a1", call("bash")),
            toolResult("t1"),
            assistant("a2"),
        )
        // 重试工具回合：自身带工具调用 → 需确认
        assertEquals(listOf("bash"), toolNamesForRewrite(messages, "a1", includeSelf = true))
        // 重试最后一条纯回复：其后没有工具调用 → 免确认
        assertEquals(emptyList<String>(), toolNamesForRewrite(messages, "a2", includeSelf = true))
    }

    @Test
    fun `only a final reply needs no confirmation`() {
        val messages = listOf(user("u1"), assistant("a1"))
        assertEquals(emptyList<String>(), toolNamesForRewrite(messages, "a1", includeSelf = true))
    }

    @Test
    fun `unknown message id yields empty`() {
        assertEquals(emptyList<String>(), toolNamesForRewrite(listOf(user("u1")), "nope", includeSelf = true))
    }
}
