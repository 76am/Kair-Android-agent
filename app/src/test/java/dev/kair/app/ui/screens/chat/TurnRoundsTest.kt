package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合切分（设计稿 v2）：折叠头的统计口径与回合边界。
 *
 * 覆盖折叠规则：一轮 = 用户发令消息到 agent 段收口；最终回答在折叠区外；
 * 运行中回合不可折叠；一步工具都没用的回合不出折叠头；回合内插入的 user 消息
 * （工具回图 / 回合中改道的引导）不切断回合。
 */
class TurnRoundsTest {

    private fun user(id: String, ts: Long, text: String = "go") = ChatMessage(
        id = id, role = Role.USER, parts = listOf(Part.Text(text)), timestamp = ts,
    )

    private fun partsOf(text: String, thinking: String, calls: List<Pair<String, String>>) = buildList {
        if (thinking.isNotBlank()) add(Part.Thinking(thinking))
        if (text.isNotBlank()) add(Part.Text(text))
        calls.forEach { add(Part.ToolCall(it.first, it.second, "{}")) }
    }

    private fun assistant(id: String, ts: Long, text: String = "", thinking: String = "") = ChatMessage(
        id = id, role = Role.ASSISTANT, parts = partsOf(text, thinking, emptyList()), timestamp = ts,
    )

    private fun assistantWithCalls(
        id: String,
        ts: Long,
        vararg calls: Pair<String, String>,
        thinking: String = "",
    ) = ChatMessage(
        id = id, role = Role.ASSISTANT, parts = partsOf("", thinking, calls.toList()), timestamp = ts,
    )

    private fun result(id: String, ts: Long, callId: String, content: String, error: Boolean = false) = ChatMessage(
        id = id,
        role = Role.TOOL_RESULT,
        parts = listOf(Part.ToolResult(callId, "bash", content, isError = error)),
        timestamp = ts,
    )

    private fun feed(messages: List<ChatMessage>, busy: Boolean = false): List<FeedItem> =
        buildFeedItems(messages, matchToolResults(messages), busy)

    @Test
    fun foldableRoundFoldsStepsAndKeepsFinalAnswerOutside() {
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "container_shell", thinking = "先看看"),
            result("r1", 3_000, "c1", "ok"),
            assistant("a2", 4_500, text = "最终回答"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertTrue(round.foldable)
        assertEquals("最终回答", round.finalAnswer?.text)
        // 折叠体 = agent 段去掉最终回答
        assertEquals(listOf("a1", "r1"), round.folded.map { it.id })
        // 步 = 一条 assistant 加它带回的结果（处理一次就一步），与分隔线数量同口径
        assertEquals(1, round.stepCount)
        assertEquals(listOf(listOf("a1", "r1")), round.stepGroups.map { g -> g.messages.map { it.id } })
        // 工具名归一化：container_ 前缀去掉
        assertEquals(listOf("shell" to 1), round.toolCounts)
        // 耗时（方案 A）：最后消息 - 用户消息
        assertEquals(3_500L, round.elapsedMs)
        assertEquals(0, round.failCount)
        assertEquals(0, round.denyCount)
    }

    @Test
    fun toolWithoutTextRoundStillFolds() {
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "workspace_files"),
            result("r1", 2_500, "c1", "listing"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertTrue(round.foldable)
        assertNull(round.finalAnswer)
        assertEquals(listOf("files" to 1), round.toolCounts)
    }

    @Test
    fun noToolRoundGetsNoFold() {
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, text = "简单回答", thinking = "想一下"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertFalse(round.foldable)
    }

    @Test
    fun runningRoundIsNotFoldableAndHasNoFinalAnswer() {
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
        )
        val round = (feed(messages, busy = true).single() as FeedTurn).round

        assertTrue(round.running)
        assertFalse(round.foldable)
        assertNull(round.finalAnswer)
    }

    @Test
    fun failuresAndDenialsAreCountedSeparately() {
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls(
                "a1", 2_000,
                "c1" to "bash", "c2" to "bash", "c3" to "write",
            ),
            result("r1", 2_500, "c1", "boom", error = true),
            result("r2", 2_600, "c2", "The user denied this tool call."),
            result("r3", 2_700, "c3", "Approval timed out after 120s."),
            assistant("a2", 3_000, text = "收尾"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        // 被拒不算失败：3 步工具 = 1 失败 + 2 被拒
        assertEquals(1, round.failCount)
        assertEquals(2, round.denyCount)
        assertEquals(listOf("bash" to 2, "write" to 1), round.toolCounts)
    }

    @Test
    fun compactionAndSystemNoteBreakRoundsAndStayStandalone() {
        val note = ChatMessage(
            id = "n1", role = Role.USER, parts = listOf(Part.Text("工具集已变化")),
            isSystemNote = true, timestamp = 1_500,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 1_200, text = "一"),
            note,
            user("u2", 2_000),
            assistant("a2", 3_000, text = "二"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "m:n1", "t:u2"), items.map { it.key })
    }

    @Test
    fun toolImageDoesNotSplitTheRound() {
        // 内核让模型看见图片时追加一条纯图片 user 消息（TurnRunner.persist）。
        // 它若被当成用户消息，一个回合会在读图处被劈成两段、后半段（含最终回答）被折掉。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_100,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "read_image"),
            result("r1", 3_000, "c1", "已读取"),
            image,
            assistant("a2", 4_000, text = "图里是登录页"),
        )
        val items = feed(messages)

        assertEquals("整段仍是一个回合", listOf("t:u1"), items.map { it.key })
        val round = (items.single() as FeedTurn).round
        assertEquals("图里是登录页", round.finalAnswer?.text)
        assertEquals("工具回图折进折叠体", listOf("a1", "r1", "img1"), round.folded.map { it.id })
        assertEquals(listOf("read·image" to 1), round.toolCounts)
    }

    @Test
    fun narrationWithToolCallsIsNotMistakenForTheFinalAnswer() {
        // 模型常先写一句「我先读一下」，再调工具；之后还可能有一条只有调用的 read_image。
        // 旧口径「最后一条有正文的 assistant」会把那句说明拎到折叠区外，于是它后面的
        // 读图反而留在折叠体里 —— 删掉说明只会让边界再往前退一格，顺序永远错位。
        val narration = ChatMessage(
            id = "a1",
            role = Role.ASSISTANT,
            parts = partsOf("先读主题", "", listOf("c1" to "read")),
            timestamp = 2_000,
        )
        val messages = listOf(
            user("u1", 1_000),
            narration,
            result("r1", 2_500, "c1", "ok"),
            assistantWithCalls("a2", 3_000, "c2" to "read_image"),
            result("r2", 3_500, "c2", "已读取"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertNull("带工具调用的中间说明不是最终回答", round.finalAnswer)
        assertTrue(round.foldable)
        assertEquals(listOf("a1", "r1", "a2", "r2"), round.folded.map { it.id })
    }

    @Test
    fun abortedFinalMessageWithTextStaysOutsideTheFold() {
        // 中止 / 出错是例外：正文就是这条回合的最终产出，即便还挂着没跑完的调用也要留在外面。
        val aborted = ChatMessage(
            id = "a2",
            role = Role.ASSISTANT,
            parts = partsOf("被中止了", "", listOf("c2" to "bash")),
            timestamp = 4_000,
            stopReason = StopReason.ABORTED,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 3_000, "c1", "done"),
            aborted,
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertEquals("a2", round.finalAnswer?.id)
        assertEquals(listOf("a1", "r1"), round.folded.map { it.id })
    }

    @Test
    fun userOwnImageOnlyMessageStillOpensANewRound() {
        // 用户自己发的纯图片消息（不带文字）必须照常开新回合，不能被折进上一轮。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_000,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistant("a1", 2_000, text = "收到"),
            image,
            assistant("a2", 4_000, text = "这是你的截图"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "t:img1"), items.map { it.key })
        assertEquals("img1", (items[1] as FeedTurn).round.user?.id)
    }

    @Test
    fun steerMidTurnDoesNotSplitTheRound() {
        // 回合中改道的引导消息也是 user 角色（AgentSession.steer）：同样不能切断回合，
        // 否则它之后的工具卡 / 回答会被折进另一张卡，顺序全乱。
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 3_000, "c1", "done"),
            user("u2", 3_500, text = "换个方向"),
            assistant("a2", 4_000, text = "好的"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1"), items.map { it.key })
        val round = (items.single() as FeedTurn).round
        assertEquals("好的", round.finalAnswer?.text)
        assertEquals("引导留在折叠体里", listOf("a1", "r1", "u2"), round.folded.map { it.id })
    }

    @Test
    fun abortedAssistantWithCallsClosesTheRound() {
        // 被中止的 assistant 消息即便带着工具调用，回合也已收口（不会再跑）：
        // 下一条用户消息必须开新回合，不能被并进上一轮。
        val aborted = ChatMessage(
            id = "a2",
            role = Role.ASSISTANT,
            parts = listOf(Part.ToolCall("c2", "bash", "{}")),
            timestamp = 4_000,
            stopReason = StopReason.ABORTED,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 3_000, "c1", "done"),
            aborted,
            user("u2", 5_000, text = "算了"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "t:u2"), items.map { it.key })
    }

    @Test
    fun steerAfterFinalAnswerDoesNotUnfoldTheFinishedRound() {
        // 忙起来后引导消息可能落在最终回答之后（sweep 队列）。上一轮已经收口，
        // 不能再被标成运行中 —— 否则它的卡片会突然全部展开，看起来像顺序错乱。
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 3_000, "c1", "done"),
            assistant("a2", 4_000, text = "先这样"),
            user("u2", 4_500, text = "再补一句"),
        )
        val items = feed(messages, busy = true)
        val first = (items[0] as FeedTurn).round
        val second = (items[1] as FeedTurn).round

        assertFalse("上一轮已收口，不是运行中", first.running)
        assertTrue(first.foldable)
        assertEquals("先这样", first.finalAnswer?.text)
        assertTrue("新一轮（引导）才是运行中", second.running)
        assertEquals("u2", second.user?.id)
    }

    @Test
    fun interruptThenSendKeepsBothRoundsInOrder() {
        // 中断后内核把悬空调用修成一条 assistant 提示，用户紧接着再发一条。
        // 中断提示是上一轮的最终产出，必须留在折叠区外；新消息必须开新回合。
        val interrupted = ChatMessage(
            id = "a2",
            role = Role.ASSISTANT,
            parts = listOf(Part.Text("(interrupted by the user)")),
            timestamp = 4_000,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "read_image"),
            result("r1", 3_000, "c1", "已读取"),
            interrupted,
            user("u2", 5_000, text = "重新来"),
            assistant("a3", 6_000, text = "好"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "t:u2"), items.map { it.key })
        val first = (items[0] as FeedTurn).round
        val second = (items[1] as FeedTurn).round
        assertEquals("a2", first.finalAnswer?.id)
        assertEquals(listOf("a1", "r1"), first.folded.map { it.id })
        assertEquals("a3", second.finalAnswer?.id)
    }

    @Test
    fun sealedToolResultTailClosesTheRound() {
        // 弱网下点停止：请求卡在「工具结果已回、下一段输出未到」，这一轮没有 assistant
        // 消息 —— 内核在中断收尾时给尾条工具结果封口（stopReason=ABORTED）。
        // 下一条用户消息必须开新回合，不能被当成回合内引导折进上一轮。
        val sealed = result("r1", 3_000, "c1", "done").copy(stopReason = StopReason.ABORTED)
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            sealed,
            user("u2", 5_000, text = "停完再发"),
            assistant("a2", 6_000, text = "新回合的回答"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "t:u2"), items.map { it.key })
        val first = (items[0] as FeedTurn).round
        assertNull("被打断的回合没有最终回答", first.finalAnswer)
        val second = (items[1] as FeedTurn).round
        assertEquals("u2", second.user?.id)
        assertEquals("新回合的回答", second.finalAnswer?.text)
    }

    @Test
    fun toolImageAfterSealedTailStaysInTheInterruptedRound() {
        // 封口之后的工具回图仍是上一轮的工具产物；只有用户自己的消息才开新回合。
        val sealed = result("r1", 3_000, "c1", "done").copy(stopReason = StopReason.ABORTED)
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_200,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "read_image"),
            sealed,
            image,
            user("u2", 5_000, text = "新问题"),
        )
        val items = feed(messages)

        assertEquals(listOf("t:u1", "t:u2"), items.map { it.key })
        val first = (items[0] as FeedTurn).round
        assertEquals("工具回图留在被打断的回合里", listOf("a1", "r1", "img1"), first.folded.map { it.id })
        assertEquals("u2", (items[1] as FeedTurn).round.user?.id)
    }

    @Test
    fun steerAndToolImageKeepTranscriptOrder() {
        // 一个回合里同时有工具回图与引导：顺序必须严格按转录，不能被拆成两段。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_200,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "read_image"),
            result("r1", 3_000, "c1", "已读取"),
            image,
            user("u2", 3_500, text = "再看看这个"),
            assistant("a2", 4_000, text = "看完了"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertEquals(
            listOf("a1", "r1", "img1", "u2"),
            round.folded.map { it.id },
        )
        assertEquals("看完了", round.finalAnswer?.text)
    }

    @Test
    fun deletingAnEarlierStepDoesNotMoveTheFoldBoundary() {
        // 删掉折叠体外的那条旧说明（连同结果）后，边界不能往前退：
        // 读图仍是折叠体里的最后一步，不能因为「最后一条有正文的 assistant」换人而跑出来。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_200,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a2", 2_000, "c2" to "read_image"),
            result("r2", 3_000, "c2", "已读取"),
            image,
            assistant("a3", 4_000, text = "图里是登录页"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertEquals(listOf("a2", "r2", "img1"), round.folded.map { it.id })
        assertEquals("a3", round.finalAnswer?.id)
    }

    @Test
    fun readImageAsLastStepStaysInsideWithoutARunningFinalAnswer() {
        // 读图是最后一步、还没收口：整段都在折叠体里，不能凭空出现外面的「回答」。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 3_200,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 2_500, "c1", "done"),
            assistantWithCalls("a2", 3_000, "c2" to "read_image"),
            result("r2", 3_100, "c2", "已读取"),
            image,
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertNull(round.finalAnswer)
        assertTrue(round.foldable)
        assertEquals(listOf("a1", "r1", "a2", "r2", "img1"), round.folded.map { it.id })
    }

    @Test
    fun stepGroupsBindResultsAndToolImagesToTheirAssistant() {
        // 步的边界 = assistant 起头，其后的结果 / 回图都归它；下一条 assistant 开新步。
        // 长按菜单的「删除这一步」按这个分组生效，不能把结果留在上一组。
        val image = ChatMessage(
            id = "img1",
            role = Role.USER,
            parts = listOf(Part.Image(base64 = "AAAA")),
            timestamp = 2_600,
        )
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "container_shell"),
            result("r1", 2_500, "c1", "ok"),
            image,
            assistantWithCalls("a2", 3_000, "c2" to "container_shell"),
            result("r2", 3_100, "c2", "boom", error = true),
            assistant("a3", 4_000, text = "收尾"),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertEquals(2, round.stepCount)
        assertEquals(
            listOf(listOf("a1", "r1", "img1"), listOf("a2", "r2")),
            round.stepGroups.map { g -> g.messages.map { it.id } },
        )
        // 失败跟着步走：第二步有错误结果，第一步没有。
        assertEquals(listOf(false, true), round.stepGroups.map { it.failed })
        // 重试锚点 = 步内第一条，撤回范围覆盖整步。
        assertEquals("a1", round.stepGroups.first().anchor.id)
    }

    @Test
    fun orphanToolResultStartsItsOwnStep() {
        // 压缩切点留下的孤儿结果（回合里没有对应 assistant）也要有个「步」兜住，
        // 否则展开体直接丢掉它。
        val messages = listOf(
            user("u1", 1_000),
            result("r1", 1_500, "c0", "被切掉的调用", error = true),
        )
        val round = (feed(messages).single() as FeedTurn).round

        assertEquals(listOf(listOf("r1")), round.stepGroups.map { g -> g.messages.map { it.id } })
    }

    @Test
    fun normalizeStripsBothPrefixesAndJoinsWords() {
        assertEquals("shell", normalizeToolName("container_shell"))
        assertEquals("files", normalizeToolName("workspace_files"))
        assertEquals("mcp·server·tool", normalizeToolName("mcp_server_tool"))
    }

    @Test
    fun elapsedFormattingIsIntegerSeconds() {
        // 只报整秒：小数尾数每 100ms 抖一次，在等宽小字上像在闪。
        assertEquals("3s", formatElapsed(3_100))
        assertEquals("12s", formatElapsed(12_000))
        assertEquals("0s", formatElapsed(0))
        // 负值按 0 处理（时钟回拨时不出现 "-1s"）。
        assertEquals("0s", formatElapsed(-5_000))
    }

    @Test
    fun elapsedFormattingSwitchesUnitsAtMinuteAndHour() {
        // 秒 → 分钟：59.9s 仍是秒，满 60s 起换单位。
        assertEquals("59s", formatElapsed(59_900))
        assertEquals("1m", formatElapsed(60_000))
        assertEquals("1m 33s", formatElapsed(93_900))
        assertEquals("2m 05s", formatElapsed(125_000))
        assertEquals("59m 59s", formatElapsed(3_599_000))

        // 分钟 → 小时：满 60m 起换单位，分钟补两位。
        assertEquals("1h", formatElapsed(3_600_000))
        assertEquals("1h 05m", formatElapsed(3_900_000))
        assertEquals("2h 05m", formatElapsed(7_500_000))
        assertEquals("3h", formatElapsed(10_800_000))
        // 小时级不再报秒：秒抖动在这一量级没有参考价值。
        assertEquals("2h 05m", formatElapsed(7_559_000))
    }

    @Test
    fun turnSpeedAggregatesResponsesByDecodeTime() {
        val messages = listOf(
            user("u1", 1_000),
            assistantWithCalls("a1", 2_000, "c1" to "bash"),
            result("r1", 2_500, "c1", "ok"),
            assistant("a2", 4_500, text = "最终回答"),
        )
        val stats = mapOf(
            "a1" to ResponseStats(
                outputTokens = 100,
                decodeMs = 2_000,
                firstTokenMs = 200,
                estimated = false,
            ),
            "a2" to ResponseStats(
                outputTokens = 300,
                decodeMs = 4_000,
                firstTokenMs = 300,
                estimated = true,
            ),
        )
        val round = (
            buildFeedItems(messages, matchToolResults(messages), busy = false, responseStats = stats)
                .single() as FeedTurn
            ).round
        val speed = round.speed!!

        assertEquals(400, speed.outputTokens)
        assertEquals(6_000L, speed.decodeMs)
        // 400 token / 6s = 66.7 tok/s；分母是解码耗时，不是回合 elapsed。
        assertEquals(66.67, speed.averageTps, 0.01)
        assertTrue(speed.estimated)
        assertEquals("a2", speed.anchorId)
        assertEquals(200L, speed.firstTokenMs)
    }
}
