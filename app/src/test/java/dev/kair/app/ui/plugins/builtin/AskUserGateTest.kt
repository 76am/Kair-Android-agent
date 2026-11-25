package dev.kair.app.ui.plugins.builtin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提问闸门：挂起等待、按 id 作答、按会话并行、排队不计入超时。 */
class AskUserGateTest {

    private fun req(id: String, sessionId: String? = "s1") =
        AskUserRequest(id = id, question = "continue?", options = emptyList(), sessionId = sessionId)

    private fun AskUserGate.firstPending() = pending.value.values.firstOrNull()

    @Test
    fun `waits until the user answers`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 5_000)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("q1")) }

        assertEquals("q1", gate.firstPending()?.id)
        gate.resolve("q1", "yes")
        assertEquals("yes", pending.await())
        assertNull(gate.firstPending())
    }

    @Test
    fun `wrong id is ignored`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 5_000)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("q1")) }

        gate.resolve("other", "nope")
        assertEquals("q1", gate.firstPending()?.id)

        gate.resolve("q1", "ok")
        assertEquals("ok", pending.await())
    }

    @Test
    fun `times out and clears pending when nobody answers`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 50)
        assertNull(gate.ask(req("q1")))
        assertNull(gate.firstPending())
    }

    @Test
    fun `subagent request can be owned by the parent session`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 5_000)
        val request = req("q1", sessionId = "child").copy(ownerSessionId = "parent", originLabel = "explorer")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(request) }

        assertEquals("parent", gate.firstPending()?.ownerSessionId)
        assertEquals("child", gate.firstPending()?.sessionId)
        gate.resolve("q1", "go on")
        assertEquals("go on", pending.await())
    }

    @Test
    fun `different sessions are pending in parallel`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 5_000)
        val a = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("a1", sessionId = "A")) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("b1", sessionId = "B")) }

        assertEquals(setOf("a1", "b1"), gate.pending.value.values.map { it.id }.toSet())
        gate.resolve("b1", "B-answer")
        assertEquals("B-answer", b.await())
        assertEquals("a1", gate.firstPending()?.id)
        gate.resolve("a1", "A-answer")
        assertEquals("A-answer", a.await())
    }

    @Test
    fun `queueing time is not counted by the timeout`() = runBlocking {
        val gate = AskUserGate(timeoutMillis = 400)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("q1")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.ask(req("q2")) }

        // 第二条已排队 250ms（超过自身超时的一半）：排队期间不该计时。
        delay(250)
        assertEquals("q1", gate.firstPending()?.id)
        gate.resolve("q1", "one")
        assertEquals("one", first.await())

        val start = System.currentTimeMillis()
        assertNull(second.await())
        val waited = System.currentTimeMillis() - start
        // 修复前超时从进入 ask 起算，排队已消耗 250ms，只剩约 150ms。
        assertTrue("second timed out after $waited ms", waited >= 250)
    }
}
