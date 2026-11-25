package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 面板内返回键的边界（U53）与返回接管的**唯一入口**（U53 返工，2026-11-11）。
 *
 * 边界是「面板打开时的根」（`panelRoot`），不是 `FsPolicy.cap` —— 后者是 Kair 根，
 * 拿它判「到根了没有」会让返回键一路翻到 `/data`。这里钉 [backTarget] 的四个边界；
 * 上机按返回的五条路径只能目视。
 *
 * 接线守卫在 B 审返工（2026-11-12）后换了形状。原先那版钉的是文件名名单 +
 * `BackHandler` 总数正好 3，反向验证证明它挡不住 U74 那类「压根没接」：新增一个二级态面板
 * （不写入口、也不手写 BackHandler）全绿，入口里的 `enabled` 改成恒 false 也全绿，
 * 因为它只数条数、不看内容。名单式守卫漏新增是必然，不是偶然。
 *
 * 现在四条判据都是**结构级、不依赖文件名名单**（全 `app/src/main` 递归扫 `stripped` 源码）：
 *
 * 1. 除 `PanelBack.kt` 外不许出现 `BackHandler(` —— 拦「绕过入口手写」；
 * 2. 凡提供内置返回（`onBack = {` 的二级页）的源码，入口调用数不得少于内置返回页数
 *    —— 拦「只写内置返回、系统返回漏接」（正是 U74 的形态）。**新增文件默认被扫到**，
 *    只有显式登记在 [KNOWN_UNWIRED] 的已知欠账可豁免；
 * 3. 三个入口**各自的函数体**里 `BackHandler(enabled = ...)` 必须引用自己的状态
 *    —— 拦「入口写成空壳 / 恒 false」；
 * 4. 每个 `rememberPanelBackSelection` 调用点的 lambda 必须引用参数以外的项
 *    —— 拦「退化成裸 id 判据」，把「吃解析后的项」钉在生产真在用的调用点上。
 *
 * 仍然守不住的（如实写进交接，别当成已解决）：同一文件里多个二级页与入口的**状态级配对**
 * 没钉；静态扫描也判不出某个布尔态在语义上算不算二级页。真机按返回仍是最后一关。
 */
class PanelBackTest {

    @Test
    fun `at the panel root the back key leaves the panel`() {
        assertNull(backTarget("/r/ws", "/r/ws"))
    }

    @Test
    fun `inside a subdirectory the back key goes up one level`() {
        assertEquals("/r/ws/src", backTarget("/r/ws/src/lib", "/r/ws"))
    }

    @Test
    fun `a sibling sharing the name prefix is outside the panel root`() {
        assertNull(backTarget("/r/ws2", "/r/ws"))
    }

    @Test
    fun `navigated above the panel root the back key leaves the panel`() {
        assertNull(backTarget("/r", "/r/ws"))
    }

    // ------------------------------------------------------------ 接线守卫（全主源码递归，不列举文件）
    // 扫 stripped（剥注释后的源码）：注释里提 BackHandler / onBack 不算接线，
    // 与 BackgroundTaskDetachGuardTest 等既有守卫同口径（KotlinSourceScanner 的 KDoc 记着 U18 逃逸）。

    @Test
    fun `no source outside the entry file hand-writes BackHandler`() {
        val offenders = sourceFiles()
            .filter { it.name != PANEL_BACK_FILE }
            .filter { KotlinSourceScanner.stripped(it.readText(Charsets.UTF_8)).contains("BackHandler(") }
            .map { it.name }
        assertTrue(
            "状态声明与返回接管分处两地时，只写一半不会有任何信号（U74 整页编辑器就是这么漏接的）。" +
                "除 $PANEL_BACK_FILE 外不许手写 BackHandler，改用 rememberPanelBackPath / " +
                "rememberPanelBackOpen / rememberPanelBackSelection；形状不够就在入口文件里加新入口。" +
                "违规文件：${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every built-in back page is covered by a panel back entry`() {
        val violations = sourceFiles()
            .filter { it.name != PANEL_BACK_FILE }
            .mapNotNull { file ->
                val src = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
                val backPages = Regex("""onBack\s*=\s*\{""").findAll(src).count()
                if (backPages == 0) return@mapNotNull null
                val entries = Regex("""rememberPanelBack\w+\s*[({]""").findAll(src).count()
                if (entries >= backPages || file.name in KNOWN_UNWIRED) return@mapNotNull null
                "${file.name}: 内置返回页 $backPages / 入口 $entries —— ${KNOWN_UNWIRED[file.name] ?: "未登记的漏接"}"
            }
        assertTrue(
            "U74 的形态是「内置返回按钮正常、系统返回直接退掉整个面板」：每个 onBack 二级页都得" +
                "有一个统一入口兜住系统返回。漏接的文件：${violations.joinToString("; ")}。" +
                "确实有历史欠账时，在 KNOWN_UNWIRED 里显式登记原因并同步看板，不要静默放过。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `each entry wires its own state into BackHandler`() {
        val path = "app/src/main/java/dev/kair/app/ui/plugins/builtin/$PANEL_BACK_FILE"
        val raw = repoFile(path).readText(Charsets.UTF_8)
        val stripped = KotlinSourceScanner.stripped(raw)
        val lines = stripped.lines()
        val functions = KotlinSourceScanner.scan(raw).associateBy { it.name }
        val enabledByEntry = mapOf(
            "rememberPanelBackPath" to "backTarget(dir.value, panelRoot) != null",
            "rememberPanelBackOpen" to "open.value",
            "rememberPanelBackSelection" to "enabledWhen(selected.value)",
        )
        enabledByEntry.forEach { (name, enabled) ->
            val fn = functions[name]
                ?: error("$PANEL_BACK_FILE 里找不到 $name —— 守卫扫描落空，不等于通过")
            val body = lines.subList(fn.startLine - 1, fn.endLine).joinToString("\n")
            assertTrue(
                "$name 的 BackHandler 必须引用自己的状态（enabled = $enabled），" +
                    "写成常量 / 空壳都不会有任何信号。函数体：\n$body",
                body.contains("BackHandler(enabled = $enabled)"),
            )
            assertEquals("$name 函数体里应恰好一条 BackHandler：\n$body", 1, Regex("BackHandler\\(").findAll(body).count())
        }
    }

    @Test
    fun `selection call sites resolve the picked item instead of testing the raw id`() {
        // 记录被 prune / remove 回收后 id 还在、界面已退回列表：用 id 判会吞掉返回键。
        // 契约不能只钉在入口实现上 —— 真正决定行为的是调用点传进去的 lambda。
        val callSites = sourceFiles()
            .filter { it.name != PANEL_BACK_FILE }
            .flatMap { file ->
                val src = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
                Regex("""rememberPanelBackSelection\s*\{""").findAll(src).map { match ->
                    file.name to lambdaBody(src, match.range.last)
                }.toList()
            }
        assertTrue(
            "一个 rememberPanelBackSelection 调用点都没扫到 —— 扫描落空不等于通过",
            callSites.isNotEmpty(),
        )
        val bare = callSites.filterNot { (_, lambda) -> resolvesBeyondId(lambda) }.map { it.first }
        assertTrue(
            "这些调用点把选中判据退化成裸 id（如 { id -> id != null }）：记录被回收后 id 还在、" +
                "界面已退回列表，返回键会被吞掉。lambda 必须引用解析后的项 / 集合。" +
                "违规文件：${bare.joinToString()}",
            bare.isEmpty(),
        )
    }

    @Test
    fun `the selection entry passes the resolved value to BackHandler`() {
        val src = strippedSource("app/src/main/java/dev/kair/app/ui/plugins/builtin/$PANEL_BACK_FILE")
        assertTrue(
            "rememberPanelBackSelection 必须把 enabledWhen(选中值) 交给 BackHandler —— 判据是解析后的项，不是原始 id",
            src.contains("BackHandler(enabled = enabledWhen(selected.value))"),
        )
    }

    // ---------------------------------------------------------------- 辅助

    /** 全主源码的 kotlin 文件（目录递归，不是文件名名单）。 */
    private fun sourceFiles(): List<File> {
        val root = repoFile("app/src/main")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().sortedBy { it.path }
        check(files.isNotEmpty()) { "主源码扫描落空：${root.path}" }
        return files
    }

    private fun strippedSource(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    /** 从 `{` 下标起按花括号配平截出一个完整 lambda（含嵌套 lambda）。 */
    private fun lambdaBody(src: String, open: Int): String {
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return src.substring(open)
    }

    /** lambda 里是否引用了参数以外的标识符（解析后的项 / 集合），而不是只判 `id != null`。 */
    private fun resolvesBeyondId(lambda: String): Boolean {
        val param = Regex("""^\s*\{\s*(\w+)\s*->""").find(lambda)?.groupValues?.get(1)
        val body = lambda.substringAfter("->", lambda)
        val ignorable = setOf(
            "null", "true", "false", "it", "let", "run", "also", "any", "none",
            "firstOrNull", "contains", "isEmpty", "isNotEmpty",
        ) + listOfNotNull(param)
        return Regex("""\b[A-Za-z_]\w*\b""").findAll(body)
            .map { it.value }
            .any { it.length >= 3 && it !in ignorable }
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }

    private companion object {
        const val PANEL_BACK_FILE = "PanelBack.kt"

        /**
         * 已知的「有内置返回、没接系统返回」欠账，**显式登记而不是静默放过**。
         *
         * 与旧版 `PANEL_BACK_ENTRIES` 的差别是方向：旧版只扫名单里那 4 个文件，新增文件
         * 不在名单就完全不检查；现在全目录都扫，只有这里登记的历史欠账可豁免。
         * 新增漏接既没有入口、也不在表里 → 守卫红。
         *
         * `CachePanel.kt`：缓存分段详情视图（`onBack = { selected = null }`）与 U74 同型，
         * 系统返回未接管。B 审返工时由新判据扫出，本批只做「统一入口」、不顺手改它的
         * 产品行为，挂账见 `docs/进度看板.md`。
         */
        val KNOWN_UNWIRED = mapOf(
            "CachePanel.kt" to "缓存分段详情视图只有内置返回，系统返回未接管（U74 同型；本批扩面之外的独立挂账）",
        )
    }
}
