package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.app.AppEnvironment
import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.InMemoryPluginSettingsStore
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.plugin.SessionRef
import dev.kair.app.core.plugin.TurnDigest
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.storage.Project
import dev.kair.app.core.subagent.SubagentManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §6 B 层 7–9 + 第 11 条（负责人 2026-11-08 追加）的落点。
 *
 * 关键回归：节拍计数**不读 `AgentEvent.turnCount`**（压缩会重写历史让它倒退）；
 * 取材水位是「会话存档里的用户回合序号」，不是插件的 `total`；
 * `runSubagent` 的返回字符串**只判失败**，绝不写回记忆文件。
 */
class MemoryPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDeps(workspacePath: String) : MemoryDeps {
        private val workspacePath: String = workspacePath
        var messages: List<ChatMessage> = emptyList()
        var active: String? = "s1"
        val notices = mutableMapOf<String, String>()
        val invalidated = mutableListOf<String>()

        override fun loadMessages(sessionId: String): List<ChatMessage> = messages
        override fun activeSessionId(): String? = active
        override fun invalidatePrompt(workspace: String) {
            invalidated += workspace
        }

        override fun recordNotice(workspace: String, text: String) {
            if (text.isBlank()) notices.remove(workspace) else notices[workspace] = text
        }

        override fun clearNotices() = notices.clear()
    }

    private class FakeBridge : AgentBridge {
        val prompts = mutableListOf<String>()

        /** §6 B 层第 10 条：记录调用形状，防「默认 search / 不限时」静默回归。 */
        val types = mutableListOf<String>()
        val timeouts = mutableListOf<Long?>()
        var onRun: ((String) -> String)? = null

        override fun sessions(): List<SessionRef> = emptyList()
        override suspend fun send(sessionId: String, text: String): Boolean = false
        override suspend fun turnsSince(sessionId: String, afterTurn: Int, maxChars: Int): List<TurnDigest> =
            emptyList()

        override suspend fun runSubagent(
            prompt: String,
            type: String,
            timeoutMillis: Long?,
            modelId: String?,
        ): String {
            prompts += prompt
            types += type
            timeouts += timeoutMillis
            return onRun?.invoke(prompt) ?: ""
        }
    }

    private class Fixture(workspace: File) {
        val workspace: File = workspace
        val store = InMemoryPluginSettingsStore()
        val deps = FakeDeps(workspace.path)
        val bridge = FakeBridge()
        val registry = PluginRegistry { store }
        val plugin = MemoryPlugin(deps)

        init {
            registry.setAgentBridge(bridge)
            registry.install(plugin)
        }

        fun memoryFile(): File = MemoryStore.fileOf(workspace.path)

        fun writeMemory(text: String) {
            val file = memoryFile()
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        fun emit(turnCount: Int, sessionId: String = "s1") {
            registry.emitEvent(
                AgentEvent.TurnEnd(
                    sessionId = sessionId,
                    workspace = workspace.path,
                    turnCount = turnCount,
                    stopReason = "stop",
                    tools = emptyList(),
                ),
            )
        }
    }

    private fun user(text: String, systemNote: Boolean = false, compaction: Boolean = false) = ChatMessage(
        role = Role.USER,
        parts = listOf(Part.Text(text)),
        isSystemNote = systemNote,
        isCompaction = compaction,
    )

    private fun assistant(text: String, systemNote: Boolean = false, compaction: Boolean = false) = ChatMessage(
        role = Role.ASSISTANT,
        parts = listOf(Part.Text(text)),
        isSystemNote = systemNote,
        isCompaction = compaction,
    )

    // ---- B7 压缩不影响节拍 ------------------------------------------------

    @Test
    fun `compaction rewrite does not stop the cadence`() {
        val fx = Fixture(tmp.newFolder("ws-cadence"))
        // 事件里的 turnCount 模拟「压缩前后」：10 → 0 大幅回落。插件若偷懒读它，
        // 计数会停在 0，every=5 永远到不了点。
        listOf(10, 3, 2, 1, 0).forEach { fx.emit(it) }

        assertEquals(5, MemoryCadence(fx.store).total)
        assertTrue(MemoryCadence(fx.store).due(MemoryStore.DEFAULT_EVERY))
    }

    // ---- B8 进程重启不丢计数 ----------------------------------------------

    @Test
    fun `cadence survives process restart`() {
        val store = InMemoryPluginSettingsStore()
        repeat(5) { MemoryCadence(store).onTurnEnd() }
        // 新实例 = 模拟进程重启：只从存储恢复，不碰进程内状态。
        assertEquals(5, MemoryCadence(store).total)
        assertTrue(MemoryCadence(store).due(5))
    }

    // ---- B9 两套刻度不混 --------------------------------------------------

    @Test
    fun `digest uses the archive watermark and never the plugin total`() {
        val fx = Fixture(tmp.newFolder("ws-watermark"))
        fx.writeMemory("## 稳定\n- 2026-10-31: 已有记忆")
        fx.deps.messages = listOf(
            user("T1"),
            assistant("A1"),
            user("COMPACTION-SUMMARY", compaction = true),
            user("T2"),
            assistant("A2"),
            user("T3"),
            assistant("A3"),
        )
        // 节拍计数远大于存档回合数：如果实现把 total 当 afterTurn 传，摘录会变成空。
        fx.store.setInt(MemoryPlugin.KEY_TOTAL, 10)
        fx.store.setInt(MemoryPlugin.KEY_WATERMARK + "s1", 1)
        fx.bridge.onRun = { SubagentManager.ERROR_PREFIX + " not editing" }

        runBlocking { fx.plugin.consolidate("s1", fx.workspace.path, 10) }

        val prompt = fx.bridge.prompts.single()
        assertTrue("水位 1 之后的 T2 必须在摘录里", prompt.contains("T2"))
        assertTrue("T3 必须在摘录里", prompt.contains("T3"))
        assertFalse("水位之前的 T1 不应重复进入摘录", prompt.contains("T1"))
        assertFalse("压缩摘要不能被当成用户发言", prompt.contains("COMPACTION-SUMMARY"))
        // 失败不推进水位：下一轮还得从同一个位置取。
        assertEquals(1, fx.store.getInt(MemoryPlugin.KEY_WATERMARK + "s1", 0))
    }

    // ---- B5 整理不变短 → 回滚 ---------------------------------------------

    @Test
    fun `longer consolidation is rolled back to the original bytes`() {
        val fx = Fixture(tmp.newFolder("ws-rollback"))
        val original = "## 稳定\n- 2026-10-31: 原文"
        fx.writeMemory(original)
        fx.bridge.onRun = { prompt ->
            // 子智能体直接编辑文件：这里模拟一次「越整理越长」的失败改写。
            fx.memoryFile().writeText(original + "\n- 2026-11-08: 越写越长的新内容")
            "done"
        }

        runBlocking { fx.plugin.consolidate("s1", fx.workspace.path, 5) }

        assertEquals("整理结果没变短必须回滚到原始字节", original, fx.memoryFile().readText())
        assertTrue(fx.store.getBoolean(MemoryPlugin.KEY_ROLLED_BACK, false))
        assertTrue(fx.deps.invalidated.isNotEmpty())
    }

    // ---- 第 11 条（追加约束）：返回值只判失败，绝不当正文 -------------------

    @Test
    fun `subagent return string is never written as memory content`() {
        val fx = Fixture(tmp.newFolder("ws-output"))
        val original = "## 稳定\n- 2026-10-31: 原文\n- 2026-11-01: 状态"
        fx.writeMemory(original)
        val shorter = "## 稳定\n- 2026-10-31: 原文"
        fx.bridge.onRun = {
            fx.memoryFile().writeText(shorter)
            "SUBAGENT-OUTPUT-不是记忆正文"
        }

        runBlocking { fx.plugin.consolidate("s1", fx.workspace.path, 5) }

        assertEquals("文件内容必须是子智能体实际写下的，而不是 runSubagent 的返回值", shorter, fx.memoryFile().readText())
        assertNotEquals("SUBAGENT-OUTPUT-不是记忆正文", fx.memoryFile().readText())
        assertEquals(5, fx.store.getInt(MemoryPlugin.KEY_LAST_CONSOLIDATED, 0))
    }

    // ---- §6 B 层第 10 条：整理调用形状（B 补，A 的派活指令只写到「9 条」） ----

    @Test
    fun `consolidation calls a coding subagent with an explicit timeout`() {
        val fx = Fixture(tmp.newFolder("ws-call-shape"))
        val original = "## 稳定\n- 2026-10-31: 原文\n- 2026-11-01: 状态"
        fx.writeMemory(original)
        fx.bridge.onRun = {
            fx.memoryFile().writeText("## 稳定\n- 2026-10-31: 原文")
            "done"
        }

        runBlocking { fx.plugin.consolidate("s1", fx.workspace.path, 5) }

        assertEquals("默认 type 是只读 search，整理必须显式传 coding", listOf("coding"), fx.bridge.types)
        assertTrue("timeoutMillis 必须给具体值（null = 不限时）", fx.bridge.timeouts.single() != null)
    }

    @Test
    fun `digest drops assistant system notes and compaction summaries`() {
        val injected = "HOST-INJECTED-SYSTEM-NOTE"
        val compacted = "HOST-COMPACTION-SUMMARY"
        val text = MemoryStore.digest(
            listOf(
                user("T1"),
                assistant("A1"),
                assistant(injected, systemNote = true),
                assistant(compacted, compaction = true),
            ),
            0,
        )
        assertFalse("注入的提示不能进整理取材：$text", injected in text)
        assertFalse("压缩摘要不能进整理取材：$text", compacted in text)
        assertTrue("正常回合必须在：$text", "T1" in text)
    }

    @Test
    fun `empty memory file can be seeded without being rolled back`() {
        val fx = Fixture(tmp.newFolder("ws-seed"))
        fx.writeMemory("") // 文件已存在、内容为空：目录由宿主建好，模拟真实「空文件播种」
        fx.deps.messages = listOf(user("T1"), assistant("A1")) // 无增量时实现会早退，不调模型
        val seeded = "## 稳定\n- 2026-11-08: 第一条"
        fx.bridge.onRun = {
            fx.memoryFile().writeText(seeded)
            "done"
        }

        runBlocking { fx.plugin.consolidate("s1", fx.workspace.path, 5) }

        assertEquals("空文件播种是「必须变短」的唯一例外", seeded, fx.memoryFile().readText())
        assertFalse(fx.store.getBoolean(MemoryPlugin.KEY_ROLLED_BACK, false))
    }

    // ---- 只在活跃会话里整理 -------------------------------------------------

    @Test
    fun `consolidation is deferred when the session is not active`() {
        val fx = Fixture(tmp.newFolder("ws-defer"))
        fx.writeMemory("## 稳定\n- 2026-10-31: 已有记忆")
        fx.store.setInt(MemoryPlugin.KEY_EVERY, 1)
        fx.deps.messages = listOf(user("T1"), assistant("A1"))
        fx.deps.active = "s2" // 另一个会话在前台

        fx.emit(turnCount = 1, sessionId = "s1")
        runBlocking { delay(100) }

        assertTrue("不在活跃会话时必须推迟，不能落进别人的项目", fx.bridge.prompts.isEmpty())
        assertEquals(0, fx.store.getInt(MemoryPlugin.KEY_LAST_CONSOLIDATED, 0))
    }

    @Test
    fun `consolidation runs when the session is active`() {
        val fx = Fixture(tmp.newFolder("ws-active"))
        fx.writeMemory("## 稳定\n- 2026-10-31: 已有记忆")
        fx.store.setInt(MemoryPlugin.KEY_EVERY, 1)
        fx.deps.messages = listOf(user("T1"), assistant("A1"))
        fx.bridge.onRun = { SubagentManager.ERROR_PREFIX + " not editing" }

        fx.emit(turnCount = 1, sessionId = "s1")
        runBlocking {
            withTimeout(5_000) {
                while (fx.bridge.prompts.isEmpty()) delay(10)
            }
        }
        assertEquals(1, fx.bridge.prompts.size)
    }

    // ---- B5 的回滚标记要出现在下一次注入 ------------------------------------

    @Test
    fun `rollback flag is rendered in the next injection`() {
        val fx = Fixture(tmp.newFolder("ws-note"))
        fx.writeMemory("## 稳定\n- 2026-10-31: 原文")
        fx.store.setBoolean(MemoryPlugin.KEY_ROLLED_BACK, true)

        val text = MemoryContributor(fx.store).section(
            dev.kair.app.core.agent.SystemPromptContext(
                cwd = fx.workspace.path,
                tools = emptyList(),
                addendum = "",
                workspace = fx.workspace.path,
            ),
        )
        assertTrue("回滚标记必须在段尾可见", text.contains(MemoryPrompts.rolledBack()))
        assertTrue(text.contains("原文"))
    }

    @Test
    fun `expired entries are reported as a notice`() {
        val fx = Fixture(tmp.newFolder("ws-expired"))
        fx.writeMemory("## 近期\n- 2026-10-01: 过期状态\n- 2026-11-07: 最近状态")
        fx.store.setInt(MemoryPlugin.KEY_RETENTION_DAYS, 30)

        MemoryContributor(fx.store, onNotice = fx.deps::recordNotice)
            .section(
                dev.kair.app.core.agent.SystemPromptContext(
                    cwd = fx.workspace.path,
                    tools = emptyList(),
                    addendum = "",
                    workspace = fx.workspace.path,
                ),
            )

        assertTrue(fx.deps.notices.values.any { it.contains("已隐藏 1 条过期近期条目") })
    }

    // ---- 跨项目查看面板（技术负责人 2026-11-09 加单） -------------------------
    // （顶部快捷入口的用例随 U101 撤下：TOP 槽位停用，入口走面板坞，见 MemoryPlugin。）

    @Test
    fun `memory panel is consolidated into one entry with no separate settings panel`() {
        val container = AppContainer(
            AppEnvironment(storageRoot = tmp.newFolder("kair-mem"), nativeLibraryDir = "/lib", primaryAbi = "arm64-v8a"),
            SettingsStore(FakeSharedPreferences()),
        )
        val registry = PluginRegistry { InMemoryPluginSettingsStore() }
        registry.setAgentBridge(FakeBridge())
        registry.install(MemoryPlugin(container))
        assertTrue("顶栏入口直达的合并面板必须注册", registry.panels().any { it.id == MemoryPlugin.PANEL_ID })
        assertTrue(
            "记忆设置已并入合并面板，不再单独注册 settings.memory（否则插件页两张卡 = 两个面板）",
            registry.settingsPanels().none { it.second.id == "settings.memory" },
        )
    }

    @Test
    fun `default project selection prefers last selected then first then null`() {
        val a = Project(id = "a", name = "A")
        val b = Project(id = "b", name = "B")
        assertEquals("a", defaultMemoryProjectId(listOf(a, b), "a"))
        assertEquals("选中项被删时落第一个", "a", defaultMemoryProjectId(listOf(a, b), "gone"))
        assertEquals("a", defaultMemoryProjectId(listOf(a, b), ""))
        assertNull(defaultMemoryProjectId(emptyList(), "a"))
    }
}
