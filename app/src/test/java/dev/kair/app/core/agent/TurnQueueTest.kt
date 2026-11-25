package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「停止期间排队、收尾后接上」的队列语义（`TurnQueue`）。
 *
 * 这些用例在搬出来之前写不了：队列是会话对象的私有字段，要观察它的接法只能起一个真会话、
 * 卡在「已点停止、收尾还没跑完」那个时序窗口里（`AgentSessionInterruptTest` 的两条队列用例
 * 就是这么做的，它们继续守时序）。本文件只守**决策本身**：谁接上、谁留在队列里、
 * 取出时会不会把新入队的消息吞掉。
 */
class TurnQueueTest {

    private fun message(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    @Test
    fun followUpHeadIsPlannedAsNewTurnButStaysQueuedUntilSubmit() {
        // U21：决策**只读不取** —— 取出发生在 submitNext 里（提交不成即归还）。
        // 决策期就离队是丢失窗口的根：abort 落在决策与提交之间时消息两头都没有。
        val first = message("停止后又发的第一条")
        val second = message("再后面的一条")
        val queue = TurnQueue()
        queue.enqueue(first, QueueKind.FOLLOW_UP)
        queue.enqueue(second, QueueKind.FOLLOW_UP)

        val next = queue.planNextAfterStop()

        assertTrue(next is TurnQueue.NextRun.StartWith)
        assertEquals(first, (next as TurnQueue.NextRun.StartWith).message)
        assertEquals(listOf(first, second), queue.messages.value.map { it.message }) // 决策不动队
    }

    @Test
    fun steerHeadStaysQueuedForRunLoopAndOnlyAsksForATurn() {
        // STEER 必须留在队列里：它要在「当前 assistant 回合之后、停止之前」并入，
        // 那是 runLoop 开头的活；这里把它 dequeue 掉就等于把消息吞了。
        val steer = message("插话")
        val queue = TurnQueue()
        queue.enqueue(steer, QueueKind.STEER)

        assertEquals(TurnQueue.NextRun.ContinueTranscript, queue.planNextAfterStop())
        assertEquals(listOf(steer), queue.messages.value.map { it.message })
    }

    @Test
    fun emptyQueueAsksForNothing() {
        val queue = TurnQueue()

        assertNull(queue.planNextAfterStop())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun submitPendingDeliversEachTakenMessageAndLeavesTheRest() {
        // 「取出 → 提交」配对出口的正常路：取到的都提交、没取到的留下，
        // 且只送同一 kind（STEER 归 STEER、FOLLOW_UP 归 FOLLOW_UP）。
        val steer = message("插话")
        val followUp = message("续问")
        val queue = TurnQueue()
        queue.enqueue(steer, QueueKind.STEER)
        queue.enqueue(followUp, QueueKind.FOLLOW_UP)

        val delivered = mutableListOf<ChatMessage>()
        val any = kotlinx.coroutines.runBlocking {
            queue.submitPending(QueueKind.STEER) { delivered += it }
        }

        assertTrue(any)
        assertEquals(listOf(steer), delivered)
        assertEquals(listOf(followUp), queue.messages.value.map { it.message })
    }

    @Test
    fun submitPendingOnAbsentKindDeliversNothingAndLeavesQueueUntouched() {
        val steer = message("插话")
        val queue = TurnQueue()
        queue.enqueue(steer, QueueKind.STEER)

        val any = kotlinx.coroutines.runBlocking {
            queue.submitPending(QueueKind.FOLLOW_UP) { error("不该有提交") }
        }

        assertEquals(false, any)
        assertEquals(1, queue.messages.value.size)
    }

    @Test
    fun cancelledSubmitReturnsTakenMessageToTheFrontInOrder() {
        // U21 判据的单元面：提交被 abort 取消 → 消息归还队首、保持原序，
        // 既不在「途中」丢，也不会被插到后来者后面。
        val first = message("起用中被 abort 打断")
        val second = message("后面排着的")
        val queue = TurnQueue()
        queue.enqueue(first, QueueKind.FOLLOW_UP)
        queue.enqueue(second, QueueKind.FOLLOW_UP)

        val attempted = mutableListOf<ChatMessage>()
        var cancelled = false
        kotlinx.coroutines.runBlocking {
            try {
                queue.submitPending(QueueKind.FOLLOW_UP) {
                    attempted += it
                    throw kotlinx.coroutines.CancellationException("abort 落进提交窗口")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
        }

        assertTrue("取消必须向上抛（不是被吞成正常返回）", cancelled)
        assertEquals(listOf(first), attempted) // 只起用过队首一条
        assertEquals(listOf(first, second), queue.messages.value.map { it.message }) // 归还队首、保序
    }

    @Test
    fun removeDropsOnlyTheMatchedMessage() {
        val keep = message("留下")
        val drop = message("取消")
        val queue = TurnQueue()
        queue.enqueue(keep, QueueKind.FOLLOW_UP)
        queue.enqueue(drop, QueueKind.FOLLOW_UP)

        queue.remove(drop.id)

        assertEquals(listOf(keep), queue.messages.value.map { it.message })
    }

    @Test
    fun clearDropsUserInputButKeepsSystemNotes() {
        // U18：通知按步投递后，队列是它的在途状态 —— abort / 删除消息清队列时
        // 清的是「用户还没说出口的话」，不能把 agent 自己的完成通知一起清没。
        val note = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Text("[background task t1: done]")),
            isSystemNote = true,
        )
        val user = message("停止前打了一半的话")
        val queue = TurnQueue()
        queue.enqueue(note, QueueKind.STEER)
        queue.enqueue(user, QueueKind.FOLLOW_UP)

        queue.clear()

        assertEquals(listOf(note), queue.messages.value.map { it.message })
    }
}
