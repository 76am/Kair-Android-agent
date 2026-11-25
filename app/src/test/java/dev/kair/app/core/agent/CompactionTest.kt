package dev.kair.app.core.agent

import dev.kair.app.core.model.AttachmentRef
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.model.cacheHitRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the compaction logic ported from pi's compaction.ts. */
class CompactionTest {

    private fun user(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))
    private fun assistant(text: String, usage: Usage? = null) =
        ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text(text)), usage = usage)
    private fun toolCall(name: String, arguments: String) =
        ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.ToolCall("c1", name, arguments)))
    private fun toolResult(content: String) =
        ChatMessage(role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult("c1", "read", content)))
    private fun compactionCheckpoint(summary: String) = ChatMessage(
        role = Role.USER,
        isCompaction = true,
        parts = listOf(Part.Text(Compaction.wrapSummary(summary))),
    )

    // ---- token estimation ---------------------------------------------------

    @Test
    fun estimateTokensUsesCharsOverFour() {
        assertEquals(2, Compaction.estimateTokens(user("12345678")))
        assertEquals(0, Compaction.estimateTokens(user("")))
    }

    @Test
    fun thinkingAndAbortedTurnsAreEstimatedAsTheyAreSent() {
        // thinking 默认随请求回传（replayThinking），按字符数计入。
        val thinking = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.Thinking("x".repeat(4000))),
            stopReason = StopReason.STOP,
        )
        assertEquals(1000, Compaction.estimateTokens(thinking))

        // 被中止的回合装配时整条投影成中断提示，按投影后的正文估算。
        val aborted = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(Part.Text("x".repeat(4000))),
            stopReason = StopReason.ABORTED,
        )
        assertEquals(
            Compaction.estimateTextTokens(AgentSession.INTERRUPTED_NOTICE),
            Compaction.estimateTokens(aborted),
        )
    }

    @Test
    fun contextTokensTrustNewestUsageAndEstimateTrailing() {
        val messages = listOf(
            user("old"),
            assistant("hi", Usage(input = 100, output = 50)),
            user("12345678"), // 2 estimated tokens
        )
        assertEquals(152, Compaction.estimateContextTokens(messages))
    }

    @Test
    fun contextTokensFallBackToFullEstimateWithoutUsage() {
        val messages = listOf(user("12345678"), assistant("12345678"))
        assertEquals(4, Compaction.estimateContextTokens(messages))
    }

    @Test
    fun errorAssistantUsageIsIgnored() {
        val messages = listOf(
            ChatMessage(
                role = Role.ASSISTANT,
                parts = listOf(Part.Text("boom")),
                usage = Usage(input = 9999),
                stopReason = StopReason.ERROR,
            ),
            user("12345678"),
        )
        assertEquals(3, Compaction.estimateContextTokens(messages))
    }

    // ---- trigger threshold --------------------------------------------------

    @Test
    fun shouldCompactRespectsReserveAndEnabled() {
        val settings = CompactionSettings(reserveTokens = 16384)
        assertTrue(Compaction.shouldCompact(200_000, 200_000, settings))
        assertFalse(Compaction.shouldCompact(100_000, 200_000, settings))
        assertFalse(Compaction.shouldCompact(200_000, 200_000, settings.copy(enabled = false)))
    }

    // ---- cut point detection ------------------------------------------------

    @Test
    fun cutPointNeverSplitsToolPairsAndSnapsToUserTurn() {
        val messages = listOf(
            user("a".repeat(4000)),      // 0: ~1000 tokens
            toolCall("read", "x".repeat(400)), // 1: ~100 tokens
            toolResult("b".repeat(4000)), // 2: ~1000 tokens
            user("c".repeat(40)),         // 3: ~10 tokens
            assistant("d".repeat(40)),    // 4: ~10 tokens
        )
        // 预算取 15：低于新回合自身（~20），向前吸附落在 user 起点且不触发保底回退。
        val cut = Compaction.findCutPoint(messages, keepRecentTokens = 15)
        assertEquals(3, cut)
        assertEquals(Role.USER, messages[cut].role)
    }

    @Test
    fun cutPointStaysAtFirstUserWhenBudgetNeverReached() {
        val messages = listOf(user("a".repeat(40)), assistant("b".repeat(40)))
        assertEquals(0, Compaction.findCutPoint(messages, keepRecentTokens = 1_000_000))
    }

    /**
     * 单个 user 回合超过保留预算 2 倍（真机 94 步 / 31 分钟那种）：切点必须进到回合内部
     * 的步骤起点，否则整段保留 = 压不动，压完仍是一百多 k。
     */
    @Test
    fun giantTurnCutsAtStepBoundaryInsteadOfKeepingWholeTurn() {
        val messages = mutableListOf(
            user("old"),
            assistant("old reply"),
            user("go"),
        )
        repeat(40) {
            messages += assistant("a".repeat(800)) // ~200 tok
            messages += toolResult("b".repeat(800)) // ~200 tok
        }
        val keep = 1_000
        val cut = Compaction.findCutPoint(messages, keep)

        assertTrue("超大单回合必须切到回合内部", cut > 2)
        assertEquals("切点应是步骤起点（assistant）", Role.ASSISTANT, messages[cut].role)
        val keptTokens = Compaction.estimateContextTokens(messages.drop(cut))
        assertTrue("保留量应回到预算附近，而不是整段 ~16k：实际 $keptTokens", keptTokens <= keep * 2 + 500)
    }

    /**
     * 真机（2026-11-25）：上一回合很满，用户刚发一句新消息、模型就在开头调 compact_context。
     * 向前吸附会吸到新回合起点，把 31k 预算吃成 ~4k；切点必须退到上一回合（至少保住预算），
     * 而不是只留新回合那几 token。
     */
    @Test
    fun freshTurnAtTailDoesNotEatTheKeepBudget() {
        val messages = listOf(
            user("a".repeat(4_000)),      // 0: 旧回合 ~1000 tok
            assistant("b".repeat(4_000)), // 1: 旧回合 ~1000 tok
            user("go"),                   // 2: 刚发的新回合
            assistant("c".repeat(40)),    // 3: 新回合首步 ~10 tok
        )
        val keep = 500
        val cut = Compaction.findCutPoint(messages, keep)
        val keptTokens = messages.drop(cut).sumOf { Compaction.estimateTokens(it) }

        assertTrue("旧历史必须还能被压掉", cut > 0)
        assertTrue("保留量不能只剩新回合的几 token：实际 $keptTokens", keptTokens >= keep)
    }

    /** 整段只有一个超大回合：早退在 cutIndex<=0 会让步骤边界兜底够不着，压缩静默失效。 */
    @Test
    fun giantFirstTurnCutsInsideItsSteps() {
        val messages = mutableListOf(user("go"))
        repeat(40) {
            messages += assistant("a".repeat(800))
            messages += toolResult("b".repeat(800))
        }
        val cut = Compaction.findCutPoint(messages, keepRecentTokens = 1_000)

        assertTrue("唯一回合也要能切", cut > 0)
        assertEquals("切点是步骤起点", Role.ASSISTANT, messages[cut].role)
    }

    /** 正常大小的回合（≤ 2 倍预算）仍吸附到 user 起点，不被步骤切碎。 */
    @Test
    fun ordinaryTurnStillSnapsToUserStart() {
        val messages = mutableListOf(
            user("old"),
            assistant("old reply"),
            user("go"),
        )
        repeat(3) {
            messages += assistant("a".repeat(800))
            messages += toolResult("b".repeat(800))
        }
        val cut = Compaction.findCutPoint(messages, keepRecentTokens = 1_000)
        assertEquals(2, cut)
        assertEquals(Role.USER, messages[cut].role)
    }

    // ---- compaction plan ----------------------------------------------------

    @Test
    fun planSummarizesOldTurnsAndKeepsRecent() {
        // 最近的回合本身要够预算才谈得上「只压旧回合」；用 4k 字符（~1000 tok）配 500 预算。
        val messages = listOf(
            user("a".repeat(4000)),
            toolCall("read", "x".repeat(400)),
            toolResult("b".repeat(4000)),
            user("c".repeat(4000)),
            assistant("d".repeat(40)),
        )
        val plan = Compaction.planCompaction(messages, CompactionSettings(keepRecentTokens = 500))
        requireNotNull(plan)
        assertEquals(3, plan.summarized.size)
        assertEquals(2, plan.kept.size)
        assertNull(plan.previousSummary)
    }

    @Test
    fun planFoldsPreviousCheckpointIntoIterativeUpdate() {
        val head = ChatMessage(
            role = Role.USER,
            isCompaction = true,
            parts = listOf(Part.Text(Compaction.wrapSummary("OLD SUMMARY"))),
        )
        val messages = listOf(
            head,
            user("a".repeat(4000)),
            toolCall("read", "x".repeat(400)),
            toolResult("b".repeat(4000)),
            user("c".repeat(4000)),
            assistant("d".repeat(40)),
        )
        val plan = Compaction.planCompaction(messages, CompactionSettings(keepRecentTokens = 500))
        requireNotNull(plan)
        assertEquals("OLD SUMMARY", plan.previousSummary)
        assertEquals(3, plan.summarized.size)
        assertEquals(2, plan.kept.size)
    }

    @Test
    fun planIsNullWhenNothingToSummarize() {
        val messages = listOf(user("hi"))
        assertNull(Compaction.planCompaction(messages, CompactionSettings()))
    }

    // ---- summary wrapping / serialization -----------------------------------

    @Test
    fun wrapAndUnwrapSummaryRoundTrip() {
        val wrapped = Compaction.wrapSummary("body")
        assertTrue(wrapped.startsWith(Compaction.COMPACTION_SUMMARY_PREFIX))
        assertTrue(wrapped.endsWith(Compaction.COMPACTION_SUMMARY_SUFFIX))
        assertEquals("body", Compaction.unwrapSummary(wrapped))
    }

    @Test
    fun unwrapSummaryDropsTheAppendedFileManifest() {
        // 检查点 = 摘要包装 +（追加在标签之后的）文件清单：再喂回下一轮
        // <previous-summary> 前必须只保留摘要正文，否则清单与 </summary> 会逐次累积。
        val checkpoint = Compaction.wrapSummary("body") +
            "\n\n<modified-files>\n/a.txt\n</modified-files>"
        assertEquals("body", Compaction.unwrapSummary(checkpoint))
    }

    @Test
    fun serializeConversationDigestsToolCallsAndResults() {
        val text = Compaction.serializeConversation(
            listOf(
                user("do it"),
                toolCall("read", """{"path":"/a"}"""),
                toolResult("file body"),
            ),
        )
        assertTrue(text.contains("[User]: do it"))
        assertTrue(text.contains("[Assistant tool calls]: read("))
        assertTrue(text.contains("[Tool result]: file body"))
    }

    @Test
    fun serializeConversationLabelsSystemNotes() {
        val text = Compaction.serializeConversation(
            listOf(
                ChatMessage(
                    role = Role.USER,
                    parts = listOf(Part.Text("2026-10-31 (Sat)")),
                    isSystemNote = true,
                ),
            ),
        )
        assertTrue("注入提示不得被摘要成用户回合", text.contains("[System note]: 2026-10-31 (Sat)"))
        assertFalse(text.contains("[User]:"))
    }

    @Test
    fun formatFileOperationsSeparatesReadAndModified() {
        val summarized = listOf(
            ChatMessage(
                role = Role.ASSISTANT,
                parts = listOf(
                    Part.ToolCall("c1", "read", """{"path":"/a"}"""),
                    Part.ToolCall("c2", "write", """{"path":"/b"}"""),
                ),
            ),
        )
        val formatted = Compaction.formatFileOperations(summarized)
        assertTrue(formatted.contains("<read-files>\n/a\n</read-files>"))
        assertTrue(formatted.contains("<modified-files>\n/b\n</modified-files>"))
    }

    // ---- usage metric -------------------------------------------------------

    @Test
    fun cacheHitRateIsCacheReadOverPromptTokens() {
        assertEquals(0.9, Usage(input = 100, cacheRead = 900).cacheHitRate!!, 1e-9)
        assertNull(Usage().cacheHitRate)
    }

    // ---- compaction token accounting ---------------------------------------
    // 真实会话里 assistant 消息都带 usage，estimateContextTokens 会「信任」最新一条的
    // usage（记录的是当时整个上下文的大小）。压缩会重写前缀：被保留消息上的旧 usage 不再
    // 对应当前前缀。修复后不再「一旦出现检查点就整体回退启发式」（那会把检查点之后累积的
    // 大量有效 usage 一起丢掉、长期低估），而是在压缩发生时用 dropStaleUsage 清掉失真值。

    @Test
    fun estimateContextTokensTrustsUsageAfterCompaction() {
        // 旧检查点常年停在历史头部，其后累积的都是**新的、有效的** usage。
        // 修复前：只要历史里有检查点就整体回退启发式 → 丢掉这条有效 usage、长期低估。
        val messages = listOf(
            compactionCheckpoint("SUMMARY"),
            assistant("new reply", Usage(input = 120_000, output = 500)),
        )
        assertEquals(120_500, Compaction.estimateContextTokens(messages))
    }

    @Test
    fun staleUsageOnRetainedMessagesIsDroppedAtCompaction() {
        // kept：最后一条 assistant 带一个很大的 usage（模拟压缩前 ≈250k 的上下文）。
        val kept = listOf(
            user("keep me"),
            assistant("kept reply", Usage(input = 250_000, output = 500)),
        )
        // 未压缩：信任 usage → 估出 ≈250k。
        assertTrue(Compaction.estimateContextTokens(kept) > 200_000)

        // 压缩时清掉被保留消息上的失真 usage → 只剩启发式的几百 token（不再 251k→251k）。
        val dropped = Compaction.dropStaleUsage(kept)
        assertNull(dropped[1].usage)
        assertTrue(
            "cleared estimate (${Compaction.estimateContextTokens(dropped)}) should be small",
            Compaction.estimateContextTokens(dropped) < 1000,
        )
    }

    @Test
    fun tokensAfterIsLessThanTokensBefore() {
        // 旧历史：读入大量内容，最后一条 assistant 的 usage 表示压缩前 ≈250k。
        val oldMessages = listOf(
            user("a".repeat(400_000)),
            toolCall("read", "x".repeat(400)),
            toolResult("b".repeat(400_000)),
            user("c".repeat(400)),
            assistant("d".repeat(400), Usage(input = 250_000)),
        )
        val plan = Compaction.planCompaction(oldMessages, CompactionSettings(keepRecentTokens = 50))
        requireNotNull(plan)
        assertTrue("before (${plan.tokensBefore}) should be big", plan.tokensBefore > 200_000)

        val newHistory = listOf(compactionCheckpoint("SUMMARY")) + Compaction.dropStaleUsage(plan.kept)
        val tokensAfter = Compaction.estimateContextTokens(newHistory)
        assertTrue(
            "tokensAfter ($tokensAfter) should be less than tokensBefore (${plan.tokensBefore})",
            tokensAfter < plan.tokensBefore,
        )
    }

    @Test
    fun tokensAfterExcludesSummarizedMessages() {
        // 确保 tokensAfter 只计入 kept，不包含被摘要的旧消息。
        val oldMessages = listOf(
            user("a".repeat(400_000)),  // 被摘要
            toolCall("read", "x".repeat(400)),  // 被摘要
            toolResult("b".repeat(400_000)),  // 被摘要
            user("c".repeat(3_000)),  // kept，~750 tok（要够 500 预算，否则会回退到上一回合）
            assistant("d".repeat(400), Usage(input = 250_000)),  // kept
        )
        // keep 取 500（末回合 ~850 tok，在预算与 2 倍预算之间）：本用例只钉 tokensAfter 的
        // usage 清理，不触发「超大单回合切步骤边界」的兜底路径。
        val plan = Compaction.planCompaction(oldMessages, CompactionSettings(keepRecentTokens = 500))
        requireNotNull(plan)
        assertEquals(2, plan.kept.size)  // 只保留 2 条

        val newHistory = listOf(compactionCheckpoint("SUMMARY")) + Compaction.dropStaleUsage(plan.kept)
        // 失真 usage 被清掉后回退启发式，整段新历史只有 ~200 token，远小于 before ~250k。
        assertTrue(
            "newHistory estimate (${Compaction.estimateContextTokens(newHistory)}) should be small",
            Compaction.estimateContextTokens(newHistory) < 1000,
        )
    }

    // ---- 逃生舱：强制压缩只在 context-overflow 触发器下提供 -------------------

    /** 10 个 user 回合的小历史：常规切点找不到（整体小于「保留原文」预算），只能靠兜底。 */
    private fun manySmallTurns(): List<ChatMessage> = buildList {
        repeat(10) { i ->
            add(user("u$i"))
            add(assistant("a$i"))
        }
    }

    @Test
    fun planIsNullForSmallHistoryYetOverBudget() {
        // 旧会话卡死的根因：chars/4 口径下整段历史「还很小」，findCutPoint 退到第一条
        // user 消息 → cut=0 → plan 为 null。此时即使判定超限也压不动。
        assertNull(Compaction.planCompaction(manySmallTurns(), CompactionSettings(keepRecentTokens = 20_000)))
    }

    @Test
    fun forcedPlanDropsOldestUserRounds() {
        val messages = manySmallTurns()
        val forced = Compaction.forcedPlan(messages, CompactionSettings(keepRecentTokens = 20_000))
        requireNotNull(forced)
        // 10 个 user 回合 × 20% = 丢弃最老的 2 个回合（4 条消息）。
        assertEquals(4, forced.summarized.size)
        assertEquals(Role.USER, messages[forced.summarized.size].role)
        assertEquals(16, forced.kept.size)
    }

    @Test
    fun forcedPlanIsOnlyOfferedOnContextOverflow() {
        val messages = manySmallTurns()
        val settings = CompactionSettings(keepRecentTokens = 20_000)
        // 常规压力：不丢数据，返回 null（没有可切之处）。
        assertNull(SummarizingContextStrategy.plan(messages, settings, CompactionTrigger.PRESSURE))
        // 上下文溢出：允许有损兜底。
        requireNotNull(SummarizingContextStrategy.plan(messages, settings, CompactionTrigger.CONTEXT_OVERFLOW))
    }

    // ---- 图片降级（bookkeeping） -------------------------------------------

    @Test
    fun estimateTokensCountsCjkAsOneTokenPerChar() {
        // CJK 约 1 token/字：8 个汉字 ≈ 8；chars/4 只给 2（长中文会话低估约 4 倍）。
        assertEquals(8, Compaction.estimateTokens(user("中文会话测试用例")))
        // 纯 ASCII 结果与 chars/4 完全一致（8 字符 → 2）。
        assertEquals(2, Compaction.estimateTokens(user("12345678")))
    }

    @Test
    fun estimateTokensCountsImages() {
        // 图片不再被当 0：一条带图消息至少计入固定图片估值。
        val withImage = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Text("12345678"), Part.Image(base64 = "AAAA")),
        )
        assertTrue(Compaction.estimateTokens(withImage) >= 1500)
    }

    @Test
    fun estimateTokensUsesRefPixelDimensionsButNeverDropsBelowFixedFloor() {
        // 大截图：带引用且已知尺寸时按视觉编码器口径 ⌈w/28⌉×⌈h/28⌉ 计入（39×86=3354）。
        val big = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Image(ref = AttachmentRef(sha256 = "x", width = 1080, height = 2400))),
        )
        assertEquals(39 * 86, Compaction.estimateTokens(big))

        // 小图：像素口径低于固定估值时取固定值 —— 只高估不低估，避免压缩迟迟不触发。
        val small = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Image(ref = AttachmentRef(sha256 = "y", width = 40, height = 40))),
        )
        assertTrue(Compaction.estimateTokens(small) >= 1500)
    }

    @Test
    fun demoteOlderImagesKeepsNewestAndCarriesSourcePath() {
        val messages = listOf(
            ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "AAA"))),
            ChatMessage(
                role = Role.ASSISTANT,
                parts = listOf(Part.ToolCall("c1", "read_image", """{"path":"shot.png"}""")),
            ),
            ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "BBB"))),
            assistant("done"),
        )

        val demoted = Compaction.demoteOlderImages(messages) { i -> if (i == 0) "shot.png" else null }

        assertEquals(0, demoted[0].images.size)
        assertEquals("[image: shot.png]", demoted[0].text)
        assertEquals(1, demoted[2].images.size) // 最新一张原样保留
        assertEquals("BBB", demoted[2].images[0].base64)
    }

    @Test
    fun demoteOlderImagesFallsBackToPlainPlaceholderWithoutPath() {
        val messages = listOf(
            ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "AAA"))),
            ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "BBB"))),
        )
        val demoted = Compaction.demoteOlderImages(messages)
        assertEquals("[image]", demoted[0].text)
    }

    @Test
    fun demoteOlderImagesIsIdentityWithoutOlderImages() {
        val only = ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "AAA")))
        val messages = listOf(user("hi"), only)
        // 幂等且缓存安全：没有「更早的图片」时返回同一实例、逐字节不变。
        assertSame(messages, Compaction.demoteOlderImages(messages))
    }
}
