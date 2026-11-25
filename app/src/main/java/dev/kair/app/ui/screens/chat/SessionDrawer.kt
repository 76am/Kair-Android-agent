package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.ProjectOpResult
import dev.kair.app.app.createProject
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.ui.components.DestructiveTextButton
import dev.kair.app.ui.components.ProjectNameDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * U102 会话抽屉：侧边栏承载「项目切换 + 会话列表 + 设置 / 插件」，会话页左上角 ☰ 拉开。
 *
 * U115 起壳由 [dev.kair.app.ui.components.PushDrawer] 提供（推屏式）：本组件只排内容，
 * 宽度由外壳限制在「卡片收起后仍可见」的 65%；背景与系统栏内边距也由外壳 / 本组件分担，
 * 不再用 M3 的 `ModalDrawerSheet`。
 *
 * 首页不再承担会话列表职责（冷启动直达最近会话，见 [KairNav]）；重命名 / 删除收进条目
 * 长按弹窗，功能不缩水。列表按会话最近活跃时间分组（今天 / 昨天 / 更早），当前会话用
 * `primaryContainer` 高亮 —— 蓝是 agent 一方的地盘，但「当前会话」同样是「它」的语义，
 * 与工具名 / 运行态共用一支色（颜色即角色，U102）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SessionDrawerContent(
    container: AppContainer,
    currentSessionId: String,
    refresh: Int,
    onRefresh: () -> Unit,
    onOpenSession: (Project, SessionMeta) -> Unit,
    onNewSession: (Project) -> Unit,
    // 新建项目成功：宿主关抽屉并换到该项目的新会话页（0 会话项目不留在抽屉空列表）。
    onProjectCreated: (Project) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    // 会话被删除后回调（项目 + 被删 id）：由宿主按**当前路由**判断是不是当前会话并跳转。
    // 抽屉里的 currentSessionId 可能落后（草稿换真 id），不能在这里比（U106）。
    onSessionDeleted: (Project, String) -> Unit = { _, _ -> },
    // 项目被删除后回调：宿主按当前路由的 projectId 判断是否需要离开该项目。
    onProjectDeleted: (Project) -> Unit = {},
) {
    val projects = remember(refresh) { container.storage.listProjects() }
    var selectedId by rememberSaveable { mutableStateOf(container.settings.lastProjectId) }
    val project = projects.firstOrNull { it.id == selectedId } ?: projects.firstOrNull()
    // 恢复 / 回落所选项目：与首页同一套约定（选中项被删时落到第一个项目并写回设置）。
    LaunchedEffect(project?.id) {
        val pid = project?.id
        if (pid != null && pid != selectedId) {
            selectedId = pid
            container.settings.lastProjectId = pid
        }
    }
    val sessions = remember(refresh, project?.id) {
        project?.let { container.storage.listSessions(it.id).sortedByDescending { it.updatedAt } } ?: emptyList()
    }
    val running by container.runningSessions.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    // 长按目标 → SessionActionDialogs（重命名 / 删除两段确认都在里面，见下）。
    var actionFor by remember { mutableStateOf<Pair<Project, SessionMeta>?>(null) }
    // 项目行的「⋯」→ 先选动作，再进改名 / 删除确认。U102 重构把项目管理留在了旧首页，
    // 抽屉成为唯一入口后这组动作就失踪了 —— 这里补回，并与消息动作弹窗同形（不是老的
    // 两个文字按钮并排，那个在窄屏 / 大字号下会被截断）。
    var manageProjectFor by remember { mutableStateOf<Project?>(null) }
    // 抽屉分段（U115 返工）：项目 → 会话。切项目不再用浮层菜单，改走就地列表。
    // 一个项目都没有（U116 无项目页）时直接开在项目段：那页唯一的动作就是新建项目。
    var tabProjects by rememberSaveable { mutableStateOf(projects.isEmpty()) }
    // 项目行右侧的会话数：只在进入项目段时算一次（本地库，项目数量级很小）。
    val sessionCounts = remember(refresh, tabProjects) {
        if (tabProjects) projects.associate { it.id to container.storage.listSessions(it.id).size }
        else emptyMap()
    }
    // 点项目 = 切项目 + 右边同步落到该项目：有会话开最近一条，0 会话直接进新会话页（U116）。
    // 不能停在抽屉空列表 —— 那样背后还是上一个项目的会话，用户读成「切了没反应」。
    val pickProject: (Project) -> Unit = { picked ->
        selectedId = picked.id
        container.settings.lastProjectId = picked.id
        tabProjects = false
        val latest = container.storage.listSessions(picked.id).maxByOrNull { it.updatedAt }
        if (latest != null) onOpenSession(picked, latest) else onNewSession(picked)
    }

    // 没有项目时（U116 无项目页）不分段：列底动作与文案都只能是「新建项目」。
    val showNewProject = tabProjects || project == null

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        DrawerHeader(project = project, onOpenProjects = { tabProjects = true })
        DrawerTabs(
            tabProjects = tabProjects,
            onProjects = { tabProjects = true },
            onSessions = { tabProjects = false },
        )

        if (tabProjects) {
            DrawerProjectList(
                projects = projects,
                sessionCounts = sessionCounts,
                currentId = project?.id,
                onPick = pickProject,
                onManage = { manageProjectFor = it },
                modifier = Modifier.weight(1f),
            )
        } else {
            DrawerSessionList(
                sessions = sessions,
                project = project,
                currentSessionId = currentSessionId,
                running = running,
                timeFmt = timeFmt,
                dateFmt = dateFmt,
                modifier = Modifier.weight(1f),
                onOpenSession = onOpenSession,
                onSessionLongPress = { p, s -> actionFor = p to s },
            )
        }

        // 新建动作钉在列底（头部不再有 ＋）：会话态建会话、项目态建项目，同一个位置一个动作。
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { if (showNewProject) showCreate = true else project?.let(onNewSession) })
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (showNewProject) R.string.projects_new else R.string.sessions_new),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, stringResource(R.string.cd_settings))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cd_settings), style = MaterialTheme.typography.bodyLarge)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlugins)
                // U105：原先只有 bottom 13dp，命中区约 37dp；补成上下对称的 13dp（≈50dp）。
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Extension, stringResource(R.string.cd_plugins))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cd_plugins), style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (showCreate) {
        ProjectNameDialog(
            title = stringResource(R.string.projects_new),
            initialName = "",
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreate = false },
            onConfirm = { raw ->
                when (val result = container.createProject(raw)) {
                    is ProjectOpResult.Ok -> {
                        selectedId = result.project.id
                        container.settings.lastProjectId = result.project.id
                        onRefresh()
                        // 建完即进该项目的新会话页：抽屉不在新页面上继续挂着。
                        onProjectCreated(result.project)
                        null
                    }

                    is ProjectOpResult.Failed -> result.error
                }
            },
        )
    }

    actionFor?.let { (owner, target) ->
        SessionActionDialogs(
            container = container,
            actionFor = owner to target,
            onDismissAction = { actionFor = null },
            onRefresh = onRefresh,
            onSessionDeleted = onSessionDeleted,
        )
    }

    ProjectActionDialogs(
        container = container,
        manageFor = manageProjectFor,
        onDismissManage = { manageProjectFor = null },
        onRefresh = onRefresh,
        onProjectSelected = {
            selectedId = it
            container.settings.lastProjectId = it
        },
        onProjectDeleted = onProjectDeleted,
    )
}

/**
 * 会话列表（抽屉「会话」段）：按最近活跃时间分组，当前会话 `primaryContainer` 高亮并带
 * `selected` 语义，长按进重命名 / 删除。U115 返工从 `SessionDrawerContent` 拆出：分段要在
 * 会话段与项目段之间切换，两段各自成块才塞得进同一个 `if / else`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionList(
    sessions: List<SessionMeta>,
    project: Project?,
    currentSessionId: String,
    running: Set<String>,
    timeFmt: SimpleDateFormat,
    dateFmt: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onOpenSession: (Project, SessionMeta) -> Unit,
    onSessionLongPress: (Project, SessionMeta) -> Unit,
) {
    LazyColumn(modifier) {
        if (sessions.isEmpty()) {
            // 空项目不能是白板：有项目时说「X 还没有会话」；一个项目都没有时（U116
            // 无项目页）不能拼出空名字，直接给创建引导。
            item {
                Text(
                    if (project == null) stringResource(R.string.projects_empty)
                    else stringResource(R.string.drawer_empty_sessions, project.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                )
            }
        }
        itemsIndexed(sessions, key = { _, it -> it.id }) { index, session ->
            val group = drawerGroup(session.updatedAt)
            val previous = sessions.getOrNull(index - 1)
            if (previous == null || drawerGroup(previous.updatedAt) != group) {
                Text(
                    stringResource(group.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            val current = session.id == currentSessionId
            val currentLabel = stringResource(R.string.sessions_current)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (current) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .combinedClickable(
                        onClick = {
                            if (project != null) {
                                onOpenSession(project, session)
                            }
                        },
                        onLongClick = { project?.let { onSessionLongPress(it, session) } },
                    )
                    // U105：当前会话不能只靠底色 —— 读屏要知道「已选中」，
                    // 灰阶 / 色觉障碍用户也要有文字线索（见下方时间行的「当前」）。
                    .semantics { selected = current }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (current) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val timeText = if (group.isToday) timeFmt.format(Date(session.updatedAt))
                    else dateFmt.format(Date(session.updatedAt))
                    Text(
                        (if (current) "$currentLabel · " else "") + timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (session.id in running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/**
 * 长按会话条目的两段确认：先选「重命名 / 删除」，再各自确认（删除不可逆）。
 * 状态自持：[SessionDrawerContent] 只负责把长按目标递进来，改名 / 删除的弹窗链在这里闭环。
 *
 * U104 修回归：子弹窗的状态（renameFor / deleteFor）活在本组合里，**选择框按钮不能
 * 顺手 onDismissAction**（那会把 actionFor 清空、本组合整个被拆掉，确认框永远渲染不出来，
 * 表现就是「删除不了会话」）。子弹窗打开期间保持组合存活，确认/取消后再由子流程收尾。
 */
@Composable
private fun SessionActionDialogs(
    container: AppContainer,
    actionFor: Pair<Project, SessionMeta>,
    onDismissAction: () -> Unit,
    onRefresh: () -> Unit,
    onSessionDeleted: (Project, String) -> Unit,
) {
    var renameFor by remember { mutableStateOf<SessionMeta?>(null) }
    var deleteFor by remember { mutableStateOf<Pair<Project, SessionMeta>?>(null) }
    val (owner, target) = actionFor

    if (renameFor == null && deleteFor == null) {
        AlertDialog(
            onDismissRequest = onDismissAction,
            title = { Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { renameFor = target }) { Text(stringResource(R.string.cd_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = owner to target }) { Text(stringResource(R.string.cd_delete)) }
            },
        )
    }

    renameFor?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = {
                renameFor = null
                onDismissAction()
            },
            title = { Text(stringResource(R.string.rename_session_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.sessions_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    container.storage.renameSession(t.id, name.trim())
                    renameFor = null
                    onDismissAction()
                    onRefresh()
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    renameFor = null
                    onDismissAction()
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleteFor?.let { (project, t) ->
        AlertDialog(
            onDismissRequest = {
                deleteFor = null
                onDismissAction()
            },
            title = { Text(stringResource(R.string.sessions_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.sessions_delete_confirm_message, t.name))
            },
            confirmButton = {
                DestructiveTextButton(stringResource(R.string.action_delete)) {
                    container.deleteSession(project, t.id)
                    // 无条件上报，是否「删的是当前会话」交给宿主按实时路由判断 ——
                    // 这里比较 currentSessionId 会因抽屉参数落后而漏跳。
                    onSessionDeleted(project, t.id)
                    deleteFor = null
                    onDismissAction()
                    onRefresh()
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteFor = null
                    onDismissAction()
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 抽屉分组：今天（0）/ 昨天（1）/ 更早（2）。 */
internal enum class DrawerGroup(val isToday: Boolean, val label: Int) {
    TODAY(true, R.string.sessions_group_today),
    YESTERDAY(false, R.string.sessions_group_yesterday),
    EARLIER(false, R.string.sessions_group_earlier),
}

/** 按最近活跃时间归组：自然日差（今天 / 昨天 / 更早），跨年用年差累加自然日近似即可。 */
internal fun drawerGroup(updatedAt: Long): DrawerGroup {
    val then = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val now = Calendar.getInstance()
    val day = { c: Calendar -> c.get(Calendar.YEAR) * 366 + c.get(Calendar.DAY_OF_YEAR) }
    return when (day(now) - day(then)) {
        0 -> DrawerGroup.TODAY
        1 -> DrawerGroup.YESTERDAY
        else -> DrawerGroup.EARLIER
    }
}
