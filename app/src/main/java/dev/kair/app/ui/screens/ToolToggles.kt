package dev.kair.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.app.AppContainer

/**
 * 一组工具开关（组名 + 每个工具一行）。插件页扁平列出全部工具（单个 [CollapsibleGroup]），
 * 插件面板在页脚渲染本插件贡献的工具 —— 两处共用本体，读写同一份开关状态。
 */
@Composable
fun ToolToggleGroup(
    container: AppContainer,
    title: String,
    owners: List<AppContainer.ToolOwner>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        owners.forEach { owner -> ToolSwitchRow(container, owner) }
    }
}

/** 单个工具的开关行：左工具名（等宽）右开关；关闭即不注册给 agent 并落盘。 */
@Composable
internal fun ToolSwitchRow(container: AppContainer, owner: AppContainer.ToolOwner) {
    // 每行自持开关状态：切换不依赖外部 refresh 计数，行本身立即翻转。
    var enabled by remember(owner.tool.name) {
        mutableStateOf(container.isToolEnabled(owner.tool.name))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            owner.tool.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                enabled = on
                container.setToolEnabled(owner.tool.name, on)
            },
        )
    }
}

/** 插件版本徽标：`v1.8.2`，紧跟在插件名后；未声明版本的插件不渲染。 */
@Composable
internal fun VersionBadge(version: String) {
    Text(
        "v$version",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    )
}
