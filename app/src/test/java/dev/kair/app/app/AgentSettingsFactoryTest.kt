package dev.kair.app.app

import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.BuiltinPromptSections
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置 → AgentSettings」装配纯函数的接线判据（P4-1 / A2）。
 *
 * 这里守的是**接线**而不是装配算法本身（装配算法由 `SystemPromptTest` 守）：
 * 输入里有的字段必须出现在 [AgentSettings] 的对应位置 —— 历史上出过的退化是
 * 「设置还在、值不再传」（`systemPromptAddendum`），当时全量单测全绿，
 * 因为它们测的是 `SystemPromptBuilder`，不是**装配**。
 *
 * 两种用例形态，故意都写：
 * - **聚合案**（`every setting reaches agent settings`）：一次性报出全部掉线字段，
 *   失败消息就是「哪个设置项在输入里有、在 AgentSettings 里没有」；
 * - **分项案**（每字段一条）：机械变异（注掉工厂里的一处赋值）时，
 *   「注掉哪处 → 哪条红」一目了然 —— P4-1 的机械验证要求「至少 6 条用例变红」，
 *   每条要能归因到具体字段。
 */
class AgentSettingsFactoryTest {

    // ---- 夹具：所有探针值都刻意取「非默认值」 ----

    private val workspace = File("projects/workspaces/p-1")

    private fun snapshotFixture() = SettingsSnapshot(
        maxSteps = 7,
        systemPromptAddendum = "ADDENDUM-PROBE",
    )

    private fun modelFixture() = SavedModel(
        id = "m-1",
        name = "probe",
        providerId = "openai",
        model = "model-probe",
        maxTokens = 1234,
        temperature = 0.42,
        replayThinking = false,
        reasoningLevels = listOf("low"),
        thinkingLevelMap = mapOf("low" to "low-probe"),
        reasoningEffort = "low",
        frequencyPenalty = 0.11,
        presencePenalty = 0.22,
        topP = 0.33,
        seed = 42,
    )

    /**
     * 贡献段落默认给**内置全集**：cwd / addendum / 工具清单这些字段是由内置段落渲染的，
     * 传空清单等于把「输入进没进 context」与「段落渲没渲染」混在一起测。
     */
    private fun build(
        snapshot: SettingsSnapshot = snapshotFixture(),
        model: SavedModel? = modelFixture(),
        containerCwd: String? = null,
        tools: List<Tool> = emptyList(),
        contributors: List<SystemPromptContributor> = BuiltinPromptSections.contributors,
        projectId: String? = null,
    ): AgentAssembly = buildAgentSettings(
        snapshot = snapshot,
        model = model,
        workspace = workspace,
        containerCwd = containerCwd,
        tools = tools,
        contributors = contributors,
        projectId = projectId,
    )

    // ---- 聚合案：失败消息即「掉线字段清单」 ----

    @Test
    fun `every setting reaches agent settings`() {
        val s = build().settings
        val missing = mutableListOf<String>()
        fun arrive(name: String, ok: Boolean) {
            if (!ok) missing += name
        }
        arrive("maxSteps", s.maxSteps == 7)
        arrive("systemPromptAddendum", s.systemPrompt.contains("ADDENDUM-PROBE"))
        arrive("model", s.model == "model-probe")
        arrive("temperature", s.sampling.temperature == 0.42)
        arrive("maxTokens", s.sampling.maxTokens == 1234)
        arrive("replayThinking", !s.replayThinking)
        arrive("frequencyPenalty", s.sampling.frequencyPenalty == 0.11)
        arrive("presencePenalty", s.sampling.presencePenalty == 0.22)
        arrive("topP", s.sampling.topP == 0.33)
        arrive("seed", s.sampling.seed == 42)
        arrive("reasoningEffort", s.sampling.reasoningEffort == "low-probe")
        assertTrue(
            "以下设置项在输入里有、AgentSettings 里没有（接线错）：$missing",
            missing.isEmpty(),
        )
    }

    // ---- 分项案：机械变异（注掉工厂里的一处赋值）时逐条归因 ----

    @Test
    fun `max steps reaches agent settings`() {
        assertEquals(7, build().settings.maxSteps)
    }

    @Test
    fun `addendum reaches agent settings`() {
        assertTrue(
            "systemPromptAddendum 没进系统提示（接线错）",
            build().settings.systemPrompt.contains("ADDENDUM-PROBE"),
        )
    }

    @Test
    fun `model name reaches agent settings`() {
        assertEquals("model-probe", build().settings.model)
    }

    @Test
    fun `temperature reaches agent settings`() {
        assertEquals(0.42, build().settings.sampling.temperature!!, 0.0)
    }

    @Test
    fun `max tokens reaches agent settings`() {
        assertEquals(1234, build().settings.sampling.maxTokens)
    }

    @Test
    fun `replay thinking reaches agent settings`() {
        assertEquals(false, build().settings.replayThinking)
    }

    @Test
    fun `frequency penalty reaches agent settings`() {
        assertEquals(0.11, build().settings.sampling.frequencyPenalty!!, 0.0)
    }

    @Test
    fun `presence penalty reaches agent settings`() {
        assertEquals(0.22, build().settings.sampling.presencePenalty!!, 0.0)
    }

    @Test
    fun `top p reaches agent settings`() {
        assertEquals(0.33, build().settings.sampling.topP!!, 0.0)
    }

    @Test
    fun `seed reaches agent settings`() {
        assertEquals(42, build().settings.sampling.seed)
    }

    @Test
    fun `reasoning effort reaches agent settings`() {
        // reasoningValue() 经 thinkingLevelMap 映射（"low" → "low-probe"）——
        // 映射断链也是接线错的一种。
        assertEquals("low-probe", build().settings.sampling.reasoningEffort)
    }

    // ---- 缓存稳定前缀：同输入必须同字节 ----

    @Test
    fun `same input yields the same bytes`() {
        val a = build()
        val b = build()
        assertEquals(a.settings, b.settings)
        assertArrayEqualsBytes(a.settings.systemPrompt, b.settings.systemPrompt)
    }

    // ---- 容器与非容器双路径 ----

    @Test
    fun `cwd uses the container path when a container runner is present`() {
        val viaContainer = build(containerCwd = "/projects/p-1")
        assertTrue(
            "容器生效时 cwd 必须是容器内工作区真实路径",
            viaContainer.settings.systemPrompt.contains("/projects/p-1"),
        )
        val onHost = build(containerCwd = null)
        assertTrue(
            "无容器时 cwd 必须是宿主工作区路径",
            onHost.settings.systemPrompt.contains(workspace.path),
        )
    }

    // ---- 其余输入的接线（工具 / 贡献段落 / 项目预设 / 缺省语义）----

    @Test
    fun `tools and contributors land in the prompt`() {
        val s = build(
            tools = listOf(probeTool("tool-probe")),
            contributors = BuiltinPromptSections.contributors + listOf(probeContributor("PROBE-SECTION")),
        ).settings
        assertTrue("工具清单没进系统提示", s.systemPrompt.contains("tool-probe"))
        assertTrue("贡献段落没进系统提示", s.systemPrompt.contains("PROBE-SECTION"))
    }

    @Test
    fun `null model uses safe defaults`() {
        val s = build(model = null).settings
        // 模型配置缺省时按「开启」处理：DeepSeek 这类强制回传的服务商才不会 400。
        assertEquals(true, s.replayThinking)
        // 没有模型配置时采样参数一律不发送（null），由请求装配层按需兜底 ——
        // effectiveMaxTokens() 的默认值只作用于「有模型但没填上限」。
        assertEquals(null, s.sampling.maxTokens)
    }

    // ---- 小工具 ----

    private fun assertArrayEqualsBytes(a: String, b: String) {
        assertEquals(
            "同输入不同字节 —— 缓存稳定前缀契约被破坏",
            a.toByteArray().toList(),
            b.toByteArray().toList(),
        )
    }

    private fun probeTool(id: String): Tool = object : Tool {
        override val name = id
        override val description = "probe"

        // 工具段只列 promptSnippet 非空的工具 —— 探针必须带 snippet 才能被渲染出来。
        override val promptSnippet = id
        override val parameters = schemaOf(props())
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    private fun probeContributor(text: String): SystemPromptContributor = object : SystemPromptContributor {
        override val id = "probe"
        override val order = 100
        override fun section(ctx: dev.kair.app.core.agent.SystemPromptContext) = text
    }
}
