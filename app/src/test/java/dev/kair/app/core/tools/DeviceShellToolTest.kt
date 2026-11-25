package dev.kair.app.core.tools

import dev.kair.app.core.device.AdbOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U91 · `device_shell` 的骨架判据。重点只有一条：**设备端失败不能被报成成功**。
 *
 * 这条通道是 libadb 的 `shell:` 服务 —— 一条裸字节流，**不带退出码**。所以 `input text '你好'`
 * 以 exit 255 失败时，我们只拿到 stderr 正文（一坨 Java 堆栈），拿不到失败状态。若原样报 `Ok`，
 * 模型会以为命令成功、继续往下走。判据因此落在「输出里出现 ShellCommand 的崩溃包装格式就翻成失败」。
 *
 * 不碰 Android：真实命令在 `AdbLink.shell` 里，真机项见任务书 §8.12。
 */
class DeviceShellToolTest {

    private val ctx = ToolContext(workspace = File("."), sessionId = "test")

    private fun args(command: String) = buildJsonObject { put("command", JsonPrimitive(command)) }

    private fun tool(outcome: AdbOutcome) = DeviceShellTool { outcome }

    private fun run(command: String, outcome: AdbOutcome) =
        runBlocking { tool(outcome).execute(args(command), ctx) }

    /** 真机（Android 13 / Redmi 22041216C）实测 `input text '你好'` 的原样输出，已截断。 */
    private val cjkCrash = """
        Exception occurred while executing 'text':
        java.lang.NullPointerException: Attempt to get length of null array
        	at com.android.server.input.InputShellCommand.sendText(InputShellCommand.java:318)
        	at com.android.server.input.InputShellCommand.runText(InputShellCommand.java:290)
        	at com.android.modules.utils.BasicShellCommandHandler.exec(BasicShellCommandHandler.java:97)
        	at android.os.ShellCommand.exec(ShellCommand.java:38)
    """.trimIndent()

    @Test
    fun `ordinary output passes through untouched`() {
        val result = run("getprop ro.product.model", AdbOutcome.Ok("22041216C\n"))

        assertFalse(result.isError)
        assertEquals("22041216C\n", result.content)
    }

    @Test
    fun `empty output says so instead of returning blank`() {
        val result = run("input keyevent 3", AdbOutcome.Ok(""))

        assertFalse(result.isError)
        assertEquals("(no output)", result.content)
    }

    @Test
    fun `shell command crash is reported as a failure, not as success`() {
        val result = run("input text '你好'", AdbOutcome.Ok(cjkCrash))

        assertTrue("设备端抛异常必须以失败回报，否则模型会以为输进去了", result.isError)
        // 原文要留着：模型可能靠堆栈里的类名定位，但必须加一句人话说明「这是失败」
        assertTrue(result.content.contains("NullPointerException"))
        assertTrue(result.content.contains("FAILED"))
        // 并告诉它下一步：别再换引号重试
        assertTrue(result.content.contains("non-ASCII"))
        assertTrue(result.content.contains("Ask the user"))
    }

    @Test
    fun `an app stack trace that merely mentions the phrase is not a shell crash`() {
        // `logcat -d` 抓到应用自己抛的异常时也会出现这句话，但不会有 BasicShellCommandHandler 帧。
        // 误判成失败会让模型对一条正常的日志查询失去信任。
        val appTrace = """
            E/AndroidRuntime: FATAL EXCEPTION: main
                java.lang.RuntimeException: Exception occurred while executing a query
                	at com.example.app.Repo.load(Repo.kt:42)
        """.trimIndent()

        val result = run("logcat -d -t 20", AdbOutcome.Ok(appTrace))

        assertFalse(result.isError)
        assertEquals(appTrace, result.content)
    }

    @Test
    fun `transport failure points at the ADB panel`() {
        val result = run("getprop", AdbOutcome.Failed(AdbOutcome.Reason.NOT_PAIRED))

        assertTrue(result.isError)
        assertTrue(result.content.contains("ADB panel"))
        assertFalse(result.content.contains("NOT_PAIRED"))
    }

    @Test(expected = ToolException::class)
    fun `blank command is rejected before touching the device`() {
        run("   ", AdbOutcome.Ok("never reached"))
    }

    @Test
    fun `guidelines warn that input text cannot carry non-ASCII`() {
        // 常驻守则（进缓存稳定前缀）。这条坑只有写在这里模型才不会白试三轮。
        val guidelines = tool(AdbOutcome.Ok("")).promptGuidelines.joinToString(" ")

        assertTrue(guidelines.contains("ASCII-only"))
        assertTrue(guidelines.contains("%s"))
    }
}
