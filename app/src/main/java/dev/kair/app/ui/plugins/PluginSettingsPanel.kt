package dev.kair.app.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.components.SettingFieldRow
import dev.kair.app.ui.components.pluginDisplayText

/**
 * 声明式设置面板的**可嵌入渲染体**：标题 + 字段列表，不含滚动容器。
 * 「设置」页把它放进已有的滚动列里直接摆放（`SettingsPanelSpec.showInSettings`），
 * 避免与外层 `verticalScroll` 嵌套。
 */
@Composable
fun PluginSettingsFields(
    container: AppContainer,
    store: PluginSettingsStore,
    spec: SettingsPanelSpec,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    // 值被「按钮动作」等外部逻辑改写后自增，触发各控件重读；普通编辑不动它，
    // 以免打断正在输入的文本框 / 光标。
    var revision by remember { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showTitle) {
            Text(pluginDisplayText(spec.title, spec.titleRes), style = MaterialTheme.typography.titleMedium)
        }
        if (spec.fields.isEmpty()) {
            Text(
                stringResource(R.string.plugins_settings_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        spec.fields.forEach { field ->
            key(field.key) {
                SettingFieldRow(container, store, field, revision) { revision++ }
            }
        }
    }
}

/**
 * 声明式设置面板的宿主渲染器（整屏版）：把插件声明的 [SettingsPanelSpec] 用内置控件
 * （开关 / 数值 / 文本 / 下拉 / 按钮 / 文件查看编辑）渲染出来，值经 [store] 持久化。
 *
 * 这是「第三方插件也能配置」的落点 —— 插件只给数据，UI 全部由宿主提供，
 * 因此插件不接触 Compose，也就不会与应用的 Compose 版本耦合。
 * 插件详情页用它；「设置」页则直接嵌入 [PluginSettingsFields]。
 */
@Composable
fun PluginSettingsPanel(container: AppContainer, store: PluginSettingsStore, spec: SettingsPanelSpec) {
    PluginSettingsFields(
        container,
        store,
        spec,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}
