package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.HookBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模型主动压缩的判定语义（U58 ①）。
 *
 * 只锁四件事，全部是可数事实（`planTriggers` / `summarizeCalls` / `replaced`）：
 * 1. 有标记时**跳过阈值**强制压一次；
 * 2. 标记只被消费一次，不会让每一步都重复压；
 * 3. 用户关掉自动压缩时，请求不生效**且标记被清掉**（关掉再打开不会隔空补压）；
 * 4. 手动压缩同样会清掉残留标记。
 *
 * 阈值判定固定为 `false`：这样「压了」只可能来自标记，排除了「刚好超阈值」的巧合。
 */
class CompactionRequestTest {

    private class Stage {
        val messages = mutableListOf<ChatMessage>()
        val replaced = mutableListOf<List<ChatMessage>>()
        val planTriggers = mutableListOf<CompactionTrigger>()
        var summarizeCalls = 0
        var settingsEnabled = true

        val runner = CompactionRunner(
            CompactionRunner.Host(
                sessionId = "s1",
                workspace = File("."),
                assembly = { AgentAssembly(AgentSettings(model = "m", systemPrompt = "sp", maxSteps = 1)) },
                compactionSettings = {
                    CompactionSettings(enabled = settingsEnabled, reserveTokens = 1_000, keepRecentTokens = 1)
                },
                contextStrategy = { strategy },
                contextWindow = { 4_000 },
                target = {
                    CompactionTarget(
                        model = "m",
                        config = ProviderConfig(providerId = "p", transportId = "t", baseUrl = "u", apiKey = "k"),
                    )
                },
                hooks = HookBus(),
                messages = { messages },
                replaceHistory = { replaced += it; true },
                demotedMessages = { messages },
                markCompacting = { },
                clearCompacting = { },
                onEvent = { },
                turnCount = { 1 },
                diagnostics = Diagnostics(),
                onCompacted = { _, _ -> },
            ),
        )

        init {
            messages += ChatMessage(role = Role.USER, parts = listOf(Part.Text("旧问题")))
            messages += ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text("旧回答")))
            messages += ChatMessage(role = Role.USER, parts = listOf(Part.Text("新问题")))
        }

        val strategy: ContextStrategy = object : ContextStrategy {
            override val id: String = "test.request"

            // 永远不按阈值压：压缩只可能来自「模型主动请求」。
            override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) = false

            override fun plan(
                messages: List<ChatMessage>,
                settings: CompactionSettings,
                trigger: CompactionTrigger,
            ): CompactionPlan {
                planTriggers += trigger
                return CompactionPlan(
                    summarized = messages.dropLast(1),
                    kept = messages.takeLast(1),
                    previousSummary = null,
                    tokensBefore = 1,
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
                return "摘要"
            }

            override fun wrapSummary(plan: CompactionPlan, summary: String): String = "SUMMARY: $summary"
        }

        fun compactIfNeeded(): Boolean = runBlocking { runner.compactIfNeeded() }
    }

    @Test
    fun `模型请求后跳过阈值强制压缩一次`() {
        val st = Stage()
        st.runner.requestCompaction()

        assertTrue("有标记时必须压，即使阈值判定为 false", st.compactIfNeeded())
        assertEquals("只压一次", 1, st.replaced.size)
        assertEquals("强制压缩仍走 PRESSURE（不走有损兜底）", CompactionTrigger.PRESSURE, st.planTriggers.single())
    }

    @Test
    fun `标记只消费一次，下一步不会再压`() {
        val st = Stage()
        st.runner.requestCompaction()

        assertTrue(st.compactIfNeeded())
        assertFalse("标记已被消费，阈值又不满足，不该再压", st.compactIfNeeded())
        assertEquals(1, st.summarizeCalls)
    }

    @Test
    fun `关闭自动压缩时请求不生效，且标记不会留到重新打开之后`() {
        val st = Stage()
        st.settingsEnabled = false
        st.runner.requestCompaction()

        assertFalse("用户关了自动压缩，主动压缩也不生效", st.compactIfNeeded())
        assertEquals(0, st.replaced.size)

        st.settingsEnabled = true
        assertFalse("关闭期间消费掉标记：重新打开后不该隔空补压一次", st.compactIfNeeded())
        assertEquals(0, st.replaced.size)
    }

    @Test
    fun `手动压缩会清掉残留标记`() {
        val st = Stage()
        st.runner.requestCompaction()

        assertTrue("手动压缩不看阈值", runBlocking { st.runner.compactNow(st.strategy) })
        assertFalse("手动压完之后，残留标记必须失效", st.compactIfNeeded())
    }
}
