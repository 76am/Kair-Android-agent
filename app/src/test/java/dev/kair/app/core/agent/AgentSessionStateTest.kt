package dev.kair.app.core.agent

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话运行状态机（ADR 0004 / `docs/tasks/archive/P2-1-会话状态机.md`）。
 *
 * 钉的是「状态只能按合法路径转移」这件事本身，而不是某段顺序写对了：
 * 1. 一轮的生命周期 `Idle → Running → Stopping → Idle` 每一步都真的到达过；
 * 2. 重复 `abort()` 不得把仍在 `NonCancellable` 收尾的轮次宣布成空闲；
 * 3. `Stopping` 期间 `send` 只入队、不起跑，收尾结束后再自动接上；
 * 4. 从空闲**写不出** `Stopping`（这是「卡在正在停止」的旧形态），且非法转移会被记录。
 *
 * 第 4 条是 ADR 0004 的核心验收：旧 bug 的形态是「`IDLE` 且 `stopping == true`」，
 * 现在这个组合在类型上不存在，`abort()` 想制造它只会被转移函数拒绝。
 */
class AgentSessionStateTest {

    /**
     * 模拟容器里的长命令：取消信号到达后，进程真正被杀还要 [killDelayMs]。
     * 这段窗口正是「点了停止、还没停干净」的真实时长，也是旧 bug 发生的窗口。
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
                withContext(NonCancellable) { delay(killDelayMs) }
                throw e
            }
            return ToolResult("done")
        }
    }

    /** 一个会触发 [SlowKillTool] 的脚本回合（工具调用参数完整，收尾原因是 TOOL_USE）。 */
    private fun toolTurn(): ScriptedTurn = ScriptedTurn(
        listOf(
            StreamEvent.ToolCallStarted(0, "slow-1", "slow-kill"),
            StreamEvent.ToolCallArgsDelta(0, "{\"text\":\"hi\"}"),
            StreamEvent.Completed(StopReason.TOOL_USE),
        ),
    )

    private fun stateHarness(
        started: AtomicBoolean,
        vararg turns: ScriptedTurn,
        killDelayMs: Long = 500,
    ): SessionHarness = harness(
        transport = ScriptedTransport(turns.toList()),
        tools = ToolRegistry().register(SlowKillTool(started, killDelayMs)),
    )

    @Test
    fun transitionsIdleRunningStoppingIdle() = runBlocking {
        val started = AtomicBoolean(false)
        val h = stateHarness(started, toolTurn(), ScriptedTurn.text("ok"))
        val session = h.session
        try {
            assertEquals("初始应为空闲", TurnState.Idle, session.turnState.value)

            session.send("first")
            await("回合应进入执行态") { session.turnState.value is TurnState.Running }
            await("工具应已开始执行") { started.get() }

            session.abort()
            await("取消信号投递后应进入停止中") { session.turnState.value == TurnState.Stopping }
            await("收尾结束后应回到空闲") { session.turnState.value == TurnState.Idle }
        } finally {
            h.close()
        }
    }

    @Test
    fun repeatedAbortDoesNotDeclareIdle() = runBlocking {
        val started = AtomicBoolean(false)
        val h = stateHarness(started, toolTurn(), ScriptedTurn.text("ok"))
        val session = h.session
        try {
            session.send("first")
            await("工具应已开始执行") { started.get() }

            session.abort()
            await("第一次 abort 应进入停止中") { session.turnState.value == TurnState.Stopping }

            // 第二次 abort 看到的是「job 已被摘掉、上一轮还在 NonCancellable 收尾」。
            session.abort()
            assertEquals(
                "第二次 abort 不得把仍在收尾的轮次宣布成空闲",
                TurnState.Stopping,
                session.turnState.value,
            )

            // 收尾至少要跑 killDelayMs：再等一小段，确认不是一瞬即逝的假绿。
            delay(100)
            assertEquals("收尾跑完前状态应保持在停止中", TurnState.Stopping, session.turnState.value)

            await("收尾结束后应回到空闲") { session.turnState.value == TurnState.Idle }

            // 收尾结束后 `job` 引用还在（已完成）：此时再 abort 不得把空闲写成停止中 ——
            // 旧实现里 `stopping` 是无条件置位的，这里正是「重开会话仍卡在转圈」的入口。
            val rejectedBefore = session.rejectedTransitions
            session.abort()
            assertEquals("已收口的会话不得被 abort 复活成停止中", TurnState.Idle, session.turnState.value)
            assertEquals("空闲时的 abort 应被记录为非法转移", rejectedBefore + 1, session.rejectedTransitions)
        } finally {
            h.close()
        }
    }

    @Test
    fun abortWhileOnlyNonTurnActionIsRejected() = runBlocking {
        // 删除消息是「非回合的串行动作」：它在跑时状态是 Busy，不是 Running。
        // 用阻塞的 onReplaceHistory 把这一瞬间钉住，避免用例靠 sleep 抢时序。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val h = harness(
            transport = ScriptedTransport(listOf(ScriptedTurn.text("ok"))),
            onReplaceHistory = {
                entered.countDown()
                release.await()
            },
        )
        val session = h.session
        try {
            val message = ChatMessage(role = Role.USER, parts = listOf(Part.Text("要删的")))
            session.loadHistory(listOf(message))

            session.deleteMessage(message.id)
            assertTrue("删除动作应已开始改写历史", entered.await(5, TimeUnit.SECONDS))
            assertEquals("非回合动作在跑时应是 Busy", TurnState.Busy, session.turnState.value)
            assertFalse(session.isRunning)

            val rejectedBefore = session.rejectedTransitions
            session.abort()
            assertEquals(
                "没有回合在跑时，abort 不得写出「停止中」（旧 bug 的形态）",
                TurnState.Busy,
                session.turnState.value,
            )
            assertEquals("该尝试应被记录而不是静默覆盖", rejectedBefore + 1, session.rejectedTransitions)

            release.countDown()
            await("非回合动作收尾后应回到空闲") { session.turnState.value == TurnState.Idle }
        } finally {
            release.countDown()
            h.close()
        }
    }

    @Test
    fun sendDuringStoppingIsQueued() = runBlocking {
        val started = AtomicBoolean(false)
        val h = stateHarness(started, toolTurn(), ScriptedTurn.text("ok"))
        val session = h.session
        try {
            session.send("first")
            await("工具应已开始执行") { started.get() && session.turnState.value is TurnState.Running }

            session.abort()
            await("应进入停止中") { session.turnState.value == TurnState.Stopping }

            session.send("while stopping")
            assertEquals("停止期间的消息应入队", 1, session.queue.value.size)
            assertEquals("停止期间不得另起一轮", 1, h.requests.size)
            assertEquals("停发消息不改变停止中状态", TurnState.Stopping, session.turnState.value)

            await("收尾结束后应自动接上排队消息") { h.requests.size >= 2 }
            assertTrue(
                "停止期间发的消息必须真的发出",
                h.requests[1].messages.any { it.role == Role.USER && it.text.contains("while stopping") },
            )
            assertTrue("队列应被清空", session.queue.value.isEmpty())
            await("接上的回合也应跑完") { session.turnState.value == TurnState.Idle }
        } finally {
            h.close()
        }
    }

    @Test
    fun illegalTransitionIsRejected() = runBlocking {
        val h = stateHarness(AtomicBoolean(false), ScriptedTurn.text("ok"))
        val session = h.session
        try {
            assertEquals(TurnState.Idle, session.turnState.value)
            val rejectedBefore = session.rejectedTransitions

            // 空闲时 abort：旧实现会先置 `stopping = true`，于是「空闲 + 停止中」这个
            // 非法组合可以被写出来（顺序反一行就永久卡住）。新状态机里 Idle → Stopping
            // 是非法转移，只记录、不改变状态。
            session.abort()

            assertEquals("空闲时 abort 不得写出停止中", TurnState.Idle, session.turnState.value)
            assertFalse("不得留下停止标记", session.stopping.value)
            assertEquals(
                "非法转移应被记录而不是静默覆盖",
                rejectedBefore + 1,
                session.rejectedTransitions,
            )
        } finally {
            h.close()
        }
    }
}
