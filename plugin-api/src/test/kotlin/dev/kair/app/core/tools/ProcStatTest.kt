package dev.kair.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/proc/<pid>/stat` PPID 解析：进程名（第 2 字段）可能含空格与括号，必须从**最后一个**
 * `)` 之后取状态 / PPID，否则下载 / 编译类命令的中文路径一出现就会切错字段。
 */
class ProcStatTest {

    @Test
    fun `parses the ppid after the process name`() {
        assertEquals(1L, parseProcStatPpid("123 (sh) S 1 123 123 0 -1 4194560"))
        assertEquals(45L, parseProcStatPpid("900 (my worker) R 45 900 900 0 -1 0"))
    }

    @Test
    fun `handles names containing parentheses`() {
        assertEquals(7L, parseProcStatPpid("12 (a (b) c) S 7 12 12 0 -1 0"))
    }

    @Test
    fun `rejects malformed stat lines`() {
        assertNull(parseProcStatPpid("not a stat line"))
        assertNull(parseProcStatPpid("12 (sh"))
        assertNull(parseProcStatPpid("12 (sh) S"))
        assertNull(parseProcStatPpid("12 (sh) S nope 12"))
    }
}
