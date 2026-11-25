package dev.kair.app.core.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 子智能体 → 顶层父会话的解析：审批卡片据此显示在父会话。 */
class SubagentRootTest {

    private fun info(id: String, parentId: String?) =
        SubagentInfo(id, parentId, id, "task", "working", 0L, 0L)

    @Test
    fun `nested subagent resolves to top level session`() {
        val agents = listOf(info("child", "mid"), info("mid", "root"))
        assertEquals("root", SubagentManager.rootSessionOf(agents, "child"))
    }

    @Test
    fun `direct child resolves to its parent session`() {
        val agents = listOf(info("child", "root"))
        assertEquals("root", SubagentManager.rootSessionOf(agents, "child"))
    }

    @Test
    fun `normal session is not a subagent`() {
        assertNull(SubagentManager.rootSessionOf(listOf(info("child", "root")), "root"))
    }

    @Test
    fun `subagent without parent has no owner session`() {
        assertNull(SubagentManager.rootSessionOf(listOf(info("child", null)), "child"))
    }

    @Test
    fun `cycle in parent chain does not loop forever`() {
        val agents = listOf(info("a", "b"), info("b", "a"))
        assertEquals("a", SubagentManager.rootSessionOf(agents, "a"))
    }
}
