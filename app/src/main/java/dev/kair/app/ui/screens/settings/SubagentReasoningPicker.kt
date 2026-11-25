package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.settings.ReasoningAuto
import dev.kair.app.core.settings.SavedModel

/**
 * 子智能体思考强度：只覆盖子智能体的请求，不改所选模型自己的默认档位。
 *
 * 选项来自所选模型已勾选的 `reasoningLevels`；空串 = 跟随模型配置。
 * 未选定子智能体模型（跟随会话模型）或模型没有可选档位时不提供编辑入口 ——
 * 此时实际档位取决于发起委派的会话，设置页拿不到它的模型。
 */
@Composable
internal fun SubagentReasoningPicker(
    model: SavedModel?,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val levels = model?.reasoningLevels.orEmpty()
    val editable = model != null && model.isReasoningModel && levels.isNotEmpty()
    val stale = when {
        !editable -> false
        selected.isBlank() -> false
        selected == ReasoningAuto -> false
        else -> selected !in levels
    }
    SettingsNavRow(
        title = stringResource(R.string.settings_subagent_reasoning),
        subtitle = when {
            model == null -> stringResource(R.string.settings_subagent_reasoning_pick_model)
            !editable -> stringResource(R.string.settings_subagent_reasoning_unavailable)
            selected.isBlank() -> stringResource(
                R.string.settings_subagent_reasoning_follow_value,
                reasoningLevelLabel(model.reasoningEffort),
            )
            else -> reasoningLevelLabel(selected)
        },
        error = if (stale) stringResource(R.string.settings_subagent_reasoning_stale) else null,
        enabled = editable,
        onClick = { open = true },
    )
    if (editable && open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_subagent_reasoning)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.settings_subagent_reasoning_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 用 for 循环而不是 map：reasoningLevelLabel 是 @Composable，
                    // 不能在普通 lambda 里调用。
                    val options = mutableListOf(
                        "" to stringResource(R.string.settings_subagent_reasoning_follow),
                        ReasoningAuto to stringResource(R.string.chat_reasoning_auto),
                    )
                    for (level in levels) options += level to reasoningLevelLabel(level)
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(value); open = false },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 点击由外层 selectable 处理，避免与 RadioButton 重复触发。
                            RadioButton(selected = selected == value, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/** 档位展示文案：空 / auto 都读作「自动」，其余直接显示模型声明的档位名。 */
@Composable
private fun reasoningLevelLabel(level: String?): String =
    if (level.isNullOrBlank() || level == ReasoningAuto) {
        stringResource(R.string.chat_reasoning_auto)
    } else {
        level
    }
