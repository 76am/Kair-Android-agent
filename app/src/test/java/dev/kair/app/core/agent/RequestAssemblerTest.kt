package dev.kair.app.core.agent

import dev.kair.app.core.ai.LlmToolSpec
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestAssemblerTest {

    private val readTool = LlmToolSpec(
        name = "read_file",
        description = "Read a file from the workspace.",
        parameters = buildJsonObject { put("type", "object") },
    )

    private fun user(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    private fun assistant(text: String) = ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text(text)))

    private fun assemble(
        messages: List<ChatMessage>,
        tools: List<LlmToolSpec> = listOf(readTool),
        systemPrompt: String = "You are Kair, a coding agent.",
    ) = RequestAssembler.assemble(
        model = "gpt-4o",
        systemPrompt = systemPrompt,
        messages = messages,
        tools = tools,
        options = SamplingOptions(temperature = 0.2, maxTokens = 1024),
        sessionId = "session-1",
    )

    @Test
    fun `stable prefix ignores message ids and timestamps`() {
        // 两次构造的消息内容相同，但 id（随机）与时间戳不同 —— 摘要必须一致。
        val a = assemble(listOf(user("hi"), assistant("hello")))
        val b = assemble(listOf(user("hi"), assistant("hello")))

        assertEquals(a.segment(SegmentKind.SYSTEM)!!.digest, b.segment(SegmentKind.SYSTEM)!!.digest)
        assertEquals(a.segment(SegmentKind.TOOLS)!!.digest, b.segment(SegmentKind.TOOLS)!!.digest)
        assertEquals(a.segment(SegmentKind.HISTORY)!!.digest, b.segment(SegmentKind.HISTORY)!!.digest)
    }

    @Test
    fun `appending a turn keeps the previous prefix`() {
        val base = assemble(listOf(user("hi")))
        val grown = assemble(listOf(user("hi"), assistant("hello")))

        assertEquals(
            SegmentChange.APPENDED,
            diffSegment(base.segment(SegmentKind.HISTORY), grown.segment(SegmentKind.HISTORY)!!),
        )
        assertEquals(
            SegmentChange.UNCHANGED,
            diffSegment(base.segment(SegmentKind.SYSTEM), grown.segment(SegmentKind.SYSTEM)!!),
        )
    }

    @Test
    fun `rewriting an earlier turn is flagged as rewritten`() {
        val base = assemble(listOf(user("hi"), assistant("hello")))
        val edited = assemble(listOf(user("HI"), assistant("hello")))

        assertEquals(
            SegmentChange.REWRITTEN,
            diffSegment(base.segment(SegmentKind.HISTORY), edited.segment(SegmentKind.HISTORY)!!),
        )
    }

    @Test
    fun `tool segment changes when the tool schema changes`() {
        val base = assemble(listOf(user("hi")))
        val other = assemble(
            listOf(user("hi")),
            tools = listOf(readTool.copy(description = "Read a file.")),
        )

        assertEquals(
            SegmentChange.REWRITTEN,
            diffSegment(base.segment(SegmentKind.TOOLS), other.segment(SegmentKind.TOOLS)!!),
        )
    }

    @Test
    fun `volatile tail is excluded from the prefix`() {
        val assembly = assemble(listOf(user("hi")))
        val tail = assembly.segment(SegmentKind.VOLATILE)!!

        assertFalse(tail.stable)
        assertEquals(SegmentChange.NOT_APPLICABLE, diffSegment(null, tail))
        assertTrue(assembly.prefixBytes < assembly.totalBytes)
    }

    @Test
    fun `inspector keeps the prefix intact when appending`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"), user("more"))))

        val report = inspector.latest.value["s1"]!!
        // 面板不再有一行总判定（`prefixIntact` 字段随它一起删了），「只追加」这件事
        // 由逐段的变化列说：HISTORY 段是 APPENDED = 前缀没被动过。
        assertEquals(SegmentChange.APPENDED, report.changes[SegmentKind.HISTORY])
    }

    @Test
    fun `inspector flags a broken prefix on rewrite`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))
        inspector.record("s1", "gpt-4o", assemble(listOf(user("HI"), assistant("ok"))))

        // 原断言是 `assertFalse(report.prefixIntact)`；字段删了，断言落到同一信号的
        // 唯一出处 —— 那一段被标成 REWRITTEN。
        assertEquals(SegmentChange.REWRITTEN, inspector.latest.value["s1"]!!.changes[SegmentKind.HISTORY])
    }

    @Test
    fun `inspector records the observed cache hit rate`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))))
        inspector.recordUsage("s1", Usage(input = 100, output = 10, cacheRead = 300))

        // 单发读数不再有字段（面板不为它单列一行），它只作为趋势样本存在 ——
        // 断言跟着搬到样本上，这条用例的牙没变小。
        assertEquals(0.75, inspector.latest.value["s1"]!!.samples.last().rate, 1e-9)
    }

    @Test
    fun `raw view escapes separators instead of rendering them`() {
        val seg = assemble(listOf(user("hi"), assistant("ok"))).segment(SegmentKind.HISTORY)!!

        // 可读视图是给人看的渲染；原始视图必须保留分隔符，并转义成可见形式。
        assertTrue(seg.raw.contains("\\0"))
        assertFalse(seg.raw.contains('\u0000'))
        assertTrue(seg.raw.contains("hi") && seg.raw.contains("ok"))
    }

    @Test
    fun `inspector exposes the appended excerpt without control chars`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))))
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))

        // 新增片段在装配时就截好：面板点开详情不再复制整段历史，也不会把 `\u0000`
        // 分隔符渲染成乱码。
        val appended = inspector.latest.value["s1"]!!.appended[SegmentKind.HISTORY]!!
        assertTrue(appended.text.contains("ok"))
        assertFalse(appended.text.contains('\u0000'))
        assertTrue(appended.bytes > 0)
    }

    @Test
    fun `oversized segment readable is bounded before display`() {
        val big = "字".repeat(30_000)
        val seg = assemble(listOf(user(big))).segment(SegmentKind.HISTORY)!!
        // 长会话的历史段可达数百 KB；详情页只排版预算内的尾部，避免打开面板时 OOM。
        assertTrue(seg.readable.length <= RequestSegment.DISPLAY_LIMIT + 100)
        assertTrue(seg.canonical.length > seg.readable.length)
    }

    @Test
    fun `bounded segments are displayed in full without truncation`() {
        val big = "字".repeat(30_000)
        // 系统提示 / 工具列表 / 请求尾部的长度由配置决定（段落数、工具数、参数量），
        // 不随回合增长 —— 截它们只会让人看不清「这段到底塞了什么」，所以整段给出。
        for (kind in listOf(SegmentKind.SYSTEM, SegmentKind.TOOLS, SegmentKind.VOLATILE)) {
            val seg = RequestSegment(kind, kind.name, big, stable = kind != SegmentKind.VOLATILE)
            assertEquals("[$kind] 可读视图不该截尾", big, seg.readable)
            assertEquals("[$kind] 原始视图不该截尾", big, seg.raw)
        }
        // 历史段仍按预算截尾：它才是会顶爆主线程排版的那一个。
        val history = RequestSegment(SegmentKind.HISTORY, "历史回合", big, stable = true)
        assertTrue(history.readable.length < big.length)
        assertTrue(history.raw.length < big.length)

        // 直构那三条绕开了装配路径，这里补一条真实装配的：整段系统提示（含各贡献点产出）
        // 走 assemble 进来后同样不许被截尾。
        val assembled = assemble(listOf(user("hi")), systemPrompt = big)
        assertEquals("装配出来的 SYSTEM 段不该截尾", big, assembled.segment(SegmentKind.SYSTEM)!!.readable)
    }

    @Test
    fun `appended excerpt keeps bounded segments whole`() {
        val added = "字".repeat(6_000) // 超过历史段引文预算 APPENDED_LIMIT(4_000)
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi")), systemPrompt = "P"))
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi")), systemPrompt = "P" + added))

        val appended = inspector.latest.value["s1"]!!.appended[SegmentKind.SYSTEM]!!
        // 「本次新增」在卡片里整段给出：6 000 字符的系统提示增量不能被历史段的 4 000 预算切掉。
        assertEquals(added, appended.raw)
        assertTrue(appended.text.contains(added.trim()))
    }

    @Test
    fun `inspector keeps the provider token sums across a new assembly`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))))
        inspector.recordUsage("s1", Usage(input = 100, output = 10, cacheRead = 900))

        // 提供商口径与对话页底部圆环一致：prompt = input + cacheRead + cacheWrite。
        // 面板上那行「提供商实测 … token」删了（第四轮），但这两个数仍是加权命中率的分子分母，
        // 所以断言落在 hitTally 的求和上，而不是被删掉的两个展示字段。
        assertEquals(1_000, inspector.latest.value["s1"]!!.hitTally!!.promptTokensSum.toInt())
        assertEquals(900, inspector.latest.value["s1"]!!.hitTally!!.cachedTokensSum.toInt())

        // 下一轮装配发生在 usage 回来之前，累计读数必须沿用而不是闪成空。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))
        assertEquals(1_000, inspector.latest.value["s1"]!!.hitTally!!.promptTokensSum.toInt())
    }

    @Test
    fun `inspector keeps a separate latest report per session`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))
        inspector.record("s2", "gpt-4o", assemble(listOf(user("yo"))))

        // 后一次上报不得覆盖前一会话的快照：面板据此按当前会话取用。
        assertEquals("s1", inspector.latest.value["s1"]!!.sessionId)
        assertEquals("s2", inspector.latest.value["s2"]!!.sessionId)
    }

    @Test
    fun `system notes are marked on the wire but keep the user role`() {
        val note = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Text("2026-10-31 (Sat)")),
            isSystemNote = true,
        )
        val assembly = assemble(listOf(user("hi"), note, assistant("ok")))
        val wire = assembly.request.messages

        // 角色仍是 user（中途 system 跨 provider 不可移植），但模型能看到来源标记。
        assertEquals(Role.USER, wire[1].role)
        assertEquals("<system-note>\n2026-10-31 (Sat)\n</system-note>", wire[1].text)
        // 普通用户消息不受影响。
        assertEquals("hi", wire[0].text)
        // 缓存口径与实际发送一致：HISTORY 段里也必须是包好的文本。
        assertTrue(assembly.segment(SegmentKind.HISTORY)!!.canonical.contains("<system-note>"))
    }

    @Test
    fun `trend samples are kept per session instead of sharing one pool`() {
        // 看板 U30 修的那条：旧实现是一个进程共用的 List + takeLast(limit)，而面板只筛当前会话
        // —— 并发会话会把当前会话的历史挤光，用户看到的就是「缓存只剩最近两发的历史」。
        // 上限给 3 是为了让「挤光」在旧实现下必然发生（旧写法这里会是 0 条）。
        val inspector = CacheInspector(historyLimit = 3)
        repeat(3) { i ->
            inspector.record("s1", "gpt-4o", assemble(listOf(user("a$i"))))
            inspector.recordUsage("s1", Usage(input = 100, output = 1, cacheRead = 100 + i))
        }
        repeat(5) { i ->
            inspector.record("s2", "gpt-4o", assemble(listOf(user("b$i"))))
            inspector.recordUsage("s2", Usage(input = 100, output = 1, cacheRead = 900))
        }

        val s1 = inspector.latest.value["s1"]!!
        assertEquals("别的工作会话不得挤掉本会话的趋势样本", 3, s1.samples.size)
        // s1 的比值都在 0.5 附近，s2 的是 0.9 —— 逐条比一下，确认这 3 个点真的是 s1 自己的
        // （只数条数的话，「池子还是全局的、只是筛错会话」这种写法碰不到）。
        assertTrue("样本必须是 s1 自己的那些比值", s1.samples.all { it.rate < 0.6 })
        assertEquals(3, inspector.latest.value["s2"]!!.samples.size)
        assertTrue("s2 的池子里全是它自己的 0.9", inspector.latest.value["s2"]!!.samples.all { it.rate > 0.8 })

        // 上面那两条单独存在时**抓不到「全进程共用一池」**：s1 的报告是它自己最后一次更新时
        // 拍的快照，池子被 s2 挤光也不会回头改写它（变异 MU-6 实测：只有 remove 那条红）。
        // 所以必须让 s1 在 s2 灌满池子**之后**再装配一次 —— 这时它若共用池子，拍到的就是
        // s2 的比值。这条才是「每会话各留一池」真正的牙。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("after s2"))))
        inspector.recordUsage("s1", Usage(input = 100, output = 1, cacheRead = 100))
        val late = inspector.latest.value["s1"]!!
        assertEquals("s1 在 s2 之后重新装配，样本仍是它自己的 3 条", 0.5, late.samples.last().rate, 1e-9)
        assertTrue("s1 的池子里不得混进 s2 的 0.9", late.samples.all { it.rate < 0.6 })
    }

    @Test
    fun `weighted hit rate sums tokens instead of averaging per request rates`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))))
        // 第一发：100 个输入 token 全没命中 → 单发 0%。
        inspector.recordUsage("s1", Usage(input = 100, output = 10, cacheRead = 0))
        // 第二发：900 个全命中 → 单发 100%。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))))
        inspector.recordUsage("s1", Usage(input = 0, output = 10, cacheRead = 900))

        val report = inspector.latest.value["s1"]!!
        // 逐发平均是 50%，按 token 加权是 90%。真实花费按 token 走，所以面板必须报加权值 ——
        // 一发只带少量输入的请求不该把整体读数拉走一半。
        // 最近那一发是 100%：它现在只落在趋势样本里（面板不再有「最近命中率」那一行），
        // 与加权 90% 并存断言，正是「两个数不是一个东西」的钉。
        assertEquals(1.0, report.samples.last().rate, 1e-9)
        assertEquals(0.9, report.hitTally!!.weightedRate!!, 1e-9)
        assertEquals(2, report.hitTally!!.ratedRequests)
        assertEquals(900L, report.hitTally!!.cachedTokensSum)
        assertEquals(1_000L, report.hitTally!!.promptTokensSum)
    }

    @Test
    fun `requests without usable usage are counted instead of silently dropped`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))))
        // 提供商这发没回 cache 字段，也没回 input → 拿不到比值。旧实现是**静默不落样本**，
        // 于是图上少点而面板一句不说。
        inspector.recordUsage("s1", Usage(input = 0, output = 5))

        val report = inspector.latest.value["s1"]!!
        // 「没有可用读数」的保证只剩一处：样本池必须是空的（原来还有一条
        // `assertNull(report.cacheHitRate)`，那个字段随面板那行一起删了）。
        assertTrue(report.samples.isEmpty())
        assertEquals("无可用 usage 的那些发必须显形", 1, report.hitTally!!.unratedRequests)
        assertNull("一发都拿不到比值时不能报 0%，那是「全没命中」的意思", report.hitTally!!.weightedRate)

        // 之后拿到一发，两条读数并存。
        inspector.recordUsage("s1", Usage(input = 100, output = 5, cacheRead = 300))
        val later = inspector.latest.value["s1"]!!
        assertEquals(1, later.hitTally!!.ratedRequests)
        assertEquals(1, later.hitTally!!.unratedRequests)
        assertEquals(0.75, later.hitTally!!.weightedRate!!, 1e-9)
    }

    @Test
    fun `a report carries only the readings of its own assembly`() {
        val cold = SectionCacheStats(sections = 9, hits = 0, missesNothingToReuse = 9)
        val warm = SectionCacheStats(sections = 9, hits = 9)
        val inspector = CacheInspector()

        // 读数走 record 的入参而不是「观测器存最近一份」：一个回合里装配会被调好几次
        // （TurnRunner 取 maxSteps / 请求之后又取 model、CompactionRunner 估 token、
        // 界面取上下文窗口），请求**之后**那一次几乎必然全命中 —— 存最近一份就会把
        // 这一发请求的数字改写成与它相反的数（U30 第一轮 B 审 P1）。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"))), cold)
        assertEquals(cold, inspector.latest.value["s1"]!!.sectionCache)

        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))), warm)
        assertEquals(warm, inspector.latest.value["s1"]!!.sectionCache)

        // 不传读数时必须是 null，**不能沿用上一发**：沿用就是把「别的装配」的数字挂到
        // 这一发请求上，与 P1 同一类错，只是换了方向（变异点：改成 `last?.sectionCache`）。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"), user("more"))))
        assertNull(inspector.latest.value["s1"]!!.sectionCache)
    }

    @Test
    fun `inspector forgets a session when it is removed`() {
        val inspector = CacheInspector()
        inspector.record("s1", "gpt-4o", assemble(listOf(user("hi"), assistant("ok"))), SectionCacheStats(sections = 3, hits = 2))
        inspector.recordUsage("s1", Usage(input = 100, output = 1, cacheRead = 400))
        inspector.record("s2", "gpt-4o", assemble(listOf(user("yo"))))

        inspector.remove("s1")

        assertNull(inspector.latest.value["s1"])
        assertEquals("s2", inspector.latest.value["s2"]!!.sessionId)

        // 趋势样本与加权累计现在**只存在于该会话那一份报告里**（不再有并行的 `Map<会话, 累计>`），
        // 所以回收报告就等于回收它们。留一张并行表的话，「会话已关闭、usage 才迟到」
        // 会把那条目重建出来，再没人删。
        inspector.record("s1", "gpt-4o", assemble(listOf(user("reopened"))))
        inspector.recordUsage("s1", Usage(input = 100, output = 1, cacheRead = 400))
        val reopened = inspector.latest.value["s1"]!!
        assertEquals("会话回收后重新打开，趋势样本只能有本次这一条", 1, reopened.samples.size)
        assertEquals(1, reopened.hitTally!!.ratedRequests)
        assertNull("上一发的段落缓存读数不得留给重新打开的会话", reopened.sectionCache)

        // 上一回合的**分段快照**也必须一起摘掉：留着它，重新打开的会话第一发就拿到一个
        // 可比对象，面板显「UNCHANGED / REWRITTEN」而不是「首见」，而那份 `segments` 再没人删。
        // 这条是变异 M8b 补的牙 —— 只断言上面三条时，删掉 `previousBySession.remove()` 全绿。
        assertEquals(
            "回收后重新装配，段落必须算首见（旧快照随会话一起摘走）",
            SegmentChange.NEW,
            reopened.changes[SegmentKind.HISTORY],
        )
    }
}
