package dev.kair.app.ui.browser

import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗抓帧的空帧判定：`PixelCopy` 在窗口还没把页面合成上去时会返回 SUCCESS，
 * 拷出来是整片透明 / 纯黑。只有这种「全同空色」才重试；真实页面（哪怕全黑或全白）
 * 不能无限重试。
 */
class BrowserBlankFrameTest {

    @Test
    fun `all transparent samples are treated as not yet rendered`() {
        assertTrue(isBlankSample(IntArray(64) { Color.TRANSPARENT }))
    }

    @Test
    fun `all opaque black samples are treated as not yet rendered`() {
        assertTrue(isBlankSample(IntArray(64) { Color.BLACK }))
    }

    @Test
    fun `mixed samples mean a real page was rendered`() {
        val samples = IntArray(64) { Color.WHITE }
        samples[0] = Color.BLACK
        assertFalse(isBlankSample(samples))
    }

    @Test
    fun `a uniformly white page is still a real frame`() {
        assertFalse(isBlankSample(IntArray(64) { Color.WHITE }))
    }

    @Test
    fun `empty sample set is not blank`() {
        assertFalse(isBlankSample(IntArray(0)))
    }
}
