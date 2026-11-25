package dev.kair.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截图窗口选择（U116 重构后）：常驻小窗是唯一来源。
 *
 * 判定只回答「现在这扇窗能不能读到一帧」：熄屏 / 无页面 / 未预热 / 后台无权限
 * 四种输入给出可执行的原因，其余一律抓小窗。
 */
class BrowserCaptureRouteTest {

    @Test
    fun `a ready window is captured in the foreground without overlay permission`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = true,
            hasPage = true,
            windowReady = true,
            foreground = true,
            overlayPermission = false,
        )
        assertEquals(BrowserCaptureWindow.WINDOW, route.window)
        assertTrue(route.available)
        assertNull(route.error)
    }

    @Test
    fun `a ready window is captured in the background with overlay permission`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = true,
            hasPage = true,
            windowReady = true,
            foreground = false,
            overlayPermission = true,
        )
        assertEquals(BrowserCaptureWindow.WINDOW, route.window)
        assertTrue(route.available)
    }

    @Test
    fun `background without overlay permission says how to grant it`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = true,
            hasPage = true,
            windowReady = true,
            foreground = false,
            overlayPermission = false,
        )
        assertFalse(route.available)
        assertTrue(route.error!!.contains("display over other apps"))
    }

    @Test
    fun `screen off fails before any window is chosen`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = false,
            hasPage = true,
            windowReady = true,
            foreground = true,
            overlayPermission = true,
        )
        assertFalse(route.available)
        assertTrue(route.error!!.contains("screen is off"))
    }

    @Test
    fun `no page tells the model to open one first`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = true,
            hasPage = false,
            windowReady = false,
            foreground = true,
            overlayPermission = true,
        )
        assertFalse(route.available)
        assertTrue(route.error!!.contains("browser_open"))
    }

    @Test
    fun `a window that has not drawn its first frame is not captured`() {
        val route = resolveBrowserCaptureRoute(
            screenOn = true,
            hasPage = true,
            windowReady = false,
            foreground = true,
            overlayPermission = true,
        )
        assertFalse(route.available)
        assertTrue(route.error!!.contains("still rendering"))
    }
}
