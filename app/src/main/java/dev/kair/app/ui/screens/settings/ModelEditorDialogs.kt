package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.ai.ProbedModel
import dev.kair.app.core.ai.ProviderDef
import dev.kair.app.core.ai.TransportIds

/**
 * 模型编辑器弹出的三个选择器。选中后写回表单的逻辑（含地址联动）留在
 * [ModelEditorScreen]，这里只负责渲染列表与转交选择。
 */

/** 服务商选择器。 */
@Composable
internal fun ProviderPickerDialog(
    onPick: (ProviderDef) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelCatalog.providers
                    .filterNot { it.id in HiddenFromProviderPicker }
                    .forEach { p ->
                        Text(
                            p.displayName,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p) }
                                .padding(vertical = 12.dp),
                        )
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 服务商公开的模型列表；选中即预填能力。 */
@Composable
internal fun ModelPickerDialog(
    models: List<ProbedModel>,
    onPick: (ProbedModel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_model)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                models.forEach { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(m) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            m.displayName?.takeIf { it != m.id } ?: m.id,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (m.displayName != null && m.displayName != m.id) {
                            Text(
                                m.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val contextText = m.contextWindow?.let {
                            stringResource(R.string.settings_model_probe_context, it)
                        }
                        val outputText = m.maxOutputTokens?.let {
                            stringResource(R.string.settings_model_probe_output, it)
                        }
                        val meta = listOfNotNull(contextText, outputText).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 协议选择器（仅「自定义」服务商可见）。 */
@Composable
internal fun ProtocolPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_choose_protocol)) },
        text = {
            Column {
                listOf(
                    TransportIds.OPENAI_CHAT to "OpenAI Chat",
                    TransportIds.OPENAI_RESPONSES to "OpenAI Responses",
                    TransportIds.ANTHROPIC_MESSAGES to "Anthropic Messages",
                ).forEach { (id, label) ->
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 服务商选择器里隐藏的内置条目（仍保留在目录中，供旧配置解析）。 */
private val HiddenFromProviderPicker = setOf("anthropic")
