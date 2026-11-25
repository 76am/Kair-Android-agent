package dev.kair.app.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子智能体思考强度覆盖的纯逻辑：设置页只保存一个档位字符串，真正生效在模型解析之后。
 * 这里钉住「只改解析结果、不回写模型配置」与四种取值语义。
 */
class SubagentReasoningTest {

    private fun model(
        levels: List<String> = listOf("low", "high"),
        effort: String? = "low",
        map: Map<String, String> = mapOf("high" to "high-probe"),
        reasoning: Boolean = true,
    ) = SavedModel(
        id = "m1",
        name = "Model",
        providerId = "openai",
        model = "model",
        isReasoningModel = reasoning,
        reasoningLevels = levels,
        thinkingLevelMap = map,
        reasoningEffort = effort,
    )

    @Test
    fun `空覆盖跟随模型自己的档位`() {
        val saved = model()
        assertSame(saved, saved.withReasoningOverride(""))
        assertSame(saved, saved.withReasoningOverride(null))
        assertEquals("low", saved.withReasoningOverride("").reasoningValue())
    }

    @Test
    fun `自动覆盖不发送思考参数`() {
        val saved = model()
        val applied = saved.withReasoningOverride(ReasoningAuto)
        assertEquals(ReasoningAuto, applied.reasoningEffort)
        assertNull("自动 = 不发送思考参数", applied.reasoningValue())
        assertEquals("原模型配置不被改写", "low", saved.reasoningEffort)
    }

    @Test
    fun `支持档位覆盖后仍走映射表`() {
        val saved = model()
        val applied = saved.withReasoningOverride("high")
        assertEquals("high", applied.reasoningEffort)
        assertEquals("high-probe", applied.reasoningValue())
        assertEquals("原模型配置不被改写", "low", saved.reasoningEffort)
    }

    @Test
    fun `模型换掉后不支持的档位忽略覆盖`() {
        val saved = model(levels = listOf("low", "medium"))
        assertSame(saved, saved.withReasoningOverride("max"))
        assertEquals("low", saved.withReasoningOverride("max").reasoningValue())
    }

    @Test
    fun `非推理模型忽略覆盖`() {
        val saved = model(reasoning = false)
        assertSame(saved, saved.withReasoningOverride("high"))
        assertSame(saved, saved.withReasoningOverride(ReasoningAuto))
    }

    @Test
    fun `换模型时只有仍适用的覆盖才保留`() {
        val reasoning = model(levels = listOf("low", "high"))
        assertTrue("跟随模型配置始终可保留", reasoning.supportsReasoningOverride(""))
        assertTrue(reasoning.supportsReasoningOverride(ReasoningAuto))
        assertTrue(reasoning.supportsReasoningOverride("high"))
        assertFalse("新模型不支持的档位应被清掉", reasoning.supportsReasoningOverride("max"))
        assertFalse("非推理模型不保留任何覆盖", model(reasoning = false).supportsReasoningOverride(""))
    }
}
