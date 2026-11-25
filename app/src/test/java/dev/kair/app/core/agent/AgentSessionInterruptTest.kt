package dev.kair.app.core.agent

import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 中断语义：**被掐断（SSE 中途停止）的回合一律不进下一回合的请求**。
 *
 * 半截正文 / 半截思考 / 没吐完的工具参数都可能是「半句话」，回灌会让模型把未完成的输出
 * 当成自己说完、做过的完整回合（例如正文说了「我这就查查」而工具调用被掐掉，模型会以为
 * 自己查过了）。已生成的部分仍留在界面给用户看，只是换成一条中断提示发给模型。
 *
 * 反面也要守住：模型输出**已正常收尾**、只是工具执行被暂停的回合，正文照旧发送（不改），
 * 工具调用则补一条「被用户中止」的配对结果 —— 既不悬空，界面上的工具卡也保留。
 */
class AgentSessionInterruptTest {

    private class SlowTool(private val started: AtomicBoolean) : Tool {
        override val name = "slow"
        override val description = "slow"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            started.set(true)
            delay(10_000)
            return ToolResult("done")
        }
    }

    /** 立即返回的工具：用来把会话推进到「工具结果已回、下一发请求未回」的窗口。 */
    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult("done")
    }

    /**
     * 模拟容器里的长命令：取消信号到达后，进程真正被杀还要一小段时间（[killDelayMs]）。
     * 这段窗口正是「点了停止、还没停干净」的真实时长。
     */
    private class SlowKillTool(private val started: AtomicBoolean, private val killDelayMs: Long) : Tool {
        override val name = "slow-kill"
        override val description = "slow-kill"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            started.set(true)
            try {
                delay(10_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(killDelayMs) }
                throw e
            }
            return ToolResult("done")
        }
    }

    /** 供所有用例复用的会话：workdir 与传输层 id 保持与原用例一致。 */
    private fun newHarness(transport: ScriptedTransport, tools: ToolRegistry = ToolRegistry()) =
        harness(
            transport = transport,
            sessionId = "test-interrupt-session",
            workspace = File("."),
            tools = tools,
        )

    @Test
    fun abortedPartialTextStaysInUiButIsDiscardedFromRequest() = kotlinx.coroutines.runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT)), ScriptedTurn.text("ok")),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("check X")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(HALF_TEXT) }
            }
            session.abort()
            await("被掐断的回合应标记为 aborted") {
                session.messages.value.any { it.stopReason == StopReason.ABORTED }
            }

            // 界面：半截内容仍在（用户要看得到被打断的半截）。
            val shown = session.messages.value.first { it.role == Role.ASSISTANT }
            assertTrue("半截正文应留在界面历史里", shown.text.contains(HALF_TEXT))
            assertEquals(StopReason.ABORTED, shown.stopReason)

            session.send("also Y")
            await("下一回合应已发出请求") { transport.requests.size >= 2 }

            // 发给模型：整条丢弃，只剩一条中断提示。
            val sent = transport.requests[1].messages
            assertFalse("半截正文不得进入请求", sent.any { it.text.contains(HALF_TEXT) })
            assertTrue(
                "被掐断的回合应换成中断提示",
                sent.any { it.role == Role.ASSISTANT && it.text == AgentSession.INTERRUPTED_NOTICE },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun abortedTurnWithPartialToolCallIsDiscardedFromRequest() = kotlinx.coroutines.runBlocking {
        // 与上一例同类，但残缺的是工具调用：参数没吐完就被掐断。
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn.hanging(
                    StreamEvent.TextDelta(HALF_TEXT),
                    StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"par"),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("check X")
            await("残缺工具调用应已出现在界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            }
            session.abort()
            await("残缺工具调用应被收尾清掉") {
                session.messages.value.none { it.toolCalls.isNotEmpty() }
            }

            session.send("also Y")
            await("下一回合应已发出请求") { transport.requests.size >= 2 }

            val sent = transport.requests[1].messages
            assertFalse("半截正文不得进入请求", sent.any { it.text.contains(HALF_TEXT) })
            assertTrue(
                "残缺工具调用不得进入请求",
                sent.none { it.toolCalls.isNotEmpty() },
            )
            assertTrue(
                "被掐断的回合应换成中断提示",
                sent.any { it.role == Role.ASSISTANT && it.text == AgentSession.INTERRUPTED_NOTICE },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun abortedEmptyTurnLeavesNoBubbleAndNoNotice() = kotlinx.coroutines.runBlocking {
        // 中止正落在「占位气泡已渲染、还没有任何产出」的窗口：回收气泡，不写任何提示。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(), ScriptedTurn.text("ok")),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("check X")
            await("占位气泡应已渲染") {
                session.messages.value.any { it.role == Role.ASSISTANT }
            }
            session.abort()
            await("空白气泡应被回收") {
                session.messages.value.none { it.role == Role.ASSISTANT }
            }

            session.send("also Y")
            await("下一回合应已发出请求") { transport.requests.size >= 2 }

            assertTrue(
                "全空中断不写中断提示",
                transport.requests[1].messages.none { it.text.contains(AgentSession.INTERRUPTED_NOTICE) },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun completedTurnKeepsItsCallWhenToolRunIsInterrupted() = kotlinx.coroutines.runBlocking {
        // 反面：模型输出已正常收尾（stopReason 有值），中断落在**工具执行**期 —— 这一轮
        // 的话是完整的，照旧发送；被中止的调用补一条配对结果（写明被中止），而不是删掉。
        val toolStarted = AtomicBoolean(false)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.TextDelta(ON_IT),
                        StreamEvent.ToolCallStarted(0, "slow-1", "slow"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport, ToolRegistry().register(SlowTool(toolStarted)))
        val session = h.session
        try {
            session.send("check X")
            await("工具应已开始执行") { toolStarted.get() }
            session.abort()
            await("被中止的调用应补上配对结果") {
                session.messages.value.any { it.role == Role.TOOL_RESULT }
            }

            val shown = session.messages.value.first { it.role == Role.ASSISTANT }
            assertTrue("完整收尾的正文应保留", shown.text.contains(ON_IT))
            assertEquals("正常收尾的回合不标 aborted", StopReason.TOOL_USE, shown.stopReason)
            assertEquals("工具卡不得凭空消失", "slow-1", shown.toolCalls.single().id)
            val result = session.messages.value.first { it.role == Role.TOOL_RESULT }.toolResults.single()
            assertEquals("slow-1", result.toolCallId)
            assertTrue(result.isError)
            assertEquals(AgentSession.TOOL_ABORTED, result.content)

            session.send("also Y")
            await("下一回合应已发出请求") { transport.requests.size >= 2 }

            val sent = transport.requests[1].messages
            assertTrue(
                "完整收尾的正文仍照旧发送",
                sent.any { it.role == Role.ASSISTANT && it.text.contains(ON_IT) },
            )
            // 调用与结果成对进入请求：模型看到的是「被中止，可重试」，而不是无从解释的悬空调用。
            assertTrue(sent.any { it.toolCalls.any { tc -> tc.id == "slow-1" } })
            assertTrue(sent.any { it.toolResults.any { r -> r.toolCallId == "slow-1" } })
            assertFalse(sent.any { it.text == AgentSession.INTERRUPTED_NOTICE })
        } finally {
            h.close()
        }
    }

    @Test
    fun abortWhileWaitingForNextRequestSealsToolResultTail() = kotlinx.coroutines.runBlocking {
        // 真机形态：工具结果已回、下一发请求卡在网络前置阶段（弱网），点停止后取消要
        // 十几秒才生效。这一轮没有产出任何 assistant 消息 —— 中断收尾必须给尾条工具结果
        // 封口，否则界面把下一条用户消息当成回合内引导，并把上一轮标成运行中。
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "c1", "echo"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
                ScriptedTurn.hanging(),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport, ToolRegistry().register(EchoTool()))
        try {
            h.session.send("check X")
            await("工具结果应已落进历史") {
                h.session.messages.value.any { it.role == Role.TOOL_RESULT }
            }
            h.session.abort()
            h.awaitIdle()

            val messages = h.session.messages.value
            assertEquals("尾条是工具结果", Role.TOOL_RESULT, messages.last().role)
            assertEquals("中断收尾必须封口", StopReason.ABORTED, messages.last().stopReason)
        } finally {
            h.close()
        }
    }

    @Test
    fun abortedTurnStaysHiddenAfterRestart() = kotlinx.coroutines.runBlocking {
        // 重启路径：被掐断的回合必须**落盘**，且重启后仍不进请求 —— 否则「内存里看不到、
        // 磁盘上也没有」会让重启前后历史不一致，前缀字节随之变化（缓存必 miss），更糟的是
        // 半截内容可能在重启后重新回灌给模型。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT)), ScriptedTurn.text("ok")),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val restarted = harness(
            transport = transport,
            sessionId = "test-interrupt-session-restarted",
            workspace = File("."),
        )
        val session = h.session
        try {
            session.send("check X")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(HALF_TEXT) }
            }
            session.abort()
            await("被掐断的回合应标记为 aborted") {
                session.messages.value.any { it.stopReason == StopReason.ABORTED }
            }

            // 盘上走的就是这条序列化路径（JSONL 一行一条消息），原样往返一遍。
            val stored = session.messages.value.map {
                Json.decodeFromString<ChatMessage>(Json.encodeToString(ChatMessage.serializer(), it))
            }
            assertTrue(
                "被掐断的标记应经 JSONL 往返存活",
                stored.any { it.role == Role.ASSISTANT && it.stopReason == StopReason.ABORTED },
            )

            // 「重启」：新会话载入存档（loadHistory 会再跑一次配对修复，它只改 parts）。
            restarted.session.loadHistory(stored)
            restarted.session.send("also Y")
            await("重启后的回合应已发出请求") { transport.requests.size >= 2 }

            val sent = transport.requests[1].messages
            assertFalse("重启后半截正文仍不得进入请求", sent.any { it.text.contains(HALF_TEXT) })
            assertTrue(
                "重启后被掐断的回合仍是中断提示",
                sent.any { it.role == Role.ASSISTANT && it.text == AgentSession.INTERRUPTED_NOTICE },
            )
        } finally {
            restarted.close()
            h.close()
        }
    }

    @Test
    fun editRightAfterStopDoesNotResurrectRetractedReply() = kotlinx.coroutines.runBlocking {
        // 回归：停止是异步的（取消后还要跑收尾）。此前用户点停止后立刻「编辑重发」，
        // 旧轮收尾会把已经撤回的回复重新 append 回来，界面上于是出现两段互不相干的
        // 模型回复。会话侧的轮次闸门必须让新一轮等旧轮彻底收尾后再改历史。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT)), ScriptedTurn.text("ok")),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("first")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.text.contains(HALF_TEXT) }
            }
            val userId = session.messages.value.first { it.role == Role.USER }.id

            session.abort()
            // 不等待 —— 直接编辑，正好踩在旧轮取消收尾尚未完成的窗口上。
            session.editUserMessage(userId, "second")

            await("新一轮应已产出回复并收尾") {
                session.messages.value.lastOrNull()?.text == "ok" && !session.isBusy
            }
            val shown = session.messages.value.filter { it.role == Role.ASSISTANT }
            assertEquals("被撤回的回合不得复活", 1, shown.size)
            assertFalse("旧回复不得残留", shown.any { it.text.contains(HALF_TEXT) })
            assertEquals(
                "用户消息应已被改写成新文本",
                "second",
                session.messages.value.first { it.role == Role.USER }.text,
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun stoppingStaysBusyUntilCleanupAndDrainsQueuedSend() = kotlinx.coroutines.runBlocking {
        // 回归：停止是异步的（容器进程要等信号送达、真被杀掉）。收尾期间会话必须继续
        // 表现为「停止中」，期间发出的消息进队列，收尾结束后自动接上 —— 否则新一轮会
        // 与仍在收尾的旧轮并发改历史，或消息滞留在队列里没人消费。
        val toolStarted = AtomicBoolean(false)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "slow-1", "slow-kill"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport, ToolRegistry().register(SlowKillTool(toolStarted, 400)))
        val session = h.session
        try {
            session.send("first")
            await("工具应已开始执行") { toolStarted.get() }

            session.abort()
            assertTrue("收尾期间应标记为停止中", session.stopping.value)
            assertEquals("收尾期间仍是忙", SessionStatus.RUNNING, session.status.value)

            // 停止期间用户抢发：只入队，不与旧轮并发。
            session.send("while stopping")
            assertEquals("停止期间的消息应入队", 1, session.queue.value.size)
            assertEquals("不得在收尾期间另起一轮", 1, transport.requests.size)

            await("收尾结束后应恢复空闲") { !session.stopping.value && !session.isBusy }
            await("队列里的消息应自动接上") { transport.requests.size >= 2 }
            assertTrue(
                "停止期间发的消息必须真的发出",
                transport.requests[1].messages.any { it.role == Role.USER && it.text.contains("while stopping") },
            )
            assertTrue("队列应被清空", session.queue.value.isEmpty())
        } finally {
            h.close()
        }
    }

    @Test
    fun emptyCompletedTurnLeavesNoBubble() = kotlinx.coroutines.runBlocking {
        // 服务端只回了结束原因（没有任何内容）时不留空白 agent 气泡，也不落盘；
        // 现在会先自动重试一次（第二次仍空），最终给一条可见的系统提示。
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(listOf(StreamEvent.Completed(StopReason.STOP))),
                ScriptedTurn(listOf(StreamEvent.Completed(StopReason.STOP))),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("hi")
            await("回合应已结束") {
                !session.isBusy && session.messages.value.any { it.role == Role.USER }
            }
            assertTrue("空回合不应留下 agent 气泡", session.messages.value.none { it.role == Role.ASSISTANT })
            assertTrue(
                "重试后仍空必须留可见提示",
                session.messages.value.any { it.isSystemNote && it.text.contains("空回复") },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun abortAfterFinishedTurnLeavesNoStoppingFlag() = kotlinx.coroutines.runBlocking {
        // 回归：`stopping` 由 abort 先置位、由收尾完成回调复位。若两处顺序反了（先查 job
        // 再置位），一轮恰好在此刻跑完就会把标记永久钉在 true —— 界面转圈顶掉停止键，
        // 用户既不能发也不能再停。这条用例钉住「空闲时 abort 不留悬挂标记」。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("done")),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport)
        val session = h.session
        try {
            session.send("go")
            await("回合应已跑完") { !session.isBusy && session.messages.value.any { it.text == "done" } }

            session.abort()

            assertFalse("空闲时 abort 不应留下停止标记", session.stopping.value)
            assertEquals(SessionStatus.IDLE, session.status.value)
        } finally {
            h.close()
        }
    }

    @Test
    fun repeatedAbortDoesNotAnnounceCleanupAsDone() = kotlinx.coroutines.runBlocking {
        // 重复点停止：第二次 abort 看到的是「job 已摘掉、上一轮还在 NonCancellable 收尾」，
        // 它不能把仍在进行的收尾宣布成结束（否则界面提前放行下一轮）。
        val toolStarted = AtomicBoolean(false)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "slow-1", "slow-kill"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport, ToolRegistry().register(SlowKillTool(toolStarted, 400)))
        val session = h.session
        try {
            session.send("first")
            await("工具应已开始执行") { toolStarted.get() }

            session.abort()
            assertTrue("第一次 abort 应进入停止中", session.stopping.value)

            session.abort()
            assertTrue("第二次 abort 不得提前清掉停止标记", session.stopping.value)
            assertEquals("收尾期间仍是忙", SessionStatus.RUNNING, session.status.value)

            await("收尾结束后应恢复空闲") { !session.stopping.value && !session.isBusy }
        } finally {
            h.close()
        }
    }

    @Test
    fun steerDuringStoppingIsQueuedAndDeliveredAfterCleanup() = kotlinx.coroutines.runBlocking {
        // 界面上的「引导」三角按钮走 `steer`（默认发送键走 `send`，U22）：停止收尾期间它
        // 看到 RUNNING，因此入 STEER 队列，由 `drainQueueAfterStop` 在收尾结束后接上（这条分支此前零覆盖）。
        val toolStarted = AtomicBoolean(false)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "slow-1", "slow-kill"),
                        StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
                ScriptedTurn.text("ok"),
            ),
            id = "test-interrupt-transport",
        )
        val h = newHarness(transport, ToolRegistry().register(SlowKillTool(toolStarted, 400)))
        val session = h.session
        try {
            session.send("first")
            await("工具应已开始执行") { toolStarted.get() }

            session.abort()
            session.steer("guide")

            assertEquals("停止期间的消息应入队", 1, session.queue.value.size)
            assertEquals(QueueKind.STEER, session.queue.value.single().kind)

            await("收尾结束后应自动接上排队消息") { transport.requests.size >= 2 }
            assertTrue(
                "停止期间发的消息必须真的发出",
                transport.requests[1].messages.any { it.role == Role.USER && it.text.contains("guide") },
            )
            assertTrue("队列应被清空", session.queue.value.isEmpty())
        } finally {
            h.close()
        }
    }

    private companion object {
        const val HALF_TEXT = "I'm on it"
        const val ON_IT = "Let me check the directory"
    }
}
