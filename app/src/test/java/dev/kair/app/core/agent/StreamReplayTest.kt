package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.ReplayTransport
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.loadTrace
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回放回归：fixture → [ReplayTransport] → 完整 [AgentSession] 路径（接收事件 → 组装
 * assistant 回合 → 落盘），断言最终生成的 [dev.kair.app.core.model.ChatMessage]。
 *
 * 覆盖：普通文本、工具调用、正文 + 工具、推理段落交替、鉴权失败（不重试）。
 * 空响应重试与录制机制见 `core/ai/StreamTraceTest`；中断收尾见 `AgentSessionInterruptTest`。
 */
class StreamReplayTest {

    private var seq = 0

    private fun newSession(trace: List<StreamEvent>, maxSteps: Int = 1): Pair<AgentSession, ReplayTransport> {
        val transport = ReplayTransport(trace, id = "replay-${seq++}")
        ProviderTransports.register(transport)
        val session = AgentSession(
            diagnostics = Diagnostics(),
            id = "replay-session-$seq",
            workspace = File("."),
            tools = ToolRegistry(),
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "", maxSteps = maxSteps)),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                )
            },
        )
        return session to transport
    }

    private fun <T> withSession(
        resource: String,
        maxSteps: Int = 1,
        block: (AgentSession, ReplayTransport) -> T,
    ): T {
        val (session, transport) = newSession(loadTrace(resource), maxSteps)
        return try {
            runBlocking { session.prompt("go") }
            block(session, transport)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    private fun AgentSession.assistant(): dev.kair.app.core.model.ChatMessage =
        messages.value.last { it.role == Role.ASSISTANT }

    @Test
    fun `plain text trace assembles one text part and usage`() {
        withSession("stream-traces/openai/text.jsonl") { session, _ ->
            val assistant = session.assistant()
            assertEquals("Hello world", assistant.text)
            assertEquals(StopReason.STOP, assistant.stopReason)
            assertEquals(12, assistant.usage?.input)
            assertEquals(2, assistant.usage?.output)
        }
    }

    @Test
    fun `assistant snapshot records the origin endpoint for thinking replay`() {
        // 来源键的写入面（U76）：落库不带 providerId / providerHost，下一回合的历史思考
        // 就一律按 foreign 处理 —— 判据再对也等于没接。主机名是归一化后的，不是 baseUrl。
        withSession("stream-traces/openai/text.jsonl") { session, _ ->
            val assistant = session.assistant()
            assertEquals("test-model", assistant.model)
            assertEquals("test-provider", assistant.providerId)
            assertEquals("example.test", assistant.providerHost)
        }
    }

    @Test
    fun `tool call trace assembles authoritative id name and arguments`() {
        withSession("stream-traces/openai/tool-call.jsonl") { session, _ ->
            val assistant = session.assistant()
            // 不支持推理的模型直接输出工具调用：段落里只有 ToolCall，不会凭空补正文。
            assertEquals(listOf(Part.ToolCall::class), assistant.parts.map { it::class })
            assertEquals(1, assistant.toolCalls.size)
            val call = assistant.toolCalls.single()
            assertEquals("call_1", call.id)
            assertEquals("echo", call.name)
            assertEquals("""{"text":"hi"}""", call.arguments)

            val result = session.messages.value.last { it.role == Role.TOOL_RESULT }.toolResults.single()
            assertEquals("call_1", result.toolCallId)
            assertTrue(result.isError)
        }
    }

    @Test
    fun `text before tool call keeps part order`() {
        withSession("stream-traces/openai/text-then-tool.jsonl") { session, _ ->
            val parts = session.assistant().parts
            assertEquals(listOf(Part.Text::class, Part.ToolCall::class), parts.map { it::class })
            assertEquals("Let me check. ", (parts[0] as Part.Text).text)
        }
    }

    @Test
    fun `mixed reasoning text and tool trace keeps part order`() {
        withSession("stream-traces/anthropic/mixed.jsonl") { session, _ ->
            val parts = session.assistant().parts
            assertEquals(
                listOf(Part.Thinking::class, Part.Text::class, Part.ToolCall::class),
                parts.map { it::class },
            )
            assertEquals("Let me think.", (parts[0] as Part.Thinking).thinking)
            assertEquals("Checking now.", (parts[1] as Part.Text).text)
            assertEquals("toolu_1", (parts[2] as Part.ToolCall).id)
        }
    }

    @Test
    fun `alternating reasoning and text segments do not merge`() {
        withSession("stream-traces/openai/reasoning.jsonl") { session, _ ->
            val parts = session.assistant().parts
            assertEquals(
                listOf(
                    Part.Thinking::class, Part.Text::class,
                    Part.Thinking::class, Part.Text::class,
                ),
                parts.map { it::class },
            )
            assertEquals("Thinking one. ", (parts[0] as Part.Thinking).thinking)
            assertEquals("Answer one. ", (parts[1] as Part.Text).text)
            assertEquals("Thinking two.", (parts[2] as Part.Thinking).thinking)
            assertEquals("Answer two.", (parts[3] as Part.Text).text)
            // 推理不混入正文。
            assertTrue(session.assistant().text.none { it.isDigit() })
        }
    }

    @Test
    fun `auth failure is surfaced once as raw server text and never retried`() {
        withSession("stream-traces/openai/auth-error.jsonl") { session, transport ->
            val assistant = session.assistant()
            assertTrue(assistant.isError)
            assertEquals(StopReason.ERROR, assistant.stopReason)
            assertTrue("应展示服务端原文", assistant.text.contains("Incorrect API key provided"))
            assertEquals("非瞬态错误只请求一次", 1, transport.calls)
        }
    }

    @Test
    fun `reasoning control chars never reach the transcript`() {
        // 帧协议残留（长度前缀、NUL 分隔符、DEL）应在传输层入口剔净：否则界面显示乱码，
        // 且这些字符会随历史永久回灌给模型。
        withSession("stream-traces/openai/reasoning-control-chars.jsonl") { session, _ ->
            val thinking = session.assistant().parts.filterIsInstance<Part.Thinking>().single().thinking
            assertEquals("Hello world", thinking)
            assertEquals("OK", session.assistant().text)
        }
    }

    @Test
    fun `real longcat reasoning and text trace assembles ordered parts`() {
        withSession("stream-traces/longcat/text-reasoning.jsonl") { session, _ ->
            val parts = session.assistant().parts
            assertEquals(listOf(Part.Thinking::class, Part.Text::class), parts.map { it::class })
            assertTrue((parts[0] as Part.Thinking).thinking.contains("The user requested"))
            assertEquals("OK", (parts[1] as Part.Text).text)
        }
    }

    @Test
    fun `real longcat tool call trace assembles the call`() {
        withSession("stream-traces/longcat/tool-call.jsonl") { session, _ ->
            // 真实模型是「推理 → 工具」，中间没有正文：允许，且不生成空的 Text 段。
            assertEquals(listOf(Part.Thinking::class, Part.ToolCall::class), session.assistant().parts.map { it::class })
            val call = session.assistant().toolCalls.single()
            assertEquals("get_weather", call.name)
            assertTrue("端点应给出真实调用 id", call.id.startsWith("call_"))
            assertTrue("参数应保留城市", call.arguments.contains("Beijing"))
        }
    }
}
