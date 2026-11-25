package dev.kair.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.ui.screens.settings.KairSwitch

/**
 * 插件页工具区（U106）：搜索 + 扁平行组。
 *
 * 工具清单是「内核内置 + 插件贡献」的一层扁平表；搜索只过滤名称，
 * 不改变归属标签与核心锁定。`container_shell` 是核心工具，锁定不可关闭。
 */
@Composable
internal fun ToolsSection(
    catalog: List<AppContainer.ToolOwner>,
    container: AppContainer,
    onInstall: () -> Unit,
    onReloadAll: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = catalog.filter { it.tool.name.contains(query, ignoreCase = true) }
    PluginSectionTitle(title = stringResource(R.string.plugins_tools), count = catalog.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.plugins_tools_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolSearchField(query = query, onQueryChange = { query = it })
        PluginGroup {
            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.plugins_tool_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            } else {
                filtered.forEachIndexed { index, owner ->
                    ToolRow(
                        owner = owner,
                        container = container,
                        divider = index > 0,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PluginMiniAction(
                text = stringResource(R.string.plugins_install),
                tone = PluginTone.ACTIVE,
                onClick = onInstall,
            )
            PluginMiniAction(text = stringResource(R.string.plugins_reload), onClick = onReloadAll)
        }
    }
}

@Composable
private fun ToolSearchField(query: String, onQueryChange: (String) -> Unit) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .heightIn(min = 42.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.plugins_tool_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToolRow(owner: AppContainer.ToolOwner, container: AppContainer, divider: Boolean) {
    val locked = owner.tool.name == "container_shell"
    var enabled by remember(owner.tool.name) {
        mutableStateOf(container.isToolEnabled(owner.tool.name))
    }
    Column {
        if (divider) PluginDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    owner.tool.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    if (owner.pluginId == null) {
                        stringResource(
                            if (locked) R.string.plugins_core_badge else R.string.plugins_tool_owner_builtin,
                        )
                    } else {
                        stringResource(R.string.plugins_tool_owner_plugin, owner.pluginId)
                    },
                    style = PluginMonoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (locked) PluginTag(stringResource(R.string.plugins_core_badge), PluginTone.WARNING)
            KairSwitch(
                checked = if (locked) true else enabled,
                onCheckedChange = if (locked) {
                    null
                } else {
                    { on ->
                        enabled = on
                        container.setToolEnabled(owner.tool.name, on)
                    }
                },
                enabled = !locked,
                contentDescription = owner.tool.name,
            )
        }
    }
}
