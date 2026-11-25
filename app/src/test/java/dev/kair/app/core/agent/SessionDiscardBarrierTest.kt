package dev.kair.app.core.agent

import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话作废（[AgentSession.discard]）与历史写入的屏障（U43）。
 *
 * 契约：`discard()` 返回后，本实例不再产生新的持久化提交 —— 包括回合执行器手里那条
 * **绕过了 `append` 的**原始回调（取消收尾 / 正常收尾）。反面对照：只 `abort()` 时
 * 中断输出仍要落盘一次，屏障不得误伤正常中断。
 *
 * 触发场景只有删会话 / 删项目 / 删日志（ADR 0005 的唯一入口），不是「退出会话」。
 */
class SessionDiscardBarrierTest {

    @Test
    fun `discard during cancelled turn does not persist the interrupted message`() = runBlocking {
        val persisted = CopyOnWriteArrayList<ChatMessage>()
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT))),
            id = "discard-barrier-transport",
        )
        val h = harness(
            transport = transport,
            sessionId = "discard-barrier-session",
            onPersist = { persisted += it },
        )
        val session = h.session
        try {
            session.send("check X")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(HALF_TEXT) }
            }

            // 删掉一个正在流式输出的会话：置作废并中止，取消收尾随后才跑。
            session.discard()
            h.awaitIdle()

            assertTrue(
                "discard 后不得再落盘助手的收尾消息，实际落盘：${persisted.map { it.role to it.text }}",
                persisted.none { it.role == Role.ASSISTANT },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `abort without discard still persists the interrupted message once`() = runBlocking {
        val persisted = CopyOnWriteArrayList<ChatMessage>()
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT))),
            id = "discard-barrier-transport",
        )
        val h = harness(
            transport = transport,
            sessionId = "discard-barrier-session",
            onPersist = { persisted += it },
        )
        val session = h.session
        try {
            session.send("check X")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(HALF_TEXT) }
            }

            session.abort()
            h.awaitIdle()

            val assistants = persisted.filter { it.role == Role.ASSISTANT }
            assertEquals("普通中止仍要保存中断输出一次", 1, assistants.size)
            assertEquals(StopReason.ABORTED, assistants.single().stopReason)
        } finally {
            h.close()
        }
    }

    @Test
    fun `discard returns without waiting for cancellation cleanup`() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta(HALF_TEXT))),
            id = "discard-barrier-transport",
        )
        val h = harness(transport = transport, sessionId = "discard-barrier-session")
        val session = h.session
        try {
            session.send("check X")
            await("半截正文应已流到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(HALF_TEXT) }
            }

            val startedAt = System.nanoTime()
            session.discard()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            // 传输层要挂 10s 才自然结束：若 discard 在锁内等了取消收尾，这一条会远超 2s。
            assertTrue("discard 不得等待取消收尾（实测 ${elapsedMs}ms）", elapsedMs < 2_000)
        } finally {
            h.close()
        }
    }

    @Test
    fun `writer refuses every commit after discard returns`() {
        val persisted = mutableListOf<ChatMessage>()
        val replaced = mutableListOf<List<ChatMessage>>()
        val writer = HistoryWriter(persist = { persisted += it }, replace = { replaced += it })
        val message = ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text("late")))

        writer.append(message)
        writer.replaceAll(listOf(message))
        writer.markClosed()
        writer.append(message)
        writer.replaceAll(listOf(message))

        assertEquals("作废后不得再追加", 1, persisted.size)
        assertEquals("作废后不得再替换", 1, replaced.size)
    }

    private companion object {
        const val HALF_TEXT = "I'm on it"
    }
}
