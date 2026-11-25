package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话侧的事件广播：`AgentEvent.TurnEnd`。
 *
 * 「每 N 回合整理一次记忆」这类插件的正确性全靠它 —— 漏一回合，计数就偏，插件要么
 * 整理得过勤（烧钱）、要么永远不触发。所以这里钉三件事：
 * 1. **纯聊天回合也必须广播**（没有工具调用的回合最容易被漏掉）；
 * 2. **被用户中止的回合也算一回合**（漏掉的话「聊到一半就停」的用户永远攒不满 N）；
 * 3. `turnCount` 数的是**用户回合**，注入的 system note 不算。
 */
class AgentSessionEventTest {

    private class PlainTransport : ProviderTransport {
        override val id = "test-event-transport"
        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            requests += request
            emit(StreamEvent.TextDelta("ok"))
            emit(StreamEvent.Completed("stop"))
        }
    }

    private class HangingTransport : ProviderTransport {
        override val id = "test-event-hanging"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            emit(StreamEvent.TextDelta("partial"))
            delay(10_000)
        }
    }

    /**
     * 第一回合停在 [release] 前，测试确认「运行中注入」的状态后再放行。
     * 同时记录同时在跑的流数量，用来证明没有并发第二个主循环。
     */
    private class GatedTransport : ProviderTransport {
        override val id = "test-event-gated"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        /** 每次请求的最后一条消息文本，用来确认排队消息进了下一次请求。 */
        val requests = CopyOnWriteArrayList<String>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), ::maxOf)
            requests += request.messages.lastOrNull()?.text.orEmpty()
            try {
                emit(StreamEvent.TextDelta("partial"))
                started.complete(Unit)
                release.await()
                // 第二轮流慢一点，把「请求已发出、回合还没收尾」的窗口撑到可观测：
                // 不这样的话，TurnEnd 的计数断言在快机器上总是恰好躲过竞态，
                // 在慢机器上偶尔读到 0（CI Linux 两次红就是这么来的）。
                if (requests.size >= 2) delay(50)
                emit(StreamEvent.Completed("stop"))
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }

    private lateinit var dir: File
    private val events = CopyOnWriteArrayList<AgentEvent>()

    private fun newSession(transport: ProviderTransport): AgentSession {
        ProviderTransports.register(transport)
        dir = Files.createTempDirectory("kair-events").toFile()
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-events",
            workspace = dir,
            tools = ToolRegistry(),
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                )
            },
            onEvent = { events.add(it) },
        )
    }

    private suspend fun await(what: String, predicate: () -> Boolean) {
        withTimeout(10_000) { while (!predicate()) delay(10) }
        assertTrue(what, predicate())
    }

    @After
    fun tearDown() {
        ProviderTransports.unregister("test-event-transport")
        ProviderTransports.unregister("test-event-hanging")
        ProviderTransports.unregister("test-event-gated")
    }

    @Test
    fun `pure chat turn emits TurnEnd with turn count and no tools`() = runBlocking {
        val transport = PlainTransport()
        val session = newSession(transport)
        try {
            session.send("hello")
            await("应收到回合结束事件") { events.any { it is AgentEvent.TurnEnd } }

            val e = events.filterIsInstance<AgentEvent.TurnEnd>().single()
            assertEquals("test-events", e.sessionId)
            assertEquals(1, e.turnCount)
            assertEquals("stop", e.stopReason)
            assertTrue("纯聊天没有工具", e.tools.isEmpty())
            assertEquals(dir.path, e.workspace)
        } finally {
            session.abort()
        }
    }

    @Test
    fun `turn count advances per user turn`() = runBlocking {
        val transport = PlainTransport()
        val session = newSession(transport)
        try {
            session.send("first")
            await("第一回合") { events.count { it is AgentEvent.TurnEnd } >= 1 }
            session.send("second")
            await("第二回合") { events.count { it is AgentEvent.TurnEnd } >= 2 }

            val counts = events.filterIsInstance<AgentEvent.TurnEnd>().map { it.turnCount }
            assertEquals(listOf(1, 2), counts)
        } finally {
            session.abort()
        }
    }

    @Test
    fun `injected system note does not count as a user turn`() = runBlocking {
        // 定时任务 / 插件注入的消息如果被算成用户回合，「每 N 回合」的计数会被自己的
        // 注入推着走 —— 最后变成每回合都触发。
        val transport = PlainTransport()
        val session = newSession(transport)
        try {
            session.send("note", systemNote = true)
            await("注入回合应广播") { events.any { it is AgentEvent.TurnEnd } }
            val e = events.filterIsInstance<AgentEvent.TurnEnd>().single()
            assertEquals("system note 不计入用户回合", 0, e.turnCount)
            assertEquals(Role.USER, session.messages.value.first().role)
            assertTrue("应标成 system note", session.messages.value.first().isSystemNote)
        } finally {
            session.abort()
        }
    }

    @Test
    fun `message sent while running is queued as follow-up instead of a second concurrent run`() = runBlocking {
        // 定时任务 / 插件注入会在 agent 运行中调用 send。这里钉住两件事：
        // 1. 不能并发起第二个主循环（否则两个回合同时改写历史 / 抢工具）；
        // 2. 注入的消息不能丢 —— 本轮结束后要作为新回合执行。
        val transport = GatedTransport()
        val session = newSession(transport)
        try {
            session.send("first")
            withTimeout(10_000) { transport.started.await() }

            session.send("scheduled", systemNote = true)

            assertEquals(SessionStatus.RUNNING, session.status.value)
            assertEquals("运行中只有一个流", 1, transport.concurrent.get())
            assertEquals(QueueKind.FOLLOW_UP, session.queue.value.single().kind)
            assertEquals("scheduled", session.queue.value.single().message.text)
            assertFalse(
                "排队消息不应立即进入历史",
                session.messages.value.any { it.text.contains("scheduled") },
            )

            transport.release.complete(Unit)
            await("排队消息应作为下一次请求发出") { transport.requests.size >= 2 }
            assertEquals("<system-note>\nscheduled\n</system-note>", transport.requests[1])
            assertTrue(
                "排队消息最终应进入历史",
                session.messages.value.any { it.text.contains("scheduled") },
            )
            assertEquals("任何时候都不超过一个主循环", 1, transport.maxConcurrent.get())
            // 队列在同一个 runUser 内消化：只广播一次回合结束，不是两次独立回合。
            // 必须先等回合彻底收尾（回到 Idle）再数：requests[1] 出现只说明第二个流开始了，
            // 它跑完之前 TurnEnd 还没发；直接数会读到 0（下面 50ms 的慢流就是把这个窗口
            // 钉住，CI Linux 的两次红都是它）。
            await("会话应回到空闲") { session.status.value == SessionStatus.IDLE }
            assertEquals(1, events.count { it is AgentEvent.TurnEnd })
        } finally {
            session.abort()
        }
    }

    @Test
    fun `aborted turn still emits TurnEnd`() = runBlocking {
        val transport = HangingTransport()
        val session = newSession(transport)
        try {
            session.send("hang")
            // 判据是"界面上已经出现助手气泡"（流式首帧到达即创建占位气泡），
            // 不是 transport 本身的状态 —— 后者恒真，等于没断言。
            await("应已开始流式") { session.messages.value.any { it.role == Role.ASSISTANT } }
            session.abort()
            await("中止也要广播回合结束（否则计数永远偏）") {
                events.any { it is AgentEvent.TurnEnd }
            }
            val e = events.filterIsInstance<AgentEvent.TurnEnd>().first()
            assertEquals("aborted", e.stopReason)
            assertEquals(1, e.turnCount)
        } finally {
            session.abort()
        }
    }
}
