package dev.kair.app.core.plugin

import dev.kair.app.core.agent.CompactionPlan
import dev.kair.app.core.agent.CompactionSettings
import dev.kair.app.core.agent.CompactionTrigger
import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插件注册表的单例能力（命令后端 / 存储 / 压缩策略）验证：
 * 后注册者胜；卸载按提供者历史栈回滚到上一个提供者；全部移除后回 null
 * （内核回退默认实现）——「社区插件替换原装后端 → 卸载还原」无需重启。
 */
class PluginRegistryTest {

    // ---- 测试桩 ---------------------------------------------------------------

    private class FakeRunner(override val id: String) : CommandRunner {
        override val displayName = id
        override val description = id
        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?) =
            CommandOutput(id, 0)
    }

    private class FakeStorage(private val storageId: String) : StorageBackend {
        override fun listProjects(): List<Project> = emptyList()
        override fun createProject(name: String): Project = throw UnsupportedOperationException()
        override fun deleteProject(id: String) {}
        override fun renameProject(id: String, name: String) {}
        override fun workspaceDir(project: Project): File = throw UnsupportedOperationException()
        override fun listSessions(projectId: String?): List<SessionMeta> = emptyList()
        override fun createSession(projectId: String, name: String, model: String): SessionMeta =
            throw UnsupportedOperationException()
        override fun renameSession(id: String, name: String) {}
        override fun touchSession(id: String) {}
        override fun deleteSession(id: String) {}
        override fun appendMessage(sessionId: String, message: ChatMessage) {}
        override fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {}
        override fun loadMessages(sessionId: String): List<ChatMessage> = emptyList()
        override fun toString(): String = storageId
    }

    private class FakeStrategy(override val id: String) : ContextStrategy {
        override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) = false
        override fun plan(
            messages: List<ChatMessage>,
            settings: CompactionSettings,
            trigger: CompactionTrigger,
        ): CompactionPlan? = null
        override suspend fun summarize(
            model: String,
            config: ProviderConfig,
            plan: CompactionPlan,
            customInstructions: String?,
            reserveTokens: Int,
            sessionId: String?,
        ): String = id
        override fun wrapSummary(plan: CompactionPlan, summary: String): String = id
    }

    /** 按开关注册贡献点的插件。 */
    private class ProviderPlugin(
        override val id: String,
        val runner: CommandRunner? = null,
        val storage: StorageBackend? = null,
        val strategy: ContextStrategy? = null,
        val tool: Tool? = null,
    ) : Plugin {
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            runner?.let { ctx.commandRunner(it) }
            storage?.let { ctx.storage(it) }
            strategy?.let { ctx.contextStrategy(it) }
            tool?.let { ctx.tool(it) }
        }
    }

    private fun toolNamed(name: String): Tool = object : Tool {
        override val name = name
        override val description = name
        override val parameters = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult(name)
    }

    // ---- 命令后端：覆盖与回滚 ---------------------------------------------------

    @Test
    fun `command runner last registered wins and rolls back on uninstall`() {
        val registry = PluginRegistry()
        val a = FakeRunner("a")
        val b = FakeRunner("b")

        registry.install(ProviderPlugin("p1", runner = a))
        assertEquals("a", registry.commandRunner()?.id)

        // 社区插件替换原装后端：后注册者胜。
        registry.install(ProviderPlugin("p2", runner = b))
        assertEquals("b", registry.commandRunner()?.id)

        // 卸载替换者：回滚到上一个提供者，无需重启。
        registry.uninstall("p2")
        assertEquals("a", registry.commandRunner()?.id)

        // 全部移除：null，内核回退内置实现。
        registry.uninstall("p1")
        assertNull(registry.commandRunner())
    }

    @Test
    fun `storage backend rolls back across three providers`() {
        val registry = PluginRegistry()
        val s1 = FakeStorage("s1")
        val s2 = FakeStorage("s2")
        val s3 = FakeStorage("s3")

        registry.install(ProviderPlugin("p1", storage = s1))
        registry.install(ProviderPlugin("p2", storage = s2))
        registry.install(ProviderPlugin("p3", storage = s3))
        assertEquals("s3", registry.storage().toString())

        registry.uninstall("p3")
        assertEquals("s2", registry.storage().toString())
        registry.uninstall("p2")
        assertEquals("s1", registry.storage().toString())
        registry.uninstall("p1")
        assertNull(registry.storage())
    }

    @Test
    fun `context strategy reload replaces own entry and stays last`() {
        val registry = PluginRegistry()
        val a = FakeStrategy("a")
        val b = FakeStrategy("b")
        val a2 = FakeStrategy("a2")

        registry.install(ProviderPlugin("p1", strategy = a))
        registry.install(ProviderPlugin("p2", strategy = b))
        assertEquals("b", registry.contextStrategy()?.id)

        // 重载 p1（宿主语义：先卸载再装新实例，registry.install 对同 id 是 no-op）。
        registry.uninstall("p1")
        registry.install(ProviderPlugin("p1", strategy = a2))
        assertEquals("a2", registry.contextStrategy()?.id)

        // 卸载 p2 不会让旧 a 复活——p1 的最新记录才是当前者。
        registry.uninstall("p2")
        assertEquals("a2", registry.contextStrategy()?.id)
        registry.uninstall("p1")
        assertNull(registry.contextStrategy())
    }

    // ---- 工具与提示段的回收 ------------------------------------------------------

    @Test
    fun `uninstall removes tools and prompt contributors but keeps other plugins`() {
        val registry = PluginRegistry()

        class PromptPlugin(override val id: String, private val order: Int) : Plugin {
            override val name = id
            override val description = ""
            override fun activate(ctx: PluginContext) {
                val pluginId = id
                ctx.systemPrompt(object : dev.kair.app.core.agent.SystemPromptContributor {
                    override val id = "$pluginId.prompt"
                    override val order = this@PromptPlugin.order
                    override fun section(ctx: dev.kair.app.core.agent.SystemPromptContext) = pluginId
                })
                ctx.tool(toolNamed("$pluginId-tool"))
            }
        }

        registry.install(PromptPlugin("p1", order = 60))
        registry.install(PromptPlugin("p2", order = 50))

        assertEquals(2, registry.tools().size)
        // 按 order 升序，同序按注册顺序。
        assertEquals(listOf("p2.prompt", "p1.prompt"), registry.systemPromptContributors().map { it.id })

        registry.uninstall("p1")
        assertEquals(listOf("p2-tool"), registry.tools().map { it.name })
        assertEquals(listOf("p2.prompt"), registry.systemPromptContributors().map { it.id })

        registry.uninstall("p2")
        assertTrue(registry.tools().isEmpty())
        assertTrue(registry.systemPromptContributors().isEmpty())
    }

    @Test
    fun `install same id twice is a no-op`() {
        val registry = PluginRegistry()
        val first = ProviderPlugin("p1", runner = FakeRunner("first"))
        val second = ProviderPlugin("p1", runner = FakeRunner("second"))

        registry.install(first)
        registry.install(second) // 同 id 直接忽略

        assertEquals(1, registry.installed.size)
        assertEquals("first", registry.commandRunner()?.id)
    }

    @Test
    fun `tool override is physical replacement and uninstall drops the override entry`() {
        val registry = PluginRegistry()
        registry.install(ProviderPlugin("holder", tool = toolNamed("bash")))
        assertEquals("bash", registry.tools().single().name)

        // 动态插件同名覆盖（如脚本版 bash）：后注册者胜，同刻物理替换旧条目。
        registry.install(ProviderPlugin("dyn", tool = toolNamed("bash")))
        assertEquals(1, registry.tools().size)

        // 卸载动态插件：其条目被回收。被覆盖的旧条目不在此处复活——内置工具
        // 由宿主 resync（clear 后从 builtinTools() 重建）恢复，注册表不背这个责。
        registry.uninstall("dyn")
        assertTrue(registry.tools().isEmpty())
        assertTrue(registry.installed.none { it.id == "dyn" })
        assertFalse(registry.uninstall("dyn"))
    }

    // ---- 声明式 UI 槽位 ---------------------------------------------------------

    /** 在指定区域声明一个槽位的插件。 */
    private class SlotPlugin(override val id: String, private val area: SlotArea) : Plugin {
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            ctx.slot(
                SlotContribution(
                    id = "$id.slot",
                    area = area,
                    elements = listOf(SlotElement.Button(key = "$id.btn", label = "B")),
                ),
            )
        }
    }

    @Test
    fun `slots register by order, filter by area, and drop on uninstall`() {
        val registry = PluginRegistry()
        registry.install(SlotPlugin("p1", SlotArea.TOP))
        registry.install(SlotPlugin("p2", SlotArea.TOP))
        registry.install(SlotPlugin("p3", SlotArea.BOTTOM))

        assertEquals(3, registry.slots().size)
        assertEquals(2, registry.slots(SlotArea.TOP).size)
        assertEquals(listOf("p3.slot"), registry.slots(SlotArea.BOTTOM).map { it.second.id })

        registry.uninstall("p1")
        assertEquals(listOf("p2.slot"), registry.slots(SlotArea.TOP).map { it.second.id })
    }

    @Test
    fun `re-declaring same slot id replaces the previous declaration`() {
        val registry = PluginRegistry()
        registry.install(object : Plugin {
            override val id = "p1"
            override val name = id
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.slot(SlotContribution("s", SlotArea.TOP, elements = listOf(SlotElement.Text("k1", "first"))))
                ctx.slot(SlotContribution("s", SlotArea.TOP, elements = listOf(SlotElement.Text("k2", "second"))))
            }
        })

        val slots = registry.slots(SlotArea.TOP)
        assertEquals(1, slots.size)
        assertEquals("k2", slots.single().second.elements.single().key)
    }
}
