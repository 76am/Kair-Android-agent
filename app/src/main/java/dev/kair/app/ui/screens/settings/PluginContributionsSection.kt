package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.components.pluginDisplayText
import dev.kair.app.ui.plugins.PluginSettingsFields

/**
 * 插件贡献的设置项与设置分区：声明式双入口，禁用对应插件时两处一并消失。
 *
 * U106 起嵌在「04 插件」的黄色虚线框里，来源与设置项本体分成两行：
 * 上面是「来自 X · 设置面板」的出处标签，下面是插件声明的标题与字段。
 */
@Composable
internal fun PluginContributionsSection(container: AppContainer) {
    // 插件贡献的设置项（声明式，双入口）：插件在 SettingsPanelSpec 上声明
    // showInSettings=true，宿主就用嵌入框把同一份声明渲染到这里，与插件详情页
    // 共用同一份数据；禁用该插件时两处一并消失（不会留下改了也不生效的死开关）。
    // 组合期直读（U46）：注册表是 Compose 状态，remember 住快照会在启停后陈旧。
    val inSettingsPanels = container.plugins.settingsPanels()
        .filter { it.second.showInSettings }
    inSettingsPanels.forEach { (pluginId, spec) ->
        PluginSettingsEmbed(container = container, pluginId = pluginId, spec = spec)
    }

    // Sections contributed by plugins, appended in their declared order.
    val pluginSections = container.plugins.settingsSections()
    pluginSections.forEach { section ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            section.content()
        }
    }
}

/** 单个插件设置面板的嵌入框：虚线描边表示「这份内容由插件贡献」。 */
@Composable
private fun PluginSettingsEmbed(
    container: AppContainer,
    pluginId: String,
    spec: SettingsPanelSpec,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                shape,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(R.string.settings_plugin_embed_from, pluginId),
            style = MonoLabel(),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            pluginDisplayText(spec.title, spec.titleRes),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        PluginSettingsFields(
            container = container,
            store = container.pluginSettings(pluginId),
            spec = spec,
            modifier = Modifier.fillMaxWidth(),
            showTitle = false,
        )
    }
}
