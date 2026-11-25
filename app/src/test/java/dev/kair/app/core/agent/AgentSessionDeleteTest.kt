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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * 「删除这条及之后」：从撤回点截断到末尾，**不撤回也不重跑**。
 *
 * 关键约束是工具配对的严格一一对应：撤回点落在配对中间时（例如直接删一条工具结果），
 * 前缀里悬空的 `tool_call` 会被换成中断提示，否则下一回合请求会被 provider 拒绝。
 */
class AgentSessionDeleteTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    private class ScriptedTransport(private val responses: ArrayDeque<List<StreamEvent>>) : ProviderTransport {
        override val id = "test-delete-transport"
        val requests = mutableListOf<ChatRequest>()
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> {
            requests += request
            return responses.removeFirst().asFlow()
        }
    }

    private fun newSession(transport: ProviderTransport): AgentSession {
        ProviderTransports.register(transport)
        val registry = ToolRegistry().register(EchoTool())
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-delete-session",
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

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    /** 删除现在是排到会话闸门后异步执行的（见 AgentSession.launchSerial），断言前先等效果落地。 */
    private suspend fun await(what: String, predicate: () -> Boolean) {
        withTimeout(10_000) { while (!predicate()) delay(20) }
        assertTrue(what, predicate())
    }

    /** 一段「工具回合 → 最终回复」的脚本。 */
    private fun toolThenReply() = ArrayDeque(
        listOf(
            listOf(StreamEvent.ToolCallStarted(0, "c1", "echo"), StreamEvent.Completed("toolUse")),
            listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")),
        ),
    )

    @Test
    fun deletingToolResultAlsoDropsItsPairedCall() = runBlocking {
        val transport = ScriptedTransport(toolThenReply())
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val before = session.messages.value
            val result = before.first { it.role == Role.TOOL_RESULT }
            val callTurn = before.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            val cut = before.indexOfFirst { it.id == result.id }

            session.deleteMessage(result.id)
            await("删除应已生效") { session.messages.value.none { it.id == result.id } }

            val after = session.messages.value
            assertTrue("工具结果应被删除", after.none { it.id == result.id })
            assertTrue("配对的那条 tool_call 应一并清掉", after.none { it.toolCalls.isNotEmpty() })
            assertTrue(
                "被掏空的回合换成中断提示（保持 assistant 角色）",
                after.any { it.id == callTurn.id && it.text == AgentSession.INTERRUPTED_NOTICE },
            )
            assertEquals("删除点之后的最终回复也应一并移除", cut, after.size)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun deletingUserMessageDoesNotRerun() = runBlocking {
        val transport = ScriptedTransport(ArrayDeque(listOf(listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")))))
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val user = session.messages.value.first { it.role == Role.USER }
            val countBefore = session.messages.value.size

            session.deleteMessage(user.id)
            await("删除应已生效") { session.messages.value.none { it.id == user.id } }

            val after = session.messages.value
            assertTrue("用户消息应被删除", after.none { it.id == user.id })
            assertEquals("从第一条删到末尾 = 清空历史", 0, after.size)
            assertTrue("删除前应确实有内容", countBefore > 0)
            await("删除任务应已收尾") { !session.isBusy }
            assertFalse("删除不触发重跑", session.isBusy)
            assertEquals("删除不发出新请求", 1, transport.requests.size)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /** 删除中间一条 = 只保留它之前的前缀，不产生缺口、不重跑。 */
    @Test
    fun deletingFromTheMiddleKeepsOnlyThePrefix() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    listOf(StreamEvent.TextDelta("one"), StreamEvent.Completed("stop")),
                    listOf(StreamEvent.TextDelta("two"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport)
        try {
            session.send("first")
            awaitIdle(session)
            session.send("second")
            awaitIdle(session)

            val before = session.messages.value
            val secondUser = before.last { it.role == Role.USER }
            val cut = before.indexOfFirst { it.id == secondUser.id }
            assertTrue("第二回合应在历史尾部", cut > 0)

            session.deleteMessage(secondUser.id)
            await("删除应已生效") { session.messages.value.none { it.id == secondUser.id } }

            assertEquals("只保留撤回点之前的前缀", before.take(cut), session.messages.value)
            assertEquals("删除不发出新请求", 2, transport.requests.size)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }
}
