package dev.kair.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * 设置页的排版原语（U106）：分区标题 / 行组 / 导航行 / 开关 / 步进器 / 文本域。
 *
 * 设计稿把这些从 M3 卡片换成「编号分区 + 单层行组」：分区的序号与计数是刻字元数据，
 * 行组只负责把同类设置收进一张面。颜色一律取主题角色（primary = 蓝 = 「当前」），
 * 不在调用点写死色值，深浅两套才能一起成立。
 */

/** 分节标题：`01 模型 3` —— 序号主色蓝、标题正文、计数次要。 */
@Composable
internal fun SettingsSection(
    number: String,
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            number,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.12.em,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (count != null) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.06.em,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 行组：一张带 hairline 描边的面，行与行之间由调用方插入 [SettingsDivider]。 */
@Composable
internal fun SettingsGroup(
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

/** 行组分隔线：与分组描边同色，只画分界不画控件边。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}

/**
 * 导航行：左标题 + 可选等宽副标题，右侧可选徽标 / 附带值 / 箭头。
 * 命中区至少 60dp；[badge] 用主色蓝（当前默认），[meta] 用于右侧简短读数。
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    meta: String? = null,
    badge: String? = null,
    error: String? = null,
    divider: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        if (divider) SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = contentColor)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MonoLabel(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (badge != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.widthIn(max = 96.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        badge,
                        style = MonoLabel(),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            if (meta != null) {
                Text(
                    meta,
                    style = MonoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
            }
            if (enabled) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 开关行：标题 + 说明 + 右侧开关。整行可点，命中区由 [KairSwitch] 保证。 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    hint: String? = null,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        if (divider) SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (!hint.isNullOrBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            KairSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = title)
        }
    }
}

/** 步进器行：标题 + 说明 + 右侧 `− 值 ＋`。值域外到达边界即置灰。 */
@Composable
internal fun SettingsStepperRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    hint: String? = null,
    unit: String? = null,
    divider: Boolean = true,
    onValueChange: (Int) -> Unit,
) {
    Column {
        if (divider) SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (!hint.isNullOrBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Stepper(value = value, min = min, max = max, unit = unit, onValueChange = onValueChange)
        }
    }
}

/** 添加入口：行组底部的虚线框（`＋ 添加模型`）。 */
@Composable
internal fun SettingsAddRow(
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("＋", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 开关本体：46×28 的紧凑轨道，命中区仍按 48dp 给（设计稿 `.sw`）。 */
@Composable
internal fun KairSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val trackShape = RoundedCornerShape(999.dp)
    val trackColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "switchTrack",
    )
    val borderColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "switchBorder",
    )
    val thumbColor by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        label = "switchThumb",
    )
    val thumbOffset by animateDpAsState(if (checked) 18.dp else 0.dp, label = "switchThumbOffset")
    val interactive = enabled && onCheckedChange != null
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (interactive) {
                    Modifier.clickable(role = Role.Switch) { onCheckedChange?.invoke(!checked) }
                } else {
                    Modifier
                },
            )
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                role = Role.Switch
            }
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(28.dp)
                .clip(trackShape)
                .background(trackColor)
                .border(1.dp, borderColor, trackShape),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 3.dp)
                    .offset(x = thumbOffset)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }
    }
}
