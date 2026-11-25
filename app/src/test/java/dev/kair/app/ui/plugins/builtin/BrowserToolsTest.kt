package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserConsoleLine
import dev.kair.app.core.browser.BrowserController
import dev.kair.app.core.browser.BrowserElement
import dev.kair.app.core.browser.BrowserMode
import dev.kair.app.core.browser.BrowserNetworkLine
import dev.kair.app.core.browser.BrowserPageInfo
import dev.kair.app.core.browser.BrowserPanelState
import dev.kair.app.core.browser.BrowserScreenshot
import dev.kair.app.core.browser.BrowserSnapshot
import dev.kair.app.core.tools.ToolContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserToolsTest {

    private val workspace: File = Files.createTempDirectory("kair-browser-tools").toFile()
    private val context = ToolContext(workspace = workspace, sessionId = "test")

    private class FakeBrowser : BrowserController {
        var closed = false
        override val state = MutableStateFlow(BrowserPanelState())
        override suspend fun open(url: String) = BrowserPageInfo(url, "Login", true)
        override suspend fun snapshot() = BrowserSnapshot(
            url = "http://127.0.0.1:5173/",
            title = "Login",
            elements = listOf(BrowserElement("e1", "textbox", "Email")),
            text = "Welcome back",
        )

        override suspend fun click(ref: String): String = "Clicked $ref."
        override suspend fun tap(x: Float, y: Float): String = "Tapped at $x,$y."
        override suspend fun type(ref: String, text: String): String = "Typed into $ref."
        override suspend fun press(key: String): String = "Pressed $key."
        override suspend fun scroll(direction: String, amount: Int?): String = "Scrolled $direction."
        override suspend fun eval(expression: String): String = "value of $expression"
        override suspend fun screenshot() = BrowserScreenshot(byteArrayOf(1, 2, 3), 100, 200, true)
        override fun setMode(mode: BrowserMode) {
            state.value = state.value.copy(mode = mode)
        }

        override fun goBack() = Unit
        override fun reload() = Unit
        override fun close() {
            closed = true
        }
        override fun clearConsole() {
            state.value = state.value.copy(console = emptyList())
        }

        override fun clearNetwork() {
            state.value = state.value.copy(network = emptyList())
        }
    }

    @Test
    fun `tool names are unique and in a stable order`() {
        val names = browserTools { FakeBrowser() }.map { it.name }
        assertEquals(
            listOf(
                "browser_open",
                "browser_snapshot",
                "browser_screenshot",
                "browser_click",
                "browser_tap",
                "browser_type",
                "browser_press",
                "browser_scroll",
                "browser_eval",
                "browser_console",
                "browser_network",
                "browser_close",
            ),
            names,
        )
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `snapshot tool renders refs and page text`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_snapshot" }
        val result = runBlocking { tool.execute(buildJsonObject { }, context) }
        assertFalse(result.isError)
        assertTrue(result.content.contains("[e1] textbox \"Email\""))
        assertTrue(result.content.contains("Welcome back"))
    }

    @Test
    fun `console tool can filter to errors only`() {
        val fake = FakeBrowser()
        fake.state.value = BrowserPanelState(
            console = listOf(
                BrowserConsoleLine("log", "hello", 1),
                BrowserConsoleLine("error", "boom", 2),
            ),
        )
        val tool = browserTools { fake }.first { it.name == "browser_console" }
        val result = runBlocking {
            tool.execute(buildJsonObject { put("only_errors", JsonPrimitive(true)) }, context)
        }
        assertFalse(result.isError)
        assertEquals("[error] boom", result.content)
    }

    @Test
    fun `network tool reports status and failed requests`() {
        val fake = FakeBrowser()
        fake.state.value = BrowserPanelState(
            network = listOf(
                BrowserNetworkLine("GET", "/app.js", 200, 10, at = 1),
                BrowserNetworkLine("POST", "/api/login", 500, 124, at = 2),
            ),
        )
        val tool = browserTools { fake }.first { it.name == "browser_network" }
        val result = runBlocking {
            tool.execute(buildJsonObject { put("only_failed", JsonPrimitive(true)) }, context)
        }
        assertFalse(result.isError)
        assertTrue(result.content.contains("POST 500 /api/login (124ms)"))
        assertFalse(result.content.contains("/app.js"))
    }

    @Test
    fun `screenshot tool saves the png and attaches the image`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_screenshot" }
        val result = runBlocking { tool.execute(buildJsonObject { }, context) }
        assertFalse(result.isError)
        assertEquals(1, result.images.size)
        assertTrue(result.content.contains("Screenshot 100x200"))
        val shots = File(workspace, ".kair/shots").listFiles().orEmpty()
        assertEquals("截图必须落盘，主会话才能用 read_image 回看", 1, shots.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), shots.single().readBytes())
        assertTrue(result.content.contains("Saved to .kair/shots/"))
    }

    @Test
    fun `screenshot save_only returns the path without attaching`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_screenshot" }
        val result = runBlocking {
            tool.execute(buildJsonObject { put("save_only", JsonPrimitive(true)) }, context)
        }
        assertFalse(result.isError)
        assertTrue("save_only 不应回灌图片", result.images.isEmpty())
        assertTrue(result.content.contains("Saved to .kair/shots/"))
        assertTrue(result.content.contains("read_image"))
    }

    @Test
    fun `eval tool returns the expression value`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_eval" }
        val result = runBlocking {
            tool.execute(buildJsonObject { put("expression", JsonPrimitive("1 + 1")) }, context)
        }
        assertFalse(result.isError)
        assertEquals("value of 1 + 1", result.content)
    }

    @Test
    fun `tap tool forwards viewport coordinates to the real touch path`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_tap" }
        val result = runBlocking {
            tool.execute(
                buildJsonObject {
                    put("x", JsonPrimitive(12.5))
                    put("y", JsonPrimitive(34))
                },
                context,
            )
        }
        assertFalse(result.isError)
        assertEquals("Tapped at 12.5,34.0.", result.content)
    }

    @Test
    fun `tap tool rejects missing coordinates`() {
        val tool = browserTools { FakeBrowser() }.first { it.name == "browser_tap" }
        val result = runBlocking { tool.execute(buildJsonObject { }, context) }
        assertTrue(result.isError)
        assertTrue(result.content.contains("numeric x and y"))
    }

    @Test
    fun `close tool closes the page`() {
        val fake = FakeBrowser()
        val tool = browserTools { fake }.first { it.name == "browser_close" }
        val result = runBlocking { tool.execute(buildJsonObject { }, context) }
        assertFalse(result.isError)
        assertEquals("Browser page closed.", result.content)
        assertTrue("browser_close 必须真的关闭页面", fake.closed)
    }

    @Test
    fun `browser action errors are returned to the model`() {
        val fake = object : BrowserController by FakeBrowser() {
            override suspend fun click(ref: String): String =
                throw BrowserActionException("The user has taken over the browser.")
        }
        val tool = browserTools { fake }.first { it.name == "browser_click" }
        val result = runBlocking {
            tool.execute(buildJsonObject { put("ref", JsonPrimitive("e1")) }, context)
        }
        assertTrue(result.isError)
        assertEquals("The user has taken over the browser.", result.content)
    }
}
