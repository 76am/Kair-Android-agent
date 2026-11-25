package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.settings.ReasoningAuto
import dev.kair.app.core.settings.ReasoningLevels

/**
 * 模型编辑器的表单分段。状态由 [ModelEditorScreen] 持有，这里只负责渲染与回调，
 * 拆出仅为控制单文件行数，行为与原内联实现一致。
 */

/** 名称 + 服务商（含「自定义」才出现的协议入口）。 */
@Composable
internal fun ProviderSection(
    name: String,
    onNameChange: (String) -> Unit,
    providerName: String,
    showsProtocol: Boolean,
    transportName: String,
    onOpenProviderPicker: () -> Unit,
    onOpenProtocolPicker: () -> Unit,
) {
    SettingsTextField(
        label = stringResource(R.string.settings_model_name),
        value = name,
        onValueChange = onNameChange,
    )

    Text(stringResource(R.string.settings_provider), style = MaterialTheme.typography.titleMedium)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenProviderPicker),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(providerName, Modifier.weight(1f))
            Text(stringResource(R.string.settings_provider_change), style = MaterialTheme.typography.labelMedium)
        }
    }

    // 自定义入口允许切换协议；LongCat / Gemini 预设的协议由 ProviderDef 固定。
    if (showsProtocol) {
        Text(stringResource(R.string.settings_protocol), style = MaterialTheme.typography.titleMedium)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenProtocolPicker),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(transportName, Modifier.weight(1f))
                Text(stringResource(R.string.settings_provider_change), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            stringResource(R.string.settings_protocol_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 连接信息：地址 / 密钥 / 模型 ID + 列表与探测。 */
@Composable
internal fun EndpointSection(
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    needsKey: Boolean,
    model: String,
    onModelChange: (String) -> Unit,
    listingModels: Boolean,
    probing: Boolean,
    probeMessage: String?,
    onListModels: () -> Unit,
    onProbe: () -> Unit,
) {
    SettingsTextField(
        label = stringResource(R.string.settings_base_url),
        value = baseUrl,
        onValueChange = onBaseUrlChange,
    )

    if (needsKey) {
        SettingsTextField(
            label = stringResource(R.string.settings_api_key),
            value = apiKey,
            onValueChange = onApiKeyChange,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsTextField(
            label = stringResource(R.string.settings_model_id),
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = !listingModels &&
                baseUrl.isNotBlank() &&
                (!needsKey || apiKey.isNotBlank()),
            onClick = onListModels,
        ) {
            Text(
                stringResource(
                    if (listingModels) R.string.settings_model_probe_running
                    else R.string.settings_model_probe_list,
                ),
            )
        }
    }

    TextButton(
        enabled = !probing &&
            model.isNotBlank() &&
            baseUrl.isNotBlank() &&
            (!needsKey || apiKey.isNotBlank()),
        onClick = onProbe,
    ) {
        Text(
            stringResource(
                if (probing) R.string.settings_model_probe_running
                else R.string.settings_model_probe,
            ),
        )
    }
    Text(
        stringResource(R.string.settings_model_probe_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    probeMessage?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 输出上限 / 上下文窗口 / 温度。 */
@Composable
internal fun LimitsSection(
    maxTokens: String,
    onMaxTokensChange: (String) -> Unit,
    errorMaxTokens: Boolean,
    contextWindow: String,
    onContextWindowChange: (String) -> Unit,
    errorContextWindow: Boolean,
    temperature: String,
    onTemperatureChange: (String) -> Unit,
    errorTemperature: Boolean,
) {
    SettingsTextField(
        label = stringResource(R.string.settings_model_max_tokens),
        value = maxTokens,
        onValueChange = onMaxTokensChange,
        isError = errorMaxTokens,
        supportingText = if (errorMaxTokens) stringResource(R.string.settings_number_min, 1) else null,
        keyboardType = KeyboardType.Number,
    )

    SettingsTextField(
        label = stringResource(R.string.settings_model_context_window),
        value = contextWindow,
        onValueChange = onContextWindowChange,
        isError = errorContextWindow,
        supportingText = if (errorContextWindow) stringResource(R.string.settings_number_min, 1) else null,
        keyboardType = KeyboardType.Number,
    )

    SettingsTextField(
        label = stringResource(R.string.settings_temperature),
        value = temperature,
        onValueChange = onTemperatureChange,
        isError = errorTemperature,
        supportingText = if (errorTemperature) stringResource(R.string.settings_number_range, "0.0", "2.0") else null,
        keyboardType = KeyboardType.Decimal,
    )
}

/** 能力标记：图片输入 / 思考 / 重放思考，以及思考档位与映射。 */
@Composable
internal fun CapabilitySection(
    isVision: Boolean,
    onVisionChange: (Boolean) -> Unit,
    isReasoning: Boolean,
    onReasoningChange: (Boolean) -> Unit,
    replayThinking: Boolean,
    onReplayThinkingChange: (Boolean) -> Unit,
    levels: Set<String>,
    onLevelsChange: (Set<String>) -> Unit,
    defaultEffort: String?,
    onDefaultEffortChange: (String) -> Unit,
    levelMap: Map<String, String>,
    onLevelMapChange: (Map<String, String>) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_model_vision), Modifier.weight(1f))
        Switch(checked = isVision, onCheckedChange = onVisionChange)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_model_reasoning), Modifier.weight(1f))
        Switch(
            checked = isReasoning,
            onCheckedChange = { on ->
                onReasoningChange(on)
                // 首次开启时默认支持全部强度，之后再按需裁剪，避免出现空选项。
                if (on && levels.isEmpty()) onLevelsChange(ReasoningLevels.toSet())
            },
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_model_replay_thinking), Modifier.weight(1f))
        Switch(checked = replayThinking, onCheckedChange = onReplayThinkingChange)
    }
    Text(
        stringResource(R.string.settings_model_replay_thinking_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (isReasoning) {
        Text(
            stringResource(R.string.settings_model_reasoning_levels),
            style = MaterialTheme.typography.labelLarge,
        )
        ReasoningLevels.forEach { level ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onLevelsChange(if (level in levels) levels - level else levels + level)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = level in levels, onCheckedChange = { on ->
                    onLevelsChange(if (on) levels + level else levels - level)
                })
                Text(level, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(stringResource(R.string.settings_model_default_reasoning), style = MaterialTheme.typography.labelLarge)
        (listOf(ReasoningAuto) + ReasoningLevels.filter { it in levels }).forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDefaultEffortChange(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = defaultEffort == option, onClick = { onDefaultEffortChange(option) })
                Text(option, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(stringResource(R.string.settings_model_thinking_map), style = MaterialTheme.typography.labelLarge)
        ReasoningLevels.filter { it in levels }.forEach { level ->
            SettingsTextField(
                label = level,
                value = levelMap[level].orEmpty(),
                onValueChange = { v ->
                    onLevelMapChange(if (v.isBlank()) levelMap - level else levelMap + (level to v))
                },
                placeholder = level,
            )
        }
    }
}

/** 高级采样参数（仅 OpenAI 兼容协议可编辑）。 */
@Composable
internal fun AdvancedSamplingSection(
    isOpenAi: Boolean,
    frequencyPenalty: String,
    onFrequencyPenaltyChange: (String) -> Unit,
    errorFrequencyPenalty: Boolean,
    presencePenalty: String,
    onPresencePenaltyChange: (String) -> Unit,
    errorPresencePenalty: Boolean,
    topP: String,
    onTopPChange: (String) -> Unit,
    errorTopP: Boolean,
    seed: String,
    onSeedChange: (String) -> Unit,
    errorSeed: Boolean,
) {
    HorizontalDivider()
    Text(stringResource(R.string.settings_model_advanced), style = MaterialTheme.typography.titleMedium)
    if (!isOpenAi) {
        Text(
            stringResource(R.string.settings_model_advanced_unsupported),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsTextField(
        label = stringResource(R.string.settings_model_frequency_penalty),
        value = frequencyPenalty,
        onValueChange = onFrequencyPenaltyChange,
        enabled = isOpenAi,
        isError = isOpenAi && errorFrequencyPenalty,
        supportingText = if (isOpenAi && errorFrequencyPenalty) {
            stringResource(R.string.settings_number_range, "-2.0", "2.0")
        } else {
            null
        },
        keyboardType = KeyboardType.Decimal,
    )
    SettingsTextField(
        label = stringResource(R.string.settings_model_presence_penalty),
        value = presencePenalty,
        onValueChange = onPresencePenaltyChange,
        enabled = isOpenAi,
        isError = isOpenAi && errorPresencePenalty,
        supportingText = if (isOpenAi && errorPresencePenalty) {
            stringResource(R.string.settings_number_range, "-2.0", "2.0")
        } else {
            null
        },
        keyboardType = KeyboardType.Decimal,
    )
    SettingsTextField(
        label = stringResource(R.string.settings_model_top_p),
        value = topP,
        onValueChange = onTopPChange,
        enabled = isOpenAi,
        isError = isOpenAi && errorTopP,
        supportingText = if (isOpenAi && errorTopP) {
            stringResource(R.string.settings_number_range, "0.0", "1.0")
        } else {
            null
        },
        keyboardType = KeyboardType.Decimal,
    )
    SettingsTextField(
        label = stringResource(R.string.settings_model_seed),
        value = seed,
        onValueChange = onSeedChange,
        enabled = isOpenAi,
        isError = isOpenAi && errorSeed,
        supportingText = if (isOpenAi && errorSeed) stringResource(R.string.settings_number_min, 0) else null,
        keyboardType = KeyboardType.Number,
    )
}
