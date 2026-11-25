package dev.kair.app.core.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlTest {

    @Test
    fun `bare host and port get an http scheme`() {
        assertEquals("http://127.0.0.1:5173", normalizeBrowserUrl("127.0.0.1:5173"))
        assertEquals("http://localhost:3000", normalizeBrowserUrl("localhost:3000"))
    }

    @Test
    fun `http and https urls are preserved`() {
        assertEquals("https://example.com/a?b=1", normalizeBrowserUrl("https://example.com/a?b=1"))
        assertEquals("http://10.0.2.2:8080/", normalizeBrowserUrl("http://10.0.2.2:8080/"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertNull(normalizeBrowserUrl("file:///etc/passwd"))
        assertNull(normalizeBrowserUrl("content://com.example/x"))
        assertNull(normalizeBrowserUrl("javascript:alert(1)"))
        assertNull(normalizeBrowserUrl("data:text/html,<h1>x</h1>"))
        assertNull(normalizeBrowserUrl("ftp://example.com/file"))
    }

    @Test
    fun `credentials and malformed input are rejected`() {
        assertNull(normalizeBrowserUrl("http://user:pass@example.com/"))
        assertNull(normalizeBrowserUrl(""))
        assertNull(normalizeBrowserUrl("   "))
        assertNull(normalizeBrowserUrl("http://"))
        assertNull(normalizeBrowserUrl("not a url"))
    }

    @Test
    fun `navigation allowlist only permits http and https hosts`() {
        assertTrue(isAllowedBrowserNavigation("http://127.0.0.1:5173"))
        assertTrue(isAllowedBrowserNavigation("https://example.com/"))
        assertFalse(isAllowedBrowserNavigation("about:blank"))
        assertFalse(isAllowedBrowserNavigation("javascript:alert(1)"))
        assertFalse(isAllowedBrowserNavigation("file:///tmp/x"))
        assertFalse(isAllowedBrowserNavigation("http://"))
    }
}
