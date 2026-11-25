package dev.kair.app.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SessionRegistry] 的行为，全部用假会话 —— `AgentSession` 有 22 个构造参数，
 * 在单测里造不出来，这也是注册表泛型化的原因。
 *
 * 这批用例守的是 ADR 0005 的两条硬指标：
 * 1. **关闭时清空每一项登记资源**，不是抽样；
 * 2. **永不卸载运行中的会话**，且「清单有机械约束力」这句话本身要被验证
 *    （见 [cleanupListCoversEveryStep] 与 [mismatchIsReported] 的一正一反）。
 *
 * 时间源注入（[clock]）不是洁癖：不注入的话，同一毫秒内打开的多个会话
 * `accessedAt` 相同，`sortedBy` 会退化成 `ConcurrentHashMap` 的桶序，
 * 于是「LRU 先淘汰谁」和「忙碌会话会不会被踢」都变成**时红时绿**的断言 ——
 * 那比漏测更糟，因为绿的时候看起来像验过了。
 */
class SessionRegistryTest {

    private class FakeSession(val id: String, val workspace: File, var idle: Boolean = true) {
        var closed = false
        var closeCount = 0

        fun discard() {
            closed = true
            closeCount++
        }
    }

    private class RecordingResources : SessionResources {
        /** 按调用顺序记录，用来断言清理的**顺序**而不只是有无。 */
        val calls = mutableListOf<String>()

        override fun releaseApprovalRules(id: String) {
            calls += "approval:$id"
        }

        override fun releasePendingQuestions(id: String) {
            calls += "askPending:$id"
        }

        override fun abortSubagents(id: String) {
            calls += "subagents:$id"
        }

        override fun releaseBackgroundTasks(id: String) {
            calls += "backgroundTasks:$id"
        }

        override fun cancelTrackJob(id: String) {
            calls += "trackJob:$id"
        }

        override fun removeRunningMark(id: String) {
            calls += "runningMark:$id"
        }

        override fun releaseCacheRecords(id: String) {
            calls += "cache:$id"
        }

        override fun releaseDiagnostics(id: String) {
            calls += "diagnostics:$id"
        }

        override fun releaseRequestRecords(id: String) {
            calls += "requests:$id"
        }

        override fun releaseSendWindow(id: String) {
            calls += "sendWindow:$id"
        }
    }

    private val resources = RecordingResources()
    private var clock = 0L

    private fun registry(maxOpen: Int = SessionRegistry.DEFAULT_MAX_OPEN) = SessionRegistry<FakeSession>(
        resources = resources,
        describe = { s -> SessionDescriptor(s.id, s.workspace, s.idle) },
        discardAction = { s ->
            s.discard()
            // 实例关闭也要进同一个序列：否则「实例必须在审批/子智能体之后、
            // 缓存/诊断之前」这条顺序在测试里等于没写。
            resources.calls += "close:${s.id}"
        },
        maxOpen = maxOpen,
        now = { ++clock },
    )

    private fun dir(name: String) = File("workspace-$name")

    // ---------------------------------------------------------------- 幂等与取出

    @Test
    fun `open is idempotent and does not rebuild the instance`() {
        val r = registry()
        var created = 0
        val first = r.open("s1", emptySet()) { created++; FakeSession("s1", dir("a")) }
        val second = r.open("s1", emptySet()) { created++; FakeSession("s1", dir("a")) }

        assertEquals("重复 open 不得重建实例（历史会丢）", 1, created)
        assertTrue("同一个 id 必须返回同一个实例", first === second)
        assertEquals(1, r.size)
    }

    @Test
    fun `get returns only what was opened`() {
        val r = registry()
        r.open("s1", emptySet()) { FakeSession("s1", dir("a")) }

        assertNotNull(r.get("s1"))
        assertNull("没打开过的会话必须查不到，不能顺手创建", r.get("s2"))
        assertEquals(setOf("s1"), r.ids)
    }

    // ---------------------------------------------------------------- 关闭 = 清空全部

    @Test
    fun `close clears every registered resource`() {
        val r = registry()
        val session = FakeSession("s1", dir("a"))
        r.open("s1", emptySet()) { session }

        assertTrue(r.release("s1"))

        // 逐项断言，不抽样。顺序本身是语义：
        // 审批与子智能体要在实例关闭之前（它们要读还活着的会话），
        // 缓存/诊断/请求记录要在之后（会话自己可能还在写）。
        assertEquals(
            "关闭必须清掉全部登记资源，且顺序固定",
            listOf(
                "approval:s1",
                "askPending:s1",
                "subagents:s1",
                "backgroundTasks:s1",
                "trackJob:s1",
                "runningMark:s1",
                "close:s1",
                "cache:s1",
                "diagnostics:s1",
                "requests:s1",
                "sendWindow:s1",
            ),
            resources.calls,
        )
        assertTrue("实例本身必须被 close", session.closed)
        assertEquals(1, session.closeCount)
        assertEquals("关掉后不得再留在注册表里", 0, r.size)
        assertNull(r.get("s1"))
    }

    @Test
    fun `repeated close is idempotent`() {
        val r = registry()
        val session = FakeSession("s1", dir("a"))
        r.open("s1", emptySet()) { session }

        assertTrue(r.release("s1"))
        assertFalse("重复关闭返回 false，但不报错（UI 上会真实发生）", r.release("s1"))
        assertEquals("实例只能被关一次", 1, session.closeCount)
        assertEquals("不在册了，不得再留在注册表里", 0, r.size)
    }

    @Test
    fun `close on a session that was never opened still releases its resources`() {
        val r = registry()

        assertFalse("没打开过就一定返回 false", r.release("ghost"))

        // 审批规则、子智能体、请求记录都不依赖实例存在：删除一个从未打开过的会话
        // （deleteSession 的常见情形）时它们仍必须清 —— 前实现 releaseSession 对这些
        // 也照样调 endSession / abortChildrenOf，这条用例就是保住那个语义。
        assertEquals(
            "没有实例，所以序列里不应出现 close:ghost",
            listOf(
                "approval:ghost",
                "askPending:ghost",
                "subagents:ghost",
                "backgroundTasks:ghost",
                "trackJob:ghost",
                "runningMark:ghost",
                "cache:ghost",
                "diagnostics:ghost",
                "requests:ghost",
                "sendWindow:ghost",
            ),
            resources.calls,
        )
    }

    // ---------------------------------------------------------------- 清单的机械约束力

    @Test
    fun `cleanupListCoversEveryStep`() {
        val r = registry()

        assertNull(
            "清理清单必须与 Cleanup 枚举逐项对应（顺序也算）：新增一项登记资源却没列进清单，" +
                "关闭时就会漏清 —— 这一类缺陷只能靠机械核验，不能靠记性",
            SessionRegistry.cleanupMismatch(r.cleanupSteps),
        )
        assertEquals(SessionRegistry.Cleanup.entries.toList(), r.cleanupSteps)
    }

    @Test
    fun `mismatchIsReported`() {
        // 反向：这一条证明上面的检查**真的会红**，而不是「现在恰好绿」。
        val missing = SessionRegistry.cleanupMismatch(SessionRegistry.Cleanup.entries.drop(1))
        assertNotNull("少列一项必须被判为不一致", missing)
        assertTrue("报错要点名缺了什么，实际：$missing", missing?.contains("缺少") == true)

        val duplicated = SessionRegistry.Cleanup.entries.toList() + SessionRegistry.Cleanup.AccessTime
        assertNotNull("重复列同一项必须被判为不一致", SessionRegistry.cleanupMismatch(duplicated))
    }

    // ---------------------------------------------------------------- 淘汰

    @Test
    fun `evict drops the least recently used idle session`() {
        val r = registry(maxOpen = 2)
        r.open("s1", emptySet()) { FakeSession("s1", dir("a")) }
        r.open("s2", emptySet()) { FakeSession("s2", dir("a")) }
        r.open("s3", emptySet()) { FakeSession("s3", dir("a")) }

        assertEquals("超上限后必须淘汰到上限为止", 2, r.size)
        assertNull("最早打开的空闲会话先走", r.get("s1"))
        assertNotNull(r.get("s2"))
        assertNotNull(r.get("s3"))
    }

    @Test
    fun `busySessionIsNeverEvicted`() {
        val r = registry(maxOpen = 2)
        // busy 是**最老**的（t=1）：不淘汰它就腾不出地方，所以这条用例
        // 在「去掉 isIdle 过滤」时会必然红，不靠哈希顺序侥幸通过。
        r.open("busy", emptySet()) { FakeSession("busy", dir("a"), idle = false) }
        r.open("idle-old", emptySet()) { FakeSession("idle-old", dir("a")) }
        r.open("idle-new", emptySet()) { FakeSession("idle-new", dir("a")) }

        assertEquals(2, r.size)
        assertNotNull("运行中的会话即使最老也不得被淘汰", r.get("busy"))
        assertNull("要腾地方只能淘汰空闲的那个", r.get("idle-old"))
        assertNotNull(r.get("idle-new"))
    }

    @Test
    fun `keptSessionIsNeverEvicted`() {
        val r = registry(maxOpen = 2)
        r.open("s1", emptySet()) { FakeSession("s1", dir("a")) }
        r.open("s2", emptySet()) { FakeSession("s2", dir("a")) }
        // 显式把 s1 钉住（「当前会话」由 AppContainer 决定，注册表不认识这个概念）。
        r.open("s3", setOf("s1")) { FakeSession("s3", dir("a")) }

        assertNotNull("钉住的会话不得淘汰", r.get("s1"))
        assertNull("只能动没被钉住的那个", r.get("s2"))
    }

    @Test
    fun `evict does not end the session`() {
        val r = registry(maxOpen = 1)
        r.open("s1", emptySet()) { FakeSession("s1", dir("a")) }
        r.open("s2", emptySet()) { FakeSession("s2", dir("a")) }

        assertEquals("淘汰不是结束", 1, r.size)
        // 精确断言：淘汰只回收内存，不清审批（用户「本会话放行」不能因内存回收失效）、
        // 不中止子智能体（可淘汰的前提就是空闲）、不清请求记录（排查材料要留着）。
        assertEquals(
            "淘汰路径只该做这五件事",
            listOf("trackJob:s1", "runningMark:s1", "close:s1", "cache:s1", "diagnostics:s1"),
            resources.calls,
        )
        assertFalse(
            "淘汰**绝不能**清限流窗口：淘汰后会话 id 还在用，清掉槽等于把「每分钟 N 条」的额度重置，" +
                "那是一条可以绕过的限流。限流窗口只随关闭清（Cleanup.SendWindow）。",
            resources.calls.any { it.startsWith("sendWindow:") },
        )
    }

    @Test
    fun `session that turns busy between filtering and unloading is skipped`() {
        val seen = mutableMapOf<String, Int>()
        var now = 0L
        val r = SessionRegistry<FakeSession>(
            resources = resources,
            describe = { s ->
                val n = (seen[s.id] ?: 0) + 1
                seen[s.id] = n
                // racer 第一次被问是在筛选阶段（空闲），第二次被问即将卸载时已转忙 ——
                // 模拟「用户刚发了一条消息」，这个窗口在真实并发下是存在的。
                SessionDescriptor(s.id, s.workspace, if (s.id == "racer") n == 1 else s.idle)
            },
            discardAction = { s -> s.discard() },
            maxOpen = 1,
            now = { ++now },
        )
        r.open("racer", emptySet()) { FakeSession("racer", dir("a")) }
        r.open("other", emptySet()) { FakeSession("other", dir("a")) }

        assertNotNull(
            "筛选时空闲、卸载前转忙的会话必须**跳过**：不能卸载（会把正在跑的对话打掉），" +
                "也不能抛异常（会从 openSession 冒到 UI 线程崩掉）。宁可超上限，下次淘汰再说。",
            r.get("racer"),
        )
    }

    // ---------------------------------------------------------------- 按工作区批量关闭

    @Test
    fun `releaseWhere releases only the sessions of that workspace`() {
        val r = registry()
        val target = dir("target")
        val other = dir("other")
        r.open("a", emptySet()) { FakeSession("a", target) }
        r.open("b", emptySet()) { FakeSession("b", target) }
        r.open("c", emptySet()) { FakeSession("c", other) }

        val closed = r.releaseWhere { it.workspace.absolutePath == target.absolutePath }

        assertEquals(2, closed)
        assertEquals("只关该项目下的会话", setOf("c"), r.ids)
        // 两个会话谁先被关取决于哈希序，所以不能断言「前 8 项是 a 的」——
        // 要断言的是：每个被关的会话都出现了一段完整且有序的清理序列。
        listOf("a", "b").forEach { id ->
            val expected = listOf(
                "approval:$id", "askPending:$id", "subagents:$id", "backgroundTasks:$id", "trackJob:$id", "runningMark:$id",
                "close:$id", "cache:$id", "diagnostics:$id", "requests:$id", "sendWindow:$id",
            )
            assertTrue(
                "会话 $id 的清理序列必须完整且有序（含请求记录），实际：${resources.calls}",
                resources.calls.windowed(expected.size).any { it == expected },
            )
        }
        assertFalse("另一个项目的会话不得被清", resources.calls.any { it.endsWith(":c") })
    }

    @Test
    fun `anyBusyWhere sees a session that is not idle`() {
        val r = registry()
        val target = dir("target")
        r.open("a", emptySet()) { FakeSession("a", target, idle = true) }
        r.open("b", emptySet()) { FakeSession("b", target, idle = false) }

        // 项目改名前的判据：只要该项目下有一个会话「此刻不能静默关闭」，就不搬目录。
        assertTrue(r.anyBusyWhere { it.workspace.absolutePath == target.absolutePath })
        assertFalse(
            "别的项目忙不阻塞本项目",
            r.anyBusyWhere { it.workspace.absolutePath == dir("other").absolutePath },
        )
        // 忙状态是现算的：会话转空闲后同一个谓词立刻为假（不是缓存的快照）。
        r.get("b")?.idle = true
        assertFalse(r.anyBusyWhere { it.workspace.absolutePath == target.absolutePath })
    }
}
