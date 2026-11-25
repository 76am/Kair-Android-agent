package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.settings.supportsReasoningOverride
import dev.kair.app.ui.components.DestructiveTextButton
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.clearFocusOnTap
import java.util.UUID

/**
 * 设置页：多模型配置管理（列表 / 增删改 / 激活）+ 全局智能体参数。
 *
 * 顶层在「列表」与「模型编辑器」之间切换：编辑器占满整屏（字段多，弹窗放不下）。
 * 模型列表本身只读 `AppContainer.activeModel`（解析本体自 U31 ③-a 起在
 * `SessionAssembly.activeModel`，容器上是同名委托），激活项由 `settings.activeModelId` 决定。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    var editing by remember { mutableStateOf<SavedModel?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    if (editorOpen) {
        ModelEditorScreen(
            initial = editing ?: newSavedModel(),
            onCancel = {
                editorOpen = false
                editing = null
            },
            onSave = { model ->
                container.settings.upsertModel(model)
                editorOpen = false
                editing = null
            },
        )
    } else {
        SettingsList(
            container = container,
            onBack = onBack,
            onOpenPlugins = onOpenPlugins,
            onAdd = {
                editing = newSavedModel()
                editorOpen = true
            },
            onEdit = { model ->
                editing = model
                editorOpen = true
            },
        )
    }
}

/** 新建模型的默认值：预填首个内置协议（OpenAI 兼容）与它的默认 URL。 */
private fun newSavedModel(): SavedModel {
    val def = ModelCatalog.providers.first()
    return SavedModel(
        id = UUID.randomUUID().toString(),
        name = "",
        providerId = def.id,
        baseUrl = def.defaultBaseUrl,
        model = "",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPlugins: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (SavedModel) -> Unit,
) {
    val settings = container.settings
    // SharedPreferences 不是 Compose 状态：直接读的话，切换 / 删除模型后界面不会重组，
    // 单选钮仍停在旧模型。这里用本地状态承接，并在每次写入后同步。
    var models by remember { mutableStateOf(settings.savedModels) }
    var activeId by remember { mutableStateOf(settings.activeModel()?.id) }

    var pendingDelete by remember { mutableStateOf<SavedModel?>(null) }

    var maxSteps by remember { mutableStateOf(settings.maxSteps.toString()) }
    var addendum by remember { mutableStateOf(settings.systemPromptAddendum) }
    var compatStrict by remember { mutableStateOf(settings.pluginCompatStrict) }
    var refreshPromptEveryTurn by remember { mutableStateOf(settings.refreshPromptEveryTurn) }
    // 子智能体默认模型：本地 state 作为显示源（见下方 SubagentModelPicker 注释）。
    var subagentModelId by remember { mutableStateOf(settings.subagentModelId) }
    // 子智能体思考强度覆盖：空串 = 跟随所选模型配置。同样用本地 state 承接显示。
    var subagentReasoningEffort by remember { mutableStateOf(settings.subagentReasoningEffort) }
    val subagentModel = models.firstOrNull { it.id == subagentModelId }

    // 插件概览：不是可观测状态，每次进入设置页重新组合即刷新。
    val builtinPlugins = container.builtinPluginDefs
    val builtinEnabled = builtinPlugins.count { container.isPluginEnabled(it.id) }
    val thirdPartyCount = container.dynamicPlugins.installedIds().size
    val disabledCount = (builtinPlugins.size - builtinEnabled) +
        container.dynamicPlugins.disabledIds().size

    Scaffold(
        topBar = {
            KairTopBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clearFocusOnTap()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 34.dp),
        ) {
            SettingsSection(
                number = "01",
                title = stringResource(R.string.settings_model),
                count = models.size,
            )
            SettingsModelSection(
                models = models,
                activeId = activeId,
                onAdd = onAdd,
                onEdit = onEdit,
                onActivate = {
                    container.activateModel(it.id)
                    activeId = it.id
                },
                onDelete = { pendingDelete = it },
            )

            SettingsSection(
                number = "02",
                title = stringResource(R.string.settings_agent),
                count = 4,
            )
            AgentSettingsGroup(
                maxSteps = maxSteps,
                onMaxStepsChange = {
                    maxSteps = it
                    it.toIntOrNull()?.let { n -> settings.maxSteps = n }
                },
                addendum = addendum,
                onAddendumChange = {
                    addendum = it
                    settings.systemPromptAddendum = it
                },
                models = models,
                subagentModelId = subagentModelId,
                onSubagentModelSelect = { id ->
                    subagentModelId = id
                    settings.subagentModelId = id
                    // 换模型 / 切回「跟随会话模型」时清掉不再适用的思考强度覆盖：
                    // 设置页看不到的值不能继续在后台生效。
                    val picked = models.firstOrNull { it.id == id }
                    val keep = picked?.supportsReasoningOverride(subagentReasoningEffort) == true
                    if (!keep) {
                        subagentReasoningEffort = ""
                        settings.subagentReasoningEffort = ""
                    }
                },
                subagentModel = subagentModel,
                subagentReasoningEffort = subagentReasoningEffort,
                onSubagentReasoningSelect = { level ->
                    subagentReasoningEffort = level
                    settings.subagentReasoningEffort = level
                },
            )

            SettingsSection(
                number = "03",
                title = stringResource(R.string.settings_section_prompt),
                count = 1,
            )
            SettingsGroup {
                // 每回合是否重算系统提示段：段落缓存看不到贡献点依赖的外部状态（插件读的
                // 工作区文件）。纯聊天也要刷新 —— 后台子智能体可能刚改写记忆文件。
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_refresh_prompt_every_turn),
                    hint = stringResource(R.string.settings_refresh_prompt_every_turn_hint),
                    checked = refreshPromptEveryTurn,
                    divider = false,
                    onCheckedChange = {
                        refreshPromptEveryTurn = it
                        settings.refreshPromptEveryTurn = it
                    },
                )
            }

            SettingsSection(
                number = "04",
                title = stringResource(R.string.settings_section_plugins),
                count = 2,
            )
            SettingsGroup {
                // 第三方插件安装时的版本兼容检查模式：严格（默认）→ 不兼容即拒绝；
                // 关闭（跳过模式）→ 仅告警放行。两种模式下都可在插件页对单个插件强制安装。
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_plugin_compat_strict),
                    hint = stringResource(R.string.settings_plugin_compat_strict_hint),
                    checked = compatStrict,
                    divider = false,
                    onCheckedChange = {
                        compatStrict = it
                        settings.pluginCompatStrict = it
                    },
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_plugins_row),
                    subtitle = stringResource(
                        R.string.settings_plugins_row_subtitle,
                        builtinPlugins.size,
                        thirdPartyCount,
                        disabledCount,
                    ),
                    onClick = onOpenPlugins,
                )
            }
            PluginContributionsSection(container = container)

            SettingsSection(
                number = "05",
                title = stringResource(R.string.settings_permissions),
                count = 2,
            )
            PermissionsSection(container = container)

            SettingsSection(
                number = "06",
                title = stringResource(R.string.settings_device_permissions),
                count = 1,
            )
            RootProbeSection()
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.settings_model_delete_confirm, target.name.ifBlank { target.model })) },
            confirmButton = {
                DestructiveTextButton(stringResource(R.string.action_delete)) {
                    container.settings.deleteModel(target.id)
                    models = settings.savedModels
                    activeId = settings.activeModel()?.id
                    pendingDelete = null
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 智能体参数组：步数、全局追加指令、子智能体模型与思考强度。 */
@Composable
private fun AgentSettingsGroup(
    maxSteps: String,
    onMaxStepsChange: (String) -> Unit,
    addendum: String,
    onAddendumChange: (String) -> Unit,
    models: List<SavedModel>,
    subagentModelId: String,
    onSubagentModelSelect: (String) -> Unit,
    subagentModel: SavedModel?,
    subagentReasoningEffort: String,
    onSubagentReasoningSelect: (String) -> Unit,
) {
    SettingsGroup {
        SettingsStepperRow(
            title = stringResource(R.string.settings_max_steps),
            hint = stringResource(R.string.settings_max_steps_hint),
            value = maxSteps.toIntOrNull() ?: 200,
            min = 1,
            max = 400,
            divider = false,
            onValueChange = { onMaxStepsChange(it.toString()) },
        )
        SettingsDivider()
        // 这里只放**全局追加**的规则；身份（开场白）不在此处改 —— 那是按项目绑定的
        // 「提示词预设」。两者别混：写在这里的内容会拼进每个会话的提示末尾。
        SettingsTextArea(
            label = stringResource(R.string.settings_addendum),
            value = addendum,
            onValueChange = onAddendumChange,
            hint = stringResource(R.string.settings_addendum_hint),
            counter = stringResource(R.string.settings_addendum_counter, addendum.length),
        )
        SubagentModelPicker(
            models = models,
            selectedId = subagentModelId,
            onSelect = onSubagentModelSelect,
        )
        SubagentReasoningPicker(
            model = subagentModel,
            selected = subagentReasoningEffort,
            onSelect = onSubagentReasoningSelect,
        )
    }
}
