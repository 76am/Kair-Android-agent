package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.ui.components.pluginDisplayText
import dev.kair.app.ui.icons.SvgPathIcon

/**
 * 顶栏「面板快捷入口」按钮（设计稿 `openQuick` 的入口）。
 *
 * U101 起顶栏是身份区、不放插件按钮；这里只加**一个**九宫格按钮 —— 面板入口
 * 从输入板上方的横滑坞挪进底部 sheet，坞撤掉（负责人 2026-11-18：「去掉」）。
 */
@Composable
internal fun QuickEntryButton(onClick: () -> Unit, showBadge: Boolean = false) {
    Box {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = stringResource(R.string.chat_quick_entry),
            )
        }
        // 浏览器正在被 Agent 操作时亮一个小点：面板收起了，用户仍知道后台在动。
        if (showBadge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 子智能体面板 id：sheet 里这一格的读屏描述带「在跑几个」（U93 的决定，随坞迁进 sheet）。 */
private const val SUBAGENT_PANEL_ID = "panels.subagents"

/** 底部 sheet：3 列网格的面板快捷入口，条目 = 插件面板贡献点（禁用即消失）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickPanelSheet(
    container: AppContainer,
    onOpenPanel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val subagents by container.subagents.agents.collectAsState()
    val entries = dockPanels(container.plugins.panels()).map { panel ->
        DockPanelEntry(
            id = panel.id,
            icon = panel.icon,
            label = pluginDisplayText(panel.title, panel.titleRes),
            // 只有子智能体需要计数：0 个时不播报，1 个以上才占读屏位置。
            badge = if (panel.id == SUBAGENT_PANEL_ID) subagents.size else 0,
            description = if (panel.id == SUBAGENT_PANEL_ID && subagents.size > 0) {
                stringResource(R.string.chat_subagents, subagents.size)
            } else {
                null
            },
            fullscreen = isFullscreenPanel(panel),
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.chat_quick_entry),
                style = MaterialTheme.typography.titleMedium,
            )
            entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry ->
                        QuickGridCell(entry, Modifier.weight(1f)) {
                            onDismiss()
                            onOpenPanel(entry.id)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                stringResource(R.string.chat_quick_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一格快捷入口（设计稿 `.qbtn`）：74dp 高、线稿图标 + 名称。 */
@Composable
private fun QuickGridCell(entry: DockPanelEntry, modifier: Modifier, onClick: () -> Unit) {
    val desc = entry.description ?: if (entry.fullscreen) {
        stringResource(R.string.panel_open_fullscreen, entry.label)
    } else {
        entry.label
    }
    Column(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!entry.icon.isNullOrBlank()) {
            SvgPathIcon(
                pathData = entry.icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
