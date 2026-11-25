package dev.kair.app.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.TurnState
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.CommandContext
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.ui.Routes
import dev.kair.app.ui.browser.BrowserHost
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.clearFocusOnTap
import dev.kair.app.ui.components.formatTokens
import dev.kair.app.ui.components.readImagePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    projectId: String,
    sessionId: String,
    onSwitchSession: (String) -> Unit,
    onOpenSession: (Project, SessionMeta) -> Unit,
    onNewSession: (Project) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    // 草稿路由 = 纯空会话界面（U110）：**不建 SessionMeta、不开 AgentSession**。
    // 此前「进页即建档」是抽屉里凭空出现「新会话」、以及删除后界面不走的根源，
    // 见 ChatDraftScreen 的文件注释。
    if (sessionId == Routes.DRAFT_SESSION_ID) {
        ChatDraftScreen(
            container = container,
            projectId = projectId,
            onSessionCreated = onSwitchSession,
            onOpenDrawer = onOpenDrawer,
            onOpenPanel = onOpenPanel,
            onOpenSettings = onOpenSettings,
        )
        return
    }
    // 会话解析与打开移出组合体（U52）：`openSession` 会同步读完整份会话日志
    // （`loadHistory` 的 `useLines`，含内联图片时可达数十 MB），放在 remember 里就是首帧读盘。
    // produceState 的块本身仍在组合线程的协程里跑，所以必须显式下到 Dispatchers.IO。
    // 一次产出 project / meta / session 的完整快照：分三次异步读会拼出不存在的组合。
    val sessionState by produceState<ChatSessionState>(
        initialValue = ChatSessionState.Loading,
        key1 = projectId,
        key2 = sessionId,
    ) {
        value = withContext(Dispatchers.IO) {
            val project = container.storage.listProjects().firstOrNull { it.id == projectId }
                ?: return@withContext ChatSessionState.Missing
            val meta = container.storage.listSessions().firstOrNull { it.id == sessionId }
                ?: return@withContext ChatSessionState.Missing
            ChatSessionState.Ready(meta, container.openSession(project, meta))
        }
    }
    val ready = sessionState as? ChatSessionState.Ready
    if (ready == null) {
        // 加载中不能显示「未找到会话」：那会让用户看到一闪而过的错误态（U52 验收 V4）。
        // U105：加载态不再是一条纯文字，补 22dp 转圈；真正「未找到」时不转圈。
        ChatLoadingState(missing = sessionState is ChatSessionState.Missing)
        return
    }
    val meta = ready.meta
    val session = ready.session
    // 顶栏标题：会话档案名可被首条消息改写（U102 自动命名），单独持有以便即时回显。
    var displayTitle by remember(meta.id) { mutableStateOf(meta.name) }
    val context = LocalContext.current
    // 九宫格角标：浏览器正在被 Agent 操作时亮一个点，提示后台仍在动。
    val browserState by BrowserHost.controller(context.applicationContext).state.collectAsState()

    val focusManager = LocalFocusManager.current
    // 退后台就放掉焦点：Compose 会在窗口重新获得焦点时把焦点还给原节点，不清就会
    // 「切出去再切回来」自动选中输入框、弹键盘。ON_RESUME 再兜一次异步恢复的机型。
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { focusManager.clearFocus() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { focusManager.clearFocus() }

    val messages by session.messages.collectAsState()
    // U111：解码速度从消息快照里还原（实时相位 + 单发 stats）。不落盘、不进状态机。
    val speed = rememberChatSpeed(session, messages)
    // 会话在干什么只看这一个封闭状态：压缩是 Running 的相位、停止收尾是 Stopping，
    // 不再分别订阅 status / compacting / stopping（它们曾是四个互相牵制的量）。
    val turnState by session.turnState.collectAsState()
    val queue by session.queue.collectAsState()
    val lastCompaction by session.lastCompaction.collectAsState()
    val pendingApproval by container.approvalGate.pending.collectAsState()
    val pendingAsk by container.askUserGate.pending.collectAsState()
    // 审批 / 提问闸门按**展示会话**索引：每个会话各自持有待处理请求，切到别的会话时
    // 不会把上一个会话的卡片带过来（见 Issue #8），不同会话也不会互相排队遮挡。
    // 子智能体发起的请求带有 ownerSessionId（顶层父会话）：按它归属，否则子智能体
    // 等审批、父会话看不到卡片，会永久挂起。
    val myApproval = pendingApproval[sessionId]
    val myAsk = pendingAsk[sessionId]
    val commands = container.plugins.commands() // 组合期直读（U46）：remember 住快照会在装/卸/启停后陈旧
    val draft = rememberChatDraft()
    val editing = resolveDraftTarget(messages, draft.editingId)
    val selectingText = resolveDraftTarget(messages, draft.selectingId)
    // 撤回类动作（编辑 / 重试）。撤回范围内的 agent 只要调用过工具，执行前先弹窗确认 ——
    // 撤回只丢弃对话内容、不撤销磁盘 / 环境的实际改动，这在「编辑」场景最容易被误以为改动也被撤销。
    var pendingRewrite by remember { mutableStateOf<HistoryRewrite?>(null) }
    var rewriteTools by remember { mutableStateOf<List<String>>(emptyList()) }
    // 用户随消息附加的图片（多模态）：选中后挂在输入框上方预览，发送时一并带上。
    var pendingImage by remember { mutableStateOf<Part.Image?>(null) }
    // 撤回编辑（U22）要把焦点送回输入框：输入框由 ComposerInputBar 持有，这里只传引用。
    val focusRequester = remember { FocusRequester() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // 读盘 / 编码可能碰到大图，放到 IO 线程；完成后回主线程更新预览状态。
            container.scope.launch {
                val image = withContext(Dispatchers.IO) { readImagePart(container, uri) }
                if (image != null) pendingImage = image
            }
        }
    }
    var showQuick by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 「是否贴底」只喂自动跟随；↓ 按钮改挂「是否跟随」（见 rememberJumpToBottomVisible）。
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // 每次「回到底部」的显式请求（轻点输入框 / 点 ↓）。用计数而非布尔：
    // 键盘已弹出时再点输入框也要能再触发一次。
    var bottomRequest by remember { mutableStateOf(0) }

    val compacting = (turnState as? TurnState.Running)?.compacting == true
    // 回合在跑（含停止收尾）→ 压缩圆环不可点；停止键与状态条由输入区按 turnState 自己派生。
    val busy = turnState is TurnState.Running || turnState == TurnState.Stopping
    // 控件随插件走：禁用压缩插件时其能力贡献点被回收，圆环面板里的压缩入口随之隐藏
    // （压缩依赖 ContextPlugin 的压缩策略）。
    val compactVisible = container.plugins.contextStrategy() != null

    // While the composer holds a bare "/word" we surface matching plugin commands.
    val slashQuery = draft.input.trim().takeIf { it.startsWith("/") && ' ' !in it }?.drop(1)
    val commandSuggestions =
        slashQuery?.let { q -> commands.filter { it.name.startsWith(q, ignoreCase = true) } } ?: emptyList()
    // 上下文用量按「消息条数 / 最近一次实测 usage / 末尾消息」取键：流式增量每帧都会换一个
    // messages 实例，若直接 remember(messages)，长思考回合就会每帧重算一遍全量估算。
    val lastUsage = messages.lastOrNull { it.usage != null }?.usage
    val usedTokens = remember(messages.size, messages.lastOrNull()?.id, lastUsage) {
        session.estimatedContextTokens()
    }
    // 上下文窗口只随「当前模型」变化、与消息无关，不在这里 remember(messages)，否则切模型后
    // 圆环要等到下一条消息才刷新；窗口由 ComposerDock 计算（见它 windowProvider 的注释）。
    // 命中率与「缓存与诊断」面板同口径：按 token 加权累计，而不是最近一发的逐发比值。
    val cacheHit = container.cacheInspector.latest.collectAsState().value[sessionId]?.hitTally?.weightedRate
    // 工具输出按「所在回合 + 调用」匹配（见 matchToolResults），不依赖 toolCallId
    // 全局唯一——部分兼容端点会复用 "call_0" 这类 id，按 id 全局建表会让老回合
    // 串到别的回合的输出。被收纳的结果不再单独成卡，因此渲染 TOOL_RESULT 消息
    // 时仍按 groupedCallIds 过滤孤儿。
    // 匹配表按结构缓存（ToolResultIndex）：流式每帧换的是消息列表实例而非配对关系，
    // 每帧重建会让下游拿到「不相等的新 Map」，已完成的回合跟着每帧重组。
    val resultIndex = remember { ToolResultIndex() }
    val matchedResults = remember(messages) { resultIndex.match(messages) }
    // 只给「最后一条 assistant 且还有挂起调用」的消息转圈；否则刚发下一条消息、新回合
    // 还没产出 assistant 的窗口里，上一条已完成的回答会被误标成运行中。
    val runningTurnId = remember(messages, matchedResults) {
        val last = messages.lastOrNull { it.role == Role.ASSISTANT } ?: return@remember null
        last.id.takeIf { matchedResults[last.id].orEmpty().any { slot -> slot == null } }
    }
    val groupedCallIds = remember(messages) {
        messages.asSequence()
            .filter { it.role == Role.ASSISTANT }
            .flatMap { it.toolCalls }
            .mapTo(mutableSetOf()) { it.id }
    }

    // 压缩完成提示：每次压缩弹一次「已压缩 X → Y」，让用户明确
    // 知道刚发生过压缩，而不是只在会话顶部留一张检查点卡片。
    // 「已提示」由会话侧消费（consumeLastCompaction）而非界面级 remembered ——
    // 会话对象长期存活、界面会重建，界面级去重会在重进会话时丢失并反复弹。
    val snackbar = remember { SnackbarHostState() }
    // 会话文件读不出来（权限 / IO）时明确告知：空会话与「本来就没有历史」长得一样，
    // 不提示用户会以为数据被清空。错误由存储层在 openSession 时记录，这里取走一次。
    LaunchedEffect(sessionId) {
        container.takeStorageLoadError(sessionId)?.let { snackbar.showSnackbar(it) }
    }
    ChatDraftRestoreEffects(draft, editing, selectingText, pendingImage, snackbar)
    // 手动压缩前先确认：顶栏是个图标按钮，不熟悉的人容易误按，先解释清楚再执行。
    var confirmCompact by remember { mutableStateOf(false) }
    val compactionMessage = lastCompaction?.let {
        stringResource(
            R.string.chat_compacted_done,
            formatTokens(it.tokensBefore),
            formatTokens(it.tokensAfter),
        )
    }
    LaunchedEffect(lastCompaction) {
        val message = compactionMessage ?: return@LaunchedEffect
        // 先展示、后消费：consumeLastCompaction() 会改掉本 effect 的 key，
        // 若先消费，effect 会被自身取消，showSnackbar 还没来得及显示就被清掉。
        snackbar.showSnackbar(message)
        session.consumeLastCompaction()
    }

    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.chat_copied)
    // 删除是「从这条到末尾」：先记下撤回点，弹确认框把条数讲清楚，确认后才进撤销窗。
    var deleteTail by remember { mutableStateOf<ChatMessage?>(null) }
    // U105：删除先生效于界面（隐藏 + 撤销条），撤销窗结束后才真正改写历史 ——
    // 不给内核加「恢复某条消息」的 API，也不会出现「点错了只能重发」。
    val deleteGate = remember { MessageDeleteGate() }
    val visibleMessages = deleteGate.visible(messages)
    // 回合切分（设计稿 v2）：消息流折成 FeedItem 列表，判据与消息、运行态同源。
    val feedItems = remember(visibleMessages, matchedResults, busy, speed.stats) {
        buildFeedItems(visibleMessages, matchedResults, busy, speed.stats)
    }
    val lastTurnSpeed = rememberCompletedSpeed(feedItems)
    // 展开态提到列表层，供行级渲染拆开折叠体（见 FeedRows.kt）；行数随展开变化。
    val expansion = rememberFoldExpansion()
    val rows = remember(feedItems, expansion.keys) { buildRows(feedItems, expansion.keys) }
    // 自动跟随最新内容到底部（实现见 AutoScrollToLatest）；返回值是 ↓ 按钮的唯一判据。
    // 滚动目标按**行**算：列表渲染的是 rows（展开的回合每一步都是一行）。
    val following = AutoScrollToLatest(listState, rows.size, messages, atBottom, bottomRequest)
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // U105：回合一旦开始就取消撤销窗内的删除 —— 删除只在空闲态可落盘，
    // 否则界面藏了消息、历史却没删。宁可把消息留下。
    LaunchedEffect(busy) {
        if (busy) deleteGate.cancel(snackbar)
    }

    /** 真正执行撤回动作（编辑 / 重试）。 */
    fun executeRewrite(action: HistoryRewrite) {
        when (action) {
            is HistoryRewrite.Edit -> session.editUserMessage(action.messageId, action.newText)
            is HistoryRewrite.Retry -> session.retryFrom(action.messageId)
        }
    }

    /**
     * 撤回前检查：撤回范围内只要**调用过工具**就先确认，没有工具调用则直接执行。
     * 编辑看这条用户消息之后的回合；重试看从撤回点起的回合（含被重试的那条自身）。
     * 只有最终回复、没碰过任何工具的回合可以直接撤回，不打扰用户。
     */
    fun requestRewrite(action: HistoryRewrite) {
        val tools = toolNamesForRewrite(messages, action.messageId, includeSelf = action is HistoryRewrite.Retry)
        if (tools.isEmpty()) {
            executeRewrite(action)
        } else {
            rewriteTools = tools
            pendingRewrite = action
        }
    }

    fun copyText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Kair", text))
        scope.launch { snackbar.showSnackbar(copiedMessage) }
    }

    /** 重试某条 agent 消息：只撤回这一条及其后续，让模型基于剩下的结尾重新生成。 */
    fun retryTurn(assistant: ChatMessage) {
        requestRewrite(HistoryRewrite.Retry(assistant.id))
    }

    /**
     * 一次「发送」：走 `send` —— 忙时入 `FOLLOW_UP`、本回合结束才作为新回合发出，空闲时直接起回合。
     * 插话不在这一排（排队区每条的 △ 改道，见 [promoteToSteer]）。
     */
    fun submit() {
        // U105：撤销窗内发消息 = 放弃这次删除（消息留下）。否则回合开始后
        // `deleteMessage` 会因 isEditable=false 静默失效，界面却已经把它藏掉了。
        deleteGate.cancel(snackbar)
        when (val action = resolveSubmit(draft.input, pendingImage, commands)) {
            is SubmitAction.Command -> {
                action.command.run(CommandContext(session, action.args))
                // 「发送」= 消费整个输入区：命令不消费图片，若不清会留下旧图静默挂在输入框。
                draft.input = ""
                pendingImage = null
            }
            is SubmitAction.Message -> {
                // U102 首条消息自动命名：空会话的第一句话取前 12 字做档案名
                // （deriveSessionTitle 见 ChatDraft.kt）。手动改过名的空会话极少见，被覆盖可接受。
                if (messages.isEmpty()) {
                    deriveSessionTitle(action.text)?.let { derived ->
                        container.storage.renameSession(sessionId, derived)
                        displayTitle = derived
                    }
                }
                session.send(action.text, listOfNotNull(action.image))
                draft.input = ""
                pendingImage = null
            }
            SubmitAction.Ignore -> Unit
        }
    }

    Scaffold(
        topBar = {
            KairTopBar(
                title = { Text(displayTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_sessions))
                    }
                },
                actions = {
                    // 顶栏基本是身份区（U101），设计稿 v2 起多一个九宫格：面板快捷入口。
                    QuickEntryButton(onClick = { showQuick = true }, showBadge = browserState.busy)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 键盘弹出时把内容整体上移，避免输入框被输入法遮挡。先消费 Scaffold
                // 已施加的系统栏内边距，再让 imePadding 只补键盘高度，不重复算导航栏。
                .consumeWindowInsets(padding)
                .imePadding()
                // 点输入框以外的地方取消焦点（光标消失、键盘收起）。U104 起改用共享
                // clearFocusOnTap：它除了清焦点还会显式收 IME（安卓 8 上只清焦点键盘不退）。
                .clearFocusOnTap(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
                    // 行距由每条消息自带（`MessageTurn` 的下留白），列表本身不再加 spacing。
                    verticalArrangement = Arrangement.Top,
                ) {
                    // 空会话（清空历史后）给一条居中提示：发消息即可继续，不是死屏。
                    if (visibleMessages.isEmpty()) {
                        item(key = "empty-hint") {
                            ChatEmptyState(
                                hint = stringResource(R.string.chat_empty_hint),
                                modifier = Modifier.fillParentMaxSize(),
                                onPick = { draft.input = it; focusRequester.requestFocus() },
                            )
                        }
                    }
                    // 回合级折叠（设计稿 v2）：用户消息 / 折叠头 / 展开后的每一步 / 最终回答
                    // 各占一行（见 FeedRows.kt）—— 上百步的回合展开时也只组合可见的几行。
                    items(
                        items = rows,
                        key = { it.key },
                        contentType = { it::class },
                    ) { row ->
                        FeedRowItem(
                            row = row,
                            expansion = expansion,
                            busy = busy,
                            matchedResults = matchedResults,
                            groupedCallIds = groupedCallIds,
                            runningTurnId = runningTurnId,
                            attachments = session.attachmentStore,
                            onEdit = { draft.editingId = it.id },
                            onRetry = { retryTurn(it) },
                            onDelete = { cut -> deleteTail = cut },
                            onCopy = { copyText(it) },
                            onSelectText = { draft.selectingId = it.id },
                        )
                    }
                }

                // 提示条挂消息区顶部，不用 Scaffold 默认的屏幕底部宿主：本页输入区与 dock 在
                // content Column 尾部、不在 bottomBar 槽里，屏幕底部会压住「停止 / 发送」。
                // 顶部盖住列表最上一截（上翻时旧消息上沿、可能含卡片长按区与 ⋯ 行），不是尾部。
                SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))

                // 上翻看历史时给一个可见的「回到底部」入口：贴底后输入框才会重新跟随键盘上推。
                // 判据是「手动上翻 + 24dp 迟滞」，不是每帧的 atBottom —— 后者流式期间会 15Hz 频闪。
                if (rememberJumpToBottomVisible(listState, following, rows.size)) {
                    JumpToBottomButton(
                        onClick = { bottomRequest++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            if (queue.isNotEmpty()) {
                QueueSection(
                    queue = queue,
                    onRemove = { session.removeQueued(it) },
                    onRecall = { queued ->
                        // 撤回竞态（B 审 §B4.4 P2）：queue 是 collectAsState 的当前值，那条若已在
                        // 回合末被排空发出，removeQueued 静默无效 —— 不在队列就直接返回，
                        // 否则留下「已发出 + 输入框又有一份」。不改 removeQueued 签名：它经
                        // CommandContext.session 暴露给插件 dex，加返回值属破坏性变更（KairApi.VERSION）。
                        if (queue.none { it.message.id == queued.message.id }) return@QueueSection
                        val recall = recallPayload(queued.message)
                        // 先回填、再移除：少了移除就是「输入框有、队列里还有」，再发一次会重复发送。
                        draft.input = recall.text
                        pendingImage = recall.image
                        session.removeQueued(queued.message.id)
                        focusRequester.requestFocus()
                    },
                    // △ 改道：回合末发送 → 立即插话（判在队列、先移除再 steer，实现见 promoteToSteer）。
                    onPromote = { promoteToSteer(session, queue, it) },
                )
            }

            // 审批闸门：agent 请求执行需确认的工具时挂起在此，直到用户决定。
            myApproval?.let { req ->
                ApprovalCard(req, container.approvalGate.timeoutSeconds) { decision ->
                    container.approvalGate.resolve(req.id, decision)
                }
            }

            // 提问面板：agent 调用 ask_user 后挂起在此，直到用户作答或跳过。
            myAsk?.let { req ->
                AskUserCard(req, container.askUserGate.timeoutSeconds) { answer ->
                    container.askUserGate.resolve(req.id, answer)
                }
            }

            if (commandSuggestions.isNotEmpty()) {
                CommandSuggestions(commandSuggestions) { cmd ->
                    draft.input = "/${cmd.name} "
                }
            }

            // 坞与控件行共享同一个展开状态：坞画在输入板上方、模型 / 圆环画在板内，
            // 点板里的 chip 要把面板弹到板的上面，两处状态分开就会「点了没反应」。
            var dockPanel by remember { mutableStateOf(DockPanel.NONE) }

            // 输入框底部 UI 区：展开的面板（模型 / 思考强度 / 上下文详情）。
            // 面板坞已撤掉（设计稿 v2）—— 面板入口在顶栏九宫格拉起的底部 sheet 里。
            ComposerDock(
                container = container,
                sessionId = sessionId,
                used = usedTokens,
                windowProvider = { session.contextWindow() },
                cacheHit = cacheHit,
                compacting = compacting,
                compactVisible = compactVisible,
                compactEnabled = compactVisible && !busy && messages.isNotEmpty(),
                turnSpeed = lastTurnSpeed,
                sessionAverageTps = speed.sessionAverageTps,
                open = dockPanel,
                onOpenChange = { dockPanel = it },
                onCompact = { confirmCompact = true },
                onOpenCache = { onOpenPanel("panels.cache") },
            )

            // 待发送图片预览 + 输入板（附件 / 输入框 / 停止键 / 发送键 + 下沿控件行）。
            ComposerInputBar(
                input = draft.input,
                onInputChange = { draft.input = it },
                pendingImage = pendingImage,
                onPickImage = { pickImage.launch("image/*") },
                onClearImage = { pendingImage = null },
                turnState = turnState,
                awaitingUser = myApproval != null || myAsk != null,
                speedPhase = speed.phase,
                liveSpeedText = speed.liveText,
                speedSuffix = sessionSpeedText(speed.sessionAverageTps),
                focusRequester = focusRequester,
                onStop = StopConfirm(queue = { queue }, onAbort = { session.abort() }),
                onSubmit = { submit() },
                onBottomRequest = { bottomRequest++ },
                footer = {
                    ComposerControlRow(
                        container = container,
                        sessionId = sessionId,
                        workspace = session.workspace.path,
                        used = usedTokens,
                        windowProvider = { session.contextWindow() },
                        compacting = compacting,
                        open = dockPanel,
                        onOpenChange = { dockPanel = it },
                        onOpenPanel = onOpenPanel,
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
        }
    }

    editing?.let { msg ->
        EditMessageDialog(
            initial = msg.text,
            onDismiss = { draft.editingId = null },
            onSave = { text ->
                draft.editingId = null
                requestRewrite(HistoryRewrite.Edit(msg.id, text))
            },
        )
    }

    if (confirmCompact) {
        CompactConfirmDialog(
            onDismiss = { confirmCompact = false },
            onConfirm = {
                confirmCompact = false
                session.compactNow()
            },
        )
    }

    // 选取文字：气泡内长按已被弹窗菜单占用，且气泡里没有任何内联选区（否则会与菜单抢
    // 手势、还弹出系统「全选 / 复制」工具条），所以选字统一在这个弹窗里做 —— 正文 + 思考
    // + 工具输入 / 输出的全文都在这里，可选可复制（见 selectableMessageText）。
    selectingText?.let { msg ->
        SelectTextDialog(
            message = msg,
            matched = matchedResults[msg.id].orEmpty(),
            onDismiss = { draft.selectingId = null },
        )
    }

    // 删除这条及之后：确认框写明条数；确认后走撤销窗，窗结束才真正截断历史。
    DeleteTailHost(
        cut = deleteTail,
        messages = messages,
        busy = busy,
        gate = deleteGate,
        scope = scope,
        snackbar = snackbar,
        onDelete = session::deleteMessage,
        onDismiss = { deleteTail = null },
    )

    pendingRewrite?.let { action ->
        RewriteConfirmDialog(
            tools = rewriteTools,
            onDismiss = {
                pendingRewrite = null
                rewriteTools = emptyList()
            },
            onConfirm = {
                pendingRewrite = null
                rewriteTools = emptyList()
                executeRewrite(action)
            },
        )
    }

    // 面板快捷入口（设计稿 v2）：顶栏九宫格 → 底部 sheet 3 列网格。
    if (showQuick) {
        QuickPanelSheet(container, onOpenPanel = onOpenPanel, onDismiss = { showQuick = false })
    }
}
