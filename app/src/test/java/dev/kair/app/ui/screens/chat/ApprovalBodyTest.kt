package dev.kair.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 审批卡片正文解包：shell 命令取 `command` 字段按真实换行铺开，其余原样返回 JSON。 */
class ApprovalBodyTest {

    @Test
    fun `unwraps the command field with real newlines`() {
        val raw = """{"command":"cd /tmp\nrm -rf apicheck","timeout":30}"""
        assertEquals("cd /tmp\nrm -rf apicheck", approvalBody(raw))
    }

    @Test
    fun `keeps raw json when there is no command field`() {
        val raw = """{"path":"a.txt","content":"hi"}"""
        assertEquals(raw, approvalBody(raw))
    }

    @Test
    fun `falls back to the raw text when it is not valid json`() {
        assertEquals("not json", approvalBody("not json"))
    }
}
