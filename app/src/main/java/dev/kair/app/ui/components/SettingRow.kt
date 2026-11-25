package dev.kair.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.ChoiceOption
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.tools.FileBackend
import java.io.File

@Composable
internal fun SettingFieldRow(
    container: AppContainer,
    store: PluginSettingsStore,
    field: SettingField,
    revision: Int,
    onExternalChange: () -> Unit,
) {
    // 文案优先走字符串资源（内置插件让文案统一登记在 strings.xml）；第三方插件只给字符串。
    // 判据不在此重复一遍：一律经 pluginDisplayText → resolveDisplayText。
    val label = pluginDisplayText(field.label, field.labelRes)
    val description = pluginDisplayText(field.description.orEmpty(), field.descriptionRes)
    when (field) {
        is SettingField.Toggle -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldLabel(label, description, Modifier.weight(1f))
            var checked by remember(revision, field.key) {
                mutableStateOf(store.getBoolean(field.key, field.default))
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    store.setBoolean(field.key, it)
                    field.onChange?.invoke(it)
                },
            )
        }

        is SettingField.Number -> {
            var text by remember(revision, field.key) {
                mutableStateOf(store.getInt(field.key, field.default).toString())
            }
            val min = field.min
            val max = field.max
            val parsed = text.toIntOrNull()
            // 越界（含输入途中只打前几位）即时判错：输入框实时标红，且只在范围内才落库。
            val inRange = parsed != null &&
                (min == null || parsed >= min) &&
                (max == null || parsed <= max)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(label, description)
                OutlinedTextField(
                    value = text,
                    onValueChange = { raw ->
                        // 只接受数字字符，避免中间态把非法输入写进存储。
                        if (raw.all { it.isDigit() } && raw.length <= 9) {
                            text = raw
                            raw.toIntOrNull()
                                ?.takeIf { v -> (min == null || v >= min) && (max == null || v <= max) }
                                ?.let { v -> store.setInt(field.key, v) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !inRange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        if (!inRange && min != null && max != null) {
                            Text(stringResource(R.string.plugins_settings_out_of_range, min, max))
                        } else {
                            numberRangeHint(min, max)?.invoke()
                        }
                    },
                )
            }
        }

        is SettingField.Text -> {
            var text by remember(revision, field.key) {
                mutableStateOf(store.getString(field.key, field.default))
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(label, description)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; store.setString(field.key, it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }

        is SettingField.Choice -> {
            // 动态来源：会话 / 模型列表随用户操作变化，插件在 activate() 里拿不到，
            // 所以在渲染时由宿主解析（静态 options 被忽略）。
            val resolvedOptions = choiceOptions(container, field)
            // 存储值为空时落到第一项，**并且写回**：只显示不落库的话，插件读到的是空串，
            // 于是「UI 显示已选中、功能从未生效」——定时任务目标会话就是这样静默失效的。
            val stored = remember(revision, field.key) {
                val raw = store.getString(field.key, field.default)
                if (raw.isNotBlank()) {
                    raw
                } else {
                    val fallback = resolvedOptions.firstOrNull()?.value.orEmpty()
                    if (fallback.isNotBlank()) store.setString(field.key, fallback)
                    fallback
                }
            }
            var selected by remember(revision, field.key) { mutableStateOf(stored) }
            var expanded by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel(label, description)
                if (resolvedOptions.isEmpty()) {
                    // 选项源为空（如用户未配置任何模型）时显示明确提示，而不是一个点不开的
                    // 空按钮——后者让用户以为 UI 坏了。模型列表变化后 revision 会触发重渲染。
                    Text(
                        stringResource(R.string.plugins_settings_no_options),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                resolvedOptions.firstOrNull { it.value == selected }
                                    ?.let { pluginDisplayText(it.label, it.labelRes) } ?: selected,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            resolvedOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(pluginDisplayText(opt.label, opt.labelRes)) },
                                    onClick = {
                                        selected = opt.value
                                        store.setString(field.key, opt.value)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        is SettingField.Action -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FieldLabel(label, description)
            Button(
                onClick = {
                    field.onClick(store)
                    onExternalChange()
                },
            ) { Text(pluginDisplayText(field.buttonLabel, field.buttonLabelRes)) }
        }

        is SettingField.FileEditor -> {
            // 相对路径必须按**真实会话工作区**解析；无活跃会话时禁用并提示，
            // 不能回退到「最近活跃工作区」（没有会话就没有项目，会静默写到错误位置）。
            val root = container.activeSessionWorkspace()
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(label, description)
                if (root == null) {
                    Text(
                        stringResource(R.string.active_session_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val absolute = remember(field.path, root.path) { resolveInWorkspace(root, field.path) }
                    val backend = remember(root.path) { container.fileBackend(root) }
                    OutlinedButton(onClick = { open = true }) {
                        Text(stringResource(R.string.plugins_settings_file_open))
                    }
                    if (open) {
                        FileEditorDialog(
                            backend = backend,
                            absolutePath = absolute,
                            title = label.ifBlank { field.path },
                            readOnly = field.readOnly,
                            onClose = { open = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 下拉的动态选项源：会话 / 模型列表随用户操作变化，插件在 `activate()` 里拿不到，
 * 所以由宿主在渲染时解析（静态 options 被忽略）。
 */
private fun choiceOptions(container: AppContainer, field: SettingField.Choice): List<ChoiceOption> =
    when (field.source) {
        "sessions" -> container.pluginSessions().map { ChoiceOption(it.id, it.title.ifBlank { it.id }) }
        "models" -> container.settings.savedModels.map { ChoiceOption(it.id, it.name.ifBlank { it.model }) }
        // 首项是空值「跟随当前会话模型」：空串表示不覆盖，于是「空值写回第一项」不会落库。
        "models_follow_session" -> listOf(
            ChoiceOption("", "").apply { labelRes = R.string.settings_model_follow_session },
        ) + container.settings.savedModels.map { ChoiceOption(it.id, it.name.ifBlank { it.model }) }
        else -> field.options
    }

/** 工作区相对路径 → 绝对路径；已是绝对路径则原样返回。 */
private fun resolveInWorkspace(root: File, path: String): String =
    File(path).let { if (it.isAbsolute) it.path else File(root, path).path }

/** 文件字段点开后弹的整屏编辑器：复用工作区文件面板的同一套查看 / 编辑体。 */
@Composable
private fun FileEditorDialog(
    backend: FileBackend,
    absolutePath: String,
    title: String,
    readOnly: Boolean,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                FileEditorBody(
                    backend = backend,
                    absolutePath = absolutePath,
                    label = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    readOnly = readOnly,
                    onBack = onClose,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.padding(top = 2.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 数值范围的辅助提示；无 min/max 时不显示。 */
private fun numberRangeHint(min: Int?, max: Int?): (@Composable () -> Unit)? = when {
    min != null && max != null -> ({ Text(stringResource(R.string.plugins_settings_range, min, max)) })
    min != null -> ({ Text(stringResource(R.string.plugins_settings_min, min)) })
    max != null -> ({ Text(stringResource(R.string.plugins_settings_max, max)) })
    else -> null
}
