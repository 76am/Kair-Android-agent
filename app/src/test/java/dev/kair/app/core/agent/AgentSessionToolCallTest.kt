package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import dev.kair.app.ui.screens.chat.matchToolResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * 流式工具调用累积的回归：ToolCallStarted 必须修正 ArgsDelta 先到时留下的
 * 占位 id/name（端点可能先吐 arguments 再吐 id），否则占位 id 漏进转录，
 * 结果按 id 关联时对不上。
 */
class AgentSessionToolCallTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) =
            ToolResult(args["text"].toString())
    }

    private class ScriptedTransport(private val responses: ArrayDeque<List<StreamEvent>>) : ProviderTransport {
        override val id = "test-scripted-transport"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            responses.removeFirst().asFlow()
    }

    /** 与 [ScriptedTransport] 相同，另记下每次请求：用于断言「真正发出去的字节」。 */
    private class RecordingScriptedTransport(
        private val responses: ArrayDeque<List<StreamEvent>>,
    ) : ProviderTransport {
        override val id = "test-recording-transport"
        val requests = mutableListOf<ChatRequest>()
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> {
            requests += request
            return responses.removeFirst().asFlow()
        }
    }

    /** 进入执行后一直挂着，直到被取消：用于验证「中止时补写结果」而非删掉工具卡。 */
    private class SlowTool : Tool {
        override val name = "slow"
        override val description = "slow"
        override val parameters = schemaOf(props())
        val started = CompletableDeferred<Unit>()
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            started.complete(Unit)
            delay(10_000)
            return ToolResult("finished")
        }
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

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            // send 是异步 launch：先等 run 真正启动（用户消息入列），再等它跑完。
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    @Test
    fun startedFixesPlaceholderLeftByEarlierArgsDelta() = kotlinx.coroutines.runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    // 先到参数增量、后到 Started：老实现会把占位 id 漏进转录。
                    listOf(
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.ToolCallStarted(0, "real-1", "echo"),
                        StreamEvent.Completed("toolUse"),
                    ),
                    listOf(
                        StreamEvent.TextDelta("done"),
                        StreamEvent.Completed("stop"),
                    ),
                ),
            ),
        )
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val messages = session.messages.value
            val assistant = messages.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            assertEquals("real-1", assistant.toolCalls[0].id)
            assertEquals("echo", assistant.toolCalls[0].name)

            val result = messages.first { it.role == Role.TOOL_RESULT }.toolResults[0]
            assertEquals("real-1", result.toolCallId)

            // UI 按回合匹配也应能对上这次调用。
            val matched = matchToolResults(messages).getValue(assistant.id)
            assertEquals(result, matched[0])
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun callsWithoutIdsAcrossTurnsStayDistinct() = kotlinx.coroutines.runBlocking {
        // 端点只吐参数增量、不回 id 也不发 Started/Completed：两回合的占位 id
        // 必须不同，否则结果按 id 关联会互相顶掉（老实现两回合都是 "call_0"）。
        val transport = object : ProviderTransport {
            override val id = "test-no-id-transport"
            private var turn = 0
            override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> {
                turn++
                return if (turn <= 2) {
                    listOf(
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"n$turn\"}"),
                        StreamEvent.Completed("toolUse"),
                    ).asFlow()
                } else {
                    listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")).asFlow()
                }
            }
        }
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            val messages = session.messages.value
            val assistants = messages.filter { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            assertEquals(2, assistants.size)
            val id1 = assistants[0].toolCalls[0].id
            val id2 = assistants[1].toolCalls[0].id

            val matched1 = matchToolResults(messages).getValue(assistants[0].id)[0]
            val matched2 = matchToolResults(messages).getValue(assistants[1].id)[0]
            // 各回合的结果按各自的 id 关联，互不串线。
            assertEquals(id1, matched1?.toolCallId)
            assertEquals(id2, matched2?.toolCallId)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun abortedPartialToolCallIsDiscardedFromHistory() = kotlinx.coroutines.runBlocking {
        // 端点吐了一半参数就卡住，模拟「用户点停止时参数还没输出完」。残缺的 tool_call
        // 若留在历史里，下一回合上传会让 provider 报错 —— 收尾时必须把它清掉。
        val transport = object : ProviderTransport {
            override val id = "test-abort-transport"
            override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
                emit(StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"par"))
                delay(10_000)
            }
        }
        val session = newSession(transport)
        try {
            session.send("go")
            withTimeout(5_000) {
                while (session.messages.value.none { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }) delay(20)
            }
            session.abort()
            withTimeout(5_000) {
                while (session.messages.value.any { it.toolCalls.isNotEmpty() }) delay(20)
            }

            val messages = session.messages.value
            assertTrue("残缺 tool_call 不应留在历史里", messages.none { it.toolCalls.isNotEmpty() })
            assertTrue(
                "应写入中断提示",
                messages.any { it.role == Role.ASSISTANT && it.text.contains(AgentSession.INTERRUPTED_NOTICE) },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun loadHistoryRepairsDanglingCallAndOrphanResult() = kotlinx.coroutines.runBlocking {
        // 旧会话可能残留「悬空 tool_call」和「无主 tool_result」（多次中断叠加、占位 id 对不上）。
        // 开/重启会话时必须修掉，否则之后每一回合的请求都会被 provider 拒绝。
        val transport = ScriptedTransport(ArrayDeque<List<StreamEvent>>())
        val session = newSession(transport)
        try {
            val history = listOf(
                ChatMessage(role = Role.USER, parts = listOf(Part.Text("go"))),
                ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.ToolCall("c1", "echo", "{}"))), // 悬空
                ChatMessage(role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult("ghost", "echo", "x"))), // 无主
                ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.ToolCall("c2", "echo", "{}"))),
                ChatMessage(role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult("c2", "echo", "ok"))),
            )
            session.loadHistory(history)

            val messages = session.messages.value
            // 悬空调用被清掉，该回合用中断提示替换正文。
            assertTrue(
                "悬空回合应留下中断提示",
                messages.any { it.role == Role.ASSISTANT && it.text.contains(AgentSession.INTERRUPTED_NOTICE) },
            )
            // 无主结果整条被丢弃。
            assertTrue(
                "无主 tool_result 应被丢弃",
                messages.none { it.toolResults.any { r -> r.toolCallId == "ghost" } },
            )
            // 配对完好的回合原样保留。
            assertEquals("c2", messages.first { it.toolCalls.isNotEmpty() }.toolCalls[0].id)
            assertTrue(messages.any { it.toolResults.any { r -> r.toolCallId == "c2" } })
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun invalidToolArgsAreNormalizedBeforeTheNextRequest() = kotlinx.coroutines.runBlocking {
        // 模型吐出的参数不是合法 JSON（未转义引号 / 被输出上限截断）时，OpenAI 兼容端点
        // 会原样回传 `arguments` 字符串；端点解析失败就把整轮请求吃掉（空响应 / 非法格式），
        // 而且此后每回合都失败。历史与界面保留原文，但**发出去的那份**必须已规范成 `{}`。
        val transport = RecordingScriptedTransport(
            ArrayDeque(
                listOf(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call_1", "echo"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\": \"a\" \"b\"}"),
                        StreamEvent.Completed("toolUse"),
                    ),
                    listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport)
        try {
            session.send("go")
            awaitIdle(session)

            // 转录保留模型原文，用户仍能看到它到底吐了什么。
            val assistant = session.messages.value.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            assertEquals("{\"text\": \"a\" \"b\"}", assistant.toolCalls[0].arguments)
            // 第一次工具执行按「非法参数」返回错误结果（既有行为）。
            assertTrue(session.messages.value.first { it.role == Role.TOOL_RESULT }.toolResults[0].isError)

            // 第二次请求里的同一条调用已被规范成合法对象。
            val sent = transport.requests[1].messages.first { it.toolCalls.isNotEmpty() }.toolCalls[0]
            assertEquals("{}", sent.arguments)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun loadHistoryRepairsInvalidToolArguments() = kotlinx.coroutines.runBlocking {
        // 旧会话（本次修复之前产生的）里可能已经躺着非法参数；开 / 重启会话时一并修好并落盘，
        // 用户不必再「撤回重发」才能脱离连续报错。
        val transport = ScriptedTransport(ArrayDeque<List<StreamEvent>>())
        val session = newSession(transport)
        try {
            session.loadHistory(
                listOf(
                    ChatMessage(role = Role.USER, parts = listOf(Part.Text("go"))),
                    ChatMessage(
                        role = Role.ASSISTANT,
                        parts = listOf(Part.ToolCall("c1", "echo", "{\"text\": \"a\" \"b\"}")),
                        stopReason = StopReason.TOOL_USE,
                    ),
                    ChatMessage(
                        role = Role.TOOL_RESULT,
                        parts = listOf(Part.ToolResult("c1", "echo", "Invalid arguments JSON", isError = true)),
                    ),
                ),
            )

            assertEquals("{}", session.messages.value.first { it.toolCalls.isNotEmpty() }.toolCalls[0].arguments)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun loadHistoryStripsControlCharsFromUserText() = kotlinx.coroutines.runBlocking {
        // 用户从缓存面板 / 终端粘贴回来的 `\u0000` / `\u0001` 分隔符会永久留在历史里并
        // 回灌给模型；开 / 重启会话时在历史里修掉，界面与请求前缀随之干净。
        val transport = ScriptedTransport(ArrayDeque<List<StreamEvent>>())
        val session = newSession(transport)
        try {
            session.loadHistory(
                listOf(
                    ChatMessage(role = Role.USER, parts = listOf(Part.Text("ASSISTANT\u0000h\u0000t\u0001USER"))),
                    // assistant 的正文可能带厂商签名，不能改写。
                    ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text("ok\u0000"))),
                ),
            )

            val messages = session.messages.value
            assertEquals("ASSISTANThtUSER", messages[0].text)
            assertEquals("ok\u0000", messages[1].text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun controlCharsInUserInputAreStrippedAtIngestion() = kotlinx.coroutines.runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed("stop")))),
        )
        val session = newSession(transport)
        try {
            session.send("vis\u0000ible\u0001")
            awaitIdle(session)

            assertEquals("visible", session.messages.value.first { it.role == Role.USER }.text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun abortDuringToolExecutionRecordsTheCancellationInsteadOfDroppingTheCall() =
        kotlinx.coroutines.runBlocking {
            // 用户点停止时工具正在执行：历史必须保留 tool_call 与配对结果（写明被中止），
            // 否则配对修复会把调用删掉 —— 界面工具卡凭空消失、模型也不知道发生了什么。
            val transport = ScriptedTransport(
                ArrayDeque(
                    listOf(
                        listOf(
                            StreamEvent.ToolCallStarted(0, "call-1", "slow"),
                            StreamEvent.ToolCallArgsDelta(0, "{}"),
                            StreamEvent.Completed("toolUse"),
                        ),
                    ),
                ),
            )
            ProviderTransports.register(transport)
            val slow = SlowTool()
            val registry = ToolRegistry().register(slow)
            val session = AgentSession(
                diagnostics = Diagnostics(),
                id = "abort-session",
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
            try {
                session.send("go")
                withTimeout(5_000) { slow.started.await() }
                session.abort()
                withTimeout(5_000) {
                    while (session.messages.value.none { it.role == Role.TOOL_RESULT }) delay(20)
                }

                val messages = session.messages.value
                val assistant = messages.first { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
                assertEquals("call-1", assistant.toolCalls.single().id)
                val result = messages.first { it.role == Role.TOOL_RESULT }.toolResults.single()
                assertEquals("call-1", result.toolCallId)
                assertTrue(result.isError)
                assertEquals(AgentSession.TOOL_ABORTED, result.content)
                // 配对完整时收尾不改写 assistant 正文。
                assertFalse(messages.any { it.text == AgentSession.INTERRUPTED_NOTICE })
            } finally {
                ProviderTransports.unregister(transport.id)
                session.abort()
            }
        }
}
