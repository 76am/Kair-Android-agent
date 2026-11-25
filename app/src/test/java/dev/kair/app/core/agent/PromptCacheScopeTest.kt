package dev.kair.app.core.agent

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 段落缓存的**失效作用域**（U19 第 ② 笔）。
 *
 * 缓存按 `(作用域, 贡献点 id)` 分桶，作用域 = `SystemPromptContext.workspace`（宿主工作区路径）。
 * 这里守的是「该清的清掉、不该清的别动」两面：
 * - 一个工作区的失效不得打掉别的工作区的槽位 —— U19 本体，丢的是命中率；
 * - 但不同工作区绝不能命中同一条正文、跨作用域的写回也不得被别人的失效连带丢弃 —— 这两条
 *   是正确性，破了就是「把 A 项目的段落发给 B 项目的会话」。
 *
 * 断言一律数「贡献点被调用几次」，不测字节：字节由 `SystemPromptTest` / `PromptWiringTest` 守，
 * 而「有没有重算」只有调用次数能直接说明。
 */
class PromptCacheScopeTest {

    /** 每次被真正调用就自增计数并产出可区分正文；正文随调用变化，因此「读到旧正文」= 命中。 */
    private class CountingContributor(
        override val id: String,
        /** 非 null 时：每次计算中途去失效**另一个**作用域，模拟并发窗口里别的会话刷新。 */
        private val invalidateScopeWhileComputing: String? = null,
    ) : SystemPromptContributor {
        var calls = 0
            private set
        override val order = 0

        override fun section(ctx: SystemPromptContext): String {
            calls++
            invalidateScopeWhileComputing?.let { SystemPromptBuilder.invalidate(it) }
            return "body-$calls"
        }
    }

    private fun registryOf(vararg contributors: SystemPromptContributor) = PluginRegistry().install(object : Plugin {
        override val id = "test.prompt.cache-scope"
        override val name = "test"
        override val description = ""
        override fun activate(ctx: PluginContext) {
            contributors.forEach { ctx.systemPrompt(it) }
        }
    }).systemPromptContributors()

    /**
     * 只换 workspace 这一个维度：cwd 固定成同一个值 —— 容器在场时两个项目的 cwd 本就相同
     * （都挂在 `/projects/<目录名>`），若这里让 cwd 跟着 workspace 一起变，「串正文」就会被
     * cwd 那一维顺手挡住，③ 那条反向用例就成了空断言（V3 变异实测：写错时它全绿）。
     */
    private fun context(workspace: String) =
        SystemPromptContext(cwd = "/shared-cwd", tools = emptyList(), addendum = "", workspace = workspace)

    @Test
    fun `a workspace invalidation leaves other workspaces cached`() {
        val contributor = CountingContributor("test.prompt.scope.isolation")
        val contributors = registryOf(contributor)
        SystemPromptBuilder.invalidate()

        SystemPromptBuilder.build(context("/ws/a"), contributors)
        SystemPromptBuilder.build(context("/ws/b"), contributors)
        assertEquals("两个作用域各算过一次", 2, contributor.calls)

        SystemPromptBuilder.invalidate("/ws/a")
        SystemPromptBuilder.build(context("/ws/a"), contributors)
        // 先确认这条失效真的动了本作用域 —— 否则下面那条「别的作用域没动」是空断言。
        assertEquals("本作用域应当被清掉并重算", 3, contributor.calls)

        SystemPromptBuilder.build(context("/ws/b"), contributors)
        assertEquals(
            "U19：别的工作区不得被这次失效带走（此前一个会话每回合刷新会把全表打掉）",
            3,
            contributor.calls,
        )
    }

    @Test
    fun `global invalidation still clears every workspace`() {
        val contributor = CountingContributor("test.prompt.scope.global")
        val contributors = registryOf(contributor)
        SystemPromptBuilder.invalidate()

        SystemPromptBuilder.build(context("/ws/a"), contributors)
        SystemPromptBuilder.build(context("/ws/b"), contributors)
        assertEquals(2, contributor.calls)

        // 改预设 / 注入覆写来源走的是全局那条：它们改的是跨工作区的输入。
        SystemPromptBuilder.invalidate()
        SystemPromptBuilder.build(context("/ws/a"), contributors)
        SystemPromptBuilder.build(context("/ws/b"), contributors)
        assertEquals("全局失效必须把每个作用域都清掉，少一个就是「改了预设却不生效」", 4, contributor.calls)
    }

    @Test
    fun `different workspaces never read the same cached section`() {
        val contributor = CountingContributor("test.prompt.scope.no-cross-read")
        val contributors = registryOf(contributor)
        SystemPromptBuilder.invalidate()

        // 其余输入逐位相同，只有 workspace 不同：B 若读到 A 的正文，这里拿到的就是 body-1。
        assertEquals("body-1", SystemPromptBuilder.build(context("/ws/a"), contributors))
        assertEquals("body-1", SystemPromptBuilder.build(context("/ws/a"), contributors))
        assertEquals(1, contributor.calls)
        assertEquals(
            "不同工作区必须各自产出，读到别处的正文是正确性问题、不是命中率问题",
            "body-2",
            SystemPromptBuilder.build(context("/ws/b"), contributors),
        )
    }

    @Test
    fun `a write-back survives invalidation of another workspace`() {
        // 并发窗口的单线程可验形态：本次计算期间，别的会话失效了**它的**作用域。
        // 代数若仍是进程级单值，这条写回会被当作过期结果丢弃，下一回合只能重算 ——
        // 「只把桶分开、代数还全局共用」就是这一条红。
        val contributor = CountingContributor("test.prompt.scope.cross-writeback", invalidateScopeWhileComputing = "/ws/other")
        val contributors = registryOf(contributor)
        SystemPromptBuilder.invalidate()

        SystemPromptBuilder.build(context("/ws/mine"), contributors)
        assertEquals(1, contributor.calls)
        SystemPromptBuilder.build(context("/ws/mine"), contributors)
        assertEquals(
            "别的会话失效不该把我的写回丢掉",
            1,
            contributor.calls,
        )
    }

    @Test
    fun `workspaces do not squeeze each other out of the slot limit`() {
        val contributor = CountingContributor("test.prompt.scope.slot-squeeze")
        val contributors = registryOf(contributor)
        SystemPromptBuilder.invalidate()

        // 槽位上限是 SLOTS_PER_ID（现值 4）：旧布局下所有工作区共用这一条队列，
        // 第 5 个作用域起就把最旧的挤出去，回到它时必然重算。读常量而不是写死 6 ——
        // 写死的话这个上限提到 ≥6 时本用例静默空转（评审 P3）。
        val scopes = (1..PromptSectionCache.SLOTS_PER_ID + 2).map { "/ws/$it" }
        scopes.forEach { SystemPromptBuilder.build(context(it), contributors) }
        val callsAfterWarm = contributor.calls
        assertEquals(scopes.size, callsAfterWarm)

        scopes.forEach { SystemPromptBuilder.build(context(it), contributors) }
        assertEquals(
            "每个 (作用域, 贡献点) 各自留槽位后，轮一遍不该再有任何 miss",
            callsAfterWarm,
            contributor.calls,
        )
    }

    @Test
    fun `generation is read before the section inputs`() {
        // 结构守卫，不是行为守卫：这条窗口要造景才观察得到（失效必须正好落在两次读之间），
        // 而「读取顺序」本身可以用源码文本钉住 —— 与 `PendingUserGateTest` /
        // `ImageScalingGuardTest` 同一做法。扫**剥注释后**的源码：写在注释里的顺序不该让守卫变绿
        // （U18 复核踩过这个坑）。
        val code = KotlinSourceScanner.stripped(repoFile("app/src/main/java/dev/kair/app/core/agent/SystemPrompt.kt").readText(Charsets.UTF_8))
        val lines = code.lines()
        // 同名 `section` 有好几个（每个内置段落都是），认「往缓存里写回」的那一个。
        val assembly = KotlinSourceScanner.scan(code)
            .filter { it.name == "section" }
            .firstOrNull { lines.subList(it.startLine - 1, it.endLine).any { l -> l.contains("PromptSectionCache.remember(") } }
            ?: error("SystemPrompt.kt 里找不到装配段落的那个 section —— 守卫扫描落空，不等于通过")
        val body = lines.subList(assembly.startLine - 1, assembly.endLine).joinToString("\n")
        val generationAt = body.indexOf("PromptSectionCache.generation(")
        val overrideAt = body.indexOf("overridesProvider(")
        assertTrue(
            "两个读点都得在（守卫本身跟着这段代码一起改）：generation=$generationAt overrides=$overrideAt",
            generationAt >= 0 && overrideAt >= 0,
        )
        assertTrue(
            "代数必须在读任何进入槽位的输入之前取 —— 顺序反了的话，一次失效正好落在两次读之间时，" +
                "这条会用失效**之后**的代数写回失效**之前**输入算出的正文，那次失效对这段等于没发生",
            generationAt < overrideAt,
        )
    }

    @Test
    fun `the invalidation call sites use the same workspace expression as the assembly`() {
        // 作用域这一维有两个生产读点：装配侧把它写进桶键，失效侧把它传给 `invalidate`。
        // 两边字符串不一致 = 「清了一个没人读的桶」，旧正文会一直留着且没有任何现象提示 ——
        // 而这条在行为层面测不出来：`PromptRefreshTest` 两侧同用一个 `dir` 变量，天生撞不上。
        // 所以按仓里守接线的既有做法钉文本（剥注释扫，写进注释不算数 —— U18 复核踩过）。
        val assembly = strippedRepo("app/src/main/java/dev/kair/app/app/AgentSettingsFactory.kt")
        assertTrue(
            "装配侧必须把宿主工作区原样写进 context（它就是桶键），实际见 AgentSettingsFactory.kt",
            assembly.contains("workspace = workspace.path"),
        )
        for ((path, expected) in listOf(
            "app/src/main/java/dev/kair/app/core/agent/TurnRunner.kt" to
                "SystemPromptBuilder.invalidate(host.workspace.path)",
            "app/src/main/java/dev/kair/app/core/agent/CompactionRunner.kt" to
                "SystemPromptBuilder.invalidate(host.workspace.path)",
            // U31 ③-b：`deleteProject` 搬进编排对象，扫描目标跟着搬、断言与访问器形态不变。
            "app/src/main/java/dev/kair/app/app/SessionLifecycle.kt" to
                "SystemPromptBuilder.invalidate(workspace.path)",
        )) {
            assertTrue(
                "$path 的失效必须传 $expected（与装配侧同一个访问器形态）；" +
                    "改成别的取值前，先证明两边仍逐字相等",
                strippedRepo(path).contains(expected),
            )
        }
    }

    @Test
    fun `the invalidate call sites are exactly the registered ones`() {
        // 失效粒度只有两种，是**口径**（交接 §5.3 / §8）：带作用域的那些必须能拿出与装配同一个
        // workspace，不带参数的那些等于声明「我知道这次改的是跨作用域的东西」。
        // 这张表能闭合的范围先说清：它守的是**跨文件、带前缀**的调用点（新增一处就红，V7 实测）；
        // 对象内部的裸自调用（`SystemPrompt.kt` 里 `setOverridesProvider` 调 `invalidate()`）与
        // `PromptSectionCache` 自己的 `clear(it)` 不在它眼里 —— 所以末尾钉住**对象的对外函数名集合**
        // （第五轮 P2：计数守名字不守出口，异名的新粒度会漏）与 `invalidate` 的两个重载。
        //
        // 第三轮复核抓到只扫 `SystemPromptBuilder.invalidate(` 不够：`SystemPrompt.kt` 里
        // `setOverridesProvider` 调的是**裸** `invalidate()`（同类自调用，带前缀的正则看不见），
        // 而真正的水槽是 `PromptSectionCache.clear` —— 任何失效最后都得过它，所以再钉一张水槽表。
        val callRe = Regex("""SystemPromptBuilder\s*\.\s*invalidate\s*\(""")
        val sinkRe = Regex("""PromptSectionCache\s*\.\s*clear\s*\(""")
        val scoped = mutableListOf<String>()
        val global = mutableListOf<String>()
        val sinks = mutableListOf<String>()
        repoFile("app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val raw = file.readText(Charsets.UTF_8)
            val code = KotlinSourceScanner.stripped(raw)
            // 「这次调用存在」用剥注释后的文本（写进注释的 `SystemPromptBuilder.invalidate(` 不算，
            // V6 实测）；「带不带实参」用**原文**偏移回看 —— 因为 stripped() 连字符串字面量一起抹成
            // 空格，`invalidate("/hardcoded/scope")` 在抹过的文本里长得像 `invalidate(   )`，
            // 会被误分类成全局。V7 实测：按抹后的文本分类时，把保存后那次全局清改成带字面量作用域
            // 的路径写法，两张名单都不变、守卫全绿。
            callRe.findAll(code).forEach { match ->
                if (isNoArg(raw, match.range.last)) global += file.name else scoped += file.name
            }
            sinkRe.findAll(code).forEach { match ->
                sinks += "${file.name}#${if (isNoArg(raw, match.range.last)) "全局" else "作用域"}"
            }
        }
        assertEquals(
            "带作用域的失效调用点（新增要先证明它拿得到与装配同形态的那个路径）",
            // U64：记忆插件在整理成功 / 回滚后失效**它自己那个工作区**的段落缓存 ——
            // `workspace` 来自 TurnEnd / SlotContext（宿主真实路径），与装配的
            // `SystemPromptContext.workspace` 是同形态的那一个。
            listOf("CompactionRunner.kt", "MemoryPlugin.kt", "SessionLifecycle.kt", "TurnRunner.kt").sorted(),
            scoped.sorted(),
        )
        assertEquals(
            "全局失效的调用点（理由写在 invalidate(workspaceScope) 的 KDoc 与交接 §8）",
            listOf("FileEditor.kt", "PromptPresetsPlugin.kt").sorted(),
            global.sorted(),
        )
        assertEquals(
            "缓存水槽只允许装配器的两个重载各调一次 —— 再加一处裸 invalidate() 也得复用这两个出口，" +
                "水槽数目不变才是这张表真正守住的东西",
            listOf("SystemPrompt.kt#全局", "SystemPrompt.kt#作用域").sorted(),
            sinks.sorted(),
        )
        // 第五轮 P2：上面两条**计数**守的是名字，不是出口 —— 第三种失效粒度若**异名**
        // （加一个 `fun purge(scope)` 并让调用点直调它），`fun clear(` 的计数与三张表全碰不到它。
        // 换成钉「这个对象的对外函数名集合」：任何新出口（不管叫什么）都必须先进这张表。
        // 口径：object -level 的 `    fun 名字(`，私有工具（`private fun …`）天然不在其中 ——
        // 私有形态不改变「宿主能怎么失效」，所以不必登记。
        val cacheSurface = Regex("\n    fun (\\w+)\\s*\\(")
            .findAll(strippedRepo("app/src/main/java/dev/kair/app/core/agent/PromptSectionCache.kt"))
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "段落缓存对外的函数面就是这些 —— 想多一个出口（无论叫什么名字）必须先改这张表，" +
                "并说清它与 `clear(scope)` / `clear()` 的分工",
            setOf("find", "generation", "remember", "forget", "clear"),
            cacheSurface,
        )
        assertEquals(
            "`invalidate` 的两个重载 = 两种失效粒度的全集（改名会掉到 1 而红，异名新增由上面那张表红）",
            2,
            Regex("fun\\s+invalidate\\s*\\(")
                .findAll(strippedRepo("app/src/main/java/dev/kair/app/core/agent/SystemPrompt.kt"))
                .count(),
        )
    }

    /** 括号后（跳过任意空白）紧跟 `)` 就是不带参数的那一条。 */
    private fun isNoArg(code: String, openParenAt: Int): Boolean =
        code.substring(openParenAt + 1).dropWhile { it == ' ' || it == '\t' || it == '\r' || it == '\n' }.startsWith(")")

    /** 剥注释后的仓库文件文本（与 `ImageScalingGuardTest` 同口径）。 */
    private fun strippedRepo(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    /** 与 `ImageScalingGuardTest` 同口径：从测试工作目录往上找仓库根。 */
    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
