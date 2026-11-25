package dev.kair.app.ui.plugins.builtin

import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.SummarizingContextStrategy
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.settings.CompactionFields
import dev.kair.app.ui.icons.PluginGlyph

/**
 * 贡献内置的摘要式上下文压缩策略。内核只认 [dev.kair.app.core.agent.ContextStrategy] 契约，
 * 换成别的算法只需替换这里的实现，不动内核。
 *
 * 同时声明一块**设置面板**（`showInSettings = true`，双入口）：同一份声明既在插件
 * 详情页、又在「设置」页的插件分区渲染，值经 [AppContainer] 的存储适配器直接读写
 * 应用级设置，因此两处共用同一份数据、禁用本插件时两处一并消失。
 */
class ContextPlugin : Plugin {
    override val id = ID
    override val name = "上下文压缩"
    override val description = "摘要式上下文压缩策略：何时压缩、如何摘要。"

    override fun activate(ctx: PluginContext) {
        ctx.contextStrategy(SummarizingContextStrategy)
        // 主动压缩工具随本插件装载 / 卸载；开关读同一份「自动压缩」设置（关掉时返回错误说明）。
        ctx.tool(CompactContextTool { ctx.settings.getBoolean(CompactionFields.KEY_ENABLED, true) })
        ctx.settingsPanel(
            SettingsPanelSpec(
                id = "settings.context",
                title = "上下文压缩",
                icon = PluginGlyph.Box,
                order = 10,
                // 双入口：本面板在插件详情页与「设置」页同时显示（showInSettings）。
                showInSettings = true,
                fields = listOf(
                    SettingField.Toggle(
                        key = CompactionFields.KEY_ENABLED,
                        labelRes = R.string.settings_compaction_enabled,
                        descriptionRes = R.string.settings_compaction_enabled_desc,
                        default = true,
                    ),
                    SettingField.Choice(
                        key = CompactionFields.KEY_MODEL,
                        labelRes = R.string.settings_compaction_model,
                        descriptionRes = R.string.settings_compaction_model_desc,
                        // 首项是空值的「跟随当前会话模型」：空串即旧行为，不写库也能生效。
                        source = "models_follow_session",
                    ),
                    SettingField.Number(
                        key = CompactionFields.KEY_RESERVE,
                        labelRes = R.string.settings_compaction_reserve,
                        descriptionRes = R.string.settings_compaction_reserve_desc,
                        default = CompactionFields.RESERVE_DEFAULT,
                        min = CompactionFields.RESERVE_MIN,
                        max = CompactionFields.RESERVE_MAX,
                    ),
                    SettingField.Number(
                        key = CompactionFields.KEY_KEEP,
                        labelRes = R.string.settings_compaction_keep,
                        descriptionRes = R.string.settings_compaction_keep_desc,
                        default = CompactionFields.KEEP_DEFAULT,
                        min = CompactionFields.KEEP_MIN,
                        max = CompactionFields.KEEP_MAX,
                    ),
                ),
            ),
        )
    }

    companion object {
        const val ID = "dev.kair.plugin.context"
    }
}
