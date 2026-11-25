package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * matchToolResults：工具结果按转录顺序匹配回所在回合的调用。重点覆盖
 * toolCallId 跨回合重复（兼容端点复用 "call_0"）时互不串线。
 */
class MatchToolResultsTest {

    private fun assistant(id: String, vararg calls: Pair<String, String>): ChatMessage = ChatMessage(
        id = id,
        role = Role.ASSISTANT,
        parts = calls.map { Part.ToolCall(it.first, it.second, "{}") },
    )

    private fun result(toolCallId: String, content: String): ChatMessage = ChatMessage(
        role = Role.TOOL_RESULT,
        parts = listOf(Part.ToolResult(toolCallId, "bash", content)),
    )

    private fun user(text: String = "hi"): ChatMessage =
        ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    @Test
    fun duplicateIdsAcrossTurnsKeepTheirOwnResults() {
        val messages = listOf(
            user("go"),
            assistant("t1", "call_0" to "bash"),
            result("call_0", "R1"),
            user("again"),
            assistant("t2", "call_0" to "bash"),
            result("call_0", "R2"),
        )

        val matched = matchToolResults(messages)

        assertEquals("R1", matched.getValue("t1")[0]?.content)
        assertEquals("R2", matched.getValue("t2")[0]?.content)
    }

    @Test
    fun toolImageBetweenResultsDoesNotOrphanTheRest() {
        // 内核让模型看见图片时会在结果后追加一条纯图片 user 消息（TurnRunner.persist）：
        // 并行工具的结果 + 图片交替落盘。若把这条假用户消息当回合结束，
        // 排在它后面的结果就全成了没人认领的孤儿卡。
        val image = ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "AAAA")))
        val messages = listOf(
            user("go"),
            assistant("t1", "a" to "read_image", "b" to "bash"),
            result("a", "RA"),
            image,
            result("b", "RB"),
        )

        val matched = matchToolResults(messages)

        assertEquals("RA", matched.getValue("t1")[0]?.content)
        assertEquals("RB", matched.getValue("t1")[1]?.content)
    }

    @Test
    fun alignsMultipleCallsByIdWithinTurn() {
        val messages = listOf(
            user("go"),
            assistant("t1", "a" to "read", "b" to "bash"),
            result("b", "RB"),
            result("a", "RA"),
        )

        val matched = matchToolResults(messages)

        assertEquals("RA", matched.getValue("t1")[0]?.content)
        assertEquals("RB", matched.getValue("t1")[1]?.content)
    }

    @Test
    fun duplicateIdsWithinTurnFillSlotsInOrder() {
        val messages = listOf(
            user("go"),
            assistant("t1", "x" to "bash", "x" to "bash"),
            result("x", "first"),
            result("x", "second"),
        )

        val matched = matchToolResults(messages)

        assertEquals("first", matched.getValue("t1")[0]?.content)
        assertEquals("second", matched.getValue("t1")[1]?.content)
    }

    @Test
    fun resultAfterNextUserTurnIsNotClaimed() {
        val messages = listOf(
            user("go"),
            assistant("t1", "a" to "bash"),
            user("interrupt"),
            result("a", "late"),
        )

        val matched = matchToolResults(messages)

        assertNull(matched.getValue("t1")[0])
    }

    @Test
    fun orphanResultIsNotClaimedByAnyTurn() {
        val messages = listOf(
            result("ghost", "orphan"),
            assistant("t1", "a" to "bash"),
        )

        val matched = matchToolResults(messages)

        assertNull(matched.getValue("t1")[0])
        assertTrue(matched.values.flatten().none { it?.content == "orphan" })
    }

    @Test
    fun pendingCallStaysNull() {
        val messages = listOf(
            user("go"),
            assistant("t1", "a" to "bash", "b" to "bash"),
            result("a", "RA"),
        )

        val matched = matchToolResults(messages)

        assertEquals("RA", matched.getValue("t1")[0]?.content)
        assertNull(matched.getValue("t1")[1])
    }

    @Test
    fun indexReusesTheMatchWhenOnlyStreamedTextChanges() {
        // 流式每帧换的是消息实例与正文长度，配对关系不变：缓存必须原样返回同一个实例，
        // 否则下游拿到的永远是「不相等的新 Map」，已完成回合会跟着每帧重组。
        val index = ToolResultIndex()
        val first = listOf(
            user("go"),
            assistant("t1", "a" to "bash"),
            result("a", "RA"),
        )
        val cached = index.match(first)
        val streamed = first.map { msg ->
            if (msg.id == "t1") msg.copy(parts = msg.parts + Part.Text("…还在写")) else msg
        }

        assertSame(cached, index.match(streamed))
    }

    @Test
    fun indexRebuildsWhenAToolResultArrives() {
        val index = ToolResultIndex()
        val pending = listOf(
            user("go"),
            assistant("t1", "a" to "bash", "b" to "bash"),
            result("a", "RA"),
        )
        val before = index.match(pending)
        assertNull(before.getValue("t1")[1])

        val after = index.match(pending + result("b", "RB"))

        assertNotSame(before, after)
        assertEquals("RB", after.getValue("t1")[1]?.content)
    }

    @Test
    fun indexRebuildsWhenTheResultIsReplacedInPlace() {
        // 同 id 同调用槽被改写（正常落盘不会发生）也要失效：签名带结果长度与错误位。
        val index = ToolResultIndex()
        val first = listOf(user("go"), assistant("t1", "a" to "bash"), result("a", "RA"))
        index.match(first)

        val replaced = first.map { msg ->
            if (msg.role == Role.TOOL_RESULT) {
                msg.copy(parts = listOf(Part.ToolResult("a", "bash", "RA 变成了更长的输出")))
            } else {
                msg
            }
        }
        val matched = index.match(replaced)

        assertEquals("RA 变成了更长的输出", matched.getValue("t1")[0]?.content)
    }
}
