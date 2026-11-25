package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.agent.BuiltinPromptSections
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext

/**
 * 把内置系统提示段落注册进提示装配（开场白 / 交付路径 / Kair 能力自述 / 工具 / 规则 /
 * 附录 / 工作目录）。内核只按 order 拼接；插件可插入自己的段落（如 order = 6）。
 *
 * 这里不再手写清单：段落统一来自 [BuiltinPromptSections]（与锁定集合、面板内置文案
 * 同源），漏注册不会再靠人眼对齐。
 */
class SystemPromptPlugin : Plugin {
    override val id = "dev.kair.plugin.systemprompt"
    override val name = "系统提示"
    override val description = "内置系统提示段落，按 order 有序拼接。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        BuiltinPromptSections.contributors.forEach { ctx.systemPrompt(it) }
    }
}
