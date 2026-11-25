package dev.kair.app.core.proot

import dev.kair.app.core.assumeLinux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 锁等待外壳的行为测试：真起 `/bin/sh`，用假的 `apt-get` 模拟「锁被占 → 稍后释放」。
 *
 * 为什么要真跑：`APT_WAIT_FN` 是一整段 shell，引号 / 变量作用域 / `$@` 传递写错了
 * 字符串断言看不出来；而 apt 的锁语义（lists 锁不受 `DPkg::Lock::Timeout` 管）无法在
 * 单测里拿真 apt 复现。
 */
class AptShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假 apt-get：前 [failures] 次报锁被占（exit 100），之后成功；调用次数写在 [counter]。 */
    private fun fakeApt(failures: Int, counter: File): File = File(tmp.root, "apt-get").apply {
        writeText(
            """
            #!/bin/sh
            n=${'$'}(cat "$counter" 2>/dev/null || echo 0)
            n=${'$'}((n + 1))
            echo "${'$'}n" > "$counter"
            if [ "${'$'}n" -le $failures ]; then
              echo "E: Could not get lock /var/lib/apt/lists/lock. It is held by process 1 (apt-get)" >&2
              exit 100
            fi
            echo "installed ${'$'}*"
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    private fun run(script: String): Pair<Int, String> {
        val proc = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }

    private fun commandWith(fakeAptDir: File, tail: String): String =
        "PATH=$fakeAptDir:\$PATH; " + APT_WAIT_FN + tail

    @Test
    fun `lock contention is retried until the holder releases`() {
        assumeLinux("真起 /bin/sh 与可执行位的假 apt-get")
        val counter = File(tmp.root, "count")
        val bin = fakeApt(failures = 1, counter)
        val (exit, out) = run(commandWith(tmp.root, "kair_apt apt-get install -y xvfb"))
        assertEquals(out, 0, exit)
        assertTrue("重试后必须打印成功输出：$out", out.contains("installed install -y xvfb"))
        assertEquals("锁释放后必须继续，不能直接放弃", "2", counter.readText().trim())
    }

    @Test
    fun `non-lock failures are not retried`() {
        assumeLinux("真起 /bin/sh 与可执行位的假 apt-get")
        val counter = File(tmp.root, "count")
        val bin = File(tmp.root, "apt-get").apply {
            writeText(
                "#!/bin/sh\n" +
                    "echo x >> \"$counter\"\n" +
                    "echo 'E: Unable to locate package nope' >&2\n" +
                    "exit 100\n",
            )
        }
        bin.setExecutable(true)
        val (exit, out) = run(commandWith(tmp.root, "kair_apt apt-get install -y nope"))
        assertEquals("真实错误必须原样返回退出码", 100, exit)
        assertTrue(out.contains("Unable to locate package"))
        assertEquals("非锁错误不得重试", 1, counter.readLines().size)
    }

    /**
     * 假 apt-get：`update` / `install` 各记一笔到 [counter]；install 只有在
     * [listsDir] 里有 `Packages` 文件时才成功 —— 模拟「重装 rootfs 后索引为空」。
     */
    private fun fakeAptIndex(counter: File, listsDir: File): File = File(tmp.root, "apt-get").apply {
        writeText(
            """
            #!/bin/sh
            echo "${'$'}1" >> "$counter"
            if [ "${'$'}1" = "update" ]; then
              echo fake > "$listsDir/mirror_binary-arm64_Packages"
              exit 0
            fi
            if ls "$listsDir" 2>/dev/null | grep -q Packages; then exit 0; fi
            echo "E: Unable to locate package curl" >&2
            exit 100
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    /** 把 aptInstallCommand 的索引判据指向临时目录（只测 `ls | grep` 这一段逻辑）。 */
    private fun installWithLists(listsDir: File): String =
        "PATH=${tmp.root}:\$PATH; " + APT_WAIT_FN +
            "ls ${listsDir.absolutePath} 2>/dev/null | grep -q Packages || kair_apt apt-get update; " +
            "kair_apt apt-get install -y --no-install-recommends curl"

    @Test
    fun `empty index gets an update before install`() {
        assumeLinux("真起 /bin/sh 与可执行位的假 apt-get")
        val counter = File(tmp.root, "calls")
        val lists = File(tmp.root, "lists").apply { mkdirs() }
        fakeAptIndex(counter, lists)
        val (exit, _) = run(installWithLists(lists))
        assertEquals("补了 update 后 install 必须成功", 0, exit)
        assertEquals("顺序必须是先 update 再 install", listOf("update", "install"), counter.readLines())
    }

    @Test
    fun `existing index skips the update`() {
        assumeLinux("真起 /bin/sh 与可执行位的假 apt-get")
        val counter = File(tmp.root, "calls")
        val lists = File(tmp.root, "lists").apply { mkdirs() }
        File(lists, "mirror_binary-arm64_Packages").writeText("stale ok\n")
        fakeAptIndex(counter, lists)
        val (exit, _) = run(installWithLists(lists))
        assertEquals(0, exit)
        assertEquals("有索引不得再 update（每次装工具多 40 MB）", listOf("install"), counter.readLines())
    }
}
