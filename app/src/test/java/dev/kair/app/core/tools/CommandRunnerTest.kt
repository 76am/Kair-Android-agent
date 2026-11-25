package dev.kair.app.core.tools

import dev.kair.app.core.assumeLinux
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 命令执行后端的接缝测试：内核按插件解析后端，`bash` 工具只认 [CommandRunner] 契约。
 * 本地实现用 `/bin/sh` 跑（JVM 单测环境），真机上是 `/system/bin/sh`。
 */
class CommandRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plugin(id: String, runner: CommandRunner) = object : Plugin {
        override val id = id
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            ctx.commandRunner(runner)
        }
    }

    private class FakeRunner(
        override val id: String = "fake",
        override val displayName: String = "fake",
        override val description: String = "",
        private val result: CommandOutput,
    ) : CommandRunner {
        var lastCommand: String? = null
        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
            lastCommand = command
            return result
        }
    }

    // ---- 注册表 ---------------------------------------------------------------

    @Test
    fun `no plugin means no runner`() {
        assertNull(PluginRegistry().commandRunner())
    }

    @Test
    fun `plugin contributes runner and later registration overrides`() {
        val first = FakeRunner(id = "first", result = CommandOutput("a", 0))
        val second = FakeRunner(id = "second", result = CommandOutput("b", 0))

        val registry = PluginRegistry()
            .install(plugin("p1", first))
            .install(plugin("p2", second))

        assertSame(second, registry.commandRunner())
    }

    // ---- 内置本地实现 ---------------------------------------------------------

    @Test
    fun `local runner returns merged output and exit code`() = runBlocking {
        assumeLinux("直接起 /bin/sh")
        val runner = LocalCommandRunner(shell = "/bin/sh")
        val out = runner.run("echo hello", tmp.newFolder("ws"), timeoutSeconds = null)

        assertEquals("hello\n", out.output)
        assertEquals(0, out.exitCode)
        assertFalse(out.timedOut)

        // android_shell 里 `su -c` 能用 su 的前置：root 管理器目录必须在 PATH 上
        // （app 进程的默认 PATH 不一定含它们）。删掉注入即红。
        val path = runner.run("echo \$PATH", tmp.newFolder("ws-path"), timeoutSeconds = null).output
        for (dir in listOf("/system/xbin", "/sbin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")) {
            assertTrue("PATH 缺少 $dir（实际=$path）", path.contains(dir))
        }
    }

    @Test
    fun `local runner reports non-zero exit code`() = runBlocking {
        assumeLinux("直接起 /bin/sh")
        val out = LocalCommandRunner(shell = "/bin/sh")
            .run("echo oops >&2; exit 3", tmp.newFolder("ws"), timeoutSeconds = null)

        assertTrue(out.output.contains("oops"))
        assertEquals(3, out.exitCode)
    }

    @Test
    fun `local runner flags a timeout`() = runBlocking {
        assumeLinux("直接起 /bin/sh")
        val out = LocalCommandRunner(shell = "/bin/sh")
            .run("sleep 10", tmp.newFolder("ws"), timeoutSeconds = 1)

        assertTrue(out.timedOut)
    }

    @Test
    fun `cancelling a local run kills the process`() = runBlocking {
        assumeLinux("直接起 /bin/sh")
        // 用户点停止时正在跑的命令必须真的被终止，而不是只停掉流式输出。
        val marker = File(tmp.root, "pid")
        val runner = LocalCommandRunner(shell = "/bin/sh")
        val job = launch(Dispatchers.IO) {
            runCatching { runner.run("echo \$\$ > '${marker.path}'; sleep 60", tmp.newFolder("ws"), null) }
        }
        withTimeout(5_000) {
            while (!marker.exists() || marker.readText().isBlank()) delay(20)
        }
        val pid = marker.readText().trim()

        // 单测跑在 Linux JVM 上，用 /proc/<pid> 判断存活（Android 桩里没有 ProcessHandle）。
        fun alive() = File("/proc/$pid").exists()
        assertTrue(alive())

        job.cancelAndJoin()

        withTimeout(5_000) {
            while (alive()) delay(20)
        }
        assertFalse(alive())
    }

    @Test
    fun `cancelling also kills grandchildren`() = runBlocking {
        // 只在有 /bin/sh 与 /proc 的平台上跑（Windows 上跳过，保持基线失败数不变）。
        assumeTrue("/proc exists", File("/proc").isDirectory)
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())

        val marker = File(tmp.root, "grandchild")
        val runner = LocalCommandRunner(shell = "/bin/sh")
        val job = launch(Dispatchers.IO) {
            runCatching {
                runner.run(
                    "sh -c 'sleep 60 & echo \$! > ${marker.path}; wait'",
                    tmp.newFolder("ws"),
                    null,
                )
            }
        }
        withTimeout(5_000) {
            while (!marker.exists() || marker.readText().isBlank()) delay(20)
        }
        val pid = marker.readText().trim()
        fun alive() = File("/proc/$pid").exists()
        assertTrue(alive())

        job.cancelAndJoin()

        withTimeout(5_000) {
            while (alive()) delay(20)
        }
        assertFalse(alive())
    }

    @Test
    fun `resolvePid reads the pid through reflection`() {
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()
        try {
            // Android 运行时没有 Process::pid，resolvePid 取不到时返回 0；
            // JVM 上必须拿到真实 pid，否则容器清理会静默失效。
            val pid = process.resolvePid()
            assertTrue(pid > 0)
            assertTrue(File("/proc/$pid").exists())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `killTree with unknown pid never throws`() {
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())
        // 真机回归：killTree 只在取消路径的 finally 里被调，抛出就是应用崩溃
        // （Android 缺 Process.pid() 时曾直接 NoSuchMethodError 崩在 CommandRunner.kt）。
        // pid 未知（0）时它必须静默退化为「只杀本体」。
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()
        process.killTree(rootPid = 0L)
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    }

    // ---- 接缝：bash 工具改用注入的后端 ----------------------------------------

    @Test
    fun `bash tool runs through the injected runner`() = runBlocking {
        val runner = FakeRunner(result = CommandOutput("from fake backend", 0))
        val ctx = ToolContext(workspace = tmp.newFolder("ws"), sessionId = "s1", commandRunner = runner)

        val result = BashTool().execute(JSON.parseToJsonElement("""{"command":"uname -a"}""").jsonObject, ctx)

        assertEquals("uname -a", runner.lastCommand)
        assertTrue(result.content.contains("from fake backend"))
        assertFalse(result.isError)
    }

    @Test
    fun `bash tool marks a non-zero exit as an error`() = runBlocking {
        val runner = FakeRunner(result = CommandOutput("boom", 3))
        val ctx = ToolContext(workspace = tmp.newFolder("ws"), sessionId = "s1", commandRunner = runner)

        val result = BashTool().execute(JSON.parseToJsonElement("""{"command":"false"}""").jsonObject, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Command exited with code 3"))
    }
}
