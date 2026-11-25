package dev.kair.app.core.term

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U85 判据：键盘只缩视口，不改 PTY 网格。
 *
 * 所有输入都是像素，不依赖 Compose / Android，纯 JVM 可跑。
 */
class TerminalViewportTest {

    /** 同一完整高度：键盘占掉一半与完全不弹，算出的行列必须一致（不触发 resize）。 */
    @Test
    fun `keyboard height does not change rows`() {
        val closed = TerminalViewport.gauge(800, 400, 0, 8f, 16f)
        val open = TerminalViewport.gauge(800, 200, 200, 8f, 16f)
        assertEquals(closed.rows, open.rows)
        assertEquals(25, closed.rows)
        assertEquals(100, closed.cols)
    }

    /** 宽度是真实尺寸变化（旋转 / 分屏），列数照常跟随。 */
    @Test
    fun `width change changes cols`() {
        val portrait = TerminalViewport.gauge(400, 800, 0, 8f, 16f)
        val landscape = TerminalViewport.gauge(800, 400, 0, 8f, 16f)
        assertEquals(50, portrait.cols)
        assertEquals(100, landscape.cols)
        assertEquals(50, portrait.rows)
        assertEquals(25, landscape.rows)
    }

    /** 网格高于视口：整体上移，底边对齐视口底边（键盘弹出时看得到提示符）。 */
    @Test
    fun `offset aligns grid bottom to viewport`() {
        val open = TerminalViewport.gauge(800, 200, 200, 8f, 16f)
        assertEquals(-200f, open.offsetY, 0.01f)
        assertEquals(0f, TerminalViewport.offsetYFor(400, 400f), 0.01f)
        assertEquals(0f, TerminalViewport.offsetYFor(400, 300f), 0.01f)
        assertEquals(-100f, TerminalViewport.offsetYFor(400, 500f), 0.01f)
    }

    /** 退化尺寸不允许出现 0 行 / 0 列或除零。 */
    @Test
    fun `degenerate sizes clamp to one cell`() {
        val degenerate = TerminalViewport.gauge(0, 0, 0, 0f, 0f)
        assertEquals(1, degenerate.rows)
        assertEquals(1, degenerate.cols)
        assertEquals(0f, degenerate.offsetY, 0.01f)
    }
}
