package dev.kair.app.core.agent

import dev.kair.app.core.storage.Delivery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置提示段的用户覆写：**身份可改，要害锁定**。
 *
 * 背景：开场白里写死了 "coding assistant"，此前用户只能往后追加（Addendum 段），改不掉
 * 已有那句 —— 想把 Kair 当翻译/写作助手用时，模型会坚持自己只会编程。
 *
 * 但路径 / 容器 / 技能 / 工具清单这类段落不能开：它们一旦被改错，后果是**静默**的
 * （写入落到 chroot 里的假路径、模型调用不存在的工具、清单与 schema 不一致），
 * 用户很难看出原因。所以锁在 `SystemPromptBuilder` 内核里，而不是只在 UI 上不给入口。
 */
class PromptOverrideTest {

    private class Fixed(private val text: String, override val id: String) : SystemPromptContributor {
        override val order = 1
        override fun section(ctx: SystemPromptContext): String = text
    }

    private val ctx = SystemPromptContext(cwd = "/host/ws", tools = emptyList(), addendum = "", workspace = "/host/ws")

    private fun build(vararg contributors: SystemPromptContributor): String =
        SystemPromptBuilder.build(ctx, contributors.toList())

    @After
    fun tearDown() {
        SystemPromptBuilder.setOverridesProvider { _, _ -> null }
    }

    @Test
    fun `preamble can be replaced by user text`() {
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id == PreambleContributor.id) SystemPromptBuilder.SectionOverride(text = "You are a translator.")
            else null
        }
        val prompt = build(PreambleContributor)
        assertEquals("You are a translator.", prompt)
        assertFalse("默认身份不得残留", prompt.contains("coding assistant"))
    }

    @Test
    fun `locked sections ignore overrides`() {
        // 即便有人（插件 / 别的入口）注入了替换文案，内核也必须当作没看见。
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id in SystemPromptBuilder.LockedSectionIds) {
                SystemPromptBuilder.SectionOverride(text = "garbage")
            } else null
        }
        val prompt = build(CapabilitiesContributor, CwdContributor)
        assertTrue("能力自述不得被替换", prompt.contains("Kair runs on the user's Android device"))
        assertFalse("覆盖文案不得生效", prompt.contains("garbage"))
    }

    @Test
    fun `locked id cannot be smuggled in through a plugin contributor`() {
        // 插件若用内置 id 贡献段落，同样受锁定约束：否则"换个 provider 就能改要害"。
        val fake = Fixed("plugin text", CapabilitiesContributor.id)
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id == CapabilitiesContributor.id) SystemPromptBuilder.SectionOverride(text = "hijacked") else null
        }
        val prompt = build(fake)
        assertEquals("plugin text", prompt)
    }

    @Test
    fun `non builtin sections are not covered by the preset rule`() {
        // 项目预设只替换身份段；插件自己的段落照常贡献（预设不接管它们）。
        val memory = Fixed("# memory", "dev.kair.prompt.memory")
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id == PreambleContributor.id) SystemPromptBuilder.SectionOverride(text = "IDENTITY") else null
        }
        assertEquals("# memory", build(memory))
    }

    @Test
    fun `changing the override invalidates the cached section`() {
        // 段落缓存必须把覆写当输入：否则用户改完下一回合看不到变化（会被当成坏掉）。
        var override: SystemPromptBuilder.SectionOverride? = null
        SystemPromptBuilder.setOverridesProvider { _, _ -> override }
        val c = Fixed("default text", "test.override.cache")
        assertEquals("default text", build(c))
        override = SystemPromptBuilder.SectionOverride(text = "custom text")
        assertEquals("custom text", build(c))
        override = null
        assertEquals("default text", build(c))
    }

    @Test
    fun `empty override text falls back to the contributor`() {
        // 设置页「恢复默认」写的是空串；空串不能被当成"把段落清空"。
        SystemPromptBuilder.setOverridesProvider { _, _ -> SystemPromptBuilder.SectionOverride(text = "   ") }
        val c = Fixed("default text", "test.override.blank")
        assertEquals("default text", build(c))
    }

    @Test
    fun `override does not disturb other sections`() {
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id == PreambleContributor.id) SystemPromptBuilder.SectionOverride(text = "IDENTITY") else null
        }
        val prompt = build(PreambleContributor, CwdContributor)
        assertTrue(prompt.startsWith("IDENTITY"))
        assertTrue("其它段落照常贡献", prompt.length > "IDENTITY".length)
    }

    @Test
    fun `delivery path survives an identity preset`() {
        // 交付路径必须留在锁定段：纯聊天项目换掉身份后，用户仍要能拿到输出文件。
        SystemPromptBuilder.setOverridesProvider { id, _ ->
            if (id == PreambleContributor.id) {
                SystemPromptBuilder.SectionOverride(text = "You are a chat companion.")
            } else null
        }
        val prompt = build(PreambleContributor, DeliveryContributor)
        assertTrue(prompt.contains("chat companion"))
        assertTrue("交付路径不得随身份一起消失", prompt.contains(Delivery.PATH))
    }

    @Test
    fun `project bound preset only affects that project`() {
        // 项目级身份预设：绑定「聊天」项目后，它的身份与其它项目互不干扰。
        SystemPromptBuilder.setOverridesProvider { id, projectId ->
            if (id == PreambleContributor.id && projectId == "chat") {
                SystemPromptBuilder.SectionOverride(text = "You are a chat companion.")
            } else null
        }
        assertEquals(
            "You are a chat companion.",
            SystemPromptBuilder.build(ctx, listOf(PreambleContributor), projectId = "chat"),
        )
        assertTrue(
            "未绑定预设的项目仍用内置身份",
            SystemPromptBuilder.build(ctx, listOf(PreambleContributor), projectId = "code")
                .contains("coding assistant"),
        )
    }

    @Test
    fun `project id keeps separate cache slots`() {
        // 项目 id 必须进缓存键：否则第二个项目会复用第一个项目的正文（切换项目身份不变）。
        SystemPromptBuilder.setOverridesProvider { id, projectId ->
            if (id == PreambleContributor.id) {
                SystemPromptBuilder.SectionOverride(text = "identity-$projectId")
            } else null
        }
        assertEquals("identity-a", SystemPromptBuilder.build(ctx, listOf(PreambleContributor), "a"))
        assertEquals("identity-b", SystemPromptBuilder.build(ctx, listOf(PreambleContributor), "b"))
        assertEquals("identity-a", SystemPromptBuilder.build(ctx, listOf(PreambleContributor), "a"))
    }

    @Test
    fun `default section text is available for the panel preview`() {
        // 「查看内置开场白」要能拿到默认文案；工具清单这种动态段落允许为空。
        val preamble = SystemPromptBuilder.defaultSectionText(PreambleContributor.id)
        assertTrue(preamble.contains("coding assistant"))
        assertEquals("", SystemPromptBuilder.defaultSectionText("not.a.section"))
        assertNull(SystemPromptBuilder.overrideOf(PreambleContributor.id))
    }
}
