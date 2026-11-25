package dev.kair.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.ui.components.pluginDisplayText

/**
 * 插件页的三层列表（U106）：内置 / 第三方 / 需要处理。
 *
 * 所有变更动作都只发意图（启用、停用、删除、重试、卸载外部版本），
 * 真正的 IO 与确认框留在 [PluginsScreen] —— 列表不持有协程，也不会在组合期写盘。
 */

/** 内置插件：默认只展示前 6 个，其余收进「查看全部」。 */
@Composable
internal fun BuiltinPluginsSection(
    container: AppContainer,
    builtins: List<Plugin>,
    showBuiltinSwitches: Boolean,
    settingsPanelIds: Map<String, String>,
    onOpenPanel: (String) -> Unit,
    onSetEnabled: (Plugin, Boolean) -> Unit,
    onRequestCoreDisable: (Plugin) -> Unit,
    onMessage: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shown = if (expanded) builtins else builtins.take(6)
    PluginSectionTitle(title = stringResource(R.string.plugins_builtin, builtins.size))
    PluginGroup {
        shown.forEachIndexed { index, plugin ->
            val enabled = container.isPluginEnabled(plugin.id)
            val name = pluginDisplayText(plugin.name, plugin.nameRes)
            val description = pluginDisplayText(plugin.description, plugin.descriptionRes)
            val panelId = settingsPanelIds[plugin.id]
            val items = buildList {
                if (panelId != null) {
                    add(PluginMenuItem(stringResource(R.string.plugins_menu_settings)) { onOpenPanel(panelId) })
                }
                add(
                    PluginMenuItem(stringResource(R.string.plugins_menu_copy_id)) {
                        copyPluginId(context, plugin.id, onMessage)
                    },
                )
                if (showBuiltinSwitches) {
                    add(
                        PluginMenuItem(
                            label = stringResource(if (enabled) R.string.plugins_disable else R.string.plugins_enable),
                            destructive = enabled && plugin.core,
                        ) {
                            when {
                                enabled && plugin.core -> onRequestCoreDisable(plugin)
                                else -> onSetEnabled(plugin, !enabled)
                            }
                        },
                    )
                }
            }
            PluginRow(
                title = name,
                version = plugin.version.ifBlank { null },
                description = description,
                tags = if (plugin.core) listOf(stringResource(R.string.plugins_core_badge) to PluginTone.WARNING) else emptyList(),
                status = stringResource(
                    if (enabled) R.string.plugins_status_enabled else R.string.plugins_status_disabled,
                ) to if (enabled) PluginTone.ACTIVE else PluginTone.NEUTRAL,
                divider = index > 0,
                trailing = { PluginOverflowMenu(title = name, items = items) },
            )
        }
        if (builtins.size > 6) {
            PluginDivider()
            Text(
                stringResource(
                    if (expanded) R.string.plugins_builtin_less else R.string.plugins_builtin_more,
                    builtins.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * 第三方插件：启用 → 停用 → 装载失败 三段。
 *
 * [onlyFailed] 用在「需要处理」筛选里：只保留装载失败项，避免与完整第三方列表重复。
 */
@Composable
internal fun ThirdPartySection(
    container: AppContainer,
    installedIds: List<String>,
    disabledIds: List<String>,
    failedIds: List<String>,
    versions: Map<String, String?>,
    compatIssues: Map<String, List<String>>,
    settingsPanelIds: Map<String, String>,
    onlyFailed: Boolean,
    onOpenPanel: (String) -> Unit,
    onReloadPlugin: (String) -> Unit,
    onDisable: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val total = installedIds.size + disabledIds.size + failedIds.size
    val context = LocalContext.current
    PluginSectionTitle(
        title = stringResource(R.string.plugins_third_party, if (onlyFailed) failedIds.size else total),
    )
    if (total == 0 || (onlyFailed && failedIds.isEmpty())) {
        Text(
            stringResource(if (onlyFailed) R.string.plugins_need_empty else R.string.plugins_third_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
        return
    }
    var index = 0
    PluginGroup {
        if (!onlyFailed) {
            installedIds.forEach { id ->
                val issues = compatIssues[id].orEmpty()
                val panelId = settingsPanelIds[id]
                val installedPlugin = container.plugins.installed.firstOrNull { it.id == id }
                val displayName = installedPlugin?.let { pluginDisplayText(it.name, it.nameRes) } ?: id
                val items = buildList {
                    if (panelId != null) {
                        add(PluginMenuItem(stringResource(R.string.plugins_menu_settings)) { onOpenPanel(panelId) })
                    }
                    add(
                        PluginMenuItem(stringResource(R.string.plugins_menu_copy_id)) {
                            copyPluginId(context, id, onMessage)
                        },
                    )
                    add(PluginMenuItem(stringResource(R.string.plugins_disable)) { onDisable(id) })
                    add(
                        PluginMenuItem(
                            stringResource(R.string.plugins_delete),
                            destructive = true,
                        ) { onRequestDelete(id) },
                    )
                }
                PluginRow(
                    title = displayName,
                    subtitle = id.takeIf { displayName != id },
                    description = installedPlugin?.let {
                        pluginDisplayText(it.description, it.descriptionRes)
                    },
                    version = versions[id],
                    status = stringResource(R.string.plugins_status_enabled) to PluginTone.ACTIVE,
                    error = issues.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.plugins_compat_warning, it.joinToString("；"))
                    },
                    divider = index++ > 0,
                    trailing = { PluginOverflowMenu(title = displayName, items = items) },
                )
            }
            if (!onlyFailed && disabledIds.isNotEmpty()) {
                DisabledGroupNote(disabledCount = disabledIds.size)
            }
            disabledIds.forEach { id ->
                PluginRow(
                    title = id,
                    version = versions[id],
                    status = stringResource(R.string.plugins_status_disabled) to PluginTone.NEUTRAL,
                    divider = index++ > 0,
                    trailing = {
                        PluginMiniAction(stringResource(R.string.plugins_enable), PluginTone.ACTIVE) {
                            onReloadPlugin(id)
                        }
                        PluginMiniAction(stringResource(R.string.plugins_delete), PluginTone.DANGER) {
                            onRequestDelete(id)
                        }
                    },
                )
            }
        }
        failedIds.forEach { id ->
            PluginRow(
                title = id,
                version = versions[id],
                status = stringResource(R.string.plugins_status_failed) to PluginTone.DANGER,
                error = stringResource(
                    R.string.plugins_load_failed,
                    container.dynamicPlugins.failureReason(id).orEmpty(),
                ),
                divider = index++ > 0,
                trailing = {
                    PluginMiniAction(stringResource(R.string.plugins_retry), PluginTone.ACTIVE) {
                        onReloadPlugin(id)
                    }
                    PluginMiniAction(stringResource(R.string.plugins_delete), PluginTone.DANGER) {
                        onRequestDelete(id)
                    }
                },
            )
        }
    }
}

/** 需要处理：被内置版顶替的外部版本 + 运行期二进制不兼容。 */
@Composable
internal fun NeedsAttentionSection(
    container: AppContainer,
    builtins: List<Plugin>,
    shadowedIds: List<String>,
    installedIds: List<String>,
    compatIssues: Map<String, List<String>>,
    versions: Map<String, String?>,
    settingsPanelIds: Map<String, String>,
    onRemoveShadowed: (String) -> Unit,
    onDisable: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
) {
    val incompatible = installedIds.filter { compatIssues[it].orEmpty().isNotEmpty() }
    val total = shadowedIds.size + incompatible.size
    PluginSectionTitle(title = stringResource(R.string.plugins_section_need), count = total)
    if (total == 0) {
        Text(
            stringResource(R.string.plugins_need_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
        return
    }
    var index = 0
    PluginGroup {
        if (shadowedIds.isNotEmpty()) {
            GroupNote(stringResource(R.string.plugins_shadowed_section, shadowedIds.size))
        }
        shadowedIds.forEach { id ->
            val builtin = builtins.firstOrNull { it.id == id }
            PluginRow(
                title = builtin?.let { pluginDisplayText(it.name, it.nameRes) } ?: id,
                subtitle = id,
                version = container.dynamicPlugins.version(id),
                status = stringResource(R.string.plugins_status_shadowed) to PluginTone.WARNING,
                description = stringResource(R.string.plugins_shadowed_state),
                divider = index++ > 0,
                trailing = {
                    PluginOverflowMenu(
                        title = builtin?.let { pluginDisplayText(it.name, it.nameRes) } ?: id,
                        items = listOf(
                            PluginMenuItem(
                                label = stringResource(R.string.plugins_shadowed_remove),
                                destructive = true,
                                onClick = { onRemoveShadowed(id) },
                            ),
                        ),
                    )
                },
            )
        }
        incompatible.forEach { id ->
            val panelId = settingsPanelIds[id]
            PluginRow(
                title = id,
                version = versions[id],
                status = stringResource(R.string.plugins_status_incompatible) to PluginTone.DANGER,
                error = stringResource(
                    R.string.plugins_compat_warning,
                    compatIssues[id].orEmpty().joinToString("；"),
                ),
                divider = index++ > 0,
                onClick = panelId?.let { { onOpenPanel(it) } },
                trailing = {
                    PluginMiniAction(stringResource(R.string.plugins_disable), PluginTone.DANGER) {
                        onDisable(id)
                    }
                },
            )
        }
    }
}

/** 第三方列表里的「已停用」小注：说明文件仍在磁盘上，可重新启用。 */
@Composable
private fun DisabledGroupNote(disabledCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            stringResource(R.string.plugins_disabled_section, disabledCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.plugins_disabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 行组内的分组小注：只说明分组，不带动作。 */
@Composable
private fun GroupNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** 复制插件 ID 并回一条 Snackbar；剪贴板服务取不到时静默失败。 */
private fun copyPluginId(context: Context, id: String, onMessage: (String) -> Unit) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Kair plugin", id))
    onMessage(context.getString(R.string.plugins_id_copied, id))
}
