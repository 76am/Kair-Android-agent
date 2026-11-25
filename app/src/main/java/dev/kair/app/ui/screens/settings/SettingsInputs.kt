package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.kair.app.R

/**
 * 设置页的输入原语（U106）：文本域 / 单行输入 / 步进器 / 等宽元数据样式。
 *
 * 与 `SettingsPrimitives.kt` 的分界：那边是「怎么排一行」，这边是「怎么收一个值」。
 * 两边共享同一个等宽标签样式，避免副标题、计数、标签各用一档字号。
 */

/** 设计稿的自定义文本域：上方标签 + 计数，下方实线框 + 说明。 */
@Composable
internal fun SettingsTextArea(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null,
    counter: String? = null,
    placeholder: String? = null,
) {
    val shape = MaterialTheme.shapes.small
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MonoLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (counter != null) {
                Text(
                    counter,
                    style = MonoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 86.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        if (!hint.isNullOrBlank()) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 表单文本输入：标签在上、输入框在下（模型编辑器统一外观）。
 *
 * 标签与输入框分开是本条的唯一目的：`OutlinedTextField` 的浮动标签在中文长标签下
 * 会压窄输入区，和设计稿的「标签行 + 独立框」不一致。校验与键盘类型仍由调用方给。
 */
@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    val shape = MaterialTheme.shapes.small
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MonoLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(
                            1.dp,
                            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            shape,
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 等宽元数据样式：设置页所有副标题 / 计数 / 标签共用一档。 */
@Composable
internal fun MonoLabel(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.04.em,
)

/** 步进器本体（`− 值 单位 ＋`）：数值范围由调用方保证，边界按钮置灰但不隐藏。 */
@Composable
internal fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    unit: String?,
    onValueChange: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(
            label = "−",
            description = stringResource(R.string.settings_stepper_decrease),
            enabled = value > min,
            onClick = { onValueChange((value - 1).coerceAtLeast(min)) },
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 54.dp).padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
        )
        if (unit != null) {
            Text(
                unit,
                style = MonoLabel(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        StepperButton(
            label = "＋",
            description = stringResource(R.string.settings_stepper_increase),
            enabled = value < max,
            onClick = { onValueChange((value + 1).coerceAtMost(max)) },
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
