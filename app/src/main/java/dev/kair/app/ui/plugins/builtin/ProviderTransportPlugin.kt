package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.ai.AnthropicProvider
import dev.kair.app.core.ai.GeminiProvider
import dev.kair.app.core.ai.OpenAiProvider
import dev.kair.app.core.ai.OpenAiResponsesProvider
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext

/**
 * 贡献内置的 Provider 传输层（OpenAI Chat Completions / OpenAI Responses /
 * Anthropic Messages / Gemini 原生）。
 * 内核按 `ProviderDef.transportId` 解析；加新协议只需新增插件，不动内核。
 */
class ProviderTransportPlugin : Plugin {
    override val id = "dev.kair.plugin.transport"
    override val name = "传输层"
    override val description = "内置 Provider 传输层：Chat Completions、Responses、Anthropic、Gemini。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        ctx.transport(OpenAiProvider())
        ctx.transport(OpenAiResponsesProvider())
        ctx.transport(AnthropicProvider())
        ctx.transport(GeminiProvider())
    }
}
