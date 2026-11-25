package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextStrategyTest {

    /** 只用于验证贡献点接线的空实现。 */
    private class FakeStrategy(override val id: String) : ContextStrategy {
        override fun shouldCompact(
            contextTokens: Int,
            contextWindow: Int,
            settings: CompactionSettings,
        ): Boolean = false

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
        ): String = ""

        override fun wrapSummary(plan: CompactionPlan, summary: String): String = summary
    }

    private fun plugin(id: String, strategy: ContextStrategy) = object : Plugin {
        override val id = id
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            ctx.contextStrategy(strategy)
        }
    }

    @Test
    fun `no plugin means no context strategy`() {
        assertNull(PluginRegistry().contextStrategy())
    }

    @Test
    fun `a plugin can contribute the context strategy`() {
        val registry = PluginRegistry().install(plugin("p1", FakeStrategy("dev.test.one")))
        assertEquals("dev.test.one", registry.contextStrategy()?.id)
    }

    @Test
    fun `a later strategy overrides the earlier one`() {
        val registry = PluginRegistry()
            .install(plugin("p1", FakeStrategy("first")))
            .install(plugin("p2", FakeStrategy("second")))
        assertEquals("second", registry.contextStrategy()?.id)
    }

    @Test
    fun `builtin strategy wraps the summary with file operations`() {
        val plan = CompactionPlan(
            summarized = listOf(
                ChatMessage(
                    role = Role.ASSISTANT,
                    parts = listOf(Part.ToolCall(id = "c1", name = "read", arguments = """{"path":"a.kt"}""")),
                ),
            ),
            kept = emptyList(),
            previousSummary = null,
            tokensBefore = 100,
        )

        val wrapped = SummarizingContextStrategy.wrapSummary(plan, "BODY")

        assertTrue(wrapped.startsWith(Compaction.COMPACTION_SUMMARY_PREFIX))
        assertTrue(wrapped.contains("BODY"))
        assertTrue(wrapped.contains("<read-files>"))
        assertTrue(wrapped.contains("a.kt"))
    }
}
