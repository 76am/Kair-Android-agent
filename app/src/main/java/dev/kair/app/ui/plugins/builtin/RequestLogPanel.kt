package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.ai.CapturedRequest
import dev.kair.app.core.ai.RequestCapture
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.components.RequestExportDialog
import dev.kair.app.ui.icons.PluginGlyph
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请求诊断插件：把本次运行内**所有项目 / 会话**保留的真实请求集中到一处，按需导出。
 *
 * 为什么独立成面板而不是挂在消息气泡上：请求是会话级（累积快照），与单条消息没有稳定映射；
 * 气泡上的入口只能取「最近一次」，还会在多入口之间产生口径分歧。面板只读
 * [RequestCapture]，是唯一入口，也顺带解决了「用户不知道要长按」。
 *
 * 子智能体的请求按父会话归组展示，保留规则更紧（只留失败），见 [RequestCapture.markSubagent]。
 */
class RequestLogPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.requestlog"
    override val name = "请求诊断"
    override val description = "查看本次运行发出过的请求并导出，用于错误反馈。"

    override fun activate(ctx: PluginContext) {
        ctx.panel(
            PanelContribution(
                id = "panels.requestlog",
                title = "请求诊断",
                icon = PluginGlyph.Envelope,
                order = 25,
                content = { RequestLogPanel(container) },
            ),
        )
    }
}

@Composable
internal fun RequestLogPanel(container: AppContainer) {
    // 记录发生在网络线程，面板只订阅版本号后重建快照，不轮询。
    val version by RequestCapture.version.collectAsState()
    val groups = remember(version) { buildRequestGroups(container, RequestCapture.snapshot()) }
    val count = remember(version) { groups.sumOf { it.count } }
    var exporting by remember { mutableStateOf<CapturedRequest?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val budgetMb = RequestCapture.MAX_TOTAL_CHARS * 2 / 1_000_000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.request_log_scope, count, budgetMb),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count > 0) {
            TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.request_log_clear))
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (groups.isEmpty()) {
            Text(
                stringResource(R.string.request_log_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        groups.forEach { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(requestGroupTitle(group), style = MaterialTheme.typography.titleSmall)
                    group.entries.forEach { entry ->
                        RequestEntryRow(entry, timeFormat) { exporting = it }
                    }
                    group.subagents.forEach { sub ->
                        Text(
                            stringResource(R.string.request_log_subagent, sub.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        sub.entries.forEach { entry ->
                            RequestEntryRow(entry, timeFormat) { exporting = it }
                        }
                    }
                }
            }
        }
    }

    exporting?.let { entry ->
        RequestExportDialog(
            requests = listOf(entry),
            onDismiss = { exporting = null },
            onDone = { file ->
                exporting = null
                note = if (file != null) {
                    context.getString(R.string.request_export_done, file.absolutePath)
                } else {
                    context.getString(R.string.request_export_failed)
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.request_log_clear_title)) },
            text = { Text(stringResource(R.string.request_log_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    RequestCapture.clearAll()
                    confirmClear = false
                    note = context.getString(R.string.request_log_cleared)
                }) { Text(stringResource(R.string.request_log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.request_export_close))
                }
            },
        )
    }
}

@Composable
private fun RequestEntryRow(
    entry: CapturedRequest,
    timeFormat: SimpleDateFormat,
    onExport: (CapturedRequest) -> Unit,
) {
    val status = if (entry.statusCode != null) {
        stringResource(R.string.request_export_status, entry.statusCode)
    } else {
        stringResource(R.string.request_export_no_status)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(
                R.string.request_export_entry,
                timeFormat.format(Date(entry.at)),
                status,
                entry.body.length / 1024,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.streamFailure != null) {
            // 状态码是 200 但流异常结束：不写这行，用户会以为这一发是成功的（U61）。
            Text(
                stringResource(R.string.request_export_stream_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (entry.bodySkipped) {
            // 没捕到请求体不是失败，但排查时看不到参数会误导，必须写明原因。
            Text(
                stringResource(R.string.request_export_body_skipped),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onExport(entry) }) {
                Text(stringResource(R.string.request_log_export))
            }
        }
    }
}

private data class RequestSubagentGroup(
    val name: String,
    val entries: List<CapturedRequest>,
)

private data class RequestSessionGroup(
    /** 项目名；没有持久化会话时为空。 */
    val project: String?,
    /** 会话标题；没有持久化会话时为空。 */
    val session: String?,
    val rawId: String,
    val entries: List<CapturedRequest>,
    val subagents: List<RequestSubagentGroup>,
) {
    val count: Int get() = entries.size + subagents.sumOf { it.entries.size }
    val latestAt: Long
        get() = maxOf(
            entries.lastOrNull()?.at ?: 0L,
            subagents.maxOfOrNull { it.entries.lastOrNull()?.at ?: 0L } ?: 0L,
        )
}

/**
 * 按「根会话」聚合：子智能体沿 parentId 归到最外层会话下，与审批卡片的口径一致。
 * 标题优先用存储里的项目 / 会话名；子智能体不是持久会话，回落成短 id。
 */
private fun buildRequestGroups(
    container: AppContainer,
    snapshot: Map<String, List<CapturedRequest>>,
): List<RequestSessionGroup> {
    val projectNames = runCatching {
        container.storage.listProjects().associate { it.id to it.name }
    }.getOrDefault(emptyMap())
    val sessionMeta = runCatching {
        container.storage.listSessions().associateBy { it.id }
    }.getOrDefault(emptyMap())
    val agents = container.subagents.agents.value.associateBy { it.id }
    val own = LinkedHashMap<String, List<CapturedRequest>>()
    val children = LinkedHashMap<String, MutableList<RequestSubagentGroup>>()
    snapshot.filterValues { it.isNotEmpty() }.forEach { (id, entries) ->
        val agent = agents[id]
        if (agent != null) {
            val root = container.subagents.rootSessionOf(id) ?: agent.parentId ?: id
            children.getOrPut(root) { mutableListOf() }
                .add(RequestSubagentGroup(agent.name, entries))
        } else {
            own[id] = entries
        }
    }
    return (own.keys + children.keys).distinct().map { root ->
        val meta = sessionMeta[root]
        RequestSessionGroup(
            project = meta?.let { projectNames[it.projectId] },
            session = meta?.name,
            rawId = root,
            entries = own[root].orEmpty(),
            subagents = children[root].orEmpty()
                .sortedByDescending { it.entries.lastOrNull()?.at ?: 0L },
        )
    }.sortedByDescending { it.latestAt }
}

/** 会话标题：有持久化元数据就用「项目 · 会话」，否则回落到短 id。 */
@Composable
private fun requestGroupTitle(group: RequestSessionGroup): String = when {
    group.session == null -> stringResource(R.string.request_log_unknown_session, group.rawId.take(8))
    group.project == null -> group.session
    else -> stringResource(R.string.request_log_group, group.project, group.session)
}
