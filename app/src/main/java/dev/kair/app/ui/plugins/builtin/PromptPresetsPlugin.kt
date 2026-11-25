package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.PreambleContributor
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.storage.PromptPreset
import dev.kair.app.ui.icons.PluginGlyph

/**
 * 提示词预设插件：给每个项目绑定一套自己编辑的「身份」提示词。
 *
 * 只改开场白：工具清单 / 行为规则 / 工作目录等段落改错会静默出错（写错路径、
 * 调用不存在的工具），仍由 [SystemPromptBuilder.LockedSectionIds] 锁死。
 *
 * 生效时机复用现有机制 —— 保存后清段落缓存；若用户关了「每回合刷新提示词」，
 * 则下一次请求仍会读到新正文（覆写是缓存键的一部分），只是外部状态的刷新变慢。
 */
class PromptPresetsPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.promptpresets"
    override val name = "提示词预设"
    override val description = "按项目切换助手身份提示词；只改开场白，工具与规则仍由内核锁定。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        ctx.panel(
            PanelContribution(
                id = "panels.promptpresets",
                title = name,
                icon = PluginGlyph.Cards,
                order = 25,
                content = { PromptPresetsPanel(container) },
            ),
        )
    }
}

/**
 * 面板：顶部选项目 → 看「当前使用」→ 从预设库里挑一套 / 编辑 / 新建。
 *
 * 预设库没有 Flow；每次写操作后自增 [revision] 触发重读（低频操作，够用）。
 */
@Composable
private fun PromptPresetsPanel(container: AppContainer) {
    val store = container.promptPresets
    val projects = remember { container.storage.listProjects() }
    var projectId by remember { mutableStateOf(projects.firstOrNull()?.id) }

    var revision by remember { mutableStateOf(0) }
    val presets = remember(revision) { store.presets() }
    val bound = remember(revision, projectId) { projectId?.let { store.boundPreset(it) } }

    var projectMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PromptPreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<PromptPreset?>(null) }
    var viewingBuiltin by remember { mutableStateOf(false) }

    /** 写操作统一收口：落盘后清段落缓存，下一回合生效。 */
    fun apply(block: () -> Unit) {
        block()
        SystemPromptBuilder.invalidate()
        revision++
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.prompt_presets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.prompt_presets_effective_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (projects.isEmpty()) {
            Text(stringResource(R.string.prompt_presets_no_projects), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // 项目下拉：只列出已有项目，绑定关系按项目 id 落盘。
        val currentProject = projects.firstOrNull { it.id == projectId }
        Box {
            OutlinedButton(onClick = { projectMenu = true }) {
                Text(currentProject?.name ?: stringResource(R.string.prompt_presets_project))
            }
            DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            projectId = project.id
                            projectMenu = false
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.prompt_presets_current), style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                bound == null -> stringResource(R.string.prompt_presets_current_builtin)
                !bound.enabled -> stringResource(R.string.prompt_presets_current_disabled, bound.name)
                bound.preamble.isBlank() -> stringResource(R.string.prompt_presets_current_empty, bound.name)
                else -> stringResource(R.string.prompt_presets_current_bound, bound.name)
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.prompt_presets_library),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.prompt_presets_new)) }
        }

        // 内置默认：永远可选（= 解除绑定），文案只读。
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.prompt_presets_builtin_name), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.prompt_presets_builtin_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { projectId?.let { id -> apply { store.bind(id, null) } } }) {
                    Text(stringResource(R.string.prompt_presets_use))
                }
                TextButton(onClick = { viewingBuiltin = true }) { Text(stringResource(R.string.prompt_presets_view)) }
            }
        }

        if (presets.isEmpty()) {
            Text(stringResource(R.string.prompt_presets_empty), style = MaterialTheme.typography.bodyMedium)
        }
        presets.forEach { preset ->
            PresetRow(
                preset = preset,
                inUse = bound?.id == preset.id,
                referenced = store.boundProjectIds(preset.id).isNotEmpty(),
                onUse = { projectId?.let { id -> apply { store.bind(id, preset.id) } } },
                onEdit = { editing = preset },
                onDelete = { deleting = preset },
            )
        }
    }

    editing?.let { preset ->
        PresetEditorDialog(
            preset = preset,
            onDismiss = { editing = null },
            onSave = { updated ->
                apply { store.update(updated) }
                editing = null
            },
        )
    }

    if (creating) {
        CreatePresetDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                val created = store.create(name)
                apply {}
                creating = false
                editing = created
            },
        )
    }

    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.prompt_presets_delete_title)) },
            text = { Text(stringResource(R.string.prompt_presets_delete_message, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    apply { store.delete(preset.id) }
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (viewingBuiltin) {
        AlertDialog(
            onDismissRequest = { viewingBuiltin = false },
            title = { Text(stringResource(R.string.prompt_presets_builtin_name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        SystemPromptBuilder.defaultSectionText(PreambleContributor.id),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingBuiltin = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun PresetRow(
    preset: PromptPreset,
    inUse: Boolean,
    referenced: Boolean,
    onUse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !preset.enabled -> stringResource(R.string.prompt_presets_state_disabled)
                        preset.preamble.isBlank() -> stringResource(R.string.prompt_presets_state_empty)
                        else -> stringResource(R.string.prompt_presets_state_active, preset.preamble.length)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUse, enabled = !inUse) {
                Text(
                    if (inUse) stringResource(R.string.prompt_presets_in_use)
                    else stringResource(R.string.prompt_presets_use),
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
            Box {
                IconButton(onClick = { menu = true }) { Text("\u22EE") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.prompt_presets_rename)) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (referenced) R.string.prompt_presets_delete_blocked
                                    else R.string.action_delete,
                                ),
                            )
                        },
                        enabled = !referenced,
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 编辑预设：名称、启停、身份正文；改动未保存时离开要确认。 */
@Composable
private fun PresetEditorDialog(
    preset: PromptPreset,
    onDismiss: () -> Unit,
    onSave: (PromptPreset) -> Unit,
) {
    var name by remember(preset.id) { mutableStateOf(preset.name) }
    var text by remember(preset.id) { mutableStateOf(preset.preamble) }
    var enabled by remember(preset.id) { mutableStateOf(preset.enabled) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = name != preset.name || text != preset.preamble || enabled != preset.enabled

    AlertDialog(
        onDismissRequest = { if (dirty) confirmDiscard = true else onDismiss() },
        title = { Text(stringResource(R.string.prompt_presets_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.prompt_presets_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.prompt_presets_enabled),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    stringResource(R.string.prompt_presets_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.prompt_presets_text_placeholder)) },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.prompt_presets_locked_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    preset.copy(
                        name = name.trim().ifEmpty { preset.name },
                        preamble = text.trim(),
                        enabled = enabled,
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.prompt_presets_discard)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDismiss()
                }) { Text(stringResource(R.string.prompt_presets_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun CreatePresetDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_presets_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.prompt_presets_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text(stringResource(R.string.prompt_presets_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
