package dev.kair.app.core.agent

import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U48「回合终止可见性」：内核静默出口的可枚举原因。
 *
 * 任务文档：`docs/tasks/2026-11-06-回合终止可见性.md`。这里只钉四个静默出口里
 * 可从会话层观察的三个：步数用尽（①）、空回合被丢弃（②）、会话关闭区分（③）。
 * 每条用例的判据都是可数的事实：TurnEnd.stopReason 的枚举值、系统提示的有无、
 * 诊断计数 —— 不猜「看起来像停了」。
 */
class TurnTerminationTest {

    /** 步数用尽的回合必须报 STEP_LIMIT，且给用户一条可见的提示（今天静默 break，什么都不留）。 */
    @Test
    fun `step limit termination reports STEP_LIMIT and leaves a visible note`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call-1", "echo"),
                        StreamEvent.ToolCallArgsDelta(0, "{}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
            ),
            id = "termination-step-limit-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-step-limit",
            tools = ToolRegistry().register(EchoTool()),
            assemblyProvider = {
                AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "", maxSteps = 1))
            },
            onEvent = { events += it },
        )
        try {
            h.runTurn("go")

            await("回合结束事件") { events.any { it is AgentEvent.TurnEnd } }
            val last = events.filterIsInstance<AgentEvent.TurnEnd>().last()
            assertEquals(StopReason.STEP_LIMIT, last.stopReason)
            assertTrue(
                "步数用尽必须给用户可见提示",
                h.session.messages.value.any { it.isSystemNote && it.text.contains("步数") },
            )
        } finally {
            h.close()
        }
    }

    /**
     * 空回合（模型只回结束原因、无正文无工具）被无痕删除：必须报 EMPTY_RESPONSE、
     * 留诊断，并自动重试一次；重试仍空则给一条可见提示（不是静默待机）。
     */
    @Test
    fun `empty assistant response retries once then reports EMPTY_RESPONSE with a note`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(listOf(StreamEvent.Completed(StopReason.STOP))),
                ScriptedTurn(listOf(StreamEvent.Completed(StopReason.STOP))),
            ),
            id = "termination-empty-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-empty",
            onEvent = { events += it },
        )
        try {
            h.runTurn("go")

            await("回合结束事件") { events.any { it is AgentEvent.TurnEnd } }
            val last = events.filterIsInstance<AgentEvent.TurnEnd>().last()
            assertEquals(StopReason.EMPTY_RESPONSE, last.stopReason)
            assertEquals("空回合应自动重试一次", 2, transport.requests.size)
            assertEquals(
                "两次空回合都要留诊断",
                2,
                h.diagnostics.emptyResponses.value["termination-empty"] ?: 0,
            )
            assertTrue(
                "重试后仍空必须给用户可见提示",
                h.session.messages.value.any { it.isSystemNote && it.text.contains("空回复") },
            )
        } finally {
            h.close()
        }
    }

    /**
     * 只有思考、没有正文的干净收尾不是回答：自动重试一次；第二次给出正文就正常收尾。
     * 这是真机 qwen 网关「推理后直接 EOS」的形态 —— 首发的思考不该落盘，也不该留空提示。
     */
    @Test
    fun `thinking-only stop is retried and the second answer wins`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(StreamEvent.ReasoningDelta("想想看……"), StreamEvent.Completed(StopReason.STOP)),
                ),
                ScriptedTurn.text("28"),
            ),
            id = "termination-thinking-retry-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-thinking-retry",
            onEvent = { events += it },
        )
        try {
            h.runTurn("go")

            await("回合结束事件") { events.any { it is AgentEvent.TurnEnd } }
            assertEquals(StopReason.STOP, events.filterIsInstance<AgentEvent.TurnEnd>().last().stopReason)
            assertEquals("思考-only 应重发一次", 2, transport.requests.size)
            assertTrue(
                "第二次的正文应落盘",
                h.session.messages.value.any { it.role == Role.ASSISTANT && it.text == "28" },
            )
            assertFalse(
                "重试成功后不应出现空回复提示",
                h.session.messages.value.any { it.isSystemNote && it.text.contains("空回复") },
            )
        } finally {
            h.close()
        }
    }

    /** 用户点停止 ≠ 空回复：中止走取消路径，不许触发空回复重试，也不许留空回复提示。 */
    @Test
    fun `user abort does not trigger the empty-response retry`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.ReasoningDelta("想……"))),
            id = "termination-abort-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-abort",
            onEvent = { events += it },
        )
        try {
            h.session.send("go")
            await("应已开始流式") { h.session.messages.value.any { it.role == Role.ASSISTANT } }
            h.session.abort()
            h.awaitIdle()

            assertEquals("用户中止不得重发", 1, transport.requests.size)
            assertEquals(StopReason.ABORTED, events.filterIsInstance<AgentEvent.TurnEnd>().last().stopReason)
            assertFalse(
                "用户中止不应出现空回复提示",
                h.session.messages.value.any { it.isSystemNote && it.text.contains("空回复") },
            )
        } finally {
            h.close()
        }
    }

    /** 网络/协议失败（ERROR）与空回复是两回事：不在这里重发，也不加空回复提示。 */
    @Test
    fun `stream failure after thinking is not retried as empty`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.failure("boom", before = listOf(StreamEvent.ReasoningDelta("想……"))),
            ),
            id = "termination-failure-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-failure",
            onEvent = { events += it },
        )
        try {
            h.runTurn("go")

            await("回合结束事件") { events.any { it is AgentEvent.TurnEnd } }
            assertEquals(StopReason.ERROR, events.filterIsInstance<AgentEvent.TurnEnd>().last().stopReason)
            assertEquals("失败不得当成空回复重发", 1, transport.requests.size)
            assertFalse(
                "失败不应出现空回复提示",
                h.session.messages.value.any { it.isSystemNote && it.text.contains("空回复") },
            )
        } finally {
            h.close()
        }
    }

    /**
     * 会话关闭中断 → SESSION_CLOSED（V5）。用户点停止 → ABORTED 的既有行为不变（V4，
     * `AgentSessionEventTest.aborted turn still emits TurnEnd` 钉住）——两者此前都报
     * 「aborted」，界面上无法区分。
     */
    @Test
    fun `closing the session mid-turn reports sessionClosed, not user abort`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta("thinking..."))),
            id = "termination-close-transport",
        )
        val events = mutableListOf<AgentEvent>()
        val h = harness(
            transport,
            sessionId = "termination-close",
            onEvent = { events += it },
        )
        try {
            h.session.send("hang")

            await("应已开始流式") { h.session.messages.value.any { it.role == Role.ASSISTANT } }
            h.session.discard()
            await("关闭也要广播回合结束") { events.any { it is AgentEvent.TurnEnd } }
            val last = events.filterIsInstance<AgentEvent.TurnEnd>().last()
            assertEquals(StopReason.SESSION_CLOSED, last.stopReason)
        } finally {
            h.close()
        }
    }

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "test echo tool"
        override val parameters: JsonObject = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult("ok")
    }
}
