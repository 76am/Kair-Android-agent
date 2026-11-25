package dev.kair.app.core.term

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 按键编码用例：方向键模式、Ctrl 映射、输入层文本 diff。
 *
 * 这些是 UI 与 PTY 之间的唯一转码点，错了会出现「方向键变成乱码」「退格删不掉」，
 * 而真机上很难定位，所以在 JVM 里钉死字节。
 */
class TerminalKeysTest {

    @Test
    fun `arrow keys follow cursor key mode`() {
        assertArrayEquals(
            byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()),
            TerminalKeys.arrow(TerminalKeys.Arrow.UP, applicationCursorKeys = false),
        )
        assertArrayEquals(
            byteArrayOf(0x1B, 'O'.code.toByte(), 'D'.code.toByte()),
            TerminalKeys.arrow(TerminalKeys.Arrow.LEFT, applicationCursorKeys = true),
        )
    }

    @Test
    fun `ctrl maps letters case insensitively`() {
        assertArrayEquals(byteArrayOf(0x01), TerminalKeys.ctrl('a'))
        assertArrayEquals(byteArrayOf(0x03), TerminalKeys.ctrl('C'))
        assertArrayEquals(byteArrayOf(0x1A), TerminalKeys.ctrl('z'))
    }

    @Test
    fun `ctrl maps punctuation and rejects unknown`() {
        assertArrayEquals(byteArrayOf(0x00), TerminalKeys.ctrl(' '))
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeys.ctrl('['))
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.ctrl('?'))
        // 扩展键里仅有的两个字符键（/、-）必须可被 CTRL 修饰，否则 armed CTRL 对它们无效。
        assertArrayEquals(byteArrayOf(0x1F), TerminalKeys.ctrl('/'))
        assertArrayEquals(byteArrayOf(0x1F), TerminalKeys.ctrl('-'))
        assertNull(TerminalKeys.ctrl('1'))
    }

    @Test
    fun `home and end follow cursor key mode`() {
        assertArrayEquals(
            byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte()),
            TerminalKeys.home(applicationCursorKeys = false),
        )
        assertArrayEquals(
            byteArrayOf(0x1B, 'O'.code.toByte(), 'F'.code.toByte()),
            TerminalKeys.end(applicationCursorKeys = true),
        )
    }

    @Test
    fun `extra keys encode termux defaults`() {
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeys.extra(TerminalKeys.ExtraKey.ESC, false))
        assertArrayEquals(byteArrayOf(0x09), TerminalKeys.extra(TerminalKeys.ExtraKey.TAB, false))
        assertArrayEquals(
            "/".toByteArray(Charsets.UTF_8),
            TerminalKeys.extra(TerminalKeys.ExtraKey.SLASH, false),
        )
        assertArrayEquals(
            "\u001B[5~".toByteArray(Charsets.US_ASCII),
            TerminalKeys.extra(TerminalKeys.ExtraKey.PGUP, false),
        )
        assertArrayEquals(
            "\u001B[6~".toByteArray(Charsets.US_ASCII),
            TerminalKeys.extra(TerminalKeys.ExtraKey.PGDN, false),
        )
        // 修饰键本身不发字节。
        assertArrayEquals(ByteArray(0), TerminalKeys.extra(TerminalKeys.ExtraKey.CTRL, false))
    }

    @Test
    fun `function keys use xterm sequences`() {
        assertArrayEquals(
            byteArrayOf(0x1B, 'O'.code.toByte(), 'P'.code.toByte()),
            TerminalKeys.fKey(1),
        )
        assertArrayEquals(
            "\u001B[15~".toByteArray(Charsets.US_ASCII),
            TerminalKeys.fKey(5),
        )
        assertArrayEquals(
            "\u001B[24~".toByteArray(Charsets.US_ASCII),
            TerminalKeys.extra(TerminalKeys.ExtraKey.F12, false),
        )
        // 越界序号与 FN 本身都不发字节。
        assertArrayEquals(ByteArray(0), TerminalKeys.fKey(13))
        assertArrayEquals(ByteArray(0), TerminalKeys.extra(TerminalKeys.ExtraKey.FN, false))
    }

    @Test
    fun `diff applies one shot ctrl and alt modifiers`() {
        // CTRL + c → 0x03（中断），ALT + a → ESC + a。
        assertArrayEquals(byteArrayOf(0x03), TerminalKeys.diff("", "c", ctrl = true))
        assertArrayEquals(
            byteArrayOf(0x1B, 'a'.code.toByte()),
            TerminalKeys.diff("", "a", alt = true),
        )
        // 删除仍按退格发，不受修饰键影响。
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.diff("ab", "a", ctrl = true))
    }

    @Test
    fun `diff sends nothing when text is unchanged`() {
        assertArrayEquals(ByteArray(0), TerminalKeys.diff("ls", "ls"))
    }

    @Test
    fun `diff sends inserted suffix`() {
        assertArrayEquals(
            "abc".toByteArray(Charsets.UTF_8),
            TerminalKeys.diff("", "abc"),
        )
        assertArrayEquals(
            "c".toByteArray(Charsets.UTF_8),
            TerminalKeys.diff("ab", "abc"),
        )
    }

    @Test
    fun `diff sends backspaces for deletion`() {
        assertArrayEquals(byteArrayOf(0x7F, 0x7F), TerminalKeys.diff("abcd", "ab"))
        assertArrayEquals(byteArrayOf(0x7F), TerminalKeys.diff("ab", "a"))
    }

    @Test
    fun `diff rewrites from the common prefix`() {
        // 行尾编辑：先退两格，再补新后缀。
        val bytes = TerminalKeys.diff("abc", "ax")
        assertArrayEquals(
            byteArrayOf(0x7F, 0x7F) + "x".toByteArray(Charsets.UTF_8),
            bytes,
        )
    }
}
