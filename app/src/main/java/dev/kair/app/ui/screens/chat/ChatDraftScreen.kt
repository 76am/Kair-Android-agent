package dev.kair.app.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.TurnState
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.model.Part
import dev.kair.app.core.plugin.CommandContext
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.Routes
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.clearFocusOnTap
import dev.kair.app.ui.components.readImagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 草稿页的项目解析结果：加载中与「项目已不存在」必须分开（同 [ChatSessionState] 的理由）。 */
private sealed interface DraftProjectState {
    data object Loading : DraftProjectState
    data object Missing : DraftProjectState
    data class Ready(val project: Project) : DraftProjectState
}

/**
 * 空会话界面（U110）：**草稿路由不再建档**。
 *
 * U102 的实现是进页即 `newSession()` 并把路由换成真 id；那条路径留下两个错位：
 * 用户什么都没发，抽屉里已经有「新会话」；换路由放在一次性 `LaunchedEffect` 里，
 * 过渡期被跳过就永远不重试，后续自动命名写到不存在的 id、删除当前会话时路由参数
 * 又对不上真 id —— 表现就是「删了，界面还在」。
 *
 * 现在：草稿 = 无 `SessionMeta`、无 `AgentSession` 的纯界面；首条消息发送时才一次完成
 * 「建档（名字取消息前缀）→ 打开 → 发送 → 换路由」。删除当前会话也回到这里，
 * 不再自动挑最近一条或补建新会话。
 *
 * 草稿的模型选择沿用 `DRAFT_SESSION_ID` 这一档覆盖：建档时复制到真会话，
 * 下次新建仍保留上一次选择（[DraftComposer] 的 submit）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDraftScreen(
    container: AppContainer,
    projectId: String,
    onSessionCreated: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val projectState by produceState<DraftProjectState>(DraftProjectState.Loading, projectId) {
        value = withContext(Dispatchers.IO) {
            container.storage.listProjects().firstOrNull { it.id == projectId }
                ?.let(DraftProjectState::Ready) ?: DraftProjectState.Missing
        }
    }
    val ready = projectState as? DraftProjectState.Ready
    if (ready == null) {
        ChatLoadingState(missing = projectState is DraftProjectState.Missing)
        return
    }
    DraftComposer(
        container = container,
        project = ready.project,
        onSessionCreated = onSessionCreated,
        onOpenDrawer = onOpenDrawer,
        onOpenPanel = onOpenPanel,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftComposer(
    container: AppContainer,
    project: Project,
    onSessionCreated: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val draft = rememberChatDraft()
    var pendingImage by remember { mutableStateOf<Part.Image?>(null) }
    // 建档是异步的（写索引 + 打开会话）：挡住在完成前连点发送。
    var creating by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    var dockPanel by remember { mutableStateOf(DockPanel.NONE) }
    val commands = container.plugins.commands()
    val noModel = stringResource(R.string.onboarding_no_model)
    val workspace = remember(project.id) { container.storage.workspaceDir(project).absolutePath }
    val windowProvider: () -> Int = {
        val model = container.sessionModel(Routes.DRAFT_SESSION_ID)
        model?.contextWindow ?: model?.let { ModelCatalog.contextWindow(it.model) } ?: 0
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val image = withContext(Dispatchers.IO) { readImagePart(container, uri) }
                if (image != null) pendingImage = image
            }
        }
    }
    val slashQuery = draft.input.trim().takeIf { it.startsWith("/") && ' ' !in it }?.drop(1)
    val commandSuggestions =
        slashQuery?.let { q -> commands.filter { it.name.startsWith(q, ignoreCase = true) } } ?: emptyList()

    /**
     * 一次「发送」：消息或命令都先建档。
     *
     * 用 `container.scope` 而不是组合作用域：建档期间用户可能按返回，组合被销毁时
     * 不能把「已建好的会话 + 已写索引」丢在后台；路由替换由宿主按 entry 身份决定是否执行。
     * 命名直接在建档时给定，不再有「先叫新会话、再 rename」的两段式。
     */
    fun submit(raw: String = draft.input, image: Part.Image? = pendingImage) {
        if (creating) return
        val action = resolveSubmit(raw, image, commands)
        if (action == SubmitAction.Ignore) return
        if (container.sessionModel(Routes.DRAFT_SESSION_ID) == null) {
            scope.launch { snackbar.showSnackbar(noModel) }
            return
        }
        creating = true
        val title = (action as? SubmitAction.Message)?.text?.let(::deriveSessionTitle)
        container.scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val meta = if (title != null) {
                        container.newSession(project, title)
                    } else {
                        container.newSession(project)
                    }
                    // 草稿里选的模型带到真会话：否则建档后模型 chip 会跳回全局默认。
                    container.settings.sessionModelId(Routes.DRAFT_SESSION_ID)
                        ?.let { container.setSessionModel(meta.id, it) }
                    meta to container.openSession(project, meta)
                }
            }.onSuccess { (meta, session) ->
                withContext(Dispatchers.Main) {
                    when (action) {
                        is SubmitAction.Command -> action.command.run(CommandContext(session, action.args))
                        is SubmitAction.Message -> session.send(action.text, listOfNotNull(action.image))
                        SubmitAction.Ignore -> Unit
                    }
                    draft.input = ""
                    pendingImage = null
                    creating = false
                    onSessionCreated(meta.id)
                }
            }.onFailure {
                withContext(Dispatchers.Main) { creating = false }
            }
        }
    }

    Scaffold(
        topBar = {
            KairTopBar(
                title = { Text(stringResource(R.string.chat_draft_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_sessions))
                    }
                },
                actions = {
                    // 与真会话同形：九宫格拉面板快捷入口（空会话里也能先看面板）。
                    QuickEntryButton(onClick = { showQuick = true })
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .clearFocusOnTap(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ChatEmptyState(
                    hint = stringResource(R.string.sessions_auto_hint),
                    modifier = Modifier.fillMaxSize(),
                    onPick = { draft.input = it; focusRequester.requestFocus() },
                )
                // 无模型等一次性提示挂在消息区顶部，不压输入板。
                SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))
            }

            if (commandSuggestions.isNotEmpty()) {
                CommandSuggestions(commandSuggestions) { cmd ->
                    draft.input = "/${cmd.name} "
                }
            }

            ComposerDock(
                container = container,
                sessionId = Routes.DRAFT_SESSION_ID,
                used = 0,
                windowProvider = windowProvider,
                cacheHit = null,
                compacting = false,
                compactVisible = container.plugins.contextStrategy() != null,
                compactEnabled = false,
                open = dockPanel,
                onOpenChange = { dockPanel = it },
                onCompact = {},
                onOpenCache = { onOpenPanel("panels.cache") },
            )

            ComposerInputBar(
                input = draft.input,
                onInputChange = { draft.input = it },
                pendingImage = pendingImage,
                onPickImage = { pickImage.launch("image/*") },
                onClearImage = { pendingImage = null },
                turnState = TurnState.Idle,
                awaitingUser = false,
                focusRequester = focusRequester,
                onStop = {},
                onSubmit = { submit() },
                onBottomRequest = {},
                footer = {
                    ComposerControlRow(
                        container = container,
                        sessionId = Routes.DRAFT_SESSION_ID,
                        workspace = workspace,
                        used = 0,
                        windowProvider = windowProvider,
                        compacting = false,
                        open = dockPanel,
                        onOpenChange = { dockPanel = it },
                        onOpenPanel = onOpenPanel,
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
        }
    }

    if (showQuick) {
        QuickPanelSheet(container, onOpenPanel = onOpenPanel, onDismiss = { showQuick = false })
    }
}
