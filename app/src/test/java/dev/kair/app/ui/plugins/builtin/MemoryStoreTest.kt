package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.InMemoryPluginSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * §6 B 层 1–6 的落点（纯函数层）。
 *
 * 这些用例描述**行为**而不是实现：输入 → 归类 / 预算 / 过期 / 确定性，改实现时若
 * 破坏「只丢近期最旧」「过期只隐藏」「同输入同字节」，必须在这里红。
 */
class MemoryStoreTest {

    private val now = LocalDate.parse("2026-11-08")

    private fun entry(date: String?, text: String, line: Int = 1) =
        MemoryStore.Entry(date?.let { LocalDate.parse(it) }, text, line)

    private fun doc(stable: List<MemoryStore.Entry> = emptyList(), recent: List<MemoryStore.Entry> = emptyList()) =
        MemoryStore.Doc(stable, recent)

    private fun message(
        role: Role,
        text: String,
        systemNote: Boolean = false,
        compaction: Boolean = false,
    ) = ChatMessage(
        role = role,
        parts = listOf(Part.Text(text)),
        isSystemNote = systemNote,
        isCompaction = compaction,
    )

    // ---- B1 解析 ---------------------------------------------------------

    @Test
    fun `parse classifies lines deterministically and loses no content`() {
        val text = listOf(
            "# 旧标题也会被当成内容",
            "",
            "## 稳定",
            "- 2026-10-31: 用户要求中文提交信息",
            "- 没有日期的稳定行",
            "- 2026-13-40: 非法日期行",
            "",
            "## 近期",
            "- 2026-11-08: main 在 2ae7938",
            "- 2026-11-07: 重复行",
            "- 2026-11-07: 重复行",
        ).joinToString("\r\n")

        val parsed = MemoryStore.parse(text)

        assertEquals(4, parsed.stable.size) // 旧标题 + 带日期 + 无日期 + 非法日期
        assertEquals(3, parsed.recent.size)
        // 无日期 / 非法日期都算格式不合法，但内容原样保留。
        assertEquals(3, parsed.invalidCount)
        assertTrue(parsed.stable.any { it.text == "用户要求中文提交信息" })
        assertTrue(parsed.stable.any { it.text == "- 没有日期的稳定行" })
        assertTrue(parsed.stable.any { it.text == "- 2026-13-40: 非法日期行" })
        // 重复行不去重（解析不丢内容，交给整理合并）。
        assertEquals(2, parsed.recent.count { it.text == "重复行" })
        assertNull(parsed.stable.first().date)
    }

    // ---- B2 预算 / 截断 ---------------------------------------------------

    @Test
    fun `budget truncation drops the oldest recent first and annotates the count`() {
        val oldest = entry("2026-10-20", "最旧的近期条目", 10)
        val middle = entry("2026-11-01", "中间的近期条目", 11)
        val newest = entry("2026-11-08", "最新的近期条目", 12)
        val stable = entry("2026-10-01", "稳定条目不许先丢", 1)
        val memory = doc(listOf(stable), listOf(oldest, middle, newest))

        // 找到「恰好丢 1 条」的预算：这样能同时钉住丢弃顺序与标注条数。
        val (budget, rendered) = (60..320).asSequence()
            .map { it to MemoryStore.render(memory, now, MemoryStore.MemorySettings(maxChars = it)) }
            .first { (_, r) -> r.dropped == 1 }

        assertEquals(1, rendered.dropped)
        assertTrue("渲染正文必须 ≤ 预算（实测 ${MemoryStore.weightedChars(rendered.text)} / $budget）",
            MemoryStore.weightedChars(rendered.text) <= budget)
        assertTrue("标注条数必须与实际丢弃一致", rendered.text.contains("另有 1 条未注入"))
        assertFalse("最旧的近期条目必须先被丢掉", rendered.text.contains("最旧的近期条目"))
        assertTrue(rendered.text.contains("中间的近期条目"))
        assertTrue(rendered.text.contains("最新的近期条目"))
        assertTrue("丢完近期才允许动稳定段", rendered.text.contains("稳定条目不许先丢"))
    }

    @Test
    fun `no truncation means no annotation`() {
        val memory = doc(recent = listOf(entry("2026-11-08", "只有一条", 1)))
        val rendered = MemoryStore.render(memory, now, MemoryStore.MemorySettings(maxChars = 600))
        assertEquals(0, rendered.dropped)
        assertFalse(rendered.text.contains("未注入"))
        assertTrue(MemoryStore.weightedChars(rendered.text) <= 600)
    }

    // ---- B3 过期 ---------------------------------------------------------

    @Test
    fun `expired recent entries are hidden while stable entries survive`() {
        val memory = doc(
            stable = listOf(entry("2026-01-01", "稳定段永不过期", 1)),
            recent = listOf(
                entry("2026-10-01", "38 天前的状态", 2),
                entry("2026-11-07", "昨天的状态", 3),
            ),
        )
        val rendered = MemoryStore.render(memory, now, MemoryStore.MemorySettings(maxChars = 600, retentionDays = 30))

        assertEquals(1, rendered.expired)
        assertFalse(rendered.text.contains("38 天前的状态"))
        assertTrue(rendered.text.contains("昨天的状态"))
        assertTrue(rendered.text.contains("稳定段永不过期"))
    }

    // ---- B4 确定性 -------------------------------------------------------

    @Test
    fun `two renders of the same input are byte identical`() {
        val raw = """
            ## 稳定
            - 2026-10-31: 偏好中文
            ## 近期
            - 2026-11-08: 状态一
            - 2026-11-08: 状态二
        """.trimIndent()
        val settings = MemoryStore.MemorySettings(maxChars = 500)
        val first = MemoryStore.render(MemoryStore.parse(raw), now, settings)
        val second = MemoryStore.render(MemoryStore.parse(raw), now, settings)
        assertEquals(first.text, second.text)
    }

    // ---- B5 回滚（纯函数部分） -------------------------------------------

    @Test
    fun `consolidation is rejected when it does not shrink`() {
        val before = "## 稳定\n- 2026-10-31: 旧内容"
        assertFalse(MemoryStore.acceptConsolidation(before, before))
        assertFalse(MemoryStore.acceptConsolidation(before, "$before\n- 2026-11-08: 新增"))
        assertTrue(MemoryStore.acceptConsolidation(before, "## 稳定\n- 2026-10-31: 旧"))
    }

    @Test
    fun `restore writes the original bytes back`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kair-memory-restore-" + System.nanoTime())
        dir.mkdirs()
        val file = File(dir, "memory.md")
        val original = "## 稳定\r\n- 2026-10-31: 原文"
        file.writeText(original)
        file.writeText("被改坏了")
        MemoryStore.restore(file, original, existed = true)
        assertEquals(original, file.readText())
        file.delete()
        dir.delete()
    }

    @Test
    fun `ensureTemplate creates headings once and never overwrites content`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kair-memory-template-" + System.nanoTime())
        dir.mkdirs()
        val workspace = dir.absolutePath
        // 新项目：建出两段标题的空模板，解析后仍是空文档（注入正文不变）。
        assertTrue(MemoryStore.ensureTemplate(workspace))
        val file = MemoryStore.fileOf(workspace)
        assertEquals(MemoryPrompts.TEMPLATE, file.readText())
        val parsed = MemoryStore.parse(file.readText())
        assertTrue(parsed.stable.isEmpty())
        assertTrue(parsed.recent.isEmpty())
        // 已有内容：第二次调用一个字节都不动。
        file.writeText("## 稳定\n- 2026-11-19: 已有")
        assertFalse(MemoryStore.ensureTemplate(workspace))
        assertEquals("## 稳定\n- 2026-11-19: 已有", file.readText())
        file.delete()
        dir.delete()
    }

    // ---- B6 旧格式兼容 ---------------------------------------------------

    @Test
    fun `legacy file without headers or dates lands in stable and is injected`() {
        val raw = "用户偏好中文\n仓库在 F:\\qoder-kair\n"
        val parsed = MemoryStore.parse(raw)
        assertEquals(2, parsed.stable.size)
        assertEquals(0, parsed.recent.size)
        assertEquals(2, parsed.invalidCount)
        val rendered = MemoryStore.render(parsed, now, MemoryStore.MemorySettings(maxChars = 600))
        assertTrue(rendered.text.contains("用户偏好中文"))
        assertTrue(rendered.text.contains("仓库在 F:\\qoder-kair"))
        assertEquals(0, rendered.dropped)
    }

    // ---- 取材：两套刻度不混（B9 的纯函数部分） -----------------------------

    @Test
    fun `digest skips system notes and compaction summaries`() {
        val messages = listOf(
            message(Role.USER, "第一回合"),
            message(Role.ASSISTANT, "回答一"),
            message(Role.USER, "压缩摘要不应该出现", compaction = true),
            message(Role.USER, "系统提示不应该出现", systemNote = true),
            message(Role.USER, "第二回合"),
            message(Role.ASSISTANT, "回答二"),
        )
        // 用户回合序号只数真正的用户消息（压缩摘要 / system note 都不算）。
        assertEquals(2, MemoryStore.eligibleTurnCount(messages))

        val afterFirst = MemoryStore.digest(messages, afterTurn = 1, maxChars = 2_000)
        assertTrue(afterFirst.contains("第二回合"))
        assertTrue(afterFirst.contains("回答二"))
        assertFalse(afterFirst.contains("压缩摘要不应该出现"))
        assertFalse(afterFirst.contains("系统提示不应该出现"))

        assertTrue(MemoryStore.digest(messages, afterTurn = 2, maxChars = 2_000).isEmpty())
    }

    @Test
    fun `digest budget keeps the most recent turns`() {
        val messages = (1..6).flatMap { turn ->
            listOf(
                message(Role.USER, "第$turn 回合" + "字".repeat(200)),
                message(Role.ASSISTANT, "回答$turn" + "字".repeat(200)),
            )
        }
        val digest = MemoryStore.digest(messages, afterTurn = 0, maxChars = 900)
        assertTrue("预算内至少要能留下最近一回合", digest.contains("第6 回合"))
        assertFalse("最早的回合应被预算挤掉", digest.contains("第1 回合"))
        assertTrue(
            "总长必须受预算约束（实测 ${MemoryStore.weightedChars(digest)}）",
            MemoryStore.weightedChars(digest) <= 900 + 40, // 头部说明行不计入预算
        )
    }

    @Test
    fun `settings keys keep the legacy memory prefix`() {
        val store = InMemoryPluginSettingsStore()
        store.setInt(MemoryPlugin.KEY_MAX_CHARS, 800)
        store.setInt(MemoryPlugin.KEY_RETENTION_DAYS, 7)
        store.setInt(MemoryPlugin.KEY_MAX_LINES, 20)
        store.setInt(MemoryPlugin.KEY_EVERY, 3)
        store.setBoolean(MemoryPlugin.KEY_AUTO, false)
        val snapshot = MemoryStore.settingsOf(store)
        assertEquals(800, snapshot.maxChars)
        assertEquals(7, snapshot.retentionDays)
        assertEquals(20, snapshot.maxLines)
        assertEquals(3, snapshot.every)
        assertFalse(snapshot.auto)
    }
}
