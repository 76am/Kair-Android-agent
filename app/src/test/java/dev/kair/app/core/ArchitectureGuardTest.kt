package dev.kair.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构守卫：把 `docs/代码规范.md` §2「依赖方向单向向下」与 `AGENTS.md` §2「内核边界」
 * 从文字约定变成「违反即失败」的机械约束。
 *
 * 为什么需要它：分层规则若只写在文档里，后续改动（尤其 AI 会话）会不知不觉越界，直到依赖
 * 关系彻底纠缠才被发现。本测试直接扫描内核源码，越界立刻报错。
 *
 * 已知且被文档承认的例外（见 [allowed]）：内核里确实需要 Android / Compose 类型的两处接缝
 * —— `core/settings` 需要 `Context` / `SharedPreferences` 做持久化（P4-1 / A2 把构造收成
 * `SharedPreferences` 一条缝，JVM 假件才接得上）、插件宿主的「面板贡献点」本身就是 Compose。
 * 除此之外，`core` 不得依赖 `ui` 包，不得引入 Compose 或 Android framework，
 * **也不得反向依赖宿主 `app` 包**（`dev.kair.app.app.*`，第四条规则）—— 分层是单向向下的，
 * 内核引宿主等于把「谁装配谁」翻过来。U30 的 `AgentAssembly` 放在 `core/agent` 而不是留在
 * `app/app`，正是为了让内核能带上装配产物而不必引宿主（第三轮 B 审 P3：这条 KDoc 此前只列了三条）。
 *
 * **U47（2026-11-11）扩了三件事**：
 * 1. 扫描根从「app 内核」扩到 **app 内核 + `plugin-api/src/main/kotlin`** —— 契约源码同样
 *    不许反向依赖宿主 / 引 Compose（plugin-api 是纯 JVM 模块，这条今天成立，守的是回归）；
 * 2. 引用形式从「只认 `import ` 行」扩到 **import / 别名 import / 全限定调用** 三种 ——
 *    全限定写法（`dev.kair.app.ui.Foo()`）此前能绕过检查；
 * 3. 每个源根有**防空扫**断言，`plugin-api` 源根有一条「必须被扫到」的覆盖断言 ——
 *    目录挪走而守卫没跟上时，空转的守卫不能伪装成通过。
 *
 * 剥注释与字符串走 [KotlinSourceScanner.stripped]（app 侧唯一实现，不复制第二份）：
 * 注释里的示例 import 与字符串里的包名不得误判（U18 / U46 的教训同源）。
 */
class ArchitectureGuardTest {

    /**
     * 白名单：文件（相对各自源根的展示路径）→ 允许出现的**精确** import 串。
     * 只放行上文说明的两处接缝；新增条目必须同时更新文档，否则一律视为越界。
     * 全限定引用同样按本表核销：引用串等于白名单条目、或是它的更长链（`foo.Bar.BAZ`）都算放行。
     */
    private val allowed: Map<String, Set<String>> = mapOf(
        "core/settings/SettingsStore.kt" to setOf(
            "android.content.Context",
            "android.content.SharedPreferences",
        ),
        "core/plugin/Plugins.kt" to setOf(
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.mutableStateListOf",
        ),
    )

    // ------------------------------------------------------------------ 四条规则（原有断言原样保留）

    @Test
    fun `core must not import the ui layer`() {
        assertNoViolation("core 不得依赖 ui 层（`dev.kair.app.ui.*`）") {
            it.startsWith("dev.kair.app.ui.")
        }
    }

    @Test
    fun `core must not import compose`() {
        assertNoViolation("core 不得引入 Compose（面板贡献点除外，见白名单）") {
            it.startsWith("androidx.compose.")
        }
    }

    @Test
    fun `core must not import the android framework`() {
        assertNoViolation("core 不得引入 Android framework（持久化接缝除外，见白名单）") {
            it.startsWith("android.") && !it.startsWith("androidx.")
        }
    }

    /**
     * `core` 不得反向依赖宿主包。第二轮 B 审（U30）指出：类注释里写着这条规则、但**当时并没有
     * 对应的用例** —— `AgentAssembly` 搬进内核的理由因此只是「说法」。补上这条之后说法成立。
     *
     * 现状是零违例，所以不需要白名单。真出现「内核必须回指宿主」的那一天，先改
     * `docs/架构与内核边界.md` §2/§3 的内核边界，再来这里登记例外。
     */
    @Test
    fun `core must not import the host app package`() {
        assertNoViolation("core 不得 import 宿主包 `dev.kair.app.app.*`（依赖方向单向向下）") {
            it.startsWith("dev.kair.app.app.")
        }
    }

    // ------------------------------------------------------------------ U47：引用形式的三条用例（合成源码，判定走同一个 [analyze]）

    /** 全限定调用（不写 import）必须被识别 —— 此前它就是逃逸口（任务文档 §1.2）。 */
    @Test
    fun `fully qualified ui calls are caught without an import`() {
        val hits = analyze(source("fun f() { val r = dev.kair.app.ui.Fake.create() }")) {
            it.startsWith("dev.kair.app.ui.")
        }
        assertTrue(
            "全限定调用必须被识别（hits=$hits）—— 只扫 import 行守不住真实引用",
            hits.any { it.form == "qualified" && it.ref.startsWith("dev.kair.app.ui.") },
        )
    }

    /** 别名 import（`import x.y.Z as W`）与普通 import 一致对待。 */
    @Test
    fun `aliased imports are caught like plain imports`() {
        val hits = analyze(source("import dev.kair.app.ui.Fake as Alias")) {
            it.startsWith("dev.kair.app.ui.")
        }
        assertTrue(
            "别名 import 必须被识别（hits=$hits）",
            hits.any { it.form == "import" && it.ref.startsWith("dev.kair.app.ui.") },
        )
    }

    /** 注释里的示例引用不得误判 —— 守卫必须扫的是剥过注释的源码（U46 教训：否则有逃逸口）。 */
    @Test
    fun `example references inside comments are ignored`() {
        val text = """
            // import dev.kair.app.ui.Fake
            /* val r = dev.kair.app.ui.Fake.create() */
            /**
             * 文档里的全限定示例 dev.kair.app.ui.Fake 同样不算引用。
             */
            fun f() = 1
        """.trimIndent()
        val hits = analyze(source(text)) { it.startsWith("dev.kair.app.ui.") }
        assertTrue("注释里的示例引用不得判违规（hits=$hits）—— 说明守卫没剥注释，真实违规会漏", hits.isEmpty())
    }

    // ------------------------------------------------------------------ U47：防空扫与覆盖

    /** 每个配置的源根都必须扫到 `.kt` 文件 —— 目录挪走而守卫没跟上时，空转的守卫不能伪装成通过。 */
    @Test
    fun `every configured source root yields sources`() {
        sourceRoots().forEach { root ->
            val count = root.dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.count()
            assertTrue(
                "源根 $root 一个 .kt 都没扫到 —— 防空扫断言：守卫空转等于没守卫（目录挪了要同步改 sourceRoots）",
                count > 0,
            )
        }
    }

    /** 守卫的覆盖必须真的伸进两个源根：用两棵树上确定存在的文件当锚点。 */
    @Test
    fun `both source roots are actually scanned by the guard`() {
        val relPaths = scanProduction().map { it.relPath }
        assertTrue(
            "app 内核源根没扫到 AgentSession.kt —— core 源根配置失效",
            relPaths.any { it.endsWith("AgentSession.kt") },
        )
        assertTrue(
            "plugin-api 源根没扫到 KairApi.kt —— plugin-api/src/main/kotlin 必须在守卫覆盖内（U47）",
            relPaths.any { it.endsWith("KairApi.kt") },
        )
    }

    // ------------------------------------------------------------------ 判定主体

    /** 一处越界引用：文件、形式（import / qualified）、引用串。 */
    private data class Violation(val file: String, val form: String, val ref: String)

    /** 扫描输入：展示路径 + 原始文本。 */
    private data class SourceFile(val relPath: String, val rawText: String)

    /** 生产扫描根：app 内核 + plugin-api 契约源码（U47 扩展）。 */
    private fun sourceRoots(): List<Root> {
        val repo = repoRoot()
        val core = repo.resolve("app/src/main/java/dev/kair/app/core")
        val pluginApi = repo.resolve("plugin-api/src/main/kotlin")
        return listOf(
            // 白名单键沿用「相对 dev/kair/app」的老口径，core 根的展示路径相对它的父目录
            Root(core, core.parentFile!!),
            Root(pluginApi, pluginApi),
        )
    }

    private data class Root(val dir: File, val relBase: File)

    private fun assertNoViolation(rule: String, forbidden: (String) -> Boolean) {
        val hits = scanProduction().flatMap { analyze(it, forbidden) }
        assertTrue(
            buildString {
                appendLine("违反架构约束：$rule")
                appendLine("详见 docs/代码规范.md §2 与 docs/架构与内核边界.md §3。")
                hits.forEach { (file, form, ref) -> appendLine("  $file ($form): $ref") }
                append("若确属已文档化的例外，请在 ArchitectureGuardTest.allowed 白名单中登记。")
            },
            hits.isEmpty(),
        )
    }

    /** 扫全部生产源根（U47 扩根前先验零违例的要求在交接里记录，违规出现必须停下报告，不许顺手放宽）。 */
    private fun scanProduction(): List<SourceFile> =
        sourceRoots().flatMap { root ->
            root.dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { file ->
                    SourceFile(
                        relPath = file.relativeTo(root.relBase).path.replace(File.separatorChar, '/'),
                        rawText = file.readText(Charsets.UTF_8),
                    )
                }
                .toList()
        }

    /**
     * 单文件判定（纯函数，合成用例与生产扫描共用）：
     * 1. 先剥注释与字符串（[KotlinSourceScanner.stripped]，app 侧唯一实现）；
     * 2. import 行（含别名）按「import 串」匹配；
     * 3. 剩余文本按「全限定链」匹配 —— `\b` 起、链尾到标识符右边界，`androidx` 不会被
     *    `android` 分支误吞（`android` 后必须紧跟 `.`）。
     * 白名单按（文件, 引用串）核销：精确相等，或引用串是白名单条目的更长链。
     */
    private fun analyze(file: SourceFile, forbidden: (String) -> Boolean): List<Violation> {
        val stripped = KotlinSourceScanner.stripped(file.rawText)
        val hits = mutableListOf<Violation>()
        stripped.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("import ")) {
                val ref = trimmed.removePrefix("import ").trim()
                if (forbidden(ref) && !whitelisted(file, ref)) hits += Violation(file.relPath, "import", ref)
            }
        }
        QUALIFIED_REF.findAll(stripped).forEach { match ->
            val ref = match.value
            if (forbidden(ref) && !whitelisted(file, ref)) hits += Violation(file.relPath, "qualified", ref)
        }
        return hits
    }

    private fun whitelisted(file: SourceFile, ref: String): Boolean =
        allowed[file.relPath].orEmpty().any { ref == it || ref.startsWith("$it.") }

    /**
     * 四个禁区族的全限定链（`(?:\w+\.)*\w+` 贪到链尾，右边界是「非单词字符」）：
     * `dev.kair.app.ui` / `dev.kair.app.app` / `androidx.compose` / `android`。
     * 注释与字符串已被 [KotlinSourceScanner.stripped] 抹掉，这里不会误报示例文本。
     */
    private val QUALIFIED_REF = Regex(
        """\b(?:dev\.kair\.app\.ui|dev\.kair\.app\.app|androidx\.compose|android)\.(?:\w+\.)*\w+""",
    )

    /** 合成用例的便利包装：给片段套上 package 行，展示路径固定（不在白名单里）。 */
    private fun source(body: String): SourceFile =
        SourceFile(relPath = "synthetic/Fake.kt", rawText = "package dev.kair.app.core\n\n$body\n")

    /** 定位仓库根（须含 docs/ 与 app/），容忍从模块目录或仓库根启动。 */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val found = File(dir, "docs").isDirectory &&
                File(dir, "app").exists() &&
                File(dir, "plugin-api").exists()
            if (found) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/ 与 plugin-api/），架构守卫无法运行")
    }
}
