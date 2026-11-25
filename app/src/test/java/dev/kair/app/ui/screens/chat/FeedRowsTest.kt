package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行级拆分（性能批次）：展开态由列表层决定，行内容与旧整卡渲染逐项对齐。
 *
 * 重点：收起只出「头 + 最终回答」；展开把每一步拆成独立行且只有最后一段画下圆角；
 * 运行中的回合不出折叠头，消息逐条成行；工具回图仍是图片行 / 步内产物。
 */
class FeedRowsTest {

    private fun user(id: String, text: String = "go", ts: Long = 1_000) = ChatMessage(
        id = id, role = Role.USER, parts = listOf(Part.Text(text)), timestamp = ts,
    )

    private fun assistant(id: String, text: String = "", thinking: String = "", ts: Long = 2_000) =
        ChatMessage(
            id = id,
            role = Role.ASSISTANT,
            parts = buildList {
                if (thinking.isNotBlank()) add(Part.Thinking(thinking))
                if (text.isNotBlank()) add(Part.Text(text))
            },
            timestamp = ts,
        )

    private fun calling(id: String, vararg calls: Pair<String, String>, thinking: String = "", ts: Long = 2_000) =
        ChatMessage(
            id = id,
            role = Role.ASSISTANT,
            parts = buildList {
                if (thinking.isNotBlank()) add(Part.Thinking(thinking))
                calls.forEach { add(Part.ToolCall(it.first, it.second, "{}")) }
            },
            timestamp = ts,
        )

    private fun result(id: String, callId: String, content: String = "ok", ts: Long = 2_500) = ChatMessage(
        id = id,
        role = Role.TOOL_RESULT,
        parts = listOf(Part.ToolResult(callId, "bash", content)),
        timestamp = ts,
    )

    private fun image(id: String, ts: Long = 3_100) = ChatMessage(
        id = id, role = Role.USER, parts = listOf(Part.Image(base64 = "AAAA")), timestamp = ts,
    )

    private fun rows(
        messages: List<ChatMessage>,
        expanded: Set<String> = emptySet(),
        busy: Boolean = false,
    ): List<FeedRow> = buildRows(buildFeedItems(messages, matchToolResults(messages), busy), expanded)

    private fun turn(): List<ChatMessage> = listOf(
        user("u1"),
        calling("a1", "c1" to "container_shell", thinking = "先看看"),
        result("r1", "c1"),
        assistant("a2", text = "最终回答", thinking = "收尾思考", ts = 4_500),
    )

    @Test
    fun collapsedFoldIsHeaderPlusFinalAnswerOnly() {
        val rows = rows(turn())

        assertEquals(3, rows.size)
        assertEquals("u1", (rows[0] as FeedRow.Message).msg.id)
        val header = rows[1] as FeedRow.FoldHeader
        assertFalse(header.expanded)
        assertEquals("u1", header.round.key)
        val answer = (rows[2] as FeedRow.Message).msg
        assertEquals("a2", answer.id)
        assertTrue("最终回答的思考折进折叠体", answer.parts.none { it is Part.Thinking })
    }

    @Test
    fun expandedFoldSplitsEveryStepAndMarksOnlyTheLastSegment() {
        val rows = rows(turn(), expanded = setOf("u1"))

        assertEquals(
            listOf(
                FeedRow.Message::class,
                FeedRow.FoldHeader::class,
                FeedRow.FoldStep::class,
                FeedRow.FoldThinking::class,
                FeedRow.Message::class,
            ),
            rows.map { it::class },
        )
        val step = rows[2] as FeedRow.FoldStep
        assertEquals(0, step.index)
        assertFalse("最终思考还在后面，这一步不是最后一段", step.last)
        assertEquals(listOf("a1", "r1"), step.step.messages.map { it.id })
        assertEquals("收尾思考", (rows[3] as FeedRow.FoldThinking).text)
        assertTrue((rows[1] as FeedRow.FoldHeader).expanded)
    }

    @Test
    fun lastStepDrawsTheBottomEdgeWhenThereIsNoFinalThinking() {
        val messages = listOf(
            user("u1"),
            calling("a1", "c1" to "bash"),
            result("r1", "c1"),
        )
        val rows = rows(messages, expanded = setOf("u1"))

        assertEquals(3, rows.size)
        assertTrue("没有最终回答也没有思考，最后一步收底", (rows[2] as FeedRow.FoldStep).last)
    }

    @Test
    fun runningRoundHasLiveHeadAndMessageRowsInsteadOfAFold() {
        val messages = listOf(
            user("u1"),
            calling("a1", "c1" to "bash"),
            result("r1", "c1"),
        )
        val rows = rows(messages, busy = true)

        assertEquals(
            listOf(FeedRow.Message::class, FeedRow.Live::class, FeedRow.Message::class, FeedRow.Message::class),
            rows.map { it::class },
        )
        assertTrue(rows.none { it is FeedRow.FoldHeader })
    }

    @Test
    fun roundWithoutToolCallsRendersEachMessageWithItsThinking() {
        val messages = listOf(user("u1"), assistant("a1", text = "简单回答", thinking = "想一下"))
        val rows = rows(messages)

        assertEquals(listOf(FeedRow.Message::class, FeedRow.Message::class), rows.map { it::class })
        assertTrue((rows[1] as FeedRow.Message).msg.thinking.isNotBlank())
    }

    @Test
    fun toolImageInsideARunningRoundGetsItsOwnImageRow() {
        val messages = listOf(
            user("u1"),
            calling("a1", "c1" to "read_image"),
            result("r1", "c1"),
            image("img1"),
        )
        val rows = rows(messages, busy = true)

        assertEquals("img1", (rows.last() as FeedRow.ToolImage).msg.id)
    }

    @Test
    fun userOwnImageMessageStaysAUserCard() {
        // 用户自己发的纯图片消息是 round.user，不能按「工具回图」渲染。
        val messages = listOf(image("img1"), assistant("a1", text = "这是你的截图"))
        val rows = rows(messages)

        assertEquals("img1", (rows.first() as FeedRow.Message).msg.id)
    }

    @Test
    fun standaloneEventsStaySingleMessageRows() {
        val note = ChatMessage(
            id = "n1", role = Role.USER, parts = listOf(Part.Text("工具集已变化")),
            isSystemNote = true, timestamp = 1_500,
        )
        val messages = listOf(user("u1"), assistant("a1", text = "一"), note)

        val rows = rows(messages)

        assertEquals(listOf(FeedRow.Message::class, FeedRow.Message::class, FeedRow.Message::class), rows.map { it::class })
        assertEquals("n1", (rows.last() as FeedRow.Message).msg.id)
    }

    @Test
    fun rowKeysAreStableAndUniqueAcrossStates() {
        val collapsed = rows(turn())
        val expanded = rows(turn(), expanded = setOf("u1"))

        assertEquals(collapsed.size, collapsed.map { it.key }.distinct().size)
        assertEquals(expanded.size, expanded.map { it.key }.distinct().size)
        // 头与最终回答的 key 在两种状态下一致：展开只插入行，滚动锚点不跳。
        assertEquals("t:u1", collapsed[1].key)
        assertEquals("t:u1", expanded[1].key)
        assertEquals("m:a2", collapsed.last().key)
        assertEquals("m:a2", expanded.last().key)
    }
}
