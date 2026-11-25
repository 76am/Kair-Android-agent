package dev.kair.app.ui.plugins.builtin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.subagent.SubagentInfo
import dev.kair.app.core.subagent.SubagentTool
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.screens.chat.ApprovalCard
import dev.kair.app.ui.screens.chat.AskUserCard
import dev.kair.app.ui.screens.chat.MessageCard
import dev.kair.app.ui.screens.chat.matchToolResults
import dev.kair.app.ui.screens.chat.selectableMessageText
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors pi-web's `applicationPanels` + `actions` contributions. */
class SubagentsPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.subagents"
    override val name = "子智能体"
    override val description = "后台子智能体会话的面板与操作。"

    override fun activate(ctx: PluginContext) {
        // 子智能体工具经**插件**贡献（而非宿主直接注册）：宿主 resyncTools() 重建执行
        // 注册表时会清空再按「内置 → 插件贡献 → 宿主工具」重排，直接注册的工具会被丢弃，
        // 模型随之看不到 subagent。
        ctx.tool(SubagentTool(container.subagents))
        // 「清除全部」不做成插件操作区动作：面板自身已提供逐条与整批清除，
        // 放在面板内所见即所得，避免操作区与面板两处重复入口。
        ctx.panel(
            PanelContribution(
                id = "panels.subagents",
                title = "子智能体",
                icon = PluginGlyph.People,
                order = 10,
                content = { SubagentsPanel(container) },
            ),
        )
    }
}

@Composable
private fun SubagentsPanel(container: AppContainer) {
    val agents by container.subagents.agents.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    // 面板内的「列表 ↔ 详情」二级页面：点击某个子智能体进入它的完整循环过程，
    // 返回时不离开面板。选中项随进程重建保留，避免旋转后跳回列表。
    // 返回接管与状态同源（rememberPanelBackSelection）—— 判据是**解析后的** selected，
    // 不是原始 id：记录被 prune / remove 回收后 id 还在、界面已退回列表，
    // 此时按返回必须退出面板，不能被一个指向不存在记录的 id 吞掉。
    var selectedId by rememberPanelBackSelection { id -> agents.any { it.id == id } }
    val selected = selectedId?.let { id -> agents.firstOrNull { it.id == id } }
    if (selected != null) {
        SubagentDetail(
            container = container,
            agent = selected,
            fmt = fmt,
            onBack = { selectedId = null },
        )
        return
    }

    if (agents.isEmpty()) {
        Text(
            stringResource(R.string.subagents_empty),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        // 整批清除放面板顶部（原插件操作区入口移入面板，所见即所得）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { container.subagents.clear() }) {
                Text(stringResource(R.string.subagents_clear))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(agents, key = { it.id }) { agent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 整卡可点：进入详情看完整循环；卡内按钮各自消费点击，不会误触。
                        .clickable { selectedId = agent.id },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(agent.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            AssistChip(onClick = { }, label = { Text(subagentStatusLabel(agent.status)) })
                        }
                        Text(
                            stringResource(R.string.subagents_started, fmt.format(Date(agent.startedAt))),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            container.subagents.lastText(agent.id)?.take(400)?.ifBlank { agent.prompt } ?: agent.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        agent.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { selectedId = agent.id }) {
                                Text(stringResource(R.string.subagents_view))
                            }
                            when (agent.status) {
                                // 活跃（运行中）只给「终止」：先停再清，避免误删正在跑的会话。
                                "working" -> TextButton(onClick = { container.subagents.kill(agent.id) }) {
                                    Text(stringResource(R.string.subagents_kill))
                                }
                                // 已结束（空闲 / 出错）才有「清除」，把这条记录连同会话历史移除。
                                else -> TextButton(onClick = { container.subagents.remove(agent.id) }) {
                                    Text(stringResource(R.string.subagents_remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 子智能体详情：完整循环过程（任务 → 模型输出 / 思考 → 工具调用 → 工具结果）。
 *
 * 消息渲染复用会话页的 [MessageCard]，因此工具分组、输出折叠、错误着色与主会话一致。
 * 若它正卡在审批上，审批卡片也直接显示在这里，用户不必退回父会话才能放行。
 */
@Composable
private fun SubagentDetail(
    container: AppContainer,
    agent: SubagentInfo,
    fmt: SimpleDateFormat,
    onBack: () -> Unit,
) {
    val session = container.subagents.sessionOf(agent.id)
    // session 已随历史修剪被移除时仍能渲染标题，只是正文给出「已清理」提示。
    val messagesFlow = remember(session) {
        session?.messages ?: MutableStateFlow(emptyList<ChatMessage>())
    }
    val messages by messagesFlow.collectAsState()
    val matched = remember(messages) { matchToolResults(messages) }
    val groupedCallIds = remember(messages) {
        messages.asSequence()
            .filter { it.role == Role.ASSISTANT }
            .flatMap { it.toolCalls }
            .mapTo(mutableSetOf()) { it.id }
    }
    val busy = agent.status == "working"
    val runningTurnId = remember(messages) { messages.lastOrNull { it.role == Role.ASSISTANT }?.id }

    // 卡在审批 / 提问时：在这里也能直接看到并处理，避免「详情页看着像卡死」。
    val pendingApproval by container.approvalGate.pending.collectAsState()
    val waiting = pendingApproval.values.firstOrNull { it.sessionId == agent.id }
    val pendingAsk by container.askUserGate.pending.collectAsState()
    val waitingAsk = pendingAsk.values.firstOrNull { it.sessionId == agent.id }

    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var selecting by remember { mutableStateOf<ChatMessage?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.subagents_started, fmt.format(Date(agent.startedAt))),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    stringResource(R.string.subagents_detail_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(onClick = { }, label = { Text(subagentStatusLabel(agent.status)) })
        }
        HorizontalDivider()

        waiting?.let { req ->
            ApprovalCard(req, container.approvalGate.timeoutSeconds) { decision ->
                container.approvalGate.resolve(req.id, decision)
            }
        }

        waitingAsk?.let { req ->
            AskUserCard(req, container.askUserGate.timeoutSeconds) { answer ->
                container.askUserGate.resolve(req.id, answer)
            }
        }

        if (session == null) {
            Text(
                stringResource(R.string.subagents_gone),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.subagents_detail_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    MessageCard(
                        msg = msg,
                        editable = false,
                        retryable = false,
                        deletable = false,
                        matched = matched[msg.id].orEmpty(),
                        running = busy && msg.id == runningTurnId,
                        groupedCallIds = groupedCallIds,
                        onEdit = {},
                        onRetry = {},
                        onDelete = {},
                        onCopy = { m ->
                            clipboard.setPrimaryClip(ClipData.newPlainText("Kair", m.text))
                        },
                        onSelectText = { selecting = it },
                        attachments = session.attachmentStore,
                    )
                }
            }
        }

        agent.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            when (agent.status) {
                "working" -> TextButton(onClick = { container.subagents.kill(agent.id) }) {
                    Text(stringResource(R.string.subagents_kill))
                }
                else -> TextButton(
                    onClick = {
                        container.subagents.remove(agent.id)
                        onBack()
                    },
                ) { Text(stringResource(R.string.subagents_remove)) }
            }
        }
    }

    selecting?.let { msg ->
        AlertDialog(
            onDismissRequest = { selecting = null },
            title = { Text(stringResource(R.string.chat_select_text_title)) },
            text = {
                SelectionContainer {
                    Text(
                        selectableMessageText(msg, matched[msg.id].orEmpty()),
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selecting = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

/** Maps a subagent's wire status (`working`/`idle`/`error`) to its Chinese label. */
@Composable
private fun subagentStatusLabel(status: String): String = when (status) {
    "working" -> stringResource(R.string.subagent_status_working)
    "idle" -> stringResource(R.string.subagent_status_idle)
    "error" -> stringResource(R.string.subagent_status_error)
    else -> status
}
