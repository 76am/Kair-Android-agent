package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Role
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * 「重试某一条 agent 消息」= 撤回这一条**及其后续**，再基于剩下的结尾重新生成；
 * 撤回点之前的消息（含所属用户回合、更早的工具调用与结果）原样保留 ——
 * 这正是与「编辑用户消息重发」（改文本 + 丢整轮）的区别，也避免与编辑语义重叠。
 */
class AgentSessionRetryTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    private class ScriptedTransport(private val responses: ArrayDeque<List<StreamEvent>>) : ProviderTransport {
        override val id = "test-retry-transport"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            responses.removeFirst().asFlow()
    }

    private fun newSession(transport: ProviderTransport): AgentSession {
        ProviderTransports.register(transport)
        val registry = ToolRegistry().register(EchoTool())
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-session",
            workspace = File("."),
            tools = registry,
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                    tools = registry.all(),
                )
            },
        )
    }

    /** 一段「工具回合 → 最终回复」的脚本，外加一条重试后使用的最终回复。 */
    private fun twoTurnScript(retryReply: String) = ArrayDeque(
        listOf(
            listOf(StreamEvent.ToolCallStarted(0, "c1", "echo"), StreamEvent.Completed("toolUse")),
            listOf(StreamEvent.TextDelta("first"), StreamEvent.Completed("stop")),
            listOf(StreamEvent.TextDelta(retryReply), StreamEvent.Completed("stop")),
        ),
    )

    private suspend fun awaitText(session: AgentSession, text: String) {
        withTimeout(10_000) {
            // 先等重试这一轮真正跑起来，再等它结束并产出新内容。
            while (session.messages.value.lastOrNull()?.text != text || session.isBusy) delay(20)
        }
    }

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    @Test
    fun retryOnFinalReplyKeepsEarlierToolTurn() = runBlocking {
        val transport = ScriptedTransport(twoTurnScript("second"))
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val before = session.messages.value
            val final1 = before.last { it.role == Role.ASSISTANT && it.toolCalls.isEmpty() }
            val toolTurn = before.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }

            session.retryFrom(final1.id)
            awaitText(session, "second")

            val after = session.messages.value
            // 只撤回了最终回复：工具回合与其结果原样保留。
            assertTrue("工具回合应保留", after.any { it.id == toolTurn.id })
            assertTrue("工具结果应保留", after.any { it.role == Role.TOOL_RESULT })
            assertEquals("second", after.last().text)
            assertEquals("go", after.first { it.role == Role.USER }.text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun retryOnToolTurnDropsItsResultAndLater() = runBlocking {
        val transport = ScriptedTransport(twoTurnScript("second"))
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val toolTurn = session.messages.value.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }

            session.retryFrom(toolTurn.id)
            awaitText(session, "second")

            // 工具回合连同其结果一并撤回，只剩用户消息 + 重新生成的一条
            // （日期提示是 system note，不是用户回合，不参与这里的角色序列）。
            assertEquals(
                listOf(Role.USER, Role.ASSISTANT),
                session.messages.value.filterNot { it.isSystemNote }.map { it.role },
            )
            assertEquals("second", session.messages.value.last().text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun retryOnToolResultLeavesNoDanglingCall() = runBlocking {
        val transport = ScriptedTransport(twoTurnScript("second"))
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            // 撤回点正好落在「调用 → 结果」中间：不带配对修复的话，上方那条 tool_call 会
            // 留在历史里、却丢了结果，下一回合请求被 provider 直接拒绝。
            val result = session.messages.value.first { it.role == Role.TOOL_RESULT }
            session.retryFrom(result.id)
            awaitText(session, "second")

            val after = session.messages.value
            assertTrue(
                "撤回后不得残留无结果的 tool_call",
                after.none { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() },
            )
            assertEquals("second", after.last().text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }
}
