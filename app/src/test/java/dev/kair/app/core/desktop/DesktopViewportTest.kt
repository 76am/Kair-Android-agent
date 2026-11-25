package dev.kair.app.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U86 §5.1 · DesktopViewport 判据：等比装入 / 1:1 裁剪 / 黑边点击落空 / 零尺寸不崩。
 */
class DesktopViewportTest {

    @Test
    fun `fit scales down proportionally and centers`() {
        // 1280x720 装入 1080x2400：宽是受限边（1080/1280 = 0.84375），高 720*0.84375 = 607.5 → 608
        val dst = fitRect(srcW = 1280, srcH = 720, viewW = 1080, viewH = 2400, mode = ScaleMode.Fit)
        assertEquals(0, dst.x)
        assertEquals(1080, dst.w)
        assertEquals(608, dst.h)
        assertEquals((2400 - 608) / 2, dst.y)
    }

    @Test
    fun `actual keeps source size and centers`() {
        val dst = fitRect(srcW = 1280, srcH = 720, viewW = 1080, viewH = 2400, mode = ScaleMode.Actual)
        assertEquals(1280, dst.w)
        assertEquals(720, dst.h)
        assertEquals((1080 - 1280) / 2, dst.x) // 负偏移 = 两侧被裁剪
        assertEquals((2400 - 720) / 2, dst.y)
    }

    @Test
    fun `point inside maps to framebuffer coordinates`() {
        val dst = fitRect(srcW = 1280, srcH = 720, viewW = 1080, viewH = 2400, mode = ScaleMode.Fit)
        // dst 左上角 → (0,0)；dst 中心 → 源中心附近
        assertEquals(0 to 0, toFramebuffer(0f, dst.y.toFloat(), dst, 1280, 720))
        val mid = toFramebuffer((dst.w / 2).toFloat(), (dst.y + dst.h / 2).toFloat(), dst, 1280, 720)
        assertEquals(640, mid!!.first)
        assertEquals(360, mid.second)
    }

    @Test
    fun `point on letterbox returns null`() {
        val dst = fitRect(srcW = 1280, srcH = 720, viewW = 1080, viewH = 2400, mode = ScaleMode.Fit)
        assertNull(toFramebuffer(100f, 10f, dst, 1280, 720)) // 上方黑边
        assertNull(toFramebuffer(-5f, 1200f, dst, 1280, 720)) // 画布外
    }

    @Test
    fun `zero sizes do not crash`() {
        val dst = fitRect(0, 0, 0, 0, ScaleMode.Fit)
        assertEquals(0, dst.w)
        assertNull(toFramebuffer(1f, 1f, dst, 0, 0))
    }
}
