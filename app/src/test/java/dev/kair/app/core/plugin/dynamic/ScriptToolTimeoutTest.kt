package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.ai.Json
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 插件脚本工具的**中断面**：`spec.timeoutSeconds` 超时、以及用户点停止时的协程取消。
 *
 * 这两条路径此前一条测试都没有 —— `timedOut` 分支从没被执行过。而它们正是「脚本卡住
 * 就再也回不来」和「取消后留下孤儿进程占住工作区 / 挂载点」的唯一防线。
 *
 * 机制在两处：`ScriptTool.execute` 把 `spec.timeoutSeconds` 交给 runner，由 runner
 * 实现真正的终止；取消面则由 runner 的 `runInterruptible { waitFor() }` + `finally
 * { killTree }` 承担 —— 所以这些用例同时也是 runner 取消路径的回归。
 */
class ScriptToolTimeoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个只含一个脚本工具的插件目录；[timeoutSeconds] 为 null 表示清单里不写该字段。 */
    private fun tool(script: String, timeoutSeconds: Long? = null): ScriptTool {
        val dir = tmp.newFolder("plugin")
        val timeout = timeoutSeconds?.let { ""","timeoutSeconds":$it""" } ?: ""
        File(dir, "manifest.json").writeText(
            """{"id":"p","name":"p","tools":[{"name":"slow","description":"d",""" +
                """"script":"s.sh","category":"execute"$timeout}]}""",
        )
        File(dir, "s.sh").writeText(script)
        val manifest = Json.decodeFromString<PluginManifest>(File(dir, "manifest.json").readText())
        return ScriptTool(manifest.tools.first(), dir)
    }

    private fun ctx(workspace: File) = ToolContext(
        workspace = workspace,
        sessionId = "test",
        commandRunner = LocalCommandRunner(shell = "/bin/sh"),
    )

    @Test
    fun `manifest timeout aborts the script and reports timed out`() = runBlocking {
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())
        val pidFile = File(tmp.root, "pid")
        val doneMarker = File(tmp.root, "done")
        // 为什么 sleep 15 而不是 5（U89H）：这条用例从来不看上墙时钟（U14），但它靠
        // 「done 标记没被写出来」证明「是中断而不是等它跑完」。容器满载时，超时判定的
        // 线程自己会被推迟几秒 —— `killTree` 落在 5 秒之后，脚本早已跑完写下 done，
        // 于是假红（2026-11-14 连续两轮全量中随机复现、单跑必过）。
        // 拉长脚本不会削弱判据：真正失效（完全不 kill）时下文那段 /proc 等待仍会红。
        val slow = tool(
            "echo \$\$ > '${pidFile.path}'; sleep 15; echo done > '${doneMarker.path}'",
            timeoutSeconds = 1,
        )

        val out = slow.execute(buildJsonObject { }, ctx(tmp.newFolder("ws")))

        assertTrue("超时必须报错", out.isError)
        assertTrue("错误文案应说明超时：${out.content}", out.content.contains("timed out"))
        // 关键：证明是「中断」而不是「等 sleep 自己跑完」—— 后者会写出 done 标记。
        // 不用墙上时钟上界（U14）：满载时 1s 超时可能被调度推迟，elapsed<4s 会假红。
        assertFalse("超时后应中断脚本，不能等 sleep 跑完写出 done", doneMarker.exists())
        // 进程要真的收掉，不能只是丢下不管。
        val pid = pidFile.readText().trim()
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(20) }
    }

    @Test
    fun `cancelling the tool call kills the script process`() = runBlocking {
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())
        assumeTrue("/proc exists", File("/proc").isDirectory)
        val pidFile = File(tmp.root, "pid")
        val slow = tool("echo \$\$ > '${pidFile.path}'; sleep 60", timeoutSeconds = null)

        val job = launch(Dispatchers.IO) {
            runCatching { slow.execute(buildJsonObject { }, ctx(tmp.newFolder("ws"))) }
        }
        withTimeout(5_000) { while (!pidFile.exists() || pidFile.readText().isBlank()) delay(20) }
        val pid = pidFile.readText().trim()
        assertTrue("脚本应该还在跑", File("/proc/$pid").exists())

        job.cancelAndJoin()

        // 用户点停止后脚本进程必须消失，否则会继续占住工作区与容器挂载点。
        withTimeout(5_000) { while (File("/proc/$pid").exists()) delay(20) }
    }

    @Test
    fun `without a manifest timeout a short script still completes normally`() = runBlocking {
        assumeTrue("/bin/sh exists", File("/bin/sh").exists())
        val quick = tool("echo hi", timeoutSeconds = null)

        val out = quick.execute(buildJsonObject { }, ctx(tmp.newFolder("ws")))

        assertTrue("正常结束不应报错：${out.content}", !out.isError)
        assertTrue(out.content.contains("hi"))
    }
}
