package dev.kair.app.core.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子智能体默认超时（U58 ③）。
 *
 * 三态：缺省 = 一小时（3600s）、`0` = 不限时、显式值原样。另外钉住模型可见的三处文案
 * （promptSnippet / description / timeout 参数）都写着 3600 / one hour —— 只改常量不改
 * 提示词，模型仍会照着 300s 规划任务。
 *
 * 文案断言作用在**活实例的属性**上，不读源码文本：扫原文会被注释里的同名词骗过
 * （B 审改；原实现读 `Subagent.kt` 原文，注释里写一句 `3600s` 就能洗绿）。
 */
class SubagentTimeoutTest {

    @Test
    fun `缺省是一小时`() {
        assertEquals(3600, subagentTimeoutSeconds(null))
        assertEquals(3600, SubagentTool.DEFAULT_TIMEOUT_SECONDS)
    }

    @Test
    fun `零表示不限时`() {
        assertEquals(0, subagentTimeoutSeconds(0))
    }

    @Test
    fun `显式值原样保留`() {
        assertEquals(120, subagentTimeoutSeconds(120))
        assertEquals(7200, subagentTimeoutSeconds(7200))
    }

    @Test
    fun `模型可见文案写明一小时`() {
        val tool = tool()

        assertTrue("promptSnippet 必须写 3600", tool.promptSnippet.contains("3600"))
        assertTrue("promptSnippet 必须写 one hour", tool.promptSnippet.contains("one hour"))
        assertTrue("description 必须写 3600", tool.description.contains("3600"))
        assertTrue("description 必须明确「至少一小时」", tool.description.contains("at least one hour"))
        assertTrue("timeout 参数必须明确「至少一小时」", tool.parameters.toString().contains("at least one hour"))
    }

    /** 文案用例不委派任务：两个依赖都给「被用到就炸」的桩。 */
    private fun tool(): SubagentTool = SubagentTool(
        SubagentManager(
            createSession = { _, _, _, _, _ -> error("文案用例不该创建子智能体会话") },
            workspaceOf = { null },
        ),
    )
}
