package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.components.FileEditorBody
import dev.kair.app.ui.plugins.PluginSettingsFields

/**
 * 记忆合并面板（修正批 2026-11-09，负责人拍板「合并成一个完整面板」）：会话顶栏
 * 图标的快捷入口直达这里 —— 一屏内做完所有事，不必退回会话列表另进设置面板。
 *
 * **交互模型（U74，2026-11-11 负责人真机反馈）**：进面板先看到「查看 / 编辑」按钮 + 设置区
 * （折叠态）；点按钮进入**整页编辑器** —— 只剩项目切换 chips 与记忆文件编辑器，
 * 编辑器用 `weight(1f)` 占满整屏（设置区与 hint 不再同屏渲染），顶部「返回」回到折叠态。
 * 与旧设置面板「按钮 → 编辑器」同形（文案复用同一资源），差别只在编辑器可切项目、
 * 不再弹只能编辑当前会话的对话框。
 *
 * 三块复用现成实现，不另起一套：
 * 1. 项目切换 = `WorkspaceFilesPlugin` 的 FilterChip 横滑行（切到别的项目、不必切换活跃项目）；
 * 2. 记忆文件 = [FileEditorBody] **可编辑**（与工作区文件面板 / 声明式文件字段同源，
 *    保存走后端自动建父目录；容器在场时 root 写的文件自动升级到容器内读写）；
 * 3. 其余设置项 = [PluginSettingsFields] 内嵌声明式 spec 渲染（自动整理 / 间隔 / 阈值 /
 *    模型），数据源仍是同一份 [SettingsPanelSpec]，不重复定义。
 *
 * spec 里的 `FileEditor` 字段不在这里渲染 —— 记忆文件编辑已由上面的项目级编辑器承担。
 * 用 copy() 换 fields 时，标题的 titleRes 是类体 var、不进构造器 copy，内嵌区因此不会
 * 重复顶出一个标题（正好）。
 * 默认选中 = `lastProjectId`（首页选中的项目，显式设置项）—— 不读活跃会话 / 工作区
 * 这类隐式全局（GlobalStateGuardTest 插件侧零容忍）。
 */
@Composable
internal fun MemoryPanel(container: AppContainer, spec: SettingsPanelSpec) {
    val projects = remember { container.storage.listProjects() }
    var projectId by remember {
        mutableStateOf(defaultMemoryProjectId(projects, container.settings.lastProjectId))
    }
    val project = projects.firstOrNull { it.id == projectId }
    // 折叠态：首屏只露按钮与设置区；点「查看 / 编辑」进整页编辑器（U74）。
    // 返回接管与状态同源（rememberPanelBackOpen）：开着的时系统返回先收起编辑器回折叠态，
    // 不退面板。旧写法把「状态声明」和「BackHandler」两处分写，U74 就只写了一半。
    var editing by rememberPanelBackOpen()
    // 根 Column 不带 verticalScroll：编辑态下 FileEditorBody 用 weight(1f) 占满剩余高度，
    // 无限高的 Column 里 weight 无效，还会造成双层滚动（任务文档 §3）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!editing) {
            // 折叠态必须自滚：内容是 hint + 按钮 + 4~5 个设置字段，每个字段带一段说明文字，
            // 一屏放不下。根 Column 不能带 verticalScroll（编辑态的 FileEditorBody 靠
            // weight(1f) 占满剩余高度，无限高的父级里 weight 失效且会双层滚动），
            // 所以把这层滚动收进折叠分支里 —— 只在需要的那一半生效。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.memory_viewer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (projects.isEmpty()) {
                    Text(
                        stringResource(R.string.memory_viewer_no_projects),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (projects.isNotEmpty()) {
                    OutlinedButton(onClick = { editing = true }) {
                        Text(stringResource(R.string.plugins_settings_file_open))
                    }
                }
                // 其余设置项（开关 / 数值 / 模型）内嵌渲染；文件字段除外（项目级编辑器承担）。
                PluginSettingsFields(
                    container,
                    container.pluginSettings(MemoryPlugin.ID),
                    spec.copy(fields = spec.fields.filterNot { it is SettingField.FileEditor }),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                projects.forEach { p ->
                    FilterChip(
                        selected = p.id == projectId,
                        onClick = { projectId = p.id },
                        label = { Text(p.name) },
                    )
                }
            }
            project?.let { sel ->
                val workspace = remember(sel.id) { container.storage.workspaceDir(sel) }
                val backend = remember(workspace) { container.fileBackend(workspace) }
                val path = remember(workspace) { MemoryStore.fileOf(workspace.path).absolutePath }
                FileEditorBody(
                    backend = backend,
                    absolutePath = path,
                    label = stringResource(R.string.memory_viewer_file_label, sel.name),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onBack = { editing = false },
                )
            }
        }
    }
}

/** 默认选中：首页选中的项目优先；不在列表里（被删 / 未设过）落第一个；空列表 null。纯逻辑供单测。 */
internal fun defaultMemoryProjectId(projects: List<Project>, lastProjectId: String): String? =
    projects.firstOrNull { it.id == lastProjectId }?.id ?: projects.firstOrNull()?.id
