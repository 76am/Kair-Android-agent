package dev.kair.app.core.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审批闸门：挂起等待用户决定、三级放行规则、按会话并行与同会话排队。 */
class ApprovalGateTest {

    private fun req(
        id: String,
        tool: String = "install_plugin",
        sessionId: String? = "s1",
        rememberable: Boolean = false,
    ) = ApprovalRequest(id, tool, "summary", "{}", sessionId, rememberable)

    private fun ApprovalGate.firstPending() = pending.value.values.firstOrNull()

    // 注：曾试图在这里用「30 轮并发复现」守 P1-1（applyRule 必须在会话锁内），
    // 实测**抓不住** —— 把实现改回锁外写规则，30 轮全绿（第一条释放锁后在原线程继续执行，
    // 第二条总要先被调度，窗口踩不中）。锁序的守卫改在 PendingUserGateTest 用结构方式测：
    // `postDecision runs before the lane is released`。

    @Test
    fun `request suspends until resolved`() = runBlocking {
        val gate = ApprovalGate()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r1")) }

        assertNotNull(gate.firstPending())
        assertEquals("install_plugin", gate.firstPending()!!.ruleKey)

        gate.resolve("r1", ApprovalDecision.DENY)
        assertEquals(ApprovalDecision.DENY, pending.await())
        assertNull(gate.firstPending())
    }

    @Test
    fun `resolve with wrong id is ignored`() = runBlocking {
        val gate = ApprovalGate()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r1")) }

        gate.resolve("other", ApprovalDecision.ALLOW_ONCE)
        assertNotNull(gate.firstPending())

        gate.resolve("r1", ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, pending.await())
    }

    @Test
    fun `session rule allows same tool in same session only`() = runBlocking {
        val gate = ApprovalGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r1", tool = "t")) }
        gate.resolve("r1", ApprovalDecision.ALLOW_SESSION)
        assertEquals(ApprovalDecision.ALLOW_SESSION, first.await())

        assertTrue(gate.isAllowed("t", "s1"))
        assertFalse(gate.isAllowed("t", "s2"))
        // 已放行：直接返回，不再挂起。
        assertEquals(ApprovalDecision.ALLOW_SESSION, gate.request(req("r2", tool = "t")))
    }

    @Test
    fun `always rule persists and spans sessions`() = runBlocking {
        val persisted = mutableListOf<Set<String>>()
        val gate = ApprovalGate(persistAlways = { persisted.add(it) })

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request(req("r1", tool = "t", rememberable = true))
        }
        gate.resolve("r1", ApprovalDecision.ALLOW_ALWAYS)
        assertEquals(ApprovalDecision.ALLOW_ALWAYS, first.await())

        assertTrue(gate.isAllowed("t", "s2"))
        assertEquals(setOf("t"), gate.alwaysRules())
        assertEquals(listOf(setOf("t")), persisted)
    }

    @Test
    fun `always on non-rememberable degrades to session rule`() = runBlocking {
        val gate = ApprovalGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request(req("r1", tool = "t", rememberable = false))
        }
        gate.resolve("r1", ApprovalDecision.ALLOW_ALWAYS)
        assertEquals(ApprovalDecision.ALLOW_ALWAYS, first.await())

        assertTrue(gate.isAllowed("t", "s1"))
        assertFalse(gate.isAllowed("t", "s2"))
        assertTrue(gate.alwaysRules().isEmpty())
    }

    @Test
    fun `same-session request is queued instead of denied`() = runBlocking {
        val gate = ApprovalGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r1", tool = "t1")) }
        assertEquals("r1", gate.firstPending()?.id)

        // 同一会话已有待批请求：第二个请求排队等待，绝不静默拒绝（用户还没看到过卡片）。
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r2", tool = "t2")) }
        assertEquals("r1", gate.firstPending()?.id)

        gate.resolve("r1", ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, first.await())

        // 前一条处理完，第二条才展示出来。
        withTimeout(2_000) {
            while (gate.firstPending()?.id != "r2") yield()
        }
        gate.resolve("r2", ApprovalDecision.DENY)
        assertEquals(ApprovalDecision.DENY, second.await())
        assertNull(gate.firstPending())
    }

    @Test
    fun `different sessions are pending in parallel`() = runBlocking {
        val gate = ApprovalGate()
        val a = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("a1", sessionId = "A")) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("b1", sessionId = "B")) }

        // 两个会话各有一条可见待批请求，互不遮挡。
        assertEquals(setOf("a1", "b1"), gate.pending.value.values.map { it.id }.toSet())
        gate.resolve("b1", ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, b.await())
        assertEquals("a1", gate.firstPending()?.id)
        gate.resolve("a1", ApprovalDecision.DENY)
        assertEquals(ApprovalDecision.DENY, a.await())
    }

    @Test
    fun `queueing time is not counted by the timeout`() = runBlocking {
        val gate = ApprovalGate(timeoutMillis = 400)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r1", tool = "t1")) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.request(req("r2", tool = "t2")) }

        // 第二条已排队 250ms（超过自身超时的一半）：排队期间不该计时。
        delay(250)
        assertEquals("r1", gate.firstPending()?.id)
        gate.resolve("r1", ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, first.await())

        val start = System.currentTimeMillis()
        assertEquals(ApprovalDecision.TIMEOUT, second.await())
        val waited = System.currentTimeMillis() - start
        // 修复前超时从进入 request 起算，排队已消耗 250ms，只剩约 150ms。
        assertTrue("second timed out after $waited ms", waited >= 250)
    }

    @Test
    fun `request times out and clears pending when user does not respond`() = runBlocking {
        // 极短超时：验证「无人操作」不会让调用无限挂起，且卡片状态被清理。
        val gate = ApprovalGate(timeoutMillis = 50)
        assertEquals(ApprovalDecision.TIMEOUT, gate.request(req("r1", tool = "t")))
        assertNull(gate.firstPending())
    }

    @Test
    fun `initial always rules take effect`() {
        val gate = ApprovalGate(initialAlwaysAllowed = setOf("safe_tool"))
        assertTrue(gate.isAllowed("safe_tool", "any-session"))
        assertFalse(gate.isAllowed("other", "any-session"))
    }

    @Test
    fun `clearing rules drops them`() = runBlocking {
        val gate = ApprovalGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request(req("r1", tool = "t", rememberable = true))
        }
        gate.resolve("r1", ApprovalDecision.ALLOW_ALWAYS)
        first.await()

        gate.clearAlwaysRules()
        assertFalse(gate.isAllowed("t", "s1"))
    }

    @Test
    fun `clearSessionRules only clears the given session`() = runBlocking {
        val gate = ApprovalGate()
        suspend fun allowSession(id: String, sessionId: String) {
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                gate.request(req(id, tool = "t", sessionId = sessionId))
            }
            gate.resolve(id, ApprovalDecision.ALLOW_SESSION)
            call.await()
        }
        allowSession("r1", "A")
        allowSession("r2", "B")
        assertTrue(gate.isAllowed("t", "A"))
        assertTrue(gate.isAllowed("t", "B"))

        gate.clearSessionRules("A")
        assertFalse(gate.isAllowed("t", "A"))
        assertTrue(gate.isAllowed("t", "B"))
    }

    @Test
    fun `endSession drops the session rules`() = runBlocking {
        val gate = ApprovalGate()
        val call = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request(req("r1", tool = "t", sessionId = "A"))
        }
        gate.resolve("r1", ApprovalDecision.ALLOW_SESSION)
        call.await()

        gate.endSession("A")
        assertFalse(gate.isAllowed("t", "A"))
    }
}
