package dev.kair.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 隐式全局守卫（B7 / P4-2）：把 `AGENTS.md` §10.4「执行层不得读『当前会话 / 当前工作区』」
 * 从文字约定变成机械判据。
 *
 * 为什么需要它：A4（批次 6）把最后一处「取不到就回退全局」删掉了（`activeWorkspace` 字段与
 * `activeWorkspaceDir` 取值器已不存在），但**没有守卫的删除会被下一个人免费加回来** ——
 * 「这里取个当前工作区比较方便」正是这条规则一直以来的死法。
 *
 * 判定口径（唯一口径，和 `UiTextGuardTest` / `DocsConsistencyTest` 一样）：
 * 1. **先剥注释**（`//`、可嵌套块注释、KDoc）—— 文档里提到这些名字是合法的，
 *    不剥会把「解释这条规则」的注释本身判成违规；
 * 2. 再按**标识符右边界**匹配（`\b`）：`"activeWorkspaceDir"` 不算一次 `activeWorkspace`，
 *    否则前缀关系会互相冒充（本仓已经踩过：`R.string.foo` 命中 `R.string.foobar`）；
 * 3. 命中要报**文件 + 行号 + 标识符**，不是一句 `assertTrue failed`。
 *
 * 四条规则分别对应 P3-3B 的三类读点（`docs/tasks/archive/P3-3B-作用域收敛.md` §step4）：
 * | 规则 | 范围 | 上限 |
 * |---|---|---|
 * | core 零容忍 | `core/` 全境 | 0（内核不得感知「当前会话」） |
 * | 插件侧工作区零容忍 | `ui/plugins/` 全境 | 0（工作区必须来自 `activeScope()`） |
 * | 插件侧会话 id | `ui/plugins/` 全境 | 按文件登记，只减不增（**UI 意图**：用户在看哪个会话） |
 * | 装配层上限 | `app/AppContainer.kt` | `activeWorkspace*` = 0、`activeSessionId` ≤ 4（U31 ③-b 起实测 4） |
 * | 编排层上限 | `app/SessionLifecycle.kt` | `activeWorkspace*` = 0、`activeSessionId` ≤ 3（实测 3，写权在此） |
 */
class GlobalStateGuardTest {

    /**
     * 插件侧允许读 `container.activeSessionId` 的文件 → 允许的出现次数。
     *
     * **为什么允许**：这三处都是「用户正在看哪个会话」这一类 **UI 意图**，不是执行层取工作区 ——
     * `P3-3B` §step4 明确把它们列为「允许保留」；`P4-2` 写的「`ui/plugins` 0 处」按 G1 的
     * 「插件侧读 container 必须走显式接口」收窄为「**工作区**类全局 0 处」。
     * 三处的形态一律是 `?:` 到**另一个会话 id**（不是工作区）或 null 即提示 / 过滤，
     * 没有一处「取不到就回退全局目录」。
     *
     * 只减不增：新增文件 / 增加次数都要改这张表并进评审。
     */
    private val pluginSessionIdBudget: Map<String, Int> = mapOf(
        // 审批卡片：子智能体的委派链解析不到时，父会话兜底用「当前会话」——
        // 卡片宁可挂到当前会话，也不能因为委派链断了就消失（`ApprovalPlugin` 的注释写了理由）。
        "ui/plugins/builtin/ApprovalPlugin.kt" to 2,
        // 提问面板：同上，找展示会话（子智能体 → 顶层父会话 → 当前会话）。
        "ui/plugins/builtin/AskUserPlugin.kt" to 1,
        // 缓存 / 诊断面板：按「用户正在看哪个会话」过滤本会话的请求与诊断条目。
        "ui/plugins/builtin/CachePanel.kt" to 3,
        // 记忆插件（U64）：整理必须「只在 TurnEnd 的会话同时是前台会话」时执行（负责人
        // 2026-11-08 追加约束的口径，原文见任务文档 §4.5 与交接）—— 这是**用户意图**读点：
        // 拿不到 / 不相等就推迟到该会话的下一个回合，**绝不回退**到别的会话工作区。
        // 5 处 = 启动判定 / 整理入口复核 / 依赖面声明 / 容器实现（最后一行两个标识符）。
        "ui/plugins/builtin/MemoryPlugin.kt" to 5,
    )

    /** `AppContainer.kt` 里 `activeSessionId` 的出现次数上限（装配层的合法读点，见交接 §7.3）。 */
    private val containerSessionIdCeiling = 4
    private val lifecycleSessionIdCeiling = 3

    @Test
    fun `core never reads the active session or workspace`() {
        val hits = hits(coreRoot(), ACTIVE_NAMES)
        assertTrue(
            buildString {
                appendLine("core 里出现了「当前会话 / 当前工作区」（规则 1，零容忍）：")
                hits.forEach { appendLine("  ${it.file}:${it.line}  ${it.name}") }
                appendLine()
                append("执行层要工作区只能从显式传入的 SessionScope 里取；拿不到就 fail-fast。")
                append("（AGENTS.md §10.4；例外必须走评审，不要加白名单。）")
            },
            hits.isEmpty(),
        )
    }

    @Test
    fun `plugin side never reads the container workspace`() {
        val hits = hits(pluginRoot(), PLUGIN_WORKSPACE_NAMES)
        assertTrue(
            buildString {
                appendLine("ui/plugins 里出现了工作区类的隐式全局（规则 2，零容忍）：")
                hits.forEach { appendLine("  ${it.file}:${it.line}  ${it.name}") }
                append("工作区必须来自 container.activeScope()?.workspace；没有作用域就提示并停手。")
            },
            hits.isEmpty(),
        )
    }

    @Test
    fun `plugin side reads the current session only where registered`() {
        val actual = hits(pluginRoot(), listOf("activeSessionId")).groupingBy { it.file }.eachCount()
        val offenders = mutableListOf<String>()
        actual.forEach { (file, n) ->
            val allowed = pluginSessionIdBudget[file]
            when {
                allowed == null -> offenders += "  $file：$n 处，未登记"
                n > allowed -> offenders += "  $file：$n 处，登记 $allowed 处（超 ${n - allowed}）"
            }
        }
        pluginSessionIdBudget.forEach { (file, _) ->
            if (file !in actual) offenders += "  $file：登记表里有、实际已无 —— 请把登记删掉（棘轮只减不增）"
        }
        assertTrue(
            buildString {
                appendLine("ui/plugins 读「当前会话」的位置与登记表不一致（规则 3）：")
                offenders.forEach { appendLine(it) }
                append("这类读点属于 UI 意图（用户在看哪个会话），但每处都要登记并写理由；")
                append("新出现的读点先问一句「是不是该由调用方把 sessionId 传进来」。")
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `assembly layer keeps the counters at or below the registered ceiling`() {
        val path = "app/src/main/java/dev/kair/app/app/AppContainer.kt"
        val code = stripComments(requireFile(path).readText(Charsets.UTF_8))
        val workspaceHits = count(code, WORKSPACE_NAMES)
        val sessionHits = count(code, listOf("activeSessionId"))
        assertTrue(
            "AppContainer 里工作区类隐式全局必须为 0（实际：$workspaceHits）—— " +
                "工作区只能经 activeScope() / 显式参数取（规则 4）",
            workspaceHits == 0,
        )
        assertTrue(
            "AppContainer 里 activeSessionId 出现 $sessionHits 次，超过登记上限 $containerSessionIdCeiling；" +
                "新增读点前先确认它是不是 UI 意图，并同步改本测试的登记值（规则 4）",
            sessionHits <= containerSessionIdCeiling,
        )

        // U31 ③-a：解析族搬进 SessionAssembly，扫描面必须跟着走 —— 否则新对象里加一个
        // `activeSessionId` / 工作区全局就完全空转。这两条在新文件里不是「上限」，而是 0：
        // 解析族只持有 settings / tools / plugins / diagnostics，没有任何会话或工作区的读写。
        val assemblyPath = "app/src/main/java/dev/kair/app/app/SessionAssembly.kt"
        val assembly = stripComments(requireFile(assemblyPath).readText(Charsets.UTF_8))
        val assemblyWorkspaceHits = count(assembly, WORKSPACE_NAMES)
        val assemblySessionHits = count(assembly, listOf("activeSessionId"))
        assertTrue(
            "SessionAssembly 里工作区类隐式全局必须为 0（实际：$assemblyWorkspaceHits）—— " +
                "解析族不得自己去找当前工作区",
            assemblyWorkspaceHits == 0,
        )
        assertTrue(
            "SessionAssembly 里 activeSessionId 必须为 0（实际：$assemblySessionHits）—— " +
                "解析族不持会话指针；写权归 ③-b",
            assemblySessionHits == 0,
        )

        // U31 ③-b：编排段搬进 SessionLifecycle，写权与读点都在新文件；扫描面必须跟着走。
        // 它不再有「当前工作区」类全局（工作区一律经 activeScope() / 显式参数），
        // activeSessionId 是登记的合法读点：取值器 + sessionFor 的 keep + activeSessionWorkspace。
        val lifecyclePath = "app/src/main/java/dev/kair/app/app/SessionLifecycle.kt"
        val lifecycle = stripComments(requireFile(lifecyclePath).readText(Charsets.UTF_8))
        val lifecycleWorkspaceHits = count(lifecycle, WORKSPACE_NAMES)
        val lifecycleSessionHits = count(lifecycle, listOf("activeSessionId"))
        assertTrue(
            "SessionLifecycle 里工作区类隐式全局必须为 0（实际：$lifecycleWorkspaceHits）—— " +
                "编排层只能经 activeScope() / 显式参数取工作区（规则 4）",
            lifecycleWorkspaceHits == 0,
        )
        assertTrue(
            "SessionLifecycle 里 activeSessionId 出现 $lifecycleSessionHits 次，超过登记上限 " +
                "$lifecycleSessionIdCeiling；新增读点前先确认它是不是编排本身需要（规则 4）",
            lifecycleSessionHits <= lifecycleSessionIdCeiling,
        )
    }

    @Test
    fun `the scan actually found source files`() {
        // 守卫最典型的失效是「路径写错 → 一个文件都没扫到 → 永远绿」。
        val core = files(coreRoot()).size
        val plugins = files(pluginRoot()).size
        assertTrue("core 一个 .kt 都没扫到 —— 扫描路径写错了，不等于没有违规", core > 0)
        assertTrue("ui/plugins 一个 .kt 都没扫到 —— 扫描路径写错了，不等于没有违规", plugins > 0)
    }

    // ------------------------------------------------------------------ 实现

    private data class Hit(val file: String, val line: Int, val name: String)

    private val ACTIVE_NAMES = listOf("activeSessionId", "activeSessionWorkspace")
    private val WORKSPACE_NAMES = listOf("activeWorkspace", "activeWorkspaceDir")

    /**
     * 插件侧的工作区类全局：除两个已删的字段名外，还包括**仍在**的 `activeSessionWorkspace()`。
     *
     * 它是「按当前会话取工作区」的取值器，同样是绕过 `activeScope()` 的入口（且不是单次快照）。
     * 规则 4 不能复用它 —— `AppContainer` 里这行函数声明本身就会命中。
     */
    private val PLUGIN_WORKSPACE_NAMES = WORKSPACE_NAMES + "activeSessionWorkspace"

    private fun hits(root: File, names: List<String>): List<Hit> =
        files(root).flatMap { file ->
            val code = stripComments(file.readText(Charsets.UTF_8))
            val rel = relativeToApp(file)
            names.flatMap { name ->
                Regex("\\b" + Regex.escape(name) + "\\b").findAll(code).map { m ->
                    Hit(rel, code.take(m.range.first).count { it == '\n' } + 1, name)
                }
            }
        }.sortedWith(compareBy({ it.file }, { it.line }))

    private fun count(code: String, names: List<String>): Int =
        names.sumOf { name -> Regex("\\b" + Regex.escape(name) + "\\b").findAll(code).count() }

    private fun files(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relativeToApp(file: File): String =
        // 先归一化分隔符再切：Windows 上是反斜杠，直接 substringAfter("/") 会切不动，
        // 于是登记表永远匹配不上（红的原因会变成「路径格式」而不是「违规」）。
        file.absolutePath.replace(File.separatorChar, '/').substringAfter("dev/kair/app/")

    /**
     * 剥注释，**保留换行**（行号要对得上）。字符串里的双斜杠不能当注释 —— 必须字符级状态机；
     * 块注释按**深度**配平（Kotlin 的块注释可嵌套，正文里出现「斜杠 + 星号」就会意外开一层，
     * 按第一个「星号 + 斜杠」收会把整份文件当注释吞掉）。
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && source.startsWith("//", i) -> {
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && source.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i + 1 < n && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> { depth++; i += 2 }
                            source.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> {
                                if (source[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                }
                c == '"' -> {
                    // 字符串原样保留（注释剥离与字面量无关；`\b` 匹配的是标识符，字面量里一般没有）。
                    out.append(c); i++
                    while (i < n) {
                        if (source[i] == '\\' && i + 1 < n) { out.append(source[i]).append(source[i + 1]); i += 2; continue }
                        if (source[i] == '\n') { out.append('\n'); i++; continue }
                        if (source[i] == '"') { out.append('"'); i++; break }
                        out.append(source[i]); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun coreRoot(): File = requireDir("app/src/main/java/dev/kair/app/core", "core 源码")

    private fun pluginRoot(): File = requireDir("app/src/main/java/dev/kair/app/ui/plugins", "ui/plugins 源码")

    private fun requireDir(path: String, what: String): File {
        val f = File(repo, path)
        assertTrue("$what 不存在（$f）—— 守卫无法运行", f.isDirectory)
        return f
    }

    private fun requireFile(path: String): File {
        val f = File(repo, path)
        assertTrue("$path 不存在 —— 守卫无法运行", f.isFile)
        return f
    }

    private val repo: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），隐式全局守卫无法运行")
    }
}
