package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U74（2026-11-11）整页编辑器结构守卫。判定抽成纯函数 [fullPageViolations]：
 * 接受 **stripped 文本**（调用方负责经 `KotlinSourceScanner.stripped()` 剥注释，M4 变异
 * 验证这一步是承重的），返回违规清单，每条带 `V1` / `V2` / `V3` 前缀 —— 变异读数据此定位
 * 「红在哪条判据」。
 *
 * 三条判据对应任务文档 §4：
 * - **V1**：`PluginSettingsFields(` 只落在折叠分支内（`if (!editing)` 闸与对应 `else` 之间）
 *   —— 整页编辑态不得再渲染设置区；
 * - **V2**：`FileEditorBody(` 之后出现 `.weight(1f)`（占满剩余高度），且函数体不再有
 *   `.height(280.dp)`（固定高度就是「半屏嵌入」的旧形状）；
 * - **V3**：`verticalScroll` 只能出现在折叠分支内（U95 收紧口径）。原判据是「全函数 0 次」，
 *   但那会连**折叠态自滚**一起禁掉 —— 折叠态内容一屏放不下，不能滚就是被裁（真机反馈）。
 *   要禁的是编辑态：那里 `weight(1f)` 与无限高容器不共存，还会双层滚动。
 * - **V5**（B 审补）：项目 chips 与 `FileEditorBody` 只能落在**展开分支**内 ——
 *   原 `MemoryPanelEditorGateGuardTest` 只钉了「在闸之后」，把它们挪进折叠分支仍会带绿。
 *
 * **定位口径**（B 审改）：折叠 / 展开分界由 [matchingElse] 按花括号深度找配对 else，
 * 不再取「第一处 `} else {`」—— 折叠分支内有嵌套 else 时，首匹配会指错。
 *
 * **口径声明（必须如实转述，不许拔高）**：V1–V3 是**结构守卫**，不是 Compose 行为证明。
 * 本仓没有 `createComposeRule`，「编辑器真的占满屏幕」在 JVM 单测里没有观测面，
 * 只能真机目视（任务文档 §4.2 的 V5–V10）。别把这几条写成「已验证整页」。
 */
internal fun fullPageViolations(body: String): List<String> {
    val lines = body.lines()
    val violations = mutableListOf<String>()
    val gate = lines.indexOfFirst { it.contains("if (!editing)") }
    if (gate < 0) {
        violations += "V1: 找不到 if (!editing) 折叠闸 —— 折叠 / 展开模型被拆了"
        return violations
    }
    val elseOpen = matchingElse(lines, gate)

    // ---- V1：设置区只能在折叠分支（闸与 else 之间）----
    val settings = lines.withIndex().filter { it.value.contains("PluginSettingsFields(") }
    if (settings.isEmpty()) {
        violations += "V1: 函数体里没有 PluginSettingsFields —— 设置区被删（空项目态也要显示设置区）"
    } else if (elseOpen == null) {
        violations += "V1: 找不到折叠闸对应的 else —— 分支结构变了，守卫无法定位折叠分支"
    } else {
        settings.forEach { (index, _) ->
            if (index !in gate + 1 until elseOpen) {
                violations += "V1: PluginSettingsFields 落在折叠分支之外（行 ${index + 1}，闸 $gate，else $elseOpen）" +
                    " —— 编辑态同屏渲染设置区 = 回到半屏嵌入的旧形状"
            }
        }
    }

    // ---- V2：编辑器占满（weight(1f)），不再固定高度 ----
    val editor = lines.indexOfFirst { it.contains("FileEditorBody(") }
    val weight = lines.indexOfFirst { it.contains(".weight(1f)") }
    if (editor < 0) {
        violations += "V2: 找不到 FileEditorBody —— 编辑器没了"
    }
    if (weight < 0) {
        violations += "V2: 函数体没有 .weight(1f) —— 编辑器没占满剩余高度（仍是固定高度的旧形状？）"
    } else if (editor >= 0 && weight < editor) {
        violations += "V2: .weight(1f)（行 ${weight + 1}）出现在 FileEditorBody（行 ${editor + 1}）之前 —— 不是给编辑器的"
    }
    if (lines.any { it.contains(".height(280.dp)") }) {
        violations += "V2: 函数体仍有 .height(280.dp) —— 固定高度就是半屏嵌入的旧形状"
    }

    // ---- V5：chips 与编辑器只在展开分支（B 审补，补 GateGuard 用例 1 的语义缺口）----
    if (elseOpen != null) {
        val chips = lines.indexOfFirst { it.contains("FilterChip") }
        if (chips < 0 || chips < elseOpen) {
            violations += "V5: 项目切换 FilterChip 不在展开分支内（行 ${chips + 1}，else ${elseOpen + 1}）" +
                " —— 折叠态不该露出跨项目入口"
        }
        if (editor >= 0 && editor < elseOpen) {
            violations += "V5: FileEditorBody 在折叠分支内（行 ${editor + 1}，else ${elseOpen + 1}）" +
                " —— GateGuard 的 `> gate` 守不住这一条"
        }
    }

    // ---- V3：滚动容器不得出现在展开分支 / 折叠闸之前 ----
    // 口径（U95 收紧）：原判据是「函数体内 verticalScroll 出现 0 次」，把**折叠态自滚**
    // 也一并禁掉了 —— 而折叠态的内容（hint + 按钮 + 4~5 个带说明的字段）一屏放不下，
    // 不能滚就是被裁掉（真机 U95 反馈）。真正要禁的是「编辑态出现滚动容器」：那里
    // FileEditorBody 靠 weight(1f) 吃饭，无限高的父级里 weight 失效且会双层滚动。
    // 所以判据改成按位置判——闸之前（根 Column）与展开分支内仍一律违规。
    val scrolls = lines.withIndex().filter { it.value.contains(Regex("""verticalScroll\b""")) }
    scrolls.forEach { (index, _) ->
        val inCollapsed = elseOpen != null && index in gate + 1 until elseOpen
        if (!inCollapsed) {
            violations += "V3: verticalScroll 出现在折叠分支之外（行 ${index + 1}，闸 $gate，else $elseOpen）" +
                " —— 编辑态的 weight(1f) 与无限高容器不共存，还会双层滚动"
        }
    }
    return violations
}

/**
 * 按花括号深度找与 `if (!editing)` **配对**的 `} else {`（U74 B 审 P1）。
 *
 * 旧口径取「第一处 `} else {`」：折叠分支里只要出现内层 else（`if (...) { } else { }`），
 * 定位就指到内层，`PluginSettingsFields` 会被误判为落在展开分支 —— 误红会诱使后人放宽守卫。
 * 从闸行起算相对深度：闸行开一个 `{`（depth=1），只有 depth 回到 1 时出现的
 * `} else {` 才是外层那一处；内层的出现在 depth≥2，跳过。
 *
 * 入参是 [KotlinSourceScanner.stripped] 文本：注释与字符串已被抹成空格，
 * 其中的花括号不会污染计数。
 */
private fun matchingElse(lines: List<String>, gate: Int): Int? {
    var depth = 0
    for (i in gate until lines.size) {
        val line = lines[i]
        if (i > gate && depth == 1 && line.trim().startsWith("} else {")) return i
        depth += line.count { it == '{' } - line.count { it == '}' }
    }
    return null
}

/**
 * V1–V3 / V5 的真实源码判据 + V4 反向自证（违规只出现在注释里时判定必须为空，
 * 证明守卫扫的是剥过注释的源码 —— U46 的教训：形状守卫不扫剥过的源码就有逃逸口）。
 */
class MemoryPanelFullPageGuardTest {

    private val source = repoFile("app/src/main/java/dev/kair/app/ui/plugins/builtin/MemoryPanel.kt")
        .readText(Charsets.UTF_8)

    /** 真实 MemoryPanel 函数体（stripped）。 */
    private val body: String by lazy { strippedBody(source) }

    @Test
    fun `v1 settings fields render only inside the collapsed branch`() {
        val v = fullPageViolations(body).filter { it.startsWith("V1") }
        assertTrue("V1 不成立：$v", v.isEmpty())
    }

    @Test
    fun `v2 editor fills the remaining height without a fixed one`() {
        val v = fullPageViolations(body).filter { it.startsWith("V2") }
        assertTrue("V2 不成立：$v", v.isEmpty())
    }

    @Test
    fun `v3 memory panel body has no vertical scroll`() {
        val v = fullPageViolations(body).filter { it.startsWith("V3") }
        assertTrue("V3 不成立：$v", v.isEmpty())
    }

    /**
     * U95：V3 放宽到「折叠分支内允许自滚」之后，必须钉住它没有被放宽成「随便滚」。
     * 展开分支里出现滚动容器仍要判违规 —— 那里是 `weight(1f)` 的编辑器，这是 V3 存在的理由。
     */
    @Test
    fun `v3 scroll inside the expanded branch is still a violation`() {
        val sample = """
            package dev.kair.app.ui.plugins.builtin

            fun MemoryPanel() {
                Column {
                    if (!editing) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PluginSettingsFields()
                        }
                    } else {
                        Row { FilterChip() }
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            FileEditorBody(modifier = Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            }
        """.trimIndent()
        val v = fullPageViolations(strippedBody(sample))
        assertTrue(
            "展开分支里的滚动容器必须判违规（violations=$v）—— 否则 V3 等于被删",
            v.any { it.startsWith("V3") },
        )
    }

    /**
     * U95：折叠闸之前的**根 Column** 加滚动同样要拦。这是 V3 最初要防的形状
     * （整个面板被包一层滚动，编辑态 weight 随之失效）。
     */
    @Test
    fun `v3 scroll on the root column is still a violation`() {
        val sample = """
            package dev.kair.app.ui.plugins.builtin

            fun MemoryPanel() {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!editing) {
                        PluginSettingsFields()
                    } else {
                        Row { FilterChip() }
                        FileEditorBody(modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        """.trimIndent()
        val v = fullPageViolations(strippedBody(sample))
        assertTrue(
            "根 Column 上的滚动必须判违规（violations=$v）",
            v.any { it.startsWith("V3") },
        )
    }

    @Test
    fun `v4 violations inside comments do not count`() {
        val sample = """
            package dev.kair.app.ui.plugins.builtin

            fun MemoryPanel() {
                // 旧形状只留在注释里就不算违规：.height(280.dp) 与 verticalScroll(rememberScrollState())
                Column {
                    if (!editing) {
                        PluginSettingsFields()
                    } else {
                        Row { FilterChip() }
                        FileEditorBody(modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        """.trimIndent()
        val v = fullPageViolations(strippedBody(sample))
        assertTrue(
            "注释里的旧形状不得判违规（violations=$v）—— 说明守卫没扫剥过注释的源码，有逃逸口",
            v.isEmpty(),
        )
    }

    /**
     * B 审（U74）P1：折叠分支里出现**嵌套 else** 时，守卫仍要定位到外层 else。
     * 旧实现用「第一处 `} else {`」定位，会指到内层 else，把折叠分支内的
     * `PluginSettingsFields` 误判为落在展开分支（误红 → 诱使后人放宽守卫）。
     */
    @Test
    fun `v5 nested else inside the collapsed branch does not confuse gate location`() {
        val sample = """
            package dev.kair.app.ui.plugins.builtin

            fun MemoryPanel() {
                if (!editing) {
                    if (projects.isEmpty()) {
                        Text("none")
                    } else {
                        OutlinedButton(onClick = { editing = true }) { Text("open") }
                    }
                    PluginSettingsFields()
                } else {
                    Row { FilterChip() }
                    FileEditorBody(modifier = Modifier.fillMaxWidth().weight(1f))
                }
            }
        """.trimIndent()
        val v = fullPageViolations(strippedBody(sample))
        assertTrue("嵌套 else 不得让折叠分支定位错位（violations=$v）", v.isEmpty())
    }

    /**
     * B 审（U74）P1：原 GateGuard 用例 1 只钉「chips / 编辑器在闸之后」，
     * 把它们挪进折叠分支仍会带绿。这里把「只在展开分支」变成有牙的判据。
     */
    @Test
    fun `v5 chips and editor must stay in the expanded branch`() {
        val sample = """
            package dev.kair.app.ui.plugins.builtin

            fun MemoryPanel() {
                if (!editing) {
                    PluginSettingsFields()
                    FileEditorBody(modifier = Modifier.fillMaxWidth().weight(1f))
                } else {
                    Row { FilterChip() }
                }
            }
        """.trimIndent()
        val v = fullPageViolations(strippedBody(sample))
        assertTrue(
            "编辑器落在折叠分支必须被判违规（violations=$v）—— GateGuard 的 `> gate` 守不住这一点",
            v.any { it.startsWith("V5") },
        )
    }

    /** 找出 [raw] 里名为 MemoryPanel 的函数体，并按 stripped 文本切出（口径同 GateGuardTest）。 */
    private fun strippedBody(raw: String): String {
        val scanned = KotlinSourceScanner.scan(raw).singleOrNull { it.name == "MemoryPanel" }
            ?: error("找不到 MemoryPanel 函数体 —— 守卫扫描落空，不等于通过")
        return KotlinSourceScanner.stripped(raw).lines()
            .subList(scanned.startLine - 1, scanned.endLine)
            .joinToString("\n")
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
