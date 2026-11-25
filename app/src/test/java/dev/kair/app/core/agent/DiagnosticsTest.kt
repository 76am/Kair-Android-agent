package dev.kair.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Diagnostics] 的归属语义：按会话覆盖式记录，随会话关闭清理。 */
class DiagnosticsTest {

    @Test
    fun `each assembly overwrites the session entry`() {
        val diagnostics = Diagnostics()
        val failed = listOf(SectionIssue("plugin.a", SectionIssue.Kind.FAILED, "IllegalStateException"))

        diagnostics.recordAssembly("s1", failed)
        assertEquals(mapOf("s1" to failed), diagnostics.sectionIssues.value)

        // 下一回合恢复正常：旧条目必须消失，否则面板会一直报一个已经好了的段落。
        diagnostics.recordAssembly("s1", emptyList())
        assertTrue(diagnostics.sectionIssues.value["s1"].orEmpty().isEmpty())
    }

    @Test
    fun `remove drops only that session`() {
        val diagnostics = Diagnostics()
        val issue = SectionIssue("plugin.a", SectionIssue.Kind.ABSENT)

        diagnostics.recordAssembly("s1", listOf(issue))
        diagnostics.recordAssembly("s2", listOf(issue))
        diagnostics.remove("s1")

        assertEquals(setOf("s2"), diagnostics.sectionIssues.value.keys)
    }

    @Test
    fun `delivery failures are counted by kind`() {
        val diagnostics = Diagnostics()
        assertEquals(DeliveryFailures(), diagnostics.deliveryFailures.value)

        diagnostics.recordDeliveryFailure(DeliveryFailureKind.PERMANENT)
        diagnostics.recordDeliveryFailure(DeliveryFailureKind.TEMPORARY)
        diagnostics.recordDeliveryFailure(DeliveryFailureKind.TEMPORARY)

        // 永久与临时分开计数：前者要用户去改配置，后者说明下个周期会重试。
        assertEquals(DeliveryFailures(permanent = 1, temporary = 2), diagnostics.deliveryFailures.value)
    }

    @Test
    fun `removing a session does not touch delivery counters`() {
        val diagnostics = Diagnostics()
        diagnostics.recordDeliveryFailure(DeliveryFailureKind.PERMANENT)
        diagnostics.recordAssembly("s1", listOf(SectionIssue("plugin.a", SectionIssue.Kind.ABSENT)))

        diagnostics.remove("s1")

        assertEquals(DeliveryFailures(permanent = 1), diagnostics.deliveryFailures.value)
    }

    @Test
    fun `compaction failure is overwritten and cleared by a later success`() {
        val diagnostics = Diagnostics()
        diagnostics.recordCompactionFailure("s1", "IllegalStateException: 摘要请求失败")
        assertEquals("IllegalStateException: 摘要请求失败", diagnostics.compactionFailures.value["s1"])

        // 覆盖式：下一次失败替换上一次，不追加 —— 面板只该显示「最近一次为什么降级」。
        diagnostics.recordCompactionFailure("s1", "IOException: 落盘失败")
        assertEquals("IOException: 落盘失败", diagnostics.compactionFailures.value["s1"])

        // 摘要成功即失败不再成立：条目必须消失，否则面板永远挂着一个已经好了的降级。
        diagnostics.clearCompactionFailure("s1")
        assertNull(diagnostics.compactionFailures.value["s1"])
    }

    @Test
    fun `removing a session drops its compaction failure too`() {
        val diagnostics = Diagnostics()
        diagnostics.recordCompactionFailure("s1", "boom")

        diagnostics.remove("s1")

        assertTrue(diagnostics.compactionFailures.value.isEmpty())
    }
}
