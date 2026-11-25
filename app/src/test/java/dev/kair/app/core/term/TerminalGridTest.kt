package dev.kair.app.core.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器黄金用例（V1）：喂字节流，断言网格快照。
 *
 * 快照按「每行可见字符」比较 —— 连续空格会被 trimEnd 掉，因此断言里保留的空白
 * 都是有内容的格子；颜色 / 宽字符等属性单独断言。
 */
class TerminalGridTest {

    private fun TerminalSnapshot.text(): List<String> = (0 until rows).map { r -> rowText(r) }

    private fun TerminalSnapshot.rowText(r: Int): String = buildString {
        for (c in 0 until cols) {
            val cell = cell(r, c)
            if (cell.continuation) continue
            append(cell.char)
        }
    }.trimEnd()

    @Test
    fun `plain text and delayed wrap`() {
        val p = TerminalParser(2, 5)
        p.feed("abcde".toByteArray())
        assertEquals(listOf("abcde", ""), p.snapshot().text())
        // 最后一格写完不立刻换行，下一个字符才换（xterm 的 delayed wrap）
        p.feed("f".toByteArray())
        assertEquals(listOf("abcde", "f"), p.snapshot().text())
    }

    @Test
    fun `crlf and cursor positioning`() {
        val p = TerminalParser(3, 10)
        p.feed("hello\r\nworld".toByteArray())
        assertEquals(listOf("hello", "world", ""), p.snapshot().text())

        p.feed("\u001b[2;4H".toByteArray())
        p.feed("X".toByteArray())
        assertEquals("X", p.snapshot().rowText(1).substring(3).take(1))
        assertEquals(1, p.snapshot().cursorRow)
        assertEquals(4, p.snapshot().cursorCol)
    }

    @Test
    fun `erase screen and line`() {
        val p = TerminalParser(2, 6)
        p.feed("abcdef".toByteArray())
        p.feed("\u001b[1K".toByteArray())
        assertEquals(listOf("", ""), p.snapshot().text())

        p.feed("top\r\nbottom".toByteArray())
        p.feed("\u001b[2J".toByteArray())
        assertEquals(listOf("", ""), p.snapshot().text())
    }

    @Test
    fun `sgr 16 color and reset`() {
        val p = TerminalParser(1, 10)
        p.feed("\u001b[31mR\u001b[0mN".toByteArray())
        assertEquals(1, p.snapshot().cell(0, 0).fg)
        assertEquals(TerminalColor.DEFAULT, p.snapshot().cell(0, 1).fg)
        assertEquals('R', p.snapshot().cell(0, 0).char)
        assertEquals('N', p.snapshot().cell(0, 1).char)
    }

    @Test
    fun `sgr 256 and truecolor`() {
        val p = TerminalParser(1, 10)
        p.feed("\u001b[38;5;196mX".toByteArray())
        p.feed("\u001b[38;2;10;20;30mY".toByteArray())
        assertEquals(196, p.snapshot().cell(0, 0).fg)
        assertEquals(TerminalColor.rgb(10, 20, 30), p.snapshot().cell(0, 1).fg)
        assertTrue(TerminalColor.isTrueColor(p.snapshot().cell(0, 1).fg))
    }

    @Test
    fun `alternate screen keeps main content`() {
        val p = TerminalParser(3, 10)
        p.feed("main".toByteArray())
        val cursor = p.snapshot().cursorCol
        p.feed("\u001b[?1049h".toByteArray())
        p.feed("alt".toByteArray())
        assertEquals(listOf("alt", "", ""), p.snapshot().text())
        p.feed("\u001b[?1049l".toByteArray())
        assertEquals(listOf("main", "", ""), p.snapshot().text())
        assertEquals(cursor, p.snapshot().cursorCol)
    }

    @Test
    fun `scroll region scrolls only inside region`() {
        val p = TerminalParser(5, 10)
        p.feed("\u001b[2;4r".toByteArray())
        // 末尾不补换行：补了会再滚一次，把 "b" 挤出滚动区（那是正确的 VT 行为，但不是本用例要断言的点）
        p.feed("a\r\nb\r\nc\r\nd".toByteArray())
        assertEquals(listOf("", "b", "c", "d", ""), p.snapshot().text())
    }

    @Test
    fun `resize keeps content and clamps cursor`() {
        val p = TerminalParser(4, 6)
        p.feed("abcd\r\nefgh\r\nijkl\r\nmnop".toByteArray())
        p.resize(2, 4)
        val snap = p.snapshot()
        assertEquals(2, snap.rows)
        assertEquals(4, snap.cols)
        assertEquals(listOf("abcd", "efgh"), snap.text())
        assertEquals(1, snap.cursorRow)
        assertEquals(3, snap.cursorCol)
    }

    @Test
    fun `wide chars occupy two cells`() {
        val p = TerminalParser(2, 6)
        p.feed("中a".toByteArray())
        val snap = p.snapshot()
        assertEquals('中', snap.cell(0, 0).char)
        assertFalse(snap.cell(0, 0).continuation)
        assertTrue(snap.cell(0, 1).continuation)
        assertEquals('a', snap.cell(0, 2).char)
        assertEquals(3, snap.cursorCol)
    }

    @Test
    fun `cursor visibility and application cursor keys`() {
        val p = TerminalParser(1, 10)
        p.feed("\u001b[?25l".toByteArray())
        assertFalse(p.snapshot().cursorVisible)
        p.feed("\u001b[?1h".toByteArray())
        assertTrue(p.snapshot().applicationCursorKeys)
        p.feed("\u001b[?25h".toByteArray())
        assertTrue(p.snapshot().cursorVisible)
    }

    @Test
    fun `save and restore cursor`() {
        val p = TerminalParser(3, 10)
        p.feed("A\u001b7".toByteArray())
        p.feed("\r\nB".toByteArray())
        p.feed("\u001b8".toByteArray())
        p.feed("C".toByteArray())
        assertEquals(listOf("AC", "B", ""), p.snapshot().text())
    }

    @Test
    fun `sequence split across reads still parses`() {
        val p = TerminalParser(2, 10)
        p.feed("\u001b[3".toByteArray())
        p.feed("1mR".toByteArray())
        assertEquals(1, p.snapshot().cell(0, 0).fg)
        assertEquals('R', p.snapshot().cell(0, 0).char)
    }

    @Test
    fun `utf8 split across reads still decodes`() {
        val p = TerminalParser(2, 6)
        val bytes = "中".toByteArray(Charsets.UTF_8)
        p.feed(bytes.copyOfRange(0, 1))
        p.feed(bytes.copyOfRange(1, bytes.size))
        assertEquals('中', p.snapshot().cell(0, 0).char)
        assertTrue(p.snapshot().cell(0, 1).continuation)
    }

    @Test
    fun `osc and dcs are swallowed`() {
        val p = TerminalParser(2, 10)
        p.feed("\u001b]0;title\u0007ok".toByteArray())
        p.feed("\u001bP1;2|x\u001b\\!".toByteArray())
        assertEquals(listOf("ok!", ""), p.snapshot().text())
    }

    @Test
    fun `absolute positioning cancels pending wrap on the last row`() {
        val p = TerminalParser(3, 4)
        // 填满最后一行后光标停在 pendingWrap 状态。
        p.feed("abcdefghijkl".toByteArray())
        // vim 画标尺：最后一列之后直接 CUP 到末行中间再写字；若定位不清 pendingWrap，
        // 第一个字符会先换行滚屏，整屏上移一行（真机 vim 花屏的根因）。
        p.feed("\u001b[3;2HXY".toByteArray())
        // 末行原为 "ijkl"，从第 2 列起写 XY → "iXYl"；关键是首行 "abcd" 还在（没有滚屏）。
        assertEquals(listOf("abcd", "efgh", "iXYl"), p.snapshot().text())
    }

    @Test
    fun `dec special graphics charset is translated and resettable`() {
        val p = TerminalParser(1, 10)
        // ESC(0 后用 q / x 画横线与竖线；ESC(B 切回 ASCII 后字母原样输出。
        p.feed("\u001b(0lqqk\u001b(Baq".toByteArray())
        assertEquals("\u250C\u2500\u2500\u2510aq", p.snapshot().rowText(0))
        // 1 行网格：第 7 列的 x 落在行尾前，确认映射只作用在 charset 生效期间。
        p.feed("\u001b(0x".toByteArray())
        assertEquals('\u2502', p.snapshot().cell(0, 6).char)
    }

    @Test
    fun `insert and delete lines inside scroll region`() {
        val p = TerminalParser(4, 6)
        p.feed("1\r\n2\r\n3\r\n4".toByteArray())
        // 光标移到第 2 行，删掉一行：2 消失，3/4 上移
        p.feed("\u001b[2;1H\u001b[M".toByteArray())
        assertEquals(listOf("1", "3", "4", ""), p.snapshot().text())
        p.feed("\u001b[2;1H\u001b[L".toByteArray())
        assertEquals(listOf("1", "", "3", "4"), p.snapshot().text())
    }
}
