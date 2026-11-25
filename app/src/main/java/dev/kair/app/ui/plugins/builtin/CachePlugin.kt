package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.icons.PluginGlyph

/**
 * 缓存与诊断面板：展示最近一次请求的缓存前缀分段、稳定性判定、观测到的命中率，
 * 以及失败可见的四类诊断事件。
 * 面板只读内核的 [dev.kair.app.core.agent.CacheInspector] 与
 * [dev.kair.app.core.agent.Diagnostics]，不参与请求装配。
 */
class CachePlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.cache"
    override val name = "缓存与诊断"
    override val description = "缓存稳定前缀、命中率与失败诊断（段落缺席 / 装载 / 投递）的检查面板。"

    override fun activate(ctx: PluginContext) {
        ctx.panel(
            PanelContribution(
                id = "panels.cache",
                title = "缓存与诊断",
                icon = PluginGlyph.Database,
                order = 20,
                content = { CachePanel(container) },
            ),
        )
    }
}
