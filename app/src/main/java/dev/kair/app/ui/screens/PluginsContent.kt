package dev.kair.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.ui.components.CollapsibleGroup
import dev.kair.app.ui.components.pluginDisplayText
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon

/**
 * 插件页主体（U106）：汇总 → 筛选 → 能力宫格 → 工具 → 内置 / 第三方 / 需处理。
 *
 * 与旧版最大的区别是「信息优先级」：先给三个总览数字与筛选，再给可点击的能力入口，
 * 工具开关与插件列表在后。内置插件开关仍是隐藏功能（连点标题 8 次解锁），
 * 但行本身始终展示启用状态与溢出菜单。
 */
@Composable
internal fun PluginsContent(
    container: AppContainer,
    refresh: Int,
    showBuiltinSwitches: Boolean,
    onOpenPanel: (String) -> Unit,
    onOpenInstall: () -> Unit,
    onReloadAll: () -> Unit,
    onReloadPlugin: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onRequestCoreDisable: (Plugin) -> Unit,
    onSetBuiltinEnabled: (Plugin, Boolean) -> Unit,
    onDisableDynamic: (String) -> Unit,
    onRemoveShadowed: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val builtins = remember(refresh) { container.builtinPluginDefs }
    val dynamicIds = remember(refresh) { container.dynamicPlugins.installedIds() }
    val failedIds = remember(refresh) { container.dynamicPlugins.failedIds() }
    val disabledIds = remember(refresh) { container.dynamicPlugins.disabledIds() }
    val shadowedIds = remember(refresh) { container.dynamicPlugins.shadowedIds() }
    // 已安装插件的运行期二进制兼容问题（宿主升级后旧插件可能缺新契约成员）。
    val compatIssues = remember(refresh) {
        dynamicIds.associateWith { container.dynamicPlugins.compatIssues(it) }
    }
    // 第三方插件的展示版本：插件代码声明的优先，缺省回退清单 version。
    // 顺带覆盖停用 / 装载失败的条目（清单仍在磁盘上，宿主可读出）。
    val versions = remember(refresh) {
        (dynamicIds + failedIds + disabledIds).associateWith { container.dynamicPlugins.version(it) }
    }
    // 贡献列表组合期直读（U46）：直读即自动订阅，refresh 只喂磁盘态。
    val capabilities = container.plugins.panels().map { panel ->
        CapabilityEntry(
            id = panel.id,
            title = panel.title,
            titleRes = panel.titleRes,
            icon = panel.icon,
            from = panel.pluginId,
            meta = null,
        )
    } + container.plugins.settingsPanels().map { (pluginId, spec) ->
        CapabilityEntry(
            id = spec.id,
            title = spec.title,
            titleRes = spec.titleRes,
            icon = spec.icon ?: PluginGlyph.Gear,
            from = pluginId,
            meta = stringResource(R.string.plugins_settings_fields, spec.fields.size),
        )
    }
    val settingsPanelIds = container.plugins.settingsPanels().associate { (pluginId, spec) ->
        pluginId to spec.id
    }
    val actions = container.plugins.actions()
    val commands = container.plugins.commands()
    val toolCatalog = remember(refresh) { container.toolCatalog() }

    var filter by remember { mutableStateOf(PluginFilter.ALL) }
    val enabledCount = builtins.count { container.isPluginEnabled(it.id) } + dynamicIds.size
    val disabledCount = (builtins.size - builtins.count { container.isPluginEnabled(it.id) }) +
        disabledIds.size
    val needCount = failedIds.size + shadowedIds.size +
        dynamicIds.count { compatIssues[it].orEmpty().isNotEmpty() }

    Column(Modifier.fillMaxWidth()) {
        PluginSummary(
            enabled = enabledCount,
            disabled = disabledCount,
            need = needCount,
            onFilter = { filter = it },
        )
        PluginFilterRow(selected = filter, onSelect = { filter = it })

        if (filter == PluginFilter.ALL) {
            PluginSectionTitle(
                title = stringResource(R.string.plugins_panels),
                count = capabilities.size,
            )
            if (capabilities.isEmpty()) {
                Text(
                    stringResource(R.string.plugins_settings_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            } else {
                CapabilityGrid(capabilities = capabilities, onOpenPanel = onOpenPanel)
            }
            ToolsSection(
                catalog = toolCatalog,
                container = container,
                onInstall = onOpenInstall,
                onReloadAll = onReloadAll,
            )
        }

        when (filter) {
            PluginFilter.ALL, PluginFilter.BUILTIN -> BuiltinPluginsSection(
                container = container,
                builtins = builtins,
                showBuiltinSwitches = showBuiltinSwitches,
                settingsPanelIds = settingsPanelIds,
                onOpenPanel = onOpenPanel,
                onSetEnabled = onSetBuiltinEnabled,
                onRequestCoreDisable = onRequestCoreDisable,
                onMessage = onMessage,
            )
            else -> Unit
        }

        when (filter) {
            PluginFilter.ALL, PluginFilter.THIRD -> ThirdPartySection(
                container = container,
                installedIds = dynamicIds,
                disabledIds = disabledIds,
                failedIds = failedIds,
                versions = versions,
                compatIssues = compatIssues,
                settingsPanelIds = settingsPanelIds,
                onlyFailed = false,
                onOpenPanel = onOpenPanel,
                onReloadPlugin = onReloadPlugin,
                onDisable = onDisableDynamic,
                onRequestDelete = onRequestDelete,
                onMessage = onMessage,
            )
            PluginFilter.NEED -> ThirdPartySection(
                container = container,
                installedIds = emptyList(),
                disabledIds = emptyList(),
                failedIds = failedIds,
                versions = versions,
                compatIssues = compatIssues,
                settingsPanelIds = settingsPanelIds,
                onlyFailed = true,
                onOpenPanel = onOpenPanel,
                onReloadPlugin = onReloadPlugin,
                onDisable = onDisableDynamic,
                onRequestDelete = onRequestDelete,
                onMessage = onMessage,
            )
            else -> Unit
        }

        when (filter) {
            PluginFilter.ALL, PluginFilter.NEED -> NeedsAttentionSection(
                container = container,
                builtins = builtins,
                shadowedIds = shadowedIds,
                installedIds = dynamicIds,
                compatIssues = compatIssues,
                versions = versions,
                settingsPanelIds = settingsPanelIds,
                onRemoveShadowed = onRemoveShadowed,
                onDisable = onDisableDynamic,
                onOpenPanel = onOpenPanel,
            )
            else -> Unit
        }

        if (filter == PluginFilter.ALL && (actions.isNotEmpty() || commands.isNotEmpty())) {
            if (actions.isNotEmpty()) {
                PluginContributionGroup(
                    title = stringResource(R.string.plugins_actions),
                    entries = actions.map {
                        ContributionEntry(
                            label = "${it.group} · ${it.title}",
                            description = it.description,
                            onClick = { it.run() },
                        )
                    },
                )
            }
            if (commands.isNotEmpty()) {
                PluginContributionGroup(
                    title = stringResource(R.string.plugins_commands),
                    entries = commands.map {
                        ContributionEntry(
                            label = "/${it.name} · ${it.title} · " +
                                stringResource(R.string.plugins_command_from, it.pluginId),
                            description = it.description,
                            onClick = null,
                        )
                    },
                )
            }
        }
    }
}

/** 汇总行：启用 / 停用 / 需处理，点数字直接切筛选。 */
@Composable
private fun PluginSummary(
    enabled: Int,
    disabled: Int,
    need: Int,
    onFilter: (PluginFilter) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryStat(
            value = enabled,
            label = stringResource(R.string.plugins_summary_enabled),
            tone = PluginTone.ACTIVE,
            modifier = Modifier.weight(1f),
            onClick = { onFilter(PluginFilter.ALL) },
        )
        HorizontalDivider(
            modifier = Modifier.size(width = 1.dp, height = 34.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SummaryStat(
            value = disabled,
            label = stringResource(R.string.plugins_summary_disabled),
            tone = PluginTone.NEUTRAL,
            modifier = Modifier.weight(1f),
            onClick = { onFilter(PluginFilter.ALL) },
        )
        HorizontalDivider(
            modifier = Modifier.size(width = 1.dp, height = 34.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SummaryStat(
            value = need,
            label = stringResource(R.string.plugins_summary_need),
            tone = if (need > 0) PluginTone.DANGER else PluginTone.NEUTRAL,
            modifier = Modifier.weight(1f),
            onClick = { onFilter(PluginFilter.NEED) },
        )
    }
}

@Composable
private fun SummaryStat(
    value: Int,
    label: String,
    tone: PluginTone,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 20.sp,
            ),
            color = tone.color(),
        )
        Text(label, style = PluginMonoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 筛选条：四个互斥视图；「全部」保留能力与工具的完整信息。 */
@Composable
private fun PluginFilterRow(selected: PluginFilter, onSelect: (PluginFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PluginFilter.values().forEach { filter ->
            val active = filter == selected
            Text(
                stringResource(filter.labelRes),
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                    )
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(role = Role.Tab) { onSelect(filter) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/** 能力入口：两列宫格，超过 6 个时折叠。 */
@Composable
private fun CapabilityGrid(
    capabilities: List<CapabilityEntry>,
    onOpenPanel: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) capabilities else capabilities.take(6)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { entry ->
                    CapabilityCard(entry = entry, modifier = Modifier.weight(1f)) {
                        onOpenPanel(entry.id)
                    }
                }
                if (rowItems.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (capabilities.size > 6) {
            Text(
                stringResource(
                    if (expanded) R.string.plugins_capabilities_less else R.string.plugins_capabilities_more,
                    capabilities.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** 单个能力入口：图标 + 标题 + 来源。 */
@Composable
private fun CapabilityCard(
    entry: CapabilityEntry,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SvgPathIcon(
            pathData = entry.icon,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                pluginDisplayText(entry.title, entry.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.meta ?: stringResource(R.string.plugins_panel_from, entry.from),
                style = PluginMonoLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 插件贡献的操作 / 命令：保留入口，收起在页尾。 */
@Composable
private fun PluginContributionGroup(
    title: String,
    entries: List<ContributionEntry>,
) {
    CollapsibleGroup(title) {
        PluginGroup {
            entries.forEachIndexed { index, entry ->
                PluginRow(
                    title = entry.label,
                    description = entry.description,
                    divider = index > 0,
                    onClick = entry.onClick,
                )
            }
        }
    }
}

/** 插件贡献入口：动作可执行，命令只展示（与旧版一致）。 */
private data class ContributionEntry(
    val label: String,
    val description: String,
    val onClick: (() -> Unit)?,
)

/** 能力入口的原始声明：标题在渲染点解析资源，避免装配期拿不到 Context。 */
private data class CapabilityEntry(
    val id: String,
    val title: String,
    val titleRes: Int?,
    val icon: String?,
    val from: String,
    val meta: String?,
)

/** 插件页筛选：四个视图互斥。 */
internal enum class PluginFilter(val labelRes: Int) {
    ALL(R.string.plugins_filter_all),
    BUILTIN(R.string.plugins_filter_builtin),
    THIRD(R.string.plugins_filter_third),
    NEED(R.string.plugins_filter_need),
}
