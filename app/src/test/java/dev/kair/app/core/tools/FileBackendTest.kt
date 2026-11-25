package dev.kair.app.core.tools

import dev.kair.app.core.assumeLinux
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 文件访问后端的接缝测试。
 *
 * - [LocalFileBackend]：与应用自身权限一致，行为须与引入接缝前逐字节相同。
 * - [ContainerFileBackend]：本地快路径优先；本地够不到/改不动时升级到容器内
 *   （root）执行。测试用一个把命令跑在「模拟容器根目录」里的 [ShellRunner]
 *   来区分两个视图，从而真实验证升级路径与 base64 落盘的字节精确性。
 */
class FileBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- LocalFileBackend：保原行为 -----------------------------------------

    @Test
    fun `local backend round-trips content byte-exactly`() = runBlocking {
        val ws = tmp.newFolder("ws")
        val backend = LocalFileBackend(ws.path)
        val target = backend.resolve("src/需要/中文.txt")

        val content = "line1\nline2 with 'quote' and \"double\"\n中文🙂\n"
        backend.writeText(target, content)

        assertTrue(backend.exists(target))
        assertFalse(backend.isDirectory(target))
        assertEquals(content, backend.readText(target))
    }

    @Test
    fun `local backend lists and walks directories`() = runBlocking {
        val ws = tmp.newFolder("ws")
        val backend = LocalFileBackend(ws.path)
        File(ws, "a.txt").writeText("a")
        File(ws, "sub").mkdirs()
        File(ws, "sub/b.log").writeText("b")
        File(ws, "node_modules").mkdirs()
        File(ws, "node_modules/skip.js").writeText("x")

        val entries = backend.list(ws.path).map { it.name }.sorted()
        assertEquals(listOf("a.txt", "node_modules", "sub"), entries)

        val walked = backend.walk(ws.path, setOf("node_modules")).map { File(it).name }.sorted()
        assertEquals(listOf("a.txt", "b.log"), walked)
    }

    // ---- ContainerFileBackend：解析与本地快路径 ------------------------------

    @Test
    fun `container backend resolves like the local view`() {
        val ws = tmp.newFolder("ws")
        val backend = ContainerFileBackend(ShellRunner(tmp.newFolder("root")), ws)

        assertEquals(PathUtils.resolveToCwd("a/b.txt", ws.path), backend.resolve("a/b.txt"))
        assertEquals(ws.path, backend.resolve("/workspace"))
    }

    /**
     * 容器把工作区父目录挂到 `/projects`：文件工具必须认这个视图，否则模型从 `<cwd>`
     * 抄来的 `/projects/<项目>/x` 只有部分工具能用（read_image 甚至完全不认）。
     */
    @Test
    fun `container backend maps the projects mount back to the workspace parent`() {
        val ws = tmp.newFolder("ws")
        val backend = ContainerFileBackend(ShellRunner(tmp.newFolder("root")), ws)

        assertEquals(File(ws, "shots/a.png").path, backend.resolve("/projects/ws/shots/a.png"))
        assertEquals(
            File(ws.parentFile, "other/a.png").path,
            backend.resolve("/projects/other/a.png"),
        )
        assertEquals(ws.parentFile!!.path, backend.resolve("/projects"))
        // 长得像但不是挂载点，保持原样。
        assertEquals("/projectsX", backend.resolve("/projectsX"))
    }

    @Test
    fun `container backend serves existing local files without invoking the runner`() = runBlocking {
        val ws = tmp.newFolder("ws")
        val runner = ShellRunner(tmp.newFolder("root"))
        val backend = ContainerFileBackend(runner, ws)
        val file = File(ws, "here.txt").apply { writeText("local-content") }

        assertTrue(backend.exists(file.path))
        assertTrue(backend.isDirectory(ws.path))
        assertEquals("local-content", backend.readText(file.path))
        assertEquals(1, backend.list(ws.path).size)

        assertNull("本地快路径不应触碰容器后端", runner.lastCommand)
    }

    // ---- ContainerFileBackend：升级到容器 ------------------------------------

    @Test
    fun `container backend upgrades existence check to the container`() = runBlocking {
        assumeLinux("容器后端读容器内文件")
        val ws = tmp.newFolder("ws")
        val root = tmp.newFolder("root")
        val runner = ShellRunner(root)
        val backend = ContainerFileBackend(runner, ws)
        // 只在「容器」里存在的文件：宿主机视图看不到。
        File(root, "only-container.txt").writeText("x")

        val target = File(ws, "only-container.txt").path
        assertFalse(File(target).exists())
        assertTrue(backend.exists(target))
        assertTrue(runner.lastCommand!!.contains("test -e"))
    }

    @Test
    fun `container backend reads a container-only file`() = runBlocking {
        assumeLinux("容器后端读容器内文件")
        val ws = tmp.newFolder("ws")
        val root = tmp.newFolder("root")
        val backend = ContainerFileBackend(ShellRunner(root), ws)
        File(root, "deep/secret.txt").apply { parentFile!!.mkdirs(); writeText("from-container") }

        assertEquals("from-container", backend.readText(File(ws, "deep/secret.txt").path))
    }

    @Test
    fun `container backend lists a container-only directory`() = runBlocking {
        assumeLinux("容器后端读容器内文件")
        val ws = tmp.newFolder("ws")
        val root = tmp.newFolder("root")
        val backend = ContainerFileBackend(ShellRunner(root), ws)
        File(root, "sub").mkdirs()
        File(root, "sub/one.txt").writeText("1")
        File(root, "sub/two.txt").writeText("2")

        val names = backend.list(File(ws, "sub").path).map { it.name }.sorted()
        assertEquals(listOf("one.txt", "two.txt"), names)
    }

    @Test
    fun `container backend writes byte-exactly even when the local view cannot`() = runBlocking {
        assumeLinux("容器后端读容器内文件")
        val ws = tmp.newFolder("ws")
        val root = tmp.newFolder("root")
        val backend = ContainerFileBackend(ShellRunner(root), ws)
        // 用普通文件占位，令本地写路径失败（无法在其中建父目录），从而触发容器升级。
        File(ws, "blocker").writeText("not a directory")

        val content = "first\nwith 'quotes' and \"doubles\"\n中文🙂\nbase64-safe\tbytes\n"
        backend.writeText(File(ws, "blocker/out.txt").path, content)

        // 内容应落在「容器」视图里，且字节精确。
        val landed = File(root, "blocker/out.txt")
        assertTrue("容器升级写应完成", landed.exists())
        assertEquals(content, landed.readText())
    }

    /**
     * 把命令跑在独立的根目录里的「模拟容器」：忽略宿主 workspace，用 [root] 作
     * cwd —— 于是容器视图与本地视图指向不同文件系统，能真实验证升级路径。
     */
    private class ShellRunner(private val root: File) : CommandRunner {
        override val id = "test-container"
        override val displayName = "test-container"
        override val description = ""
        var lastCommand: String? = null

        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
            lastCommand = command
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(timeoutSeconds ?: 10, TimeUnit.SECONDS)
            return CommandOutput(out, process.exitValue())
        }
    }
}
