package dev.kair.app.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具参数收尾：`ToolCallsCompleted` 的全文必须覆盖流式前缀。
 *
 * 现场（2026-11-19 真机）：`edit` 卡在 `{"edits":` / 3 tok，直到工具输出回来都没变 ——
 * 兼容端点只把参数开头几个字符当增量推出，其余全在收尾帧；旧逻辑「累积器非空就不采信
 * 收尾帧」让界面和工具都停在半截 JSON。这里守的就是这条合并规则。
 */
class ToolArgsSettleTest {

    private fun settle(streamed: String, completed: String): String =
        StringBuilder(streamed).apply { settleToolArguments(completed) }.toString()

    @Test
    fun `the completed arguments win over a streamed prefix`() {
        val streamed = """{"edits":"""
        val full = """{"edits":[{"path":"src/main.js","newText":"const x = 1\n"}]}"""
        assertEquals(full, settle(streamed, full))
    }

    @Test
    fun `an empty completed frame keeps what was streamed`() {
        // 无参数调用：收尾帧给空串，不能把已收到的内容清掉。
        assertEquals("""{"path":"/projects/01"}""", settle("""{"path":"/projects/01"}""", ""))
        assertEquals("", settle("", ""))
    }

    @Test
    fun `completed arguments are used when nothing was streamed`() {
        assertEquals("""{"cmd":"ls"}""", settle("", """{"cmd":"ls"}"""))
    }
}
