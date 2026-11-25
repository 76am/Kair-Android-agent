package dev.kair.app.core.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * `FileBackend.delete` 的诚实性（2026-11-13，负责人真机报的「谎报已删除」）。
 *
 * 现象：工作区里一个 root 属主、`700` 的目录，文件面板删它 → 界面写「已删除 …」，
 * 目录原封不动躺在列表里。根因是 `delete()` 只做「尝试」、不复查结果：
 * `LocalFileBackend` 丢掉 `delete()` 的返回值，`ContainerFileBackend` 升级到容器后
 * 仍存在也不抛。于是调用方的 `onSuccess` 分支必然执行，提示就成了谎话。
 *
 * 口径（与 U78 的 C-5 同构）：**命令末尾自证** —— 删完必须复查，仍在就抛。
 * 「不存在视为成功」这条契约不变（幂等删除仍要能跑）。
 *
 * 造失败的方式用 `/proc/self/cmdline`：procfs 对 unlink 一律 `EPERM`，**任何 uid 都删不掉**，
 * 不依赖权限位。第一版用 `chmod 500` 造失败，在本机被两件事坑了：proot 的 `fake_id0` 会
 * 伪装 `access(2)`，且容器里 chmod 过的目录会被还原 —— 结果 4 条里 2 条静默 skipped。
 */
class FileBackendDeleteHonestyTest {

    /** procfs 上不可删的条目；非 Linux 平台（Windows 工位）返回 null → 相关用例跳过。 */
    private fun undeletablePath(): String? {
        val path = "/proc/self/cmdline"
        val file = File(path)
        if (!file.exists()) return null
        // 前提自检：它必须真的删不掉，否则这条用例证明不了任何事。
        if (runCatching { file.delete() }.getOrDefault(false)) return null
        if (!file.exists()) return null
        return path
    }

    private fun tempDir(): File = Files.createTempDirectory("kair-del").toFile()

    @Test
    fun `deleting a missing path is still success`() {
        runBlocking {
            LocalFileBackend(tempDir().path).delete("/definitely/not/here/nothing.txt")
        }
    }

    @Test
    fun `deleting an existing file removes it without throwing`() {
        runBlocking {
            val dir = tempDir()
            val file = File(dir, "a.txt").apply { writeText("hello") }
            LocalFileBackend(dir.path).delete(file.path)
            assertFalse("有权限的删除必须真的删掉", file.exists())
        }
    }

    @Test
    fun `local backend throws when the entry survives deletion`() {
        runBlocking {
            val path = undeletablePath()
            assumeTrue("本平台没有不可删的 procfs 条目，跳过", path != null)
            val thrown = runCatching { LocalFileBackend(tempDir().path).delete(path!!) }.exceptionOrNull()
            assertTrue(
                "删不掉却不抛 = 面板谎报「已删除」。必须抛 FileNotFoundException，实际：$thrown",
                thrown is FileNotFoundException,
            )
            assertTrue("既然抛了，条目应当还在（证明它确实没删掉）", File(path).exists())
        }
    }

    @Test
    fun `container backend throws when neither side removed it`() {
        runBlocking {
            val path = undeletablePath()
            assumeTrue("本平台没有不可删的 procfs 条目，跳过", path != null)
            // 容器侧假装执行成功：真实 proot 的 --root-id 只伪造 uid，同样删不掉 procfs 条目。
            val noopRunner = object : CommandRunner {
                override val id = "noop"
                override val displayName = "空转后端"
                override val description = "命令返回成功但什么都不做，用来验证复查逻辑"
                override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?) =
                    CommandOutput("", 0)
            }
            val backend = ContainerFileBackend(noopRunner, tempDir())
            val thrown = runCatching { backend.delete(path!!) }.exceptionOrNull()
            assertTrue(
                "本地与容器都没删掉时必须抛，不许当成功返回。实际：$thrown",
                thrown is FileNotFoundException,
            )
        }
    }

    /**
     * 本地那一步现在会抛（`LocalFileBackend.delete` 自证），但容器档的正确行为是
     * **本地删不掉就升级到容器** —— 异常必须就地吞掉再升级。`root` 属主、目录可写的条目
     * 正好走这条路：直删 EACCES，容器 root 删得掉。吞不掉就变成误报失败（U80 补漏）。
     */
    @Test
    fun `container backend still upgrades when the local delete throws`() {
        runBlocking {
            val locked = File(tempDir(), "locked").apply { mkdirs() }
            val target = File(locked, "child.txt").apply { writeText("x") }
            locked.setWritable(false)
            assumeTrue("本平台没有 POSIX 目录权限位语义，跳过", target.exists() && !target.delete())
            try {
                assumeTrue("目录已有写权限时这条用例证明不了「本地失败」，跳过", target.exists())
                // 容器侧会真的删掉它（模拟 root）：先在 runner 里放开目录权限，删完再收回。
                val rootLikeRunner = object : CommandRunner {
                    override val id = "root-like"
                    override val displayName = "假 root 后端"
                    override val description = "本地删不掉时由它删，用来验证升级路径没被异常截断"
                    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
                        if (command.startsWith("rm -rf")) {
                            locked.setWritable(true)
                            target.delete()
                            locked.setWritable(false)
                        }
                        return CommandOutput("", 0)
                    }
                }
                ContainerFileBackend(rootLikeRunner, tempDir()).delete(target.path)
                assertFalse("容器删掉了就必须当成功返回，不许因为本地那次失败而抛", target.exists())
            } finally {
                locked.setWritable(true)
            }
        }
    }

    /**
     * 「查不到」不等于「不存在」（U84）。
     *
     * 父目录去掉 `x` 位后，`stat("父/子")` 返回 `EACCES` —— 而 `File.exists()` 把任何失败都吞成
     * `false`。旧实现据此认定「本来就没有」，**不删也不抛**，直接当成功返回（静默成功）。
     *
     * 这条只在本地档可达：容器档已被「宿主看不见 → 问容器 `test -e`」覆盖（U80 补漏）。
     * 面板/agent 在有容器时都走容器档，所以真机上点不到 —— 但 `delete()` 的契约是
     * 「删不掉就抛」，契约不能留这种缝。
     */
    @Test
    fun `local backend throws when the parent directory cannot be searched`() {
        runBlocking {
            val parent = File(tempDir(), "unsearchable").apply { mkdirs() }
            val target = File(parent, "child.txt").apply { writeText("x") }
            // 只去 `x`：列目录（r）还在，但 `stat` 子条目需要父目录的搜索权限。
            parent.setExecutable(false, false)
            try {
                assumeTrue(
                    "本平台没有 POSIX 目录权限位语义（去掉 x 后仍能 stat），跳过",
                    !target.exists(),
                )
                val thrown = runCatching { LocalFileBackend(parent.path).delete(target.path) }
                    .exceptionOrNull()
                assertTrue(
                    "父目录不可搜索时，「查不到」被当成「不存在」→ 删不掉却报成功。必须抛，实际：$thrown",
                    thrown is FileNotFoundException,
                )
            } finally {
                parent.setExecutable(true, false)
            }
        }
    }

    /**
     * 宿主**看不见**的路径（父目录 `root` 属主 `700`，应用连 `stat` 都 EACCES）本地复查恒为
     * 「不存在」，只有容器 `test -e` 能回答「到底删掉没有」。不许把「我看不见」当作「已删除」。
     */
    @Test
    fun `container backend asks the container when the host cannot see the path`() {
        runBlocking {
            fun runner(containerSaysExists: Boolean) = object : CommandRunner {
                override val id = "probe"
                override val displayName = "探针后端"
                override val description = "只回答 test -e，用来验证「宿主看不见」时的自证来源"
                override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?) =
                    CommandOutput("", if (command.startsWith("test -e") && containerSaysExists) 0 else 1)
            }
            val ghost = File(tempDir(), "ghost.txt") // 宿主视图里确实不存在
            val thrown = runCatching { ContainerFileBackend(runner(true), tempDir()).delete(ghost.path) }
                .exceptionOrNull()
            assertTrue(
                "宿主看不见不等于删掉了：容器说还在就必须抛。实际：$thrown",
                thrown is FileNotFoundException,
            )
            val ok = runCatching { ContainerFileBackend(runner(false), tempDir()).delete(ghost.path) }
                .exceptionOrNull()
            assertTrue("容器也说没有 = 本来就不存在，按契约必须当成功返回。实际：$ok", ok == null)
        }
    }
}
