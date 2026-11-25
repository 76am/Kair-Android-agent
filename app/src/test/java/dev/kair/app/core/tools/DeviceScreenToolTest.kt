package dev.kair.app.core.tools

import dev.kair.app.core.device.AdbOutcome
import dev.kair.app.core.device.ScreenOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * U91 · `device_screen` 的骨架判据：图确实进 `ToolResult.images`（模型才看得见）、
 * 尺寸写进文案（模型才知道坐标系）、失败说清下一步。
 *
 * 不碰 Android：真实截屏在 `AdbLink.screenshot` 里，由真机项（任务书 §5.3 #3）覆盖。
 */
class DeviceScreenToolTest {
    /** 一张 2x3 的假 PNG 头：只要 IHDR 对，校验逻辑走不到像素数据。 */
    private val fakePng = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 2, // width
        0, 0, 0, 3, // height
    ).map { it.toByte() }.toByteArray()

    private fun tool(outcome: ScreenOutcome) = DeviceScreenTool { outcome }

    private val ctx = ToolContext(workspace = File("."), sessionId = "test")

    private val noArgs = buildJsonObject { }

    @Test
    fun `screenshot is delivered as an image, not as base64 text in content`() = runBlocking {
        val result = tool(ScreenOutcome.Ok(fakePng, 2, 3)).execute(noArgs, ctx)

        assertFalse(result.isError)
        assertEquals(1, result.images.size)
        val image = result.images.single()
        assertEquals("image/png", image.mediaType)
        assertArrayEquals(fakePng, Base64.getDecoder().decode(image.base64))
        // 正文只说「图在哪儿、怎么用」，不贴 base64
        assertFalse(result.content.contains(image.base64.orEmpty()))
    }

    @Test
    fun `content tells the model the coordinate space so taps line up`() = runBlocking {
        val result = tool(ScreenOutcome.Ok(fakePng, 1080, 2400)).execute(noArgs, ctx)

        assertTrue(result.content.contains("1080x2400"))
        assertTrue(result.content.contains("input tap"))
        assertTrue(result.content.contains("device_shell"))
    }

    @Test
    fun `not paired points at the ADB panel and still returns no image`() = runBlocking {
        val result = tool(ScreenOutcome.Failed(AdbOutcome.Reason.NOT_PAIRED)).execute(noArgs, ctx)

        assertTrue(result.isError)
        assertTrue(result.images.isEmpty())
        assertTrue(result.content.contains("ADB panel"))
        assertFalse(result.content.contains("NOT_PAIRED"))
    }

    @Test
    fun `timeout carries its detail instead of a bare reason`() = runBlocking {
        val result = tool(ScreenOutcome.Failed(AdbOutcome.Reason.TIMEOUT, "127.0.0.1:44467"))
            .execute(noArgs, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("127.0.0.1:44467"))
    }

    @Test
    fun `tool is read only and takes no parameters`() {
        val screen = tool(ScreenOutcome.Failed(AdbOutcome.Reason.NOT_PAIRED))
        assertEquals(ToolCategory.READ, screen.category)
        assertTrue(screen.readOnly)
        // 无参：required 必须是空数组，否则有的 provider 会强制模型编一个参数
        assertTrue(screen.parameters.toString().contains("\"required\":[]"))
    }

    @Test(expected = ToolException::class)
    fun `aborted session does not touch the device`() {
        val aborted = ToolContext(workspace = File("."), sessionId = "test", isAborted = { true })
        runBlocking { tool(ScreenOutcome.Ok(fakePng, 2, 3)).execute(noArgs, aborted) }
    }
}
