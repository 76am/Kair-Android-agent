package dev.kair.app.core.agent

import dev.kair.app.core.ai.Json
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主循环级测试：断言的是**完整链路**（请求装配 → 传输层 → 状态转移 → 历史落盘），
 * 而不是某个检测函数本身。
 *
 * 为什么必须有这一层：`OverflowTest` 曾全绿，但调用点把溢出判定嵌在 ERROR 分支里，
 * 另两条入口永远不可达。单测证明「函数正确」，集成测试才能证明「接线正确」。
 *
 * 这批用例只负责**让缺陷变红**；修 bug 归 P1 / P2，不在这里顺手改内核（P0-1 提示词）。
 */
class AgentSessionLoopTest {

    @Test
    fun scriptedTurnProducesDeterministicRequest() = runBlocking {
        // 两回合脚本、同一会话连续两次 send：第二回合请求的前缀必须与第一回合逐字节一致。
        // 这是缓存命中的前提，也是「历史装配是否确定」最直接的证据。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("ack-one"), ScriptedTurn.text("ack-two")),
            id = "loop-transport",
        )
        val h = harness(transport, sessionId = "loop-session")
        try {
            h.runTurn("first")
            h.runTurn("second")

            assertEquals("两回合应各发一次请求", 2, h.requests.size)
            val first = h.requests[0]
            val second = h.requests[1]

            assertEquals("system prompt 不得逐回合漂移", first.systemPrompt, second.systemPrompt)
            assertEquals("工具清单不得逐回合漂移", first.tools, second.tools)
            assertEquals("采样参数不得逐回合漂移", first.options, second.options)
            // 固定时钟下第一回合 = 用户消息 + 当天日期提示（F2）；不把提示算成用户回合。
            assertEquals(
                "第一回合只有一条真正的用户消息",
                1,
                first.messages.count { it.role == Role.USER && !it.isSystemNote },
            )

            val prefix = second.messages.take(first.messages.size)
            assertEquals(
                "第二回合请求的前缀必须与第一回合逐字节一致",
                serialize(first.messages),
                serialize(prefix),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun maxStepsFloorOneStillTerminatesAndMergesSteerNote() = runBlocking {
        // U20 行为判据「回合仍能结束、通知仍能并入」—— 钳制后的最小合法值。
        // maxSteps=0 的旧形状：`runLoop` 的 `if (step >= maxSteps) break@inner` 在并入点
        // **之前** → STEER 永不并入，`runUser` 收尾的 sweep 空跑 `runLoop`，回合永不结束。
        // 钳制发生在存储层（`SettingsStore.maxSteps` ≥1），内核从此见不到 0；
        // 本用例把「最小值仍能收尾 + 通知仍能并入」钉住 —— 对着活锁会超时变红。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta("one")), ScriptedTurn.text("ack-two")),
            id = "loop-maxsteps-transport",
        )
        val h = harness(
            transport,
            sessionId = "loop-maxsteps-session",
            assemblyProvider = { AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "", maxSteps = 1)) },
        )
        try {
            h.session.send("hi")
            await("第一次请求应已发出") { transport.requests.isNotEmpty() }
            // 忙时投系统通知（与后台任务投递同一条路）：入 STEER，abort 的 clear 保留它。
            h.session.steer("后台通知", systemNote = true)
            h.session.abort()

            await("通知应在取消收尾后被并入并发出第二次请求") { transport.requests.size >= 2 }
            assertTrue(
                "STEER 通知必须在第二次请求里",
                transport.requests[1].messages.any { it.isSystemNote && it.text.contains("后台通知") },
            )
            await("回合必须结束（maxSteps=1 不得活锁）") { !h.session.isBusy }
        } finally {
            h.close()
        }
    }

    @Test
    fun errorStopSanitizesDanglingToolCall() = runBlocking {
        // 传输层吐到一半（工具调用参数还没收完）就 Failure：回合以 ERROR 收尾，历史里
        // 会留下参数残缺、且没有 tool_result 的悬空调用。若不修复，下一回合请求会被
        // provider 直接拒绝（只能重开会话自救）。
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.failure(
                    "connection reset",
                    before = listOf(
                        StreamEvent.ToolCallStarted(0, "call-1", "missing"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"path\":\"a"),
                    ),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "loop-transport",
        )
        val h = harness(transport, sessionId = "loop-session")
        try {
            h.runTurn("first")

            val assistant = h.session.messages.value.first { it.role == Role.ASSISTANT }
            assertEquals("半截失败应以 ERROR 收尾", StopReason.ERROR, assistant.stopReason)
            assertTrue("悬空工具调用应被清掉", assistant.toolCalls.isEmpty())

            h.runTurn("second")
            val sent = h.requests[1].messages
            assertTrue(
                "下一回合请求不得携带悬空工具调用",
                sent.filter { it.role == Role.ASSISTANT }.all { it.toolCalls.isEmpty() },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun errorTurnKeepsReadableErrorTextInHistory() = runBlocking {
        // 被误解的契约，单独钉一条：半截失败的回合**保留可读的错误正文**，
        // 不会被统一换成中断提示。按「统一成中断提示」去改内核，会把这一轮为什么失败
        // 从用户眼前抹掉（AGENTS.md §4：错误文本由内核直出、UI 不二次翻译）。
        // 中断提示只在两种情况下出现：删掉悬空调用后回合被**掏空**
        // （repairToolPairing 的 kept.isEmpty() 分支），或 ABORTED 回合的请求投影
        // （modelVisibleHistory）—— 两者都不适用于带着错误正文的 ERROR 回合。
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.failure(
                    "connection reset",
                    before = listOf(
                        StreamEvent.ToolCallStarted(0, "call-1", "missing"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"path\":\"a"),
                    ),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "loop-transport",
        )
        val h = harness(transport, sessionId = "loop-session")
        try {
            h.runTurn("first")

            val assistant = h.session.messages.value.first { it.role == Role.ASSISTANT }
            assertEquals("本用例的前提是这一回合以 ERROR 收尾", StopReason.ERROR, assistant.stopReason)
            assertTrue(
                "错误正文必须留在历史里，用户才看得到失败原因",
                assistant.text.contains("connection reset"),
            )
            assertNotEquals(
                "ERROR 回合不得被换成中断提示",
                AgentSession.INTERRUPTED_NOTICE,
                assistant.text,
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun contextOverflowReachesCompactionBranch() = runBlocking {
        // 静默溢出：provider 正常回 STOP，但输入用量越过窗口。调用点若把判定嵌在 ERROR
        // 分支里，这条路径永远不可达 —— 本用例钉住「STOP + 超窗用量 → 触发压缩」。
        val contextWindow = 10
        val strategy = RecordingStrategy()
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.text("one"),
                ScriptedTurn.text("two"),
                ScriptedTurn.silentOverflow(contextWindow),
                ScriptedTurn.text("after"),
            ),
            id = "loop-transport",
        )
        val replaced = mutableListOf<List<ChatMessage>>()
        val h = harness(
            transport = transport,
            sessionId = "loop-session",
            contextStrategyProvider = { strategy },
            contextWindowProvider = { contextWindow },
            onReplaceHistory = { replaced += it },
        )
        try {
            h.runTurn("first")
            h.runTurn("second")
            h.runTurn("third")

            assertEquals(
                "静默溢出必须进入压缩分支",
                CompactionTrigger.CONTEXT_OVERFLOW,
                strategy.triggers.last(),
            )
            assertTrue("压缩应重写历史并通知宿主", replaced.isNotEmpty())
            assertTrue(
                "重写后的历史应以检查点开头",
                replaced.last().first().isCompaction,
            )
            assertEquals("压缩后应继续完成本回合", 4, h.requests.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun lengthZeroOutputReachesOverflowBranch() = runBlocking {
        // 另一种形态：stopReason = LENGTH 且零输出（服务端截断输入）。若只按
        // 「LENGTH 就补截断结果」提前退出，会永久卡在同一个超窗上下文里。
        val contextWindow = 10
        val strategy = RecordingStrategy()
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.text("one"),
                ScriptedTurn.lengthZeroOutput(contextWindow),
                ScriptedTurn.text("after"),
            ),
            id = "loop-transport",
        )
        val replaced = mutableListOf<List<ChatMessage>>()
        val h = harness(
            transport = transport,
            sessionId = "loop-session",
            contextStrategyProvider = { strategy },
            contextWindowProvider = { contextWindow },
            onReplaceHistory = { replaced += it },
        )
        try {
            h.runTurn("first")
            h.runTurn("second")

            assertEquals(
                "LENGTH + 零输出必须进入压缩分支",
                CompactionTrigger.CONTEXT_OVERFLOW,
                strategy.triggers.last(),
            )
            assertTrue("压缩应重写历史并通知宿主", replaced.isNotEmpty())
        } finally {
            h.close()
        }
    }

    @Test
    fun failedSummaryStillRewritesHistory() = runBlocking {
        // 摘要模型自己失败（鉴权过期、端点抖动）时，历史当前会原样回滚：上下文仍超窗，
        // 下一回合再次溢出、再次失败 —— 会话永久卡死在这条路径上。
        // 期望：失败也要落一个「机械检查点」（不调摘要模型，按 token 预算硬切），且异常
        // 对用户可见、不被静默吞掉。
        val contextWindow = 10
        val strategy = RecordingStrategy(failSummary = true)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.text("one"),
                ScriptedTurn.silentOverflow(contextWindow),
            ),
            id = "loop-transport",
        )
        val replaced = mutableListOf<List<ChatMessage>>()
        val h = harness(
            transport = transport,
            sessionId = "loop-session",
            contextStrategyProvider = { strategy },
            contextWindowProvider = { contextWindow },
            onReplaceHistory = { replaced += it },
        )
        try {
            h.runTurn("first")
            h.runTurn("second")

            assertFalse("摘要失败不应把会话卡在忙状态", h.session.isBusy)
            assertTrue(
                "摘要失败后历史仍应由机械检查点重写，而不是原样回滚",
                replaced.isNotEmpty(),
            )
            // 机械检查点：不调模型、正文是常量，因此逐字节确定（缓存前缀的确定性要求）。
            assertEquals(
                Compaction.MECHANICAL_CHECKPOINT,
                Compaction.unwrapSummary(replaced.last().first().text),
            )
            assertEquals(
                "机械检查点应作为首条替换历史",
                true,
                replaced.last().first().isCompaction,
            )
            // 降级必须可见：失败原因进诊断，否则用户只看到「上下文忽然少了一截」。
            val reason = h.diagnostics.compactionFailures.value["loop-session"]
            assertTrue("摘要失败必须留下诊断，实际：$reason", reason?.contains("IllegalStateException") == true)
        } finally {
            h.close()
        }
    }

    /** 逐条消息的序列化结果：比较的是真正上线的字节，不是内容相似度。 */
    private fun serialize(messages: List<ChatMessage>): List<String> =
        messages.map { Json.encodeToString(ChatMessage.serializer(), it) }

    /**
     * 只记录「何时、以什么原因」被要求压缩的策略。计划固定为「保持最后一条，其余摘要」，
     * 摘要可注入失败。
     */
    private class RecordingStrategy(private val failSummary: Boolean = false) : ContextStrategy {
        override val id: String = "loop-test-strategy"
        val triggers: MutableList<CompactionTrigger> = mutableListOf()

        override fun shouldCompact(
            contextTokens: Int,
            contextWindow: Int,
            settings: CompactionSettings,
        ): Boolean = false

        override fun plan(
            messages: List<ChatMessage>,
            settings: CompactionSettings,
            trigger: CompactionTrigger,
        ): CompactionPlan? {
            triggers += trigger
            return CompactionPlan(
                summarized = messages.dropLast(1),
                kept = messages.takeLast(1),
                previousSummary = null,
                tokensBefore = Compaction.estimateContextTokens(messages),
            )
        }

        override suspend fun summarize(
            model: String,
            config: ProviderConfig,
            plan: CompactionPlan,
            customInstructions: String?,
            reserveTokens: Int,
            sessionId: String?,
        ): String {
            if (failSummary) error("summary endpoint rejected the request")
            return "context checkpoint"
        }

        override fun wrapSummary(plan: CompactionPlan, summary: String): String =
            Compaction.wrapSummary(summary)
    }
}
