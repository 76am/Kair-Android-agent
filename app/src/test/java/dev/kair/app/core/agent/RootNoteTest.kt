package dev.kair.app.core.agent

import dev.kair.app.core.tools.LocalShellTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Root note 的文本与工具名同源（U116）。 */
class RootNoteTest {

    @Test
    fun `android shell tool name matches the built in tool`() {
        // root note 的适用性前置看的是这个常量；工具改名而常量不跟着变，note 会静默消失。
        assertEquals("android_shell", LocalShellTool().name)
        assertEquals(LocalShellTool().name, ANDROID_SHELL_TOOL_NAME)
    }

    @Test
    fun `granted and denied notes are distinct and carry the marker`() {
        val granted = rootNoteText(true)
        val denied = rootNoteText(false)
        assertTrue(granted.startsWith(ROOT_NOTE_MARKER))
        assertTrue(denied.startsWith(ROOT_NOTE_MARKER))
        assertNotEquals("授权收回时必须能写出与正向 note 不同的纠正句", granted, denied)
        assertTrue(granted.contains("granted"))
        assertTrue(denied.contains("not confirmed"))
    }
}
