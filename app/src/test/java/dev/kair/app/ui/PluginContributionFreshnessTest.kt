package dev.kair.app.ui

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插件贡献的新鲜度（U46）。
 *
 * `core/plugin` 注册表的贡献集合是 Compose 状态（`mutableStateListOf`），组合期读它就会自动订阅；
 * 一旦把导出快照（`panels()` / `actions()` / `commands()` / `settingsSections()` / `settingsPanels()` /
 * `slots()`）`remember` 住，后续重组就拿不到新值 —— 装 / 卸 / 启停插件后入口不刷新。
 * 本守卫扫 `ui/` 下所有 `.kt`，断言不存在「`remember`（含带 key 的 `remember(x)`）包着
 * `plugins.*()` 导出调用」。
 *
 * 口径三条：
 * - 必须扫 [KotlinSourceScanner.stripped]：注释里的同形文字不算命中（U18 的逃逸，
 *   `KotlinSourceScanner` 的 KDoc 记着），与仓库既有守卫统一；
 * - `remember(refresh)` 包磁盘态（`dynamicPlugins.*` / `toolCatalog()`）**不在本守卫范围** ——
 *   「磁盘态用 refresh、贡献直读」正是本批留下的区分，磁盘态刷新是正确形态；
 * - `tools()` / `toolEntries()` 也不在范围：它们背后的 `tools` 是 `CopyOnWriteArrayList`
 *   （`Plugins.kt`），**不是 Compose 状态** —— 组合期直读不产生订阅，新鲜度只能靠
 *   `refresh++`（`PluginsScreen` 的 `remember(refresh) { toolCatalog() }` 是有意形态），
 *   与磁盘态同类。B 评审（2026-11-11）修正 A 交接 §5.2 的理由：排除它不是因为
 *   「经 toolCatalog 聚合」，而是它根本不属于 Compose 态；同时把同为 Compose 态、
 *   A 漏掉的 `slots()` 补进口径。
 */
class PluginContributionFreshnessTest {

    @Test
    fun `no remember wraps a plugin contribution export under ui`() {
        val uiDir = File(repoRoot(), "app/src/main/java/dev/kair/app/ui")
        assertTrue("找不到 ui 目录：$uiDir", uiDir.isDirectory)
        val hits = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.relativeTo(uiDir).path }
            .flatMap { file ->
                val rel = file.relativeTo(uiDir).path.replace(File.separatorChar, '/')
                findRememberWrappedExports(KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8)))
                    .map { line -> "$rel:$line" }
            }
            .toList()
        assertEquals(
            "插件贡献导出必须组合期直读，remember 住快照会在装/卸/启停后变陈旧：" + hits.joinToString("; "),
            emptyList<String>(),
            hits,
        )
    }

    // ---------------------------------------------------------------- 守卫自身的正/反例
    // 防守卫因正则或配平写错而「空转带绿」：同一段扫描逻辑喂合成源码，该命中的必须命中。

    @Test
    fun `the scan catches keyless and keyed remember but not direct reads`() {
        assertEquals(
            listOf(1, 2, 3),
            violations(
                "val a = remember { container.plugins.commands() }\n" +
                    "val b = remember(refresh) { container.plugins.panels() }\n" +
                    "val c = remember(area) { container.plugins.slots(area) }",
            ),
        )
        assertEquals(emptyList<Int>(), violations("val a = container.plugins.commands()"))
        assertEquals(emptyList<Int>(), violations("val s = container.plugins.slots(area)"))
        assertEquals(emptyList<Int>(), violations("var refresh by remember { mutableIntStateOf(0) }"))
        // rememberSaveable / rememberCoroutineScope 不是 remember 调用点，不误报
        assertEquals(emptyList<Int>(), violations("val a = rememberSaveable { plugins.panels() }"))
        assertEquals(emptyList<Int>(), violations("val s = rememberCoroutineScope()"))
    }

    @Test
    fun `a mention inside a comment does not count as a violation`() {
        // 注释里的同形文字必须被 stripped() 剥掉；真实代码直读 → 0 命中
        assertEquals(
            emptyList<Int>(),
            violations(
                """
                // remember { container.plugins.commands() }
                val a = container.plugins.commands()
                """.trimIndent(),
            ),
        )
        // 多行 remember 块：块内任意一行出现导出调用都要命中（PluginsScreen:626 的真实形状）
        assertEquals(
            listOf(1),
            violations(
                """
                val p = remember(panelId) {
                    container.plugins.settingsPanels().firstOrNull { it.second.id == panelId }
                }
                """.trimIndent(),
            ),
        )
    }

    // ---------------------------------------------------------------- 扫描实现

    /** `plugins.<导出名>(` —— 注册表 Compose 态贡献集合的导出快照。 */
    private val exportCall = Regex("""\bplugins\.(panels|actions|commands|settingsSections|settingsPanels|slots)\s*\(""")

    /** 返回 stripped 源码里「remember 块包着插件贡献导出」的命中行号（1 起）。 */
    private fun findRememberWrappedExports(stripped: String): List<Int> {
        val hits = mutableListOf<Int>()
        var i = 0
        while (i < stripped.length) {
            if (!isKeywordAt(stripped, i, REMEMBER)) {
                i++
                continue
            }
            var j = skipSpaces(stripped, i + REMEMBER.length)
            if (j < stripped.length && stripped[j] == '(') {
                val close = matchPair(stripped, j, '(', ')')
                if (close == null) {
                    i += REMEMBER.length
                    continue
                }
                j = skipSpaces(stripped, close + 1)
            }
            if (j < stripped.length && stripped[j] == '{') {
                val end = matchPair(stripped, j, '{', '}')
                if (end != null && exportCall.containsMatchIn(stripped.substring(j, end))) {
                    hits += stripped.substring(0, i).count { it == '\n' } + 1
                }
            }
            // 从关键字之后继续：嵌套的 remember 也要各自检查
            i += REMEMBER.length
        }
        return hits
    }

    private fun violations(source: String): List<Int> =
        findRememberWrappedExports(KotlinSourceScanner.stripped(source))

    private fun isKeywordAt(text: String, at: Int, keyword: String): Boolean {
        if (!text.startsWith(keyword, at)) return false
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + keyword.length)
        return (before == null || !before.isLetterOrDigit() && before != '_') &&
            (after == null || !after.isLetterOrDigit() && after != '_')
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** stripped 里字符串字面量已被抹成空格，配平直接数括号即可。 */
    private fun matchPair(text: String, open: Int, l: Char, r: Char): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                l -> depth++
                r -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/）")
    }

    private companion object {
        const val REMEMBER = "remember"
    }
}
