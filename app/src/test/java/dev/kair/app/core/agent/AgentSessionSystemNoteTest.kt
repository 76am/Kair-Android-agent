package dev.kair.app.core.agent

import dev.kair.app.app.deliverToSession
import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 系统通知（后台任务完成通知）在**忙回合**里的投递（U18）：走 steer 的 systemNote 通道 →
 * 入 STEER 队列 → **下一步请求前**并入历史；abort 的 clearQueue 不得把它清没，
 * 停止收尾后由 ContinueTranscript 接上。
 *
 * 断言落在「真正发出去的字节」（第二次请求的 messages）上，不是界面状态。
 *
 * 后两条走真投递链路（`deliverToSession` → `sessionNoteSink`），前面两条只调 `steer`：
 * 少了后者，把 sink 从 `steer` 改回 `send`（= 回到 U18 修掉的老路）不会有任何用例变红。
 */
class AgentSessionSystemNoteTest {

    /** 进入执行后先开门（[started]）再拖一会儿：让 steer 确定落在「工具执行中」这个窗口。 */
    private class SlowTool : Tool {
        override val name = "slow"
        override val description = "slow"
        override val parameters = schemaOf(props())
        val started = CompletableDeferred<Unit>()
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            started.complete(Unit)
            delay(1_500)
            return ToolResult("finished")
        }
    }

    /** 记录每次请求；[hangFirst] = true 时第一条响应流挂住不结束（模拟连发工具的长回合）。 */
    private class RecordingTransport(
        private val responses: ArrayDeque<List<StreamEvent>>,
        private val hangFirst: Boolean = false,
    ) : ProviderTransport {
        // id 必须**逐实例唯一**：`abort()` 是异步的，收尾里起的续跑回合会按 id 回注册表取
        // 传输层。共用一个字面量时，上一条用例漏下的续跑会「借用」下一条用例的实例，
        // 表现是新会话永远等不到自己的请求（15s 超时）。
        override val id = "note-delivery-transport-${nextOrdinal.incrementAndGet()}"

        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            val index = requests.size
            requests += request
            responses.removeFirstOrNull()?.forEach { emit(it) }
            if (hangFirst && index == 0) awaitCancellation()
        }

        companion object {
            private val nextOrdinal = AtomicInteger()
        }
    }

    private fun newSession(
        transport: ProviderTransport,
        tool: Tool = SlowTool(),
        hooks: HookBus = HookBus(),
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentSession {
        ProviderTransports.register(transport)
        val registry = ToolRegistry().register(tool)
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "note-session",
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
            hooks = hooks,
            onEvent = onEvent,
        )
    }

    private suspend fun await(what: String, snapshot: () -> String = { "" }, predicate: () -> Boolean) {
        val t0 = System.nanoTime()
        try {
            withTimeout(15_000) { while (!predicate()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // 取证探针（不改行为，只在超时路径上分类）：再宽限 60s 分辨「迟到」还是「丢事件」。
            // 【丢事件】= 产品竞态（abort × 队列 × 收尾，U21 前置批的账）——本测试是它的探测器，
            // **不许为了绿把判定盖掉**（取证见 docs/tasks/2026-11-06-计时红两条取证与同步层收紧.md §1）。
            val late = try {
                withTimeout(60_000) { while (!predicate()) delay(20) }
                true
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            }
            org.junit.Assert.fail(
                "$what —— 15s 未成立；+60s 宽限后 " +
                    (if (late) "成立【迟到】，共 ${(System.nanoTime() - t0) / 1_000_000}ms" else "仍不成立【丢事件】") +
                    "；快照：${snapshot()}",
            )
        }
        assertTrue(what, predicate())
    }

    private val note = "[background task t1: finished with exit code 0 after 1s]"

    /**
     * 某次**请求**里带了几条该任务的通知。
     *
     * 按 `contains` 而不是 `startsWith`：装配时 system note 会被包上 `<system-note>` 标签
     * （`SystemNoteWire`），请求里的正文不是裸通知。存盘 / 队列侧才是裸正文，
     * 所以 [dev.kair.app.app.hasNote] 用 `startsWith` 判的是那两侧。
     */
    private fun ChatRequest.noteCount(taskId: String): Int =
        messages.count { it.isSystemNote && it.text.contains("[background task $taskId:") }

    /** 通知是否已进到**首条之后**的某一次请求里（首条是发通知之前就已发出的）。 */
    private fun noteArrived(transport: RecordingTransport): Boolean =
        transport.requests.drop(1).any { it.noteCount(TASK_ID) > 0 }

    @Test
    fun aSystemNoteSteeredMidTurnJoinsTheNextRequest() = runBlocking {
        val tool = SlowTool()
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call_1", "slow"),
                        StreamEvent.ToolCallArgsDelta(0, "{}"),
                        StreamEvent.Completed("toolUse"),
                    ),
                    listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport, tool)
        try {
            session.send("go")
            // 工具执行中 = 回合还在跑、下一步请求还没发：通知此刻入队，下一步就该带上它。
            withTimeout(15_000) { tool.started.await() }
            session.steer(note, systemNote = true)

            await("回合应收尾") { !session.isBusy }
            val second = transport.requests.getOrNull(1)
            assertTrue("应有第二次请求", second != null)
            assertTrue(
                "系统通知应在下一步请求前并入历史，而不是等回合结束",
                second!!.messages.any { it.isSystemNote && it.text.contains("t1") },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun aQueuedSystemNoteSurvivesAbortAndDrainsIntoTheContinueTurn() = runBlocking {
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    emptyList(), // 挂住的长回合：等 abort
                    listOf(StreamEvent.TextDelta("after stop"), StreamEvent.Completed("stop")),
                ),
            ),
            hangFirst = true,
        )
        val session = newSession(transport)
        try {
            session.send("go")
            await("回合应处于 Running") { session.turnState.value is TurnState.Running }
            // 同步收紧（收尾卡死批）：等首条请求真发出去再动手。否则 abort 可能落在
            // 「首条请求之前」，接上的继跑请求会成为传输层第 0 号请求、被 hangFirst 陷阱
            // 挂住，谓词 requests >= 2 永不成立（红快照 requests=1 state=Running(compacting=false) queue=[]）。
            await("首条请求应已发出") { transport.requests.isNotEmpty() }

            session.steer(note, systemNote = true)
            await("通知应入队") { session.queue.value.any { it.message.isSystemNote } }

            session.abort()
            await("收尾后通知应被接上并发送", snapshot = {
                "requests=${transport.requests.size} state=${session.turnState.value} queue=${session.queue.value}"
            }) { transport.requests.size >= 2 }
            assertTrue(
                "abort 的 clearQueue 不得清掉系统通知",
                transport.requests[1].messages.any { it.isSystemNote && it.text.contains("t1") },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /**
     * 真投递链路（`deliverToSession` → `sessionNoteSink` → `steer`）在**忙回合**里的一整趟。
     *
     * 上面两条直接调 `session.steer`，把 sink 改回老路的 `session.send`（= 通知落进
     * FOLLOW_UP、只能等回合结束）它们照样绿 —— U18 修的正是这条线，所以必须有一条用例
     * 从投递入口走。断言用「次数 = 1」而不是「存在」：`queuedMessages` 一旦失效，
     * [dev.kair.app.app.deliverNote] 的补投会把同一条通知写三遍。
     */
    @Test
    fun aNoteDeliveredThroughTheRealSinkJoinsTheNextRequestMidTurn() = runBlocking {
        val tool = SlowTool()
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call_1", "slow"),
                        StreamEvent.ToolCallArgsDelta(0, "{}"),
                        StreamEvent.Completed("toolUse"),
                    ),
                    listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport, tool)
        try {
            session.send("go")
            withTimeout(15_000) { tool.started.await() }

            deliverToSession({ session }, "t1", note)

            await("回合应收尾") { !session.isBusy }
            val second = transport.requests.getOrNull(1)
            assertTrue("应有第二次请求", second != null)
            assertEquals(
                "通知应在下一步请求前并入历史、且只并入一次（改回 send 会落进 FOLLOW_UP → 0 次）",
                1,
                second!!.messages.count { it.isSystemNote && it.text.contains("t1") },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /**
     * 长回合（请求挂住、通知只能留在队列里）时，同一条通知**只补投一次**。
     *
     * 守的是 sink 的 `queuedMessages` 接线：它若读不到真队列，[dev.kair.app.app.deliverNote]
     * 的「核对没落地就补投」会把同一条通知写进队列三遍 —— 界面上就是三连弹。
     * 上面的假 sink 用例只验了 `deliverNote` 自己的逻辑，验不到这条接线。
     */
    @Test
    fun aNoteDeliveredAgainstAHangingTurnIsNotWrittenToTheQueueThreeTimes() = runBlocking {
        val transport = RecordingTransport(
            ArrayDeque(listOf(emptyList())),
            hangFirst = true,
        )
        val session = newSession(transport)
        try {
            session.send("go")
            await("回合应处于 Running") { session.turnState.value is TurnState.Running }
            // 同步收紧（收尾卡死批）：通知要落进**还在挂住的**回合的队列里 —— 抢在首条
            // 请求前投递会被回合的 STEER 排空消费进请求，queue 计数成 0，断言随调度而红。
            await("首条请求应已发出") { transport.requests.isNotEmpty() }

            deliverToSession({ session }, "t1", note)

            assertEquals(
                "队列里只该有一条通知（读不到队列 → 补投三遍）",
                1,
                session.queue.value.count { it.message.isSystemNote && it.message.text.contains("t1") },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /** 真投递链路在**空闲**会话上的一整趟：通知自己起一个唤醒回合，而不是排进队列没人取。 */
    @Test
    fun aNoteDeliveredThroughTheRealSinkWakesAnIdleSession() = runBlocking {
        val transport = RecordingTransport(
            ArrayDeque(listOf(listOf(StreamEvent.TextDelta("ok"), StreamEvent.Completed("stop")))),
        )
        val session = newSession(transport)
        try {
            deliverToSession({ session }, "t1", note)

            await("空闲时应起一个唤醒回合") { transport.requests.isNotEmpty() && !session.isBusy }
            assertEquals(
                "唤醒回合的第一条请求就该带上通知",
                1,
                transport.requests.first().messages.count { it.isSystemNote && it.text.contains("t1") },
            )
            // 核对接的是 sink 的 `messages` 观察点：它若读不到真历史，补投会把这一条写三遍
            // （评审变异 V1 实证：接线改坏时上面两条断言照样绿，只有这里红）。
            assertEquals(
                "历史里只该有一条通知（`messages` 接线失效 → 补投三遍）",
                1,
                session.messages.value.count { it.isSystemNote && it.text.startsWith("[background task t1:") },
            )
            assertEquals("不该因为核对不到而重复起回合", 1, transport.requests.size)
            assertTrue("投递完不该在队列里留尾巴", session.queue.value.isEmpty())
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /**
     * 通知落在**收尾广播窗口**里也要有人接（B 审 P2）。
     *
     * `runUser` 的 `finally` 广播 `TurnEnd` 时，队列**已经排空过**（那个 sweep 在 try 里）、
     * 状态还没回空闲：此刻 `isRunning` 仍为真，通知按 steer 语义只会入队，而投递方一看
     * 队列里有它就返回「已送达」、`markDelivered` 跟着打上。老写法只在「停止收尾」时排空
     * 队列，于是这条通知既看不见（界面已滤掉系统通知）、也进不了任何请求，
     * 并且 `AppContainer` 的 `describe` 判 `queue.isEmpty` —— 该会话从此永不空闲、
     * LRU 淘汰绕开它。用 `TurnEnd` 事件在这个窗口里投一条，把三件事一起钉住。
     */
    @Test
    fun aNoteLandingDuringTheTurnEndBroadcastIsStillDrainedIntoAFollowUpTurn() = runBlocking {
        val holder = AtomicReference<AgentSession?>()
        val steered = AtomicInteger()
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    // 必须是**工具回合**：TurnEnd 广播发生在「历史落盘之后」，而单轮直答
                    // 的落点在流式协程里、比 `append(assistant)` 还早 —— 在那里 steer 时
                    // 历史还是空的，走的就不是「收尾广播窗口」这个待守的形态了。
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call_1", "slow"),
                        StreamEvent.ToolCallArgsDelta(0, "{}"),
                        StreamEvent.Completed("toolUse"),
                    ),
                    listOf(StreamEvent.TextDelta("second"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport, onEvent = { event ->
            if (event is AgentEvent.TurnEnd && steered.getAndIncrement() == 0) {
                holder.get()?.steer(note, systemNote = true)
            }
        })
        holder.set(session)
        try {
            session.send("go")
            await("收尾窗口里的通知应被接进后续请求") { noteArrived(transport) }
            val first = transport.requests.indexOfFirst { it.noteCount(TASK_ID) > 0 }
            assertTrue("通知不该出现在首条请求之前", first >= 1)
            assertEquals(
                "带通知的那次请求里只该有一条（补投三遍会在这里红）",
                1,
                transport.requests[first].noteCount(TASK_ID),
            )
            await("队列不该留下尾巴（留下就等于会话永不判空闲）") { session.queue.value.isEmpty() }
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    /**
     * 系统通知**不许**被插件的 `onInput` 改写（评审变异 V3：`inbound` 改回无条件
     * `hooks.runInput(text)` 时，本批其余用例全绿 —— 这条语义当时没人守）。
     *
     * `onInput` 是「用户输入改写点」：通知不是用户说的话，被改写会把 task id / 退出码
     * 刷掉，模型读到的是插件拼进去的文本。
     */
    @Test
    fun aSystemNoteBypassesTheInputRewriteHookWhileUserTextIsRewritten() = runBlocking {
        val hooks = HookBus().apply { onInput { text -> "[plugin] $text" } }
        val transport = RecordingTransport(
            ArrayDeque(
                listOf(
                    listOf(StreamEvent.TextDelta("one"), StreamEvent.Completed("stop")),
                    listOf(StreamEvent.TextDelta("two"), StreamEvent.Completed("stop")),
                ),
            ),
        )
        val session = newSession(transport, hooks = hooks)
        try {
            session.send("go")
            await("第一回合应收尾") { !session.isBusy }
            assertTrue(
                "用户输入该被 onInput 改写（改写点本身要有效）",
                session.messages.value.any { !it.isSystemNote && it.text.startsWith("[plugin] ") },
            )

            deliverToSession({ session }, "t1", note)
            await("通知应起唤醒回合") { transport.requests.size >= 2 }
            assertTrue(
                "系统通知必须逐字进历史，不能被 onInput 改写",
                session.messages.value.any { it.isSystemNote && it.text == note },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    private companion object {
        private const val TASK_ID = "t1"
    }
}
