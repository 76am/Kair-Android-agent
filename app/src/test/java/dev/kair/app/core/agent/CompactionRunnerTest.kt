package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.HookBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * 压缩执行器的定点用例（A5 第二步拆出后一直缺这一类，2026-10-31 补）。
 *
 * 锁的核心语义只有一条：**只有压缩真的提交了才产生副作用**。
 * 更早的实现把「更早的图片降级」放在 `run()` 第一句并立即落盘，于是摘要失败 / 切点找不到 /
 * 被取消这些**没有压缩成功**的路径也会永久改写历史（用户看到历史图片只剩一张，附件本体
 * 还会被随后的 GC 删掉）。这里的用例把四条路径逐条钉住。
 *
 * 判据都是可数事实：`replaceHistory` 调用次数、落盘历史里的图片部件个数、摘要请求次数、
 * 诊断文本 —— 不看「看起来对不对」。
 *
 * 图片 token 的下限是 1500（[Compaction] 的固定估值），所以预算按「几张图」来配：
 * 阈值 3000 = 两张图。下面每个场景的数值都写在注释里，改预算前先算一遍。
 */
class CompactionRunnerTest {

    /** 一次压缩的现场：历史、落盘记录、调用计数。 */
    private class Stage(
        /** 上下文窗口；阈值 = window − reserve。 */
        val window: Int = 4_000,
        val reserve: Int = 1_000,
        /** `plan.kept` 取历史末尾几条。 */
        val keep: Int = 2,
        /** system prompt 也占端点窗口；默认很短，只有需要构造「前缀把预算顶上去」时才调大。 */
        val systemPrompt: String = "sp",
    ) {
        val messages = mutableListOf<ChatMessage>()
        val replaced = mutableListOf<List<ChatMessage>>()
        val diagnostics = Diagnostics()
        var summarizeCalls = 0

        /** 摘要请求实际收到的模型与端点：锁「压缩模型」是否真的换了落点。 */
        var summarizeModel: String? = null
        var summarizeConfig: ProviderConfig? = null

        /** 摘要请求的目标；默认与会话模型同源（旧行为）。 */
        var target = CompactionTarget(
            model = "m",
            config = ProviderConfig(providerId = "p", transportId = "t", baseUrl = "u", apiKey = "k"),
        )

        /** `strategy.plan` 每次收到的 trigger，用来锁「溢出信号是否可信」的判定。 */
        val planTriggers = mutableListOf<CompactionTrigger>()
        var markCompacting = 0
        var clearCompacting = 0
        var planAvailable = true
        var summarize: suspend () -> String = { "摘要正文" }
        var compacted: Pair<Int, Int>? = null

        val settings = CompactionSettings(enabled = true, reserveTokens = reserve, keepRecentTokens = 1_000)

        val runner = CompactionRunner(
            CompactionRunner.Host(
                sessionId = "s1",
                workspace = File("."),
                assembly = { AgentAssembly(AgentSettings(model = "m", systemPrompt = systemPrompt, maxSteps = 1)) },
                compactionSettings = { settings },
                contextStrategy = { strategy },
                contextWindow = { window },
                target = { target },
                hooks = HookBus(),
                messages = { messages },
                replaceHistory = { replaced += it; true },
                // 真实会话交出的就是这份纯内存候选；这里给同一套降级规则（无来源路径）。
                demotedMessages = { Compaction.demoteOlderImages(messages) { null } },
                markCompacting = { markCompacting++ },
                clearCompacting = { clearCompacting++ },
                onEvent = { },
                turnCount = { 1 },
                diagnostics = diagnostics,
                onCompacted = { before, after -> compacted = before to after },
            ),
        )

        val strategy: ContextStrategy = object : ContextStrategy {
            override val id: String = "test.compaction"

            override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) =
                Compaction.shouldCompact(contextTokens, contextWindow, settings)

            override fun plan(
                messages: List<ChatMessage>,
                settings: CompactionSettings,
                trigger: CompactionTrigger,
            ): CompactionPlan? {
                planTriggers += trigger
                if (!planAvailable) return null
                return CompactionPlan(
                    summarized = messages.dropLast(keep),
                    kept = messages.takeLast(keep),
                    previousSummary = null,
                    tokensBefore = Compaction.estimateContextTokens(messages),
                )
            }

            override suspend fun summarize(
                model: String,
                config: ProviderConfig,
                plan: CompactionPlan,
                customInstructions: String?,
                reserveTokens: Int,
                sessionId: String?,
            ): String {
                summarizeCalls++
                summarizeModel = model
                summarizeConfig = config
                return summarize()
            }

            override fun wrapSummary(plan: CompactionPlan, summary: String): String = "SUMMARY: $summary"
        }

        fun run(trigger: CompactionTrigger = CompactionTrigger.PRESSURE): Boolean =
            runBlocking { runner.run(strategy, settings, trigger) }
    }

    private fun image(): ChatMessage =
        ChatMessage(role = Role.USER, parts = listOf(Part.Image(base64 = "AAAA")))

    private fun text(value: String, role: Role = Role.USER): ChatMessage =
        ChatMessage(role = role, parts = listOf(Part.Text(value)))

    /** 一张图 = 1500 token；[chars] 个汉字 ≈ 同等 token。 */
    private fun filler(chars: Int): String = "字".repeat(chars)

    private fun List<ChatMessage>.imageCount(): Int = sumOf { it.images.size }

    private fun List<ChatMessage>.placeholders(): Int =
        count { m -> m.parts.any { it is Part.Text && it.text.startsWith("[image") } }

    // ---- 三条「没有压缩成功」的路径：历史必须原样 ----------------------------------------

    @Test
    fun `降级后不超预算也照常压缩（没有「降载够了就不压」的分支）`() {
        // 负责人 2026-10-31 去掉了那条短路：判定进入压缩就压到底，只留一条规则。
        val st = Stage()
        // 原始：图 1500 + 图 1500 + 文本 100 = 3100 > 阈值 3000 → 判定需要压缩
        // 降级后：1500 + 100 = 1600 < 3000 —— 旧实现会在这里返回，现在照样压。
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += image()
        st.messages += text(filler(100), Role.ASSISTANT)

        val compacted = st.run()

        assertEquals("判超就压，不再看降级后的值", true, compacted)
        assertEquals(1, st.replaced.size)
        assertEquals(1, st.summarizeCalls)
    }

    @Test
    fun `切点找不到时历史原样`() {
        val st = Stage()
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += image()
        st.messages += text(filler(100), Role.ASSISTANT)
        st.planAvailable = false

        val compacted = st.run()

        assertEquals(false, compacted)
        assertEquals("不该落盘", 0, st.replaced.size)
        assertEquals(0, st.summarizeCalls)
    }

    @Test
    fun `摘要被取消时历史原样`() {
        val st = Stage()
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)
        st.summarize = { throw CancellationException("用户点了停止") }

        val thrown = runCatching { st.run() }.exceptionOrNull()

        assertTrue("必须把取消原样抛出，不能吞成 false", thrown is CancellationException)
        assertEquals("取消不该落盘", 0, st.replaced.size)
        assertEquals("图片原样", 3, st.messages.imageCount())
        assertEquals("收起压缩态", 1, st.clearCompacting)
    }

    // ---- 成功路径：一次落盘，图片降级随之生效 -------------------------------------------

    @Test
    fun `摘要成功才一次落盘，更早的图片降级为占位`() {
        // keep=6 才有意义：kept 里必须同时有「更早的那张」（图B，该被降级）与
        // 「最后一张」（图C，该保留）。keep=4 时图B 落在 summarized 段，就断言不到降级。
        val st = Stage(keep = 6)
        // 3 张图 + 两段填充文本：
        //  原始       = 4500 + 3000 = 7500 > 3000
        //  降级后     = 1500（最后一张）+ 3000 = 4500 > 3000 → 仍要压缩
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)

        val compacted = st.run()

        assertEquals(true, compacted)
        assertEquals("只落盘一次", 1, st.replaced.size)
        assertEquals(1, st.summarizeCalls)
        val history = st.replaced.single()
        assertEquals("摘要消息在首位", true, history.first().isCompaction)
        // kept 是最后 4 条：[图, "b"... ] 里更早的那张已降级，最后一张保留
        assertEquals("新历史只剩最后一张图", 1, history.imageCount())
        assertEquals("另一张变成了占位", 1, history.placeholders())
    }

    @Test
    fun `压缩前的 token 按原始历史算，不是降级后的小值`() {
        val st = Stage(keep = 2)
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)

        st.run()

        val (before, after) = requireNotNull(st.compacted)
        val original = Compaction.estimateContextTokens(
            st.messages.filterNot { it.isCompaction },
        )
        assertEquals("before 必须是原始大小（含全部图片）", original / 100, before / 100)
        assertTrue("压缩后必须变小", after < before)
    }

    // ---- 失败路径：机械检查点仍然重写历史，但不碰图片 -----------------------------------

    @Test
    fun `摘要失败时历史被机械切分，但图片一张不少`() {
        val st = Stage(keep = 2)
        // 阈值 3000；kept = 最后 2 条（纯文本）。
        //  降级后 = 1500（最后一张图）+ 3000 文本 = 4500 > 3000 → 仍要压缩
        //  机械检查点后 = 摘要（短）+ 纯文本 kept < 3000 → 不会触发最后那道降级
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)
        st.summarize = { throw IllegalStateException("429") }

        val compacted = st.run()

        assertEquals("机械检查点仍算「历史改了」", true, compacted)
        assertEquals("只落盘一次", 1, st.replaced.size)
        val history = st.replaced.single()
        assertTrue(
            "落盘的必须是机械检查点正文",
            history.first().text.contains("The earlier turns of this conversation were dropped"),
        )
        assertEquals("kept 里不带占位（图片没被降级）", 0, history.placeholders())
        assertTrue(
            "失败必须留诊断",
            st.diagnostics.compactionFailures.value["s1"]?.contains("429") == true,
        )
    }

    @Test
    fun `机械检查点后仍超预算：最后降级一次，并单独记诊断`() {
        // keep=6：kept 里有**两张**图 → 机械检查点后 = 3000 + 摘要 ≈ 3075 > 3000
        // → 必须再降一次，否则「溢出 → 机械切分 → 仍溢出」会一直循环。
        val st = Stage(keep = 6)
        // 同上，但 kept 里有**两张**图：机械检查点后 = 3000 + 摘要 ≈ 3075 > 3000
        // → 必须再降一次，否则「溢出 → 机械切分 → 仍溢出」会一直循环。
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)
        st.summarize = { throw IllegalStateException("timeout") }

        st.run()

        assertEquals("第一次机械切分 + 第二次降级", 2, st.replaced.size)
        // 第一次落盘必须用**原历史**的后缀：两张图都还在（一张不少）。
        // 这一条是「失败不降级」唯一可观测的位置 —— 没有二次降级时，kept 里的图必然
        // 就是最后一张，降不降级看不出差别（那正是下面单图用例覆盖不到的分支）。
        assertEquals("机械切分保留原图", 2, st.replaced.first().imageCount())
        assertEquals("机械切分不产生占位", 0, st.replaced.first().placeholders())
        val emergency = st.replaced.last()
        assertEquals("最后只剩一张图", 1, emergency.imageCount())
        assertEquals("另一张变占位", 1, emergency.placeholders())
        assertTrue(
            "诊断要区分「仍是机械切分」与「已降级图片」",
            st.diagnostics.compactionFailures.value["s1"]?.contains("已降级历史图片") == true,
        )
    }

    @Test
    fun `溢出信号是否可信要含 system prompt 与工具 schema 的固定前缀`() {
        // `CONTEXT_OVERFLOW` 只有在内核**独立确认仍超预算**时才升级为真溢出（允许策略走
        // 有损兜底）。判定漏了固定前缀 → 明明超窗却按普通压力压缩 → 压得不够狠、仍超窗。
        //
        // 阈值 3000；system prompt 500 token。
        //  只看历史 = 降级后 1500 + 1200 = 2700 < 3000  ← 漏前缀会判「不超」
        //  加上前缀 =                    3200 > 3000  ← 实际仍超，必须升级为真溢出
        fun stage(prefixTokens: Int): Stage {
            val st = Stage(systemPrompt = if (prefixTokens == 0) "sp" else filler(prefixTokens))
            st.messages += image()
            st.messages += text("a", Role.ASSISTANT)
            st.messages += image()
            st.messages += text(filler(1_200), Role.ASSISTANT)
            return st
        }

        val withPrefix = stage(500)
        withPrefix.run(CompactionTrigger.CONTEXT_OVERFLOW)
        assertEquals(
            "含前缀仍超预算，溢出信号可信，必须升级",
            listOf(CompactionTrigger.CONTEXT_OVERFLOW),
            withPrefix.planTriggers,
        )

        // 反例：前缀很短时降级后确实回到预算内 → 溢出信号不可信，按普通压力压缩。
        val withoutPrefix = stage(0)
        withoutPrefix.run(CompactionTrigger.CONTEXT_OVERFLOW)
        assertEquals(
            "降级后确实不超，溢出信号不可信，退回压力压缩",
            listOf(CompactionTrigger.PRESSURE),
            withoutPrefix.planTriggers,
        )
    }

    // ---- 压缩模型：摘要这一发可以落在另一个模型 / 端点 -----------------------------------

    @Test
    fun `压缩模型生效时摘要请求换用它自己的模型与端点`() {
        val st = Stage()
        st.messages += text("a")
        st.messages += text("b", Role.ASSISTANT)
        st.target = CompactionTarget(
            model = "cheap-compactor",
            config = ProviderConfig(
                providerId = "cheap-provider",
                transportId = "cheap-transport",
                baseUrl = "https://cheap.example/v1",
                apiKey = "sk-cheap",
            ),
        )

        st.run()

        assertEquals("模型必须取压缩目标，而不是会话模型", "cheap-compactor", st.summarizeModel)
        assertEquals(
            "端点必须与压缩模型成对，不能沿用主请求的 config",
            "https://cheap.example/v1",
            st.summarizeConfig?.baseUrl,
        )
        assertEquals("sk-cheap", st.summarizeConfig?.apiKey)
    }

    @Test
    fun `四类压缩入口（自动阈值、模型主动、手动、溢出救援）都用指定压缩模型`() {
        // 负责人要求：无论哪条路径，指定压缩模型后都必须走它。四条入口全部汇到
        // `run -> summarize`，本用例逐条走一遍，防止将来新增入口绕过 target。
        val cheap = CompactionTarget(
            model = "cheap-compactor",
            config = ProviderConfig(
                providerId = "cheap-provider",
                transportId = "cheap-transport",
                baseUrl = "https://cheap.example/v1",
                apiKey = "sk-cheap",
            ),
        )

        // 1) 自动阈值：3500 字 > 窗口 4000 − 预留 1000。
        val auto = Stage().apply {
            target = cheap
            messages += text(filler(3_500))
            messages += text("a", Role.ASSISTANT)
            messages += text("b")
        }
        assertEquals(true, runBlocking { auto.runner.compactIfNeeded() })

        // 2) 模型主动请求：跳过阈值，仍走同一个 target。
        val requested = Stage().apply {
            target = cheap
            messages += text("a")
            messages += text("b", Role.ASSISTANT)
            runner.requestCompaction()
        }
        assertEquals(true, runBlocking { requested.runner.compactIfNeeded() })

        // 3) 用户手动。
        val manual = Stage().apply {
            target = cheap
            messages += text("a")
            messages += text("b", Role.ASSISTANT)
        }
        assertEquals(true, runBlocking { manual.runner.compactNow(manual.strategy) })

        // 4) 端点溢出救援。
        val overflow = Stage().apply {
            target = cheap
            messages += text("a")
            messages += text("b", Role.ASSISTANT)
        }
        assertEquals(
            true,
            runBlocking { overflow.runner.compactOnOverflow(overflow.strategy) },
        )

        listOf("自动" to auto, "主动" to requested, "手动" to manual, "溢出" to overflow)
            .forEach { (name, st) ->
                assertEquals("$name：模型必须是压缩模型", "cheap-compactor", st.summarizeModel)
                assertEquals(
                    "$name：端点必须与压缩模型成对",
                    "https://cheap.example/v1",
                    st.summarizeConfig?.baseUrl,
                )
            }
    }

    @Test
    fun `未指定压缩模型时摘要仍用会话模型与端点`() {
        val st = Stage()
        st.messages += text("a")
        st.messages += text("b", Role.ASSISTANT)

        st.run()

        assertEquals("m", st.summarizeModel)
        assertEquals("u", st.summarizeConfig?.baseUrl)
    }

    @Test
    fun `压缩成功会清掉上一次的失败诊断`() {
        val st = Stage(keep = 4)
        st.diagnostics.recordCompactionFailure("s1", "上一次失败")
        st.messages += image()
        st.messages += text("a", Role.ASSISTANT)
        st.messages += text(filler(2_000))
        st.messages += image()
        st.messages += text("b", Role.ASSISTANT)
        st.messages += text(filler(1_000))
        st.messages += image()
        st.messages += text("c", Role.ASSISTANT)

        st.run()

        assertNull("成功即覆盖式清除", st.diagnostics.compactionFailures.value["s1"])
    }
}
