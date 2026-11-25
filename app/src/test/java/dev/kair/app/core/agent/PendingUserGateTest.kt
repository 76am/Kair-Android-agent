package dev.kair.app.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingUserGate] 的行为。用真实超时（毫秒级）而不是虚拟时间 ——
 * 项目还没有 `kotlinx-coroutines-test` 依赖，为这批去加一个测试依赖属于超出范围。
 *
 * 守的是 `P3-2` 列出的五条「必须保住的语义」，外加一条本批新增的（会话结束立即收尾）。
 */
class PendingUserGateTest {

    private data class Req(val id: String, val owner: String)

    private companion object {
        const val TIMEOUT_MS = 80L
        const val LONG_MS = 30_000L
    }

    private fun gate(
        timeout: Long = TIMEOUT_MS,
        fastPath: ((Req) -> String?)? = null,
    ) = PendingUserGate<Req, String>(
        timeoutMillis = timeout,
        displaySessionId = { it.owner },
        idOf = { it.id },
        timeoutAnswer = { "TIMEOUT(${it.id})" },
        fastPath = fastPath,
    )

    /**
     * 等到该会话的待决项出现再继续 —— 否则断言会撞上协程还没启动的窗口。
     *
     * **必须带截止时间**。第一版是裸的 `while (... ) yield()`，某个用例的条件永远不成立时
     * 它就在那儿空转：2026-10-28 凌晨那次全量，测试 JVM 跑了 **5.8 小时、CPU 100%**，
     * 整条流水线挂死。**一个会挂住流水线的用例，比没有用例更糟** —— 它看起来是「还在跑」。
     */
    private suspend fun waitPending(gate: PendingUserGate<Req, String>, owner: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (gate.pendingSnapshot()[owner] == null) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("5 秒内等不到会话 $owner 的待决项：协程没跑起来，或它已经先收尾了")
            }
            yield()
        }
    }

    // ------------------------------------------------------------ 语义 5：超时返回值

    @Test
    fun `timeout returns the fallback answer and clears the card`() = runBlocking {
        val g = gate()

        val answer = g.await(Req("r1", "s1"))

        assertEquals("TIMEOUT(r1)", answer)
        assertTrue("超时后卡片不得残留", g.pendingSnapshot().isEmpty())
    }

    // ------------------------------------------------------------ 语义 4：resolve 与 id 匹配

    @Test
    fun `resolve delivers the answer to the waiting caller`() = runBlocking {
        val g = gate(timeout = LONG_MS)
        var answer: String? = null
        val job = launch { answer = g.await(Req("r1", "s1")) }
        waitPending(g, "s1")

        g.resolve("r1", "yes")
        job.join()

        assertEquals("yes", answer)
        assertTrue("作答后卡片必须撤下", g.pendingSnapshot().isEmpty())
    }

    @Test
    fun `resolve with an unknown id is ignored`() = runBlocking {
        val g = gate(timeout = LONG_MS)
        val job = launch { g.await(Req("r1", "s1")) }
        waitPending(g, "s1")

        g.resolve("no-such-id", "whatever")

        assertTrue("id 不匹配必须忽略，卡片还在", g.pendingSnapshot()["s1"]?.id == "r1")
        g.resolve("r1", "ok")
        job.join()
    }

    // ------------------------------------------------------------ 语义 2：每会话一条，互不阻塞

    @Test
    fun `different sessions do not block each other`() = runBlocking {
        val g = gate(timeout = LONG_MS)
        val a = async { g.await(Req("a", "s1")) }
        val b = async { g.await(Req("b", "s2")) }
        waitPending(g, "s1")
        waitPending(g, "s2")

        assertEquals(2, g.pendingSnapshot().size)
        g.resolve("a", "A")
        g.resolve("b", "B")

        assertEquals("A", a.await())
        assertEquals("B", b.await())
    }

    // ------------------------------------------------------------ 语义 1：排队不计入超时

    @Test
    fun `queued request stays out of pending until it gets the lock`() = runBlocking {
        // 这条用例的**角度**很重要，我第一版写错了：
        // 原来想「让第二条排队超过超时，看它会不会超时」，但同一个 gate 的超时对所有请求一样，
        // 而排队的上限就是第一条的存活时间（≤ 超时）—— 排队时长根本不可能超过超时。
        // 于是第二条必然超时、pending 永远等不到它，测试空转死循环（5.8 小时那个就是它）。
        //
        // 正确的可观测代理：第二条在拿到锁之前**不进待决表**，而计时是在拿到锁之后才开始的
        // （`withTimeout` 在 `withLock` 内部）—— 所以「不在表里」就等于「还没开始计时」。
        val g = gate(timeout = LONG_MS)
        val first = async { g.await(Req("a", "s1")) }
        waitPending(g, "s1")
        val second = async { g.await(Req("b", "s1")) }

        repeat(8) { yield() }

        assertEquals(
            "排队中的请求不得进入待决表 —— 没进表就说明计时还没开始",
            "a",
            g.pendingSnapshot()["s1"]?.id,
        )

        g.resolve("a", "A")
        assertEquals("A", first.await())
        // 第一条走人、锁释放，第二条这才进表
        waitPending(g, "s1")
        assertEquals("b", g.pendingSnapshot()["s1"]?.id)
        g.resolve("b", "B")
        assertEquals("B", second.await())
    }

    // ------------------------------------------------------------ 语义 3：会话结束立即收尾

    @Test
    fun `endSession settles the pending item immediately`() = runBlocking {
        val g = gate(timeout = LONG_MS)
        var answer: String? = null
        val job = launch { answer = g.await(Req("r1", "s1")) }
        waitPending(g, "s1")

        assertEquals(1, g.endSession("s1"))
        // 不等 30 秒超时：会话都结束了，没人能作答，必须立刻带着兜底答案返回。
        job.join()

        assertEquals("TIMEOUT(r1)", answer)
    }

    @Test
    fun `endSession on a session with nothing pending is a no-op`() = runBlocking {
        val g = gate()

        assertEquals(0, g.endSession("s-nothing"))
    }

    // ------------------------------------------------------------ 短路（审批的放行规则用）

    @Test
    fun `fastPath short circuits without ever hanging`() = runBlocking {
        val g = gate(timeout = LONG_MS, fastPath = { "ALLOWED" })

        val answer = g.await(Req("r1", "s1"))

        assertEquals("ALLOWED", answer)
        assertTrue("短路的请求不得进入待决表", g.pendingSnapshot().isEmpty())
    }

    @Test
    fun `fastPath may decline and fall through to waiting`() = runBlocking {
        val g = gate(timeout = LONG_MS, fastPath = { null })
        var answer: String? = null
        val job = launch { answer = g.await(Req("r1", "s1")) }
        waitPending(g, "s1")

        g.resolve("r1", "answered")
        job.join()

        assertEquals("answered", answer)
    }

    @Test
    fun `timeoutSnapshot is empty before anything is asked`() {
        assertNull(gate().pendingSnapshot()["s1"])
    }

    // ------------------------------------------------------------ 锁内钩子（评审 P1-1）

    /**
     * `postDecision` 必须在**会话锁释放之前**执行。
     *
     * 为什么不用「多轮并发复现」：实测抓不住 —— 把 `applyRule` 改回锁外后，
     * `ApprovalGateTest` 里那条 30 轮并发用例依然全绿（第一条释放锁后在原线程继续跑，
     * 第二条总要先被调度，窗口踩不中）。所以换成结构观测：在钩子里发一条同会话请求，
     * 若此刻锁还没释放，它就不可能进待决表；钩子跑在锁外时这条必红。
     */
    @Test
    fun `postDecision runs before the lane is released`() = runBlocking {
        var secondEnteredPending = true
        lateinit var g: PendingUserGate<Req, String>
        g = PendingUserGate(
            timeoutMillis = 3_000,
            displaySessionId = { it.owner },
            idOf = { it.id },
            timeoutAnswer = { "TIMEOUT" },
            postDecision = { req, _ ->
                if (req.id == "a") {
                    val other = async(Dispatchers.Default) { g.await(Req("b", "s1")) }
                    repeat(10) { yield() }
                    secondEnteredPending = g.pendingSnapshot()["s1"]?.id == "b"
                    other.cancel()
                }
            },
        )

        val first = async(Dispatchers.Default) { g.await(Req("a", "s1")) }
        waitPending(g, "s1")
        g.resolve("a", "OK")
        assertEquals("OK", first.await())

        assertFalse(
            "postDecision 必须在会话锁内执行：钩子里发的第二条同会话请求此刻不该进待决表；" +
                "进得去说明钩子跑在锁外（评审 P1-1）",
            secondEnteredPending,
        )
    }
}
