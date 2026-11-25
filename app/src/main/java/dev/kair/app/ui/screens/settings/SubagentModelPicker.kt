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
import dev.kair.app.core.settings.ModelResolution
import dev.kair.app.core.settings.SavedModel

/**
 * 子智能体默认模型的选拣行：一行摘要 + 点开单选对话框。
 *
 * 首项是「跟随会话模型」（空串）：保持旧行为 —— 子智能体继承发起委派的会话的模型。
 * 选具体模型后只影响子智能体，不改主会话的模型；单次运行还可用 `subagent` 工具的
 * `model` 参数覆盖。选中的模型被删除时不报错，按 [ModelResolution] 静默落到下一层。
 */
@Composable
internal fun SubagentModelPicker(
    models: List<SavedModel>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = models.firstOrNull { it.id == selectedId }
    SettingsNavRow(
        title = stringResource(R.string.settings_subagent_model),
        subtitle = if (current != null) {
            current.name.ifBlank { current.model }
        } else {
            stringResource(R.string.settings_subagent_model_follow)
        },
        // 选过但模型已被删除：说清楚现在实际在用哪个，而不是默默回退。
        error = if (selectedId.isNotBlank() && current == null) {
            stringResource(R.string.settings_subagent_model_missing)
        } else {
            null
        },
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_subagent_model)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.settings_subagent_model_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (listOf("" to stringResource(R.string.settings_subagent_model_follow)) +
                        models.map { it.id to (it.name.ifBlank { it.model }) }).forEach { (id, label) ->
                        // 整行可点 + 单选语义：只靠 RadioButton 的小圆点当点击区太难中，
                        // 且无障碍读屏需要 selectable 才知道这是一组单选。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedId == id,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(id); open = false },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // onClick = null：点击由外层 selectable 处理，避免两处重复触发。
                            RadioButton(selected = selectedId == id, onClick = null)
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
