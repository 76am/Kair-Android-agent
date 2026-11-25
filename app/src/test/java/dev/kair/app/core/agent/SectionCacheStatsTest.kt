package dev.kair.app.core.agent

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 段落缓存的**读数**（看板 U30，U19 第 ② 笔留下的「命中率没有观测面」那条账）。
 *
 * 这里守两面：
 * - 数得对：命中 / 未命中两类归因的**分派**，以及「hits + misses 等于遍历过的段落数」这条
 *   恒等式（分派分支写错时最容易变成少数或多算一份）；
 * - 数不改字节：读数与诊断同一条旁路，记与不记都不该动装配输出 —— 破坏缓存稳定前缀的
 *   观测面无效（`AGENTS.md` §3 / §10.6）。
 *
 * 与 `PromptCacheScopeTest` 的分工：那边数「贡献点被调用几次」证明失效粒度对，
 * 这边数「装配报告说什么」证明**观测面说的是同一件事**。两个口径都要有：只守调用次数的话，
 * 面板报什么没人管（面板与真实行为漂开是这类观测面最常见的失效方式）。
 *
 * 用例各用自己的**贡献点 id 与工作区路径**，互不共用缓存槽位。
 */
class SectionCacheStatsTest {

    /** 产出稳定正文（不随调用变化 —— 「读到旧正文」在这里是**期望行为**，不是 bug）。 */
    private class FixedContributor(
        override val id: String,
        private val body: String = "body",
    ) : SystemPromptContributor {
        override val order = 0
        var calls = 0
            private set

        override fun section(ctx: SystemPromptContext): String {
            calls++
            return body
        }
    }

    /** 合法产出空白的贡献点：契约允许（插件在本项目下没有可注入的内容）。 */
    private class BlankContributor(override val id: String) : SystemPromptContributor {
        override val order = 0
        override fun section(ctx: SystemPromptContext): String = ""
    }

    private fun registryOf(vararg contributors: SystemPromptContributor) = PluginRegistry().install(object : Plugin {
        override val id = "test.prompt.section-stats"
        override val name = "test"
        override val description = ""
        override fun activate(ctx: PluginContext) {
            contributors.forEach { ctx.systemPrompt(it) }
        }
    }).systemPromptContributors()

    private fun context(workspace: String, cwd: String = "/shared-cwd") =
        SystemPromptContext(cwd = cwd, tools = emptyList(), addendum = "", workspace = workspace)

    @Test
    fun `a rebuilt prompt with unchanged inputs counts every section as a hit`() {
        val contributors = registryOf(FixedContributor("test.stats.hit"))
        SystemPromptBuilder.invalidate()

        val first = SystemPromptBuilder.buildDetailed(context("/stats/hit"), contributors)
        val second = SystemPromptBuilder.buildDetailed(context("/stats/hit"), contributors)

        assertEquals("首次装配没有可复用的正文", 0, first.sectionCache.hits)
        assertEquals(1, first.sectionCache.missesNothingToReuse)
        assertEquals("输入逐位相同就必须报命中", 1, second.sectionCache.hits)
        assertEquals(0, second.sectionCache.misses)
        assertEquals("命中就不该重算", 1, second.sectionCache.sections)
    }

    @Test
    fun `a scoped invalidation shows up as nothing to reuse`() {
        // 工具执行后 / 压缩后走的就是这条粒度。归因必须是 NOTHING_TO_REUSE 而不是
        // INPUT_CHANGED —— 反过来就是「面板把失效说成输入变了」，方案 B 的靶子会被读没。
        val contributors = registryOf(FixedContributor("test.stats.scoped"))
        SystemPromptBuilder.invalidate()
        SystemPromptBuilder.buildDetailed(context("/stats/scoped"), contributors)

        SystemPromptBuilder.invalidate("/stats/scoped")
        val after = SystemPromptBuilder.buildDetailed(context("/stats/scoped"), contributors)

        assertEquals(0, after.sectionCache.hits)
        assertEquals("被失效摘走的桶，归因是无缓存可复用", 1, after.sectionCache.missesNothingToReuse)
        assertEquals(0, after.sectionCache.missesInputChanged)
    }

    @Test
    fun `changed inputs show up as input changed and not as invalidation`() {
        // 只换 cwd 这一维（进槽位谓词、不进桶键）：桶还在、槽位也在，但没有一条匹配。
        val contributors = registryOf(FixedContributor("test.stats.changed"))
        SystemPromptBuilder.invalidate()
        SystemPromptBuilder.buildDetailed(context("/stats/changed", cwd = "/a"), contributors)

        val after = SystemPromptBuilder.buildDetailed(context("/stats/changed", cwd = "/b"), contributors)

        assertEquals(0, after.sectionCache.hits)
        assertEquals("输入变了必须归到 INPUT_CHANGED", 1, after.sectionCache.missesInputChanged)
        assertEquals(0, after.sectionCache.missesNothingToReuse)
    }

    @Test
    fun `another workspace invalidation costs this workspace no hits`() {
        // U19 第 ② 笔的效果读数：改回全表清时，这条会报出 miss 而不是 hit。
        // 行为侧 `PromptCacheScopeTest` 已经守着调用次数了，这条守的是**面板会不会撒谎**。
        val contributors = registryOf(FixedContributor("test.stats.cross"))
        SystemPromptBuilder.invalidate()
        SystemPromptBuilder.buildDetailed(context("/stats/mine"), contributors)
        SystemPromptBuilder.buildDetailed(context("/stats/theirs"), contributors)

        SystemPromptBuilder.invalidate("/stats/theirs")
        val mine = SystemPromptBuilder.buildDetailed(context("/stats/mine"), contributors)

        assertEquals("别的工作区失效，我的工作区当回合必须全命中", 1, mine.sectionCache.hits)
        assertEquals(0, mine.sectionCache.misses)
    }

    @Test
    fun `hits and misses account for every section examined`() {
        // 分派分支写错最典型的形态是「少数一段」或「双计」，恒等式比逐类断言更早抓到。
        val ok = FixedContributor("test.stats.sum.ok")
        val blank = BlankContributor("test.stats.sum.blank")
        val contributors = registryOf(ok, blank)
        SystemPromptBuilder.invalidate()

        val cold = SystemPromptBuilder.buildDetailed(context("/stats/sum"), contributors)
        assertEquals("分母是遍历过的贡献点，含合法产出空白的段落", 2, cold.sectionCache.sections)
        assertEquals(cold.sectionCache.sections, cold.sectionCache.hits + cold.sectionCache.misses)
        assertEquals("空白正文也要被缓存，否则它每次都重算", 2, cold.sectionCache.missesNothingToReuse)

        val warm = SystemPromptBuilder.buildDetailed(context("/stats/sum"), contributors)
        assertEquals(warm.sectionCache.sections, warm.sectionCache.hits + warm.sectionCache.misses)
        assertEquals("两段的输入都没变", 2, warm.sectionCache.hits)
    }

    @Test
    fun `recording the readings does not change the assembled text`() {
        // 缓存稳定前缀（AGENTS §3）：读数是旁路，装配两次必须逐字节相同。
        val contributors = registryOf(FixedContributor("test.stats.bytes", body = "identity"), FixedContributor("test.stats.bytes.two", body = "tools"))
        SystemPromptBuilder.invalidate()

        val first = SystemPromptBuilder.buildDetailed(context("/stats/bytes"), contributors)
        val second = SystemPromptBuilder.buildDetailed(context("/stats/bytes"), contributors)

        assertEquals("记读数不得动正文", first.text, second.text)
        assertEquals("identity\n\ntools", second.text)
    }

    @Test
    fun `the readings are wired from the assembly to the panel`() {
        // 纯函数用例守不住「面板到底读没读它」（`AGENTS.md` §10.4 的口径），所以按仓里既有
        // 做法钉源码文本。扫**剥注释后**的正文（U18 复核踩过：写进注释的调用点会骗过守卫）。
        // 判据分层：这里断言的都是**标识符形态**（`R.string.xxx` 是代码不是字面量），
        // 所以扫 stripped() 是对的；若哪天要断言字符串字面量内容，必须回原文（U24 踩过）。
        val executor = strippedRepo("app/src/main/java/dev/kair/app/core/agent/TurnExecutor.kt")
        assertTrue(
            "执行器必须把**它自己那次装配**的读数交给观测器 —— 换成「观测器存最近一份」就是第一轮 B 审 P1",
            executor.contains("record(host.sessionId, settings.model, assembly, built.sectionCache)"),
        )
        assertTrue(executor.contains("val built = inputs.assembly"))

        val assemblySource = strippedRepo("app/src/main/java/dev/kair/app/app/SessionAssembly.kt")
        assertTrue(
            "诊断仍然只在 agentSettings 里记一次",
            assemblySource.contains("diagnostics.recordAssembly(it, built.issues)"),
        )
        // 反向断言：**两个文件都扫**。宿主侧与新的装配对象都不许再有第二条把读数交给观测器的路 ——
        // 它现在是 P1 唯一的复活路径：有人「顺手在这里也记一份」，面板就会重新显示与那一发请求相反的数。
        val container = strippedRepo("app/src/main/java/dev/kair/app/app/AppContainer.kt")
        assertTrue(
            "读数只能沿装配产物走，宿主不得另开 recordSectionCache 那条路",
            !container.contains("recordSectionCache"),
        )
        assertTrue(
            "装配已搬进 SessionAssembly，容器不得再自己记诊断",
            !container.contains("recordAssembly"),
        )
        assertTrue(
            "读数只能沿装配产物走，SessionAssembly 不得另开 recordSectionCache 那条路",
            !assemblySource.contains("recordSectionCache"),
        )

        val factory = strippedRepo("app/src/main/java/dev/kair/app/app/AgentSettingsFactory.kt")
        assertTrue(
            "装配产物必须把读数带过宿主边界（`AgentAssembly.sectionCache`），否则执行器拿不到",
            factory.contains("AgentAssembly(settings, assembly.issues, assembly.sectionCache, tools)"),
        )

        // 子智能体不进 SessionRegistry → 没人调它的 remove，所以它**必须**拿一份临时实例；
        // 共用宿主那一份就是「跑过的子智能体数 × 一整份报告（含 segments 快照）」留在进程里。
        // 这条只能按文本守（行为侧没有「子智能体被回收」这条路可测），与诊断的临时实例同形。
        //
        // U31 ③-b：两处构造搬进 SessionLifecycle.kt（编排段），扫描目标跟着搬、断言一字不动 ——
        // 这是「改目标」不是「放宽」。宿主那份经 spec 传给 agentSession，子智能体那份是临时实例，
        // 两者仍必须写成不同形态。
        val lifecycle = strippedRepo("app/src/main/java/dev/kair/app/app/SessionLifecycle.kt")
        val subagentInspector = Regex("cacheInspector = CacheInspector\\(\\),")
        assertTrue(
            "子智能体用的是临时 CacheInspector —— 换成共享那份，报告就再也没有人回收（第一轮 B 审 P2）",
            subagentInspector.containsMatchIn(lifecycle),
        )
        assertTrue(
            "宿主自己的实例仍要传给主会话（两处不能写成一样）",
            lifecycle.contains("cacheInspector = cacheInspector,"),
        )

        // 报告正文（命中数 / 归因 / 趋势 / 段落缓存）已抽到 CacheReportBody.kt ——
        // U32 加会话选择器后 CachePanel 函数体 203 行，撞 MethodSizeGuardTest 的 200 阈值，
        // 正确做法是拆（守卫原文：“只显其一就读不出 miss 的大头落在哪”），不是把文件换回来。
        val panel = strippedRepo("app/src/main/java/dev/kair/app/ui/plugins/builtin/CacheReportBody.kt")
        assertTrue(
            "面板要显示命中数与归因两条（只显其一就读不出 miss 的大头落在哪）",
            panel.contains("R.string.cache_section_stats") && panel.contains("R.string.cache_section_miss"),
        )

        val assembly = strippedRepo("app/src/main/java/dev/kair/app/core/agent/SystemPrompt.kt")
        assertTrue(
            "装配把读数带出 PromptAssembly（不新增常驻计数器）；丢了这条就是面板读一个恒空的字段",
            assembly.contains("SectionCacheStats(sections, hits, nothingToReuse, inputChanged)"),
        )

        val cache = strippedRepo("app/src/main/java/dev/kair/app/core/agent/PromptSectionCache.kt")
        assertTrue(
            "两类归因都得有各自的出口，把它们并成一类就等于没有归因",
            cache.contains("SectionCacheMiss.NOTHING_TO_REUSE") && cache.contains("SectionCacheMiss.INPUT_CHANGED"),
        )
    }

    @Test
    fun `only the executor writes readings into the inspector`() {
        // 第一轮的反向断言只扫 `AppContainer.kt` 一个文件（第二轮 B 审 P2 抓的覆盖面缺口）：
        // 换成在 `AgentSession` 或别处直接调 `record(...)`，行为侧与那条反向断言都不会红。
        // 范围因此扩到**整个 `app/src/main/java`**（不是「全仓」—— 判定口径得跟代码走的目录一致）：
        // ① `cacheInspector.record(` 的调用点必须**恰好一处**，且就在 `TurnExecutor.kt`
        //    （P1 的写入路径唯一）。这里**不 distinct** —— 第三轮 B 审 P2 指出 distinct 会把
        //    「同一个文件里的第二处调用」折叠掉，而它同样是第二条能改写当前报告的路径；
        // ② `app/src/main/java` 下不得再出现 `recordSectionCache` 这个名字 —— 它是被删掉的那条旧路，
        //    有人「顺手在这里也记一份」就会把它复活。
        //
        // **这条守卫挡得住什么、挡不住什么**（第四轮增量复核定稿，别把注释写成断言的超集）：
        // 挡得住 ① 别处新增 `cacheInspector.record(...)`；② 同一文件里的第二处调用（名单不去重）；
        // ③ 「删掉原处 + 在别处新增」（名单变空或变成别的文件名，照样红）。
        // 挡不住 接收者改名或换别名、`with(x) { record(...) }`、`cacheInspector?.record(` ——
        // 因为正则锚的是「`cacheInspector.record(`」这个文本形态。要连这些一起挡就得按**类型**扫
        // （`CacheInspector` 的公开 fun 名集合 + 提及该类型的文件闭合名单，先例见
        // `PromptCacheScopeTest.the invalidate call sites are exactly the registered ones`），
        // 代价是以后每加一个方法、每新建一个引用该类型的文件都得改表 —— 本批**不做**，
        // 理由：这里的威胁模型是「顺手再记一份」，那种写法就是文本形态的第三种；按类型扫留给
        // 真出现别名绕过时再上（这一问的原始出处是交接 §12.2，第四轮 P3-2 记下结论）。
        val callers = mutableListOf<String>()
        repoFile("app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
            Regex("""cacheInspector\s*\.\s*record\s*\(""").findAll(code).forEach { callers += file.name }
            assertFalse(
                "${file.name} 里不得再出现 recordSectionCache（读数只能沿装配产物走）",
                code.contains("recordSectionCache"),
            )
        }
        assertEquals(
            "段落缓存读数只能由发出那一发请求的执行器写进观测器：`app/src/main/java` 下**一处**调用，" +
                "且在 TurnExecutor.kt（名单按出现次数收、不按文件去重 —— 同一文件里多写一处也算多一条改写路径）",
            listOf("TurnExecutor.kt"),
            callers,
        )
    }

    /** 剥注释后的仓库文件文本（与 `ImageScalingGuardTest` / `PromptCacheScopeTest` 同口径）。 */
    private fun strippedRepo(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    /** 从测试工作目录往上找仓库根。 */
    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
