package dev.kair.app.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U86 §5.1 · 胶囊淡出判据：进入 3s 后 alpha → 0.3，触摸（idle 归零）恢复 1.0。
 */
class DesktopOverlayFadeTest {

    @Test
    fun `fresh overlay is fully opaque`() {
        assertEquals(1f, overlayAlpha(0), 0.0001f)
    }

    @Test
    fun `exactly three seconds is still opaque`() {
        assertEquals(1f, overlayAlpha(3_000), 0.0001f)
    }

    @Test
    fun `after three seconds it fades to 0_3`() {
        assertEquals(0.3f, overlayAlpha(5_000), 0.0001f)
    }

    @Test
    fun `touch resets idle and restores opacity`() {
        // 触摸后 idle 重新计时：同一函数按新 idle 求值即恢复
        assertEquals(0.3f, overlayAlpha(4_000), 0.0001f)
        assertEquals(1f, overlayAlpha(0), 0.0001f)
    }
}
