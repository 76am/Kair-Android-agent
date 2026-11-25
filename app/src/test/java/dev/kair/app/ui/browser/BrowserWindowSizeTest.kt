package dev.kair.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮小窗的页面区尺寸必须跟真实设备视口一致：竖屏 ≈ 9:19.5、横屏 ≈ 19.5:9。
 * 之前固定 1.4 的兜底会把网页压成平板 / 桌面视口，这条用例把比例钉住。
 *
 * 2026-11-25：宽度比例由 0.60 收到 0.30（整体缩小一半），下面第一条把新宽度钉死；
 * 下限只准兜底，不允许把比例拉变形。
 */
class BrowserWindowSizeTest {

    private fun ratio(w: Int, h: Int): Float = h.toFloat() / w

    @Test
    fun `portrait phone keeps the screen ratio at half the old width`() {
        val (w, h) = browserWindowSizePx(1080, 2400, minWidthPx = 160, minHeightPx = 220)
        assertEquals("屏宽 30%：旧实现 648 的一半", 324, w)
        assertEquals(720, h)
        assertEquals(2.22f, ratio(w, h), 0.02f)
    }

    @Test
    fun `landscape phone flips the ratio`() {
        val (w, h) = browserWindowSizePx(2400, 1080, minWidthPx = 160, minHeightPx = 220)
        assertEquals(0.45f, ratio(w, h), 0.02f)
    }

    @Test
    fun `sixteen by nine phone keeps its own ratio`() {
        val (w, h) = browserWindowSizePx(1080, 1920, minWidthPx = 160, minHeightPx = 220)
        assertEquals(324, w)
        assertEquals(1.78f, ratio(w, h), 0.02f)
    }

    @Test
    fun `min height grows the width instead of squashing the ratio`() {
        // 横屏 9:19.5 反过来时高度只有 ~324px；若下限 660 只抬高度，小窗会变成方块。
        val (w, h) = browserWindowSizePx(2400, 1080, minWidthPx = 160, minHeightPx = 660)

        assertEquals(660, h)
        assertEquals(0.45f, ratio(w, h), 0.02f)
        assertTrue("宽度应一起放大到能容纳下限高度", w > 720)
    }

    @Test
    fun `degenerate screen size falls back to a phone viewport`() {
        val (w, h) = browserWindowSizePx(0, 0, minWidthPx = 160, minHeightPx = 220)
        assertTrue("宽度不应为 0", w > 0)
        assertTrue("高度不应为 0", h > 0)
        assertEquals(2.1667f, ratio(w, h), 0.03f)
    }
}
