package dev.kair.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kair.app.R

/**
 * 插件页的行组与行原语（U106）。
 *
 * 与设置页同构：分区标题在上、面（surfaceContainer + hairline）在下，
 * 行里只放名称、版本、状态与一个行尾动作。颜色不在调用点写死。
 */

/** 分区标题：`内置插件 24` —— 标题正文、计数等宽。 */
@Composable
internal fun PluginSectionTitle(
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(count.toString(), style = PluginMonoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 行组：与设置页一致的单层面。 */
@Composable
internal fun PluginGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        content = content,
    )
}

/** 行组内的分隔线；`divider = false` 用于每组第一行。 */
@Composable
internal fun PluginDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
}

/** 插件页的等宽元数据样式。 */
@Composable
internal fun PluginMonoLabel() = MaterialTheme.typography.labelSmall.copy(
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.04.em,
)

/** 行尾状态：小圆点 + 等宽文字，颜色表示语气（启用 / 停用 / 需处理）。 */
@Composable
internal fun PluginStatusPill(text: String, tone: PluginTone) {
    val color = tone.color()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text, style = PluginMonoLabel(), color = color, maxLines = 1)
    }
}

/** 行内标签（核心 / 版本 / 来源）。 */
@Composable
internal fun PluginTag(text: String, tone: PluginTone = PluginTone.NEUTRAL) {
    val color = tone.color()
    Text(
        text,
        style = PluginMonoLabel(),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 插件行：标题行（名称 + 版本 + 标签）、说明、错误行、行尾动作。 */
@Composable
internal fun PluginRow(
    title: String,
    subtitle: String? = null,
    description: String? = null,
    version: String? = null,
    tags: List<Pair<String, PluginTone>> = emptyList(),
    status: Pair<String, PluginTone>? = null,
    error: String? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column {
        if (divider) PluginDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (version != null) PluginTag("v$version")
                    tags.forEach { (label, tone) -> PluginTag(label, tone) }
                }
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = PluginMonoLabel(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (status != null) {
                PluginStatusPill(status.first, status.second)
            }
            trailing()
        }
    }
}

/** 行内小动作（`停用` / `删除` / `重试`）：命中区 44dp，避免和行点击混淆。 */
@Composable
internal fun PluginMiniAction(
    text: String,
    tone: PluginTone = PluginTone.NEUTRAL,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tone.color(),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

/** 溢出菜单项：文案 + 是否危险色 + 动作。 */
internal data class PluginMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 行尾「⋯」溢出菜单：所有次级动作都收在这里，行本身只保留状态。 */
@Composable
internal fun PluginOverflowMenu(
    title: String,
    items: List<PluginMenuItem>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.plugins_more, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            item.label,
                            color = if (item.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

/** 状态语气：把「用哪个颜色」收在一处，行原语只认语气不认具体色。 */
internal enum class PluginTone {
    NEUTRAL,
    ACTIVE,
    WARNING,
    DANGER,
    LOCKED,
    ;

    @Composable
    fun color() = when (this) {
        NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ACTIVE -> MaterialTheme.colorScheme.primary
        WARNING -> MaterialTheme.colorScheme.primary
        DANGER -> MaterialTheme.colorScheme.error
        LOCKED -> MaterialTheme.colorScheme.outline
    }
}

/** 插件页里「强调数字」的字重（汇总卡用）。 */
internal val PluginStatWeight = FontWeight.SemiBold
