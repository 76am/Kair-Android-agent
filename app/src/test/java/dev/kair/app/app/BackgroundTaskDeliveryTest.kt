package dev.kair.app.app

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完成通知的投递语义（U18 起）：**不等会话空闲** —— 交给 sink（steer 语义）后核对落地，
 * 去重、补投、三次放弃。
 *
 * 旧判据「等不到空闲就放弃」随等空闲一起删除：现在忙时通知进 STEER 队列，
 * 「进了队列」就算送达（队列对系统通知丢不掉），所以不再存在「永久等空闲」这个形态。
 */
class BackgroundTaskDeliveryTest {

    /** [running] = true 时模拟「会话忙」：sendNote 只入队列（STEER），不直接进历史。 */
    private class FakeSink(private val running: Boolean = false) : NoteSink {
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val sent = mutableListOf<String>()
        var queued: List<ChatMessage> = emptyList()

        /** 模拟「投出去之后被外力弄丢」（旧实现里是 abort 的 clearQueue）。 */
        var dropNext = false
        var dropAll = false

        override fun queuedMessages(): List<ChatMessage> = queued

        override fun sendNote(text: String) {
            sent += text
            if (dropNext) {
                dropNext = false
                return
            }
            if (dropAll) return
            val msg = noteMessage(text)
            if (running) {
                queued = queued + msg
            } else {
                messages.value = messages.value + msg
            }
        }
    }

    private fun note(text: String) = noteMessage(text)

    @Test
    fun `a note is handed over while the turn is still running`() = runBlocking {
        // U18 的核心判据：忙时不等待 —— 通知进队列即算送达（下一步请求前并入历史）。
        val sink = FakeSink(running = true)
        val ok = withTimeout(5_000) { deliverNote({ sink }, "t1", "[background task t1: done]") }
        assertTrue(ok)
        assertEquals(1, sink.sent.size)
        assertTrue("忙时通知应落在队列里等下一步并入", hasNote(emptyList(), sink.queued, "t1"))
        assertFalse("不该在忙时直接写历史", hasNote(sink.messages.value, emptyList(), "t1"))
    }

    @Test
    fun `a note reaches history when the session is idle`() = runBlocking {
        val sink = FakeSink()
        val ok = deliverNote({ sink }, "t1", "[background task t1: done]")
        assertTrue(ok)
        assertEquals(1, sink.sent.size)
        assertTrue(hasNote(sink.messages.value, emptyList(), "t1"))
    }

    @Test
    fun `a note already in history is not sent again`() = runBlocking {
        val sink = FakeSink()
        sink.messages.value = listOf(note("[background task t1: done]"))
        val ok = deliverNote({ sink }, "t1", "[background task t1: done]")
        assertTrue(ok)
        assertTrue("已经在历史里就不再追加", sink.sent.isEmpty())
    }

    @Test
    fun `a note still in the queue is not sent again`() = runBlocking {
        val sink = FakeSink()
        sink.queued = listOf(note("[background task t1: queued]"))
        val ok = deliverNote({ sink }, "t1", "[background task t1: done]")
        assertTrue(ok)
        assertTrue(sink.sent.isEmpty())
    }

    @Test
    fun `a session that is already gone drops the note`() = runBlocking {
        val ok = deliverNote({ null }, "t1", "[background task t1: done]")
        assertFalse(ok)
    }

    @Test
    fun `a note swallowed once is delivered on the retry`() = runBlocking {
        val sink = FakeSink().apply { dropNext = true }
        val ok = withTimeout(15_000) { deliverNote({ sink }, "t1", "[background task t1: done]") }
        assertTrue("投出去被弄丢后应补投", ok)
        assertEquals(2, sink.sent.size)
        assertTrue(hasNote(sink.messages.value, emptyList(), "t1"))
    }

    @Test
    fun `delivery gives up after three lost attempts`() = runBlocking {
        // 有界：不能无限补投把同一条通知刷成刷屏；三次都丢就放弃（消息会丢，但不挂协程）。
        val sink = FakeSink().apply { dropAll = true }
        val ok = withTimeout(15_000) { deliverNote({ sink }, "t1", "[background task t1: done]") }
        assertFalse(ok)
        assertEquals(3, sink.sent.size)
    }
}

private fun noteMessage(text: String) =
    ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)), isSystemNote = true)
