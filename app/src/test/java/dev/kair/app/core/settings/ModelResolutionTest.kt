package dev.kair.app.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 模型解析优先级：**本次运行 > 子智能体默认 > 会话级 > 全局默认 > 列表第一条**。
 *
 * 为什么值得单独测：需要模型的地方有五处（系统提示 / 鉴权端点 / 视觉工具可见性 /
 * 上下文窗口 / 子智能体会话）。它们此前各自调一次解析，一旦某处多一层覆盖就会出现
 * 「提示用 A 模型、鉴权走 B、窗口按 C、视觉工具按 D」的错位 —— 接错端点 401/404、
 * 窗口算错提前压缩或撞窗、视觉工具错位让模型调用它用不了的工具。
 * 现在五处共用本解析器，这几条断言就是那个不变量。
 */
class ModelResolutionTest {

    private fun model(id: String, vision: Boolean = false) = SavedModel(
        id = id,
        name = id,
        providerId = "openai",
        model = id,
        isVisionModel = vision,
    )

    private val models = listOf(model("cheap"), model("session-model"), model("app-default"))

    @Test
    fun `run override wins over everything`() {
        val picked = ModelResolution.resolve(
            saved = models,
            runOverrideId = "cheap",
            subagentDefaultId = "session-model",
            sessionOverrideId = "session-model",
            appDefaultId = "app-default",
        )
        assertEquals("cheap", picked?.id)
    }

    @Test
    fun `subagent default wins over session and app default`() {
        val picked = ModelResolution.resolve(
            saved = models,
            subagentDefaultId = "cheap",
            sessionOverrideId = "session-model",
            appDefaultId = "app-default",
        )
        assertEquals("cheap", picked?.id)
    }

    @Test
    fun `session override wins over app default`() {
        val picked = ModelResolution.resolve(
            saved = models,
            sessionOverrideId = "session-model",
            appDefaultId = "app-default",
        )
        assertEquals("session-model", picked?.id)
    }

    @Test
    fun `app default is used when nothing is overridden`() {
        val picked = ModelResolution.resolve(saved = models, appDefaultId = "app-default")
        assertEquals("app-default", picked?.id)
    }

    @Test
    fun `deleted model id falls through instead of failing`() {
        // 用户把某个模型删了：覆盖值还留在设置里。必须静默落到下一层，
        // 否则子智能体一启动就失败，而用户完全看不出为什么。
        val picked = ModelResolution.resolve(
            saved = models,
            runOverrideId = "deleted-model",
            subagentDefaultId = "also-deleted",
            sessionOverrideId = null,
            appDefaultId = "app-default",
        )
        assertEquals("app-default", picked?.id)
    }

    @Test
    fun `blank ids are treated as unset`() {
        // 空串是「跟随上一层」的表示法（UI 里选「跟随会话模型」就是写空串），不能当成 id 去查。
        val picked = ModelResolution.resolve(
            saved = models,
            runOverrideId = "",
            subagentDefaultId = "   ",
            sessionOverrideId = "",
            appDefaultId = "app-default",
        )
        assertEquals("app-default", picked?.id)
    }

    @Test
    fun `first saved model is the last resort`() {
        // 用户还没选过全局默认（activeModelId 为空）：仍然要给出一个能用的模型，
        // 否则首回合会因为「没有可用模型」直接失败。
        val picked = ModelResolution.resolve(saved = models)
        assertEquals("cheap", picked?.id)
    }

    @Test
    fun `no saved models resolves to null`() {
        assertNull(ModelResolution.resolve(saved = emptyList(), appDefaultId = "gone"))
    }

    @Test
    fun `compaction model follows the session unless explicitly overridden`() {
        val session = model("session-model")
        // 空串 / 空白 = 跟随会话模型（UI 的「跟随当前会话模型」写的就是空串）。
        assertEquals("session-model", ModelResolution.resolveCompaction(models, "", session)?.id)
        assertEquals("session-model", ModelResolution.resolveCompaction(models, "   ", session)?.id)
        // 指定了有效模型：压过会话模型。
        assertEquals("cheap", ModelResolution.resolveCompaction(models, "cheap", session)?.id)
        // 指定的模型被删：静默回到会话模型，不跳到全局默认（那会让摘要换端点）。
        assertEquals("session-model", ModelResolution.resolveCompaction(models, "deleted", session)?.id)
        // 会话模型本身也可能已被删除（子智能体固定下来的那个）：原样保留，仍用它。
        val frozen = model("frozen")
        assertEquals("frozen", ModelResolution.resolveCompaction(models, "deleted", frozen)?.id)
        assertNull(ModelResolution.resolveCompaction(emptyList(), "cheap", null))
    }

    @Test
    fun `vision flag comes from the resolved model, not from the session`() {
        // 视觉工具可见性按**生效模型**判定：子智能体被覆盖成非视觉模型时 read_image 必须消失。
        val saved = listOf(model("blind"), model("sighted", vision = true))
        val effective = ModelResolution.resolve(saved = saved, runOverrideId = "blind")
        assertEquals(false, effective?.isVisionModel)
        val inherited = ModelResolution.resolve(saved = saved, sessionOverrideId = "sighted")
        assertEquals(true, inherited?.isVisionModel)
    }
}
