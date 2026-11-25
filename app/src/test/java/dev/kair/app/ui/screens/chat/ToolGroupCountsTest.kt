package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.Part
import org.junit.Assert.assertEquals
import org.junit.Test

/** 工具折叠块标题的归类计数（纯函数，标题文案由 @Composable 层拼装）。 */
class ToolGroupCountsTest {

    private fun call(name: String) = Part.ToolCall(id = "$name-${counter++}", name = name, arguments = "{}")
    private var counter = 0

    @Test
    fun `single call yields one entry`() {
        assertEquals(listOf("edit" to 1), toolGroupCounts(listOf(call("edit"))))
    }

    @Test
    fun `same category calls are counted together`() {
        assertEquals(listOf("read" to 3), toolGroupCounts(listOf(call("read"), call("read"), call("read"))))
    }

    @Test
    fun `write and edit share the edit category`() {
        assertEquals(listOf("edit" to 2), toolGroupCounts(listOf(call("write"), call("edit"))))
    }

    @Test
    fun `mixed categories keep first-seen order`() {
        val calls = listOf(call("bash"), call("read"), call("read"), call("ls"), call("grep"))
        assertEquals(
            listOf("command" to 1, "read" to 2, "browse" to 1, "search" to 1),
            toolGroupCounts(calls),
        )
    }

    @Test
    fun `unknown tools fall back to other`() {
        assertEquals(listOf("other" to 2), toolGroupCounts(listOf(call("mystery"), call("mystery2"))))
    }
}
