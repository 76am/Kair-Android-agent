package dev.kair.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：`assumeLinux()` 的使用点必须**恰好等于**已登记的 Linux-only 用例清单。
 *
 * 为什么必须有这条守卫（`docs/团队分工与交付流程.md` §3.1.5）：`assumeLinux()` 让
 * `:app:testDebugUnitTest` 在非 Linux 上也全绿，代价是它同时成了「把红变绿」的万能开关 ——
 * 没有白名单，任何人用例写崩了都能加一句「需要 Linux」让它消失（`AGENTS.md` §10.2：
 * 能靠开关复现的旧问题，等于没修）。所以**每个跳过点都要在这里署名一条平台原因**，
 * 加调用点必须同时改这个 `mapOf`，走评审。
 *
 * 清单来源：2026-10-27 在 Windows（JDK 17.0.20.1 + wrapper 8.14.5）实测
 * `:app:testDebugUnitTest` 的 36 个失败用例，与 §3.1.1 的九类逐类计数一致
 * （11 / 6 / 4 / 4 / 4 / 2 / 2 / 2 / 1）。逐条列名而不是按类跳过：这九个类里
 * 共有上百个用例，**绝大多数在 Windows 上能跑**，整类跳过会把 90% 的回归网一起废掉。
 */
class LinuxOnlyGuardTest {

    /**
     * 白名单：测试文件（相对 `dev/kair/app/`）→ 该类里**确实**需要 Linux 的用例显示名。
     * 每条后面的原因取自 §3.1.1 的「为什么需要 Linux」列。
     */
    private val allowed: Map<String, Set<String>> = mapOf(
        // ToolsTest —— 容器路径映射按 / 写死，Windows 上是盘符（11 例）
        "core/tools/ToolsTest.kt" to setOf(
            "atMentionedContainerPathAlsoMaps",
            "doesNotMapLookalikePrefixes",
            "doesNotMapSdcardLookalikes",
            "keepsAbsolutePaths",
            "mapsContainerSdcardChildToHostChild",
            "mapsContainerSdcardRootToHostStorage",
            "mapsContainerWorkspaceChildToCwdChild",
            "mapsContainerWorkspaceRootToCwd",
            "relativeWorkspaceSubdirStaysRelative",
            "resolvesRelativeAgainstCwd",
            "stripsLeadingAtMention",
        ),
        // WorkspacePermissionRepairTest —— 依赖 chmod / chown（6 例）
        "core/maintenance/WorkspacePermissionRepairTest.kt" to setOf(
            "container relative keeps workspace paths relative and rejects outside paths",
            "detects a broken child even when its parent directory looks healthy",
            "failed or missing root falls back to app-side adoption",
            "repair chowns only broken declared paths inside the workspace",
            "repair counts failed chown when adoption is impossible",
            "shared trash is repaired through its host path",
        ),
        // TarExtractorTest —— 依赖 tar 的权限位与符号链接语义（5 例）
        "core/proot/TarExtractorTest.kt" to setOf(
            "extracts regular file with content and mode",
            "extracts symlink and hardlink",
            "hardlink falls back to copy when the filesystem refuses link",
            "writing through symlink pointing inside root lands at real location",
            "writing through symlink pointing outside root is rejected",
        ),
        // AptShellTest —— 真起 /bin/sh 执行锁等待外壳与索引判据（4 例）
        "core/proot/AptShellTest.kt" to setOf(
            "lock contention is retried until the holder releases",
            "non-lock failures are not retried",
            "empty index gets an update before install",
            "existing index skips the update",
        ),
        // CommandRunnerTest —— 直接起 /bin/sh（4 例）
        "core/tools/CommandRunnerTest.kt" to setOf(
            "cancelling a local run kills the process",
            "local runner flags a timeout",
            "local runner reports non-zero exit code",
            "local runner returns merged output and exit code",
        ),
        // FileBackendTest —— 容器后端读容器内文件（4 例）
        "core/tools/FileBackendTest.kt" to setOf(
            "container backend lists a container-only directory",
            "container backend reads a container-only file",
            "container backend upgrades existence check to the container",
            "container backend writes byte-exactly even when the local view cannot",
        ),
        // DynamicPluginHostTest —— 脚本工具走 /bin/sh（2 例）
        "core/plugin/dynamic/DynamicPluginHostTest.kt" to setOf(
            "script tool passes args via env and captures stdout",
            "script tool reports non-zero exit as error",
        ),
        // ProotCommandRunnerTest —— proot 通道（2 例）
        "core/proot/ProotCommandRunnerTest.kt" to setOf(
            "delegates to fallback when abi unsupported",
            "delegates to fallback when rootfs not installed",
        ),
        // RootfsManagerTest —— rootfs 权限 / 属主（2 例）
        "core/proot/RootfsManagerTest.kt" to setOf(
            "archive with absolute symlink bin-sh (real alpine layout) installs",
            "detectState recognizes installed rootfs after process restart",
        ),
        // RootCommandRunnerTest —— PATH 解析语义（which / 可执行位）（1 例）
        "core/proot/RootCommandRunnerTest.kt" to setOf(
            "locateSu resolves relative names via PATH and falls back to absolute candidates",
        ),
    )

    @Test
    fun `assumeLinux call sites are exactly the registered whitelist`() {
        val root = testSourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // 扫到 0 个文件必须失败：路径写错的扫描测试会「永远绿」，那比没有测试更糟（P0-2 §验收点）。
        assertTrue(
            "测试源集里一个 .kt 都没扫到（root=$root）—— 是扫描路径写错了，不是真的没有调用点",
            files.isNotEmpty(),
        )

        val hits = files.flatMap { callSitesIn(it, root) }
        val unregistered = hits.filter { (rel, name) -> name !in allowed[rel].orEmpty() }
        val stale = allowed.toList().flatMap { (rel, names) ->
            names.filter { name -> hits.none { it.first == rel && it.second == name } }.map { rel to it }
        }

        assertTrue(
            buildString {
                if (unregistered.isNotEmpty()) {
                    appendLine("发现未登记的 assumeLinux() 调用点（${unregistered.size} 处）：")
                    unregistered.forEach { (rel, name) -> appendLine("  $rel #$name") }
                    appendLine()
                    appendLine("跳过用例的理由只能是「平台不具备」，且必须先在 docs/团队分工与交付流程.md §3.1.1 登记；")
                    append("确需新增时，把「文件 #用例名」加进 LinuxOnlyGuardTest.allowed 并在 PR 里说明平台原因。")
                }
                if (stale.isNotEmpty()) {
                    appendLine("白名单里有 ${stale.size} 条已不再对应任何调用点（用例被改名 / 删除 / 平台已可跑）：")
                    stale.forEach { (rel, name) -> appendLine("  $rel #$name") }
                    append("请同步删掉这些条目 —— 白名单只减不增，减的同时要把痕迹清干净。")
                }
            },
            unregistered.isEmpty() && stale.isEmpty(),
        )
    }

    @Test
    fun `every whitelisted file exists`() {
        val root = testSourceRoot()
        val ghosts = allowed.keys.filterNot { root.resolve(it).isFile }
        assertTrue(
            buildString {
                appendLine("白名单指向的测试文件不存在（${ghosts.size} 个）：")
                ghosts.forEach { appendLine("  $it") }
                append("文件名写错会让该条目永远匹配不上，白名单就成了死账。")
            },
            ghosts.isEmpty(),
        )
    }

    @Test
    fun `whitelist still covers the nine registered classes`() {
        // 数量对不上说明有人整类删条目或重复登记；这两件事都该被看见，而不是悄悄发生。
        // 2026-11-16：TarExtractorTest 新增「硬链降级」用例（+1）；AptShellTest 4 例
        // （apt 锁等待外壳真跑 /bin/sh ×2、索引为空先 update ×2）。
        assertEquals(10, allowed.size)
        assertEquals(41, allowed.values.sumOf { it.size })
    }

    /** 文件里的 `assumeLinux(...)` 调用点 →（相对路径, 所在用例显示名）。 */
    private fun callSitesIn(file: File, root: File): List<Pair<String, String>> {
        val lines = file.readLines(Charsets.UTF_8)
        val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
        val out = mutableListOf<Pair<String, String>>()
        lines.forEachIndexed { i, line ->
            val trimmed = line.trimStart()
            // 注释里提到 assumeLinux() 不算调用点（本文件的 KDoc 就到处是这个词）。
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                return@forEachIndexed
            }
            if (!CALL.containsMatchIn(line)) return@forEachIndexed
            val name = enclosingFunName(lines, i) ?: return@forEachIndexed
            // 命中的是 define 本身（TestPlatform.kt 里的 fun assumeLinux(why: String)），不算调用点。
            if (name == "assumeLinux") return@forEachIndexed
            out += rel to name
        }
        return out
    }

    /** 从 [at] 往上找最近一层 `fun 名(`，返回用例显示名（支持反引号里的空格名）。 */
    private fun enclosingFunName(lines: List<String>, at: Int): String? {
        for (i in at downTo 0) {
            FUN_NAME.find(lines[i])?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }
        return null
    }

    /**
     * 定位测试源集根（`.../dev/kair/app`）：测试工作目录通常在模块目录下，但也容忍从仓库根启动。
     * 找不到直接失败 —— 宁可报错也不要静默跳过检查。
     */
    private fun testSourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "app/src/test/java/dev/kair/app"),
                File(dir, "src/test/java/dev/kair/app"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("找不到测试源集目录，Linux-only 守卫无法运行（工作目录须位于仓库内）")
    }

    private companion object {
        /**
         * 只认**句首**调用（ktlint 风格下一条语句一行，36 个登记点全是这个形状）。
         * 这样本文件里的注释与报错文案（「发现未登记的 assumeLinux() 调用点」）不会被当成调用点；
         * 代价是把 `assumeLinux()` 写进表达式中间会扫不到 —— 那种写法本来就该被评审拦下。
         */
        val CALL = Regex("""^\s*assumeLinux\(""")

        /** 用例声明行：允许缩进与 `private` / `suspend` 之类修饰符，名字允许反引号（含空格）。 */
        val FUN_NAME = Regex("""^\s*(?:\w+\s+)*fun\s+(?:`([^`]+)`|([A-Za-z0-9_]+))\s*\(""")
    }
}
