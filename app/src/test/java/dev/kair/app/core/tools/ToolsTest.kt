package dev.kair.app.core.tools

import dev.kair.app.core.assumeLinux
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Unit tests for the tool primitives ported from pi (truncation, glob, path). */
class ToolsTest {

    // ---- truncation (pi: truncate.ts) -------------------------------------

    @Test
    fun headDoesNotTruncateWithinLimits() {
        val r = truncateHead("a\nb\nc", maxLines = 10, maxBytes = 1024)
        assertFalse(r.truncated)
        assertEquals("a\nb\nc", r.content)
        assertEquals(3, r.totalLines)
    }

    @Test
    fun headTruncatesByLines() {
        val r = truncateHead("a\nb\nc\nd", maxLines = 2, maxBytes = 1024)
        assertTrue(r.truncated)
        assertEquals("lines", r.truncatedBy)
        assertEquals("a\nb", r.content)
        assertEquals(4, r.totalLines)
        assertEquals(2, r.outputLines)
    }

    @Test
    fun headTruncatesByBytes() {
        // Each line is 5 bytes ("aaaa\n" -> lines are "aaaa"); limit 6 bytes allows 1 line.
        val r = truncateHead("aaaa\nbbbb\ncccc", maxLines = 10, maxBytes = 6)
        assertTrue(r.truncated)
        assertEquals("bytes", r.truncatedBy)
        assertEquals("aaaa", r.content)
    }

    @Test
    fun tailKeepsLastLines() {
        val r = truncateTail("a\nb\nc\nd", maxLines = 2, maxBytes = 1024)
        assertTrue(r.truncated)
        assertEquals("c\nd", r.content)
    }

    @Test
    fun truncateLineAddsSuffix() {
        val (out, truncated) = truncateLine("abcdef", maxChars = 3)
        assertTrue(truncated)
        assertEquals("abc... [truncated]", out)
        val (keep, no) = truncateLine("ab", maxChars = 3)
        assertFalse(no)
        assertEquals("ab", keep)
    }

    // ---- glob (pi: find/glob matcher) --------------------------------------

    @Test
    fun globMatchesBasename() {
        val re = globToRegex("*.ts")
        assertTrue(re.matches("foo.ts"))
        assertFalse(re.matches("foo.js"))
        assertFalse(re.matches("dir/foo.ts"))
    }

    @Test
    fun globDoubleStarCrossesDirectories() {
        val re = globToRegex("src/**/*.ts")
        assertTrue(re.matches("src/a.ts"))
        assertTrue(re.matches("src/a/b.ts"))
        assertFalse(re.matches("lib/a.ts"))
    }

    // ---- path resolution (pi: path-utils.ts) -------------------------------

    @Test
    fun resolvesRelativeAgainstCwd() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/work/a/b.txt", PathUtils.resolveToCwd("a/b.txt", "/work"))
    }

    @Test
    fun keepsAbsolutePaths() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/etc/hosts", PathUtils.resolveToCwd("/etc/hosts", "/work"))
    }

    @Test
    fun stripsLeadingAtMention() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/work/a.txt", PathUtils.resolveToCwd("@a.txt", "/work"))
    }

    // ---- 容器路径桥接（/workspace → 真实工作区） ---------------------------

    @Test
    fun mapsContainerWorkspaceRootToCwd() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/real/ws", PathUtils.resolveToCwd("/workspace", "/real/ws"))
    }

    @Test
    fun mapsContainerWorkspaceChildToCwdChild() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/real/ws/src/Main.kt", PathUtils.resolveToCwd("/workspace/src/Main.kt", "/real/ws"))
    }

    @Test
    fun doesNotMapLookalikePrefixes() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        // "/workspaceX" 不是挂载点；"/workspac" 也不是
        assertEquals("/workspaceX", PathUtils.resolveToCwd("/workspaceX", "/real/ws"))
        assertEquals("/workspac", PathUtils.resolveToCwd("/workspac", "/real/ws"))
    }

    @Test
    fun relativeWorkspaceSubdirStaysRelative() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        // 相对路径下的 workspace/ 子目录本来就是 cwd 内目录，不映射
        assertEquals("/real/ws/workspace/a", PathUtils.resolveToCwd("workspace/a", "/real/ws"))
    }

    @Test
    fun atMentionedContainerPathAlsoMaps() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/real/ws/a.txt", PathUtils.resolveToCwd("@/workspace/a.txt", "/real/ws"))
    }

    @Test
    fun mapsContainerSdcardRootToHostStorage() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/storage/emulated/0", PathUtils.resolveToCwd("/sdcard", "/real/ws"))
    }

    @Test
    fun mapsContainerSdcardChildToHostChild() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals(
            "/storage/emulated/0/DCIM/IMG_1.jpg",
            PathUtils.resolveToCwd("/sdcard/DCIM/IMG_1.jpg", "/real/ws"),
        )
    }

    @Test
    fun doesNotMapSdcardLookalikes() {
        assumeLinux("容器路径映射按 / 写死，Windows 上是盘符")
        assertEquals("/sdcardX", PathUtils.resolveToCwd("/sdcardX", "/real/ws"))
        assertEquals("/sdcards", PathUtils.resolveToCwd("/sdcards", "/real/ws"))
    }

    // ---- bash 默认超时（防挂起命令卡死串行工具循环） -------------------------

    private class CapturingRunner : CommandRunner {
        var timeoutSeconds: Long? = -999L
        override val id = "capturing"
        override val displayName = "capturing"
        override val description = "capturing"
        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
            this.timeoutSeconds = timeoutSeconds
            return CommandOutput("ok", 0)
        }
    }

    private suspend fun runBash(args: String, runner: CommandRunner): ToolResult {
        val ctx = ToolContext(File("."), "test", commandRunner = runner)
        return BashTool().execute(JSON.parseToJsonElement(args).jsonObject, ctx)
    }

    @Test
    fun bashDefaultsTo300sWhenTimeoutOmitted() = runBlocking {
        val runner = CapturingRunner()
        runBash("""{"command":"echo hi"}""", runner)
        assertEquals(300L, runner.timeoutSeconds)
    }

    @Test
    fun bashPassesExplicitTimeoutThrough() = runBlocking {
        val runner = CapturingRunner()
        runBash("""{"command":"echo hi","timeout":60}""", runner)
        assertEquals(60L, runner.timeoutSeconds)
    }

    @Test
    fun bashTreatsZeroAsNoTimeout() = runBlocking {
        val runner = CapturingRunner()
        runBash("""{"command":"sleep 999","timeout":0}""", runner)
        assertNull(runner.timeoutSeconds)
    }

    // ---- bash 拦截宿主工作区绝对路径（防 find / 全盘扫吃满超时） ----------------

    @Test
    fun bashRejectsHostWorkspacePathInsteadOfRunningIt() = runBlocking {
        val ws = File("").absoluteFile // 与 ToolContext 的 workspace 同口径（绝对路径）
        val runner = CapturingRunner()
        val ctx = ToolContext(ws, "test", commandRunner = runner)
        val command = "wc -l $ws/.kair/memory.md" // 含宿主工作区绝对路径字面量
        // 用 JSON builder，别把路径拼进字符串：Windows 的 `F:\...` 反斜杠是非法 JSON 转义。
        val args = buildJsonObject { put("command", command) }
        val e = runCatching { BashTool().execute(args, ctx) }.exceptionOrNull()
        assertTrue("应拦截宿主路径命令", e is ToolException)
        assertTrue("报错必须指路（容器内同目录 / read 系工具）", (e as ToolException).message!!.contains("does not exist inside"))
        assertEquals("拦截发生在执行前，命令不许真的跑（初始值 -999 未被覆盖）", -999L, runner.timeoutSeconds)
    }

    @Test
    fun bashStillRunsCommandsWithoutHostPath() = runBlocking {
        val runner = CapturingRunner()
        runBash("""{"command":"wc -l /projects/ws/.kair/memory.md"}""", runner)
        assertEquals(300L, runner.timeoutSeconds) // 容器路径正常放行
    }

    @Test
    fun localShellDoesNotRejectHostWorkspacePaths() = runBlocking {
        val ws = File("").absoluteFile
        val runner = CapturingRunner()
        // 宿主 shell 里宿主路径合法：bash 的拦截若搬进共用 runShell，这条会红。
        LocalShellTool(runner).execute(
            buildJsonObject { put("command", "echo $ws/memory.md") },
            ToolContext(ws, "test"),
        )
        assertEquals("android_shell 不得被 container_shell 的宿主路径拦截误伤", 300L, runner.timeoutSeconds)
    }

    // ---- 只读工具标记（`subagent` 的 search 类型据此过滤工具集） -------------

    @Test
    fun onlyReadOnlyToolsAreMarkedReadOnly() {
        val readOnly = builtinTools().filter { it.readOnly }.map { it.name }.toSet()
        assertEquals(setOf("read", "ls", "grep", "find", "read_image"), readOnly)
    }
}
