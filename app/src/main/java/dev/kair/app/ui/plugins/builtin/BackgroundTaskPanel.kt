package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.BackgroundTask
import dev.kair.app.app.BackgroundTaskManager
import dev.kair.app.app.BackgroundTaskState
import dev.kair.app.app.BackgroundTaskTool
import dev.kair.app.app.elapsedSeconds
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.tools.Tool
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 后台任务面板：agent 启动的长命令、状态、耗时、输出尾部与取消入口。 */
internal fun backgroundTaskPanel(title: String, manager: BackgroundTaskManager): PanelContribution =
    PanelContribution(
        id = "panels.tasks",
        title = title,
        icon = PluginGlyph.List,
        order = 32,
        content = { BackgroundTaskPanel(manager, title) },
    )

/** 由 ProotPlugin 注册工具时调用：面板文件里一并导出，避免容器插件多一条 import。 */
internal fun backgroundTaskTool(manager: BackgroundTaskManager): Tool = BackgroundTaskTool(manager)

@Composable
internal fun BackgroundTaskPanel(manager: BackgroundTaskManager, title: String) {
    val tasks by manager.tasks.collectAsState()
    // 运行中的耗时每秒刷新；面板离开组合时协程自动取消。
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick = System.currentTimeMillis()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.task_panel_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tasks.isEmpty()) {
            Text(stringResource(R.string.task_panel_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            tasks.forEach { task -> key(task.id) { TaskCard(task, manager, tick) } }
        }
    }
}

@Composable
private fun TaskCard(task: BackgroundTask, manager: BackgroundTaskManager, now: Long) {
    var showLog by remember(task.id) { mutableStateOf(false) }
    var logText by remember(task.id) { mutableStateOf<String?>(null) }
    var confirmCancel by remember(task.id) { mutableStateOf(false) }
    LaunchedEffect(showLog) {
        if (showLog) logText = withContext(Dispatchers.IO) { manager.logTail(task.id) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLabel(task.state),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(task.state),
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.task_meta_session, task.sessionId.take(8)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            task.command.lineSequence().first(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (task.running) {
                // 上限 0 = 不限时（常驻服务）。走共用文案会印成「上限 0 秒」，
                // 用户读成「0 秒就超时」，与真实语义正好相反，所以这种任务不带时限。
                if (task.timeoutSeconds == 0L) {
                    stringResource(
                        R.string.task_meta_running_unbounded,
                        elapsedSeconds(task, now),
                        task.pid,
                    )
                } else {
                    stringResource(
                        R.string.task_meta_running,
                        elapsedSeconds(task, now),
                        task.pid,
                        task.timeoutSeconds,
                    )
                }
            } else {
                stringResource(
                    R.string.task_meta_finished,
                    elapsedSeconds(task, now),
                    task.exitCode?.toString() ?: stringResource(R.string.task_meta_exit_unknown),
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton({ showLog = !showLog }) {
                Text(stringResource(if (showLog) R.string.task_log_hide else R.string.task_log_show))
            }
            if (task.running) {
                TextButton({ confirmCancel = true }) {
                    Text(stringResource(R.string.task_action_cancel))
                }
            }
        }
        if (showLog) {
            val shown = logText?.takeIf { it.isNotBlank() } ?: stringResource(R.string.task_log_empty)
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.task_cancel_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.task_cancel_message,
                        task.id,
                        task.command.lineSequence().first(),
                    ),
                )
            },
            confirmButton = {
                TextButton({
                    confirmCancel = false
                    manager.cancelAsync(task.sessionId, task.id)
                }) { Text(stringResource(R.string.task_action_cancel)) }
            },
            dismissButton = {
                TextButton({ confirmCancel = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun statusLabel(state: BackgroundTaskState): String = stringResource(
    when (state) {
        BackgroundTaskState.RUNNING -> R.string.task_status_running
        BackgroundTaskState.SUCCEEDED -> R.string.task_status_succeeded
        BackgroundTaskState.FAILED -> R.string.task_status_failed
        BackgroundTaskState.CANCELLED -> R.string.task_status_cancelled
        BackgroundTaskState.TIMED_OUT -> R.string.task_status_timed_out
        BackgroundTaskState.TERMINATED -> R.string.task_status_terminated
    },
)

/**
 * `TERMINATED` 与 `FAILED` 同色（红）：它不是用户点的，也不该被当成「已结束、可忽略」——
 * 用户以为还在跑的常驻服务其实已经挂了，必须一眼看见。灰色留给 `CANCELLED`（用户自己干的）。
 */
@Composable
private fun statusColor(state: BackgroundTaskState): Color = when (state) {
    BackgroundTaskState.RUNNING -> MaterialTheme.colorScheme.primary
    BackgroundTaskState.SUCCEEDED -> MaterialTheme.colorScheme.primary
    BackgroundTaskState.FAILED, BackgroundTaskState.TIMED_OUT, BackgroundTaskState.TERMINATED ->
        MaterialTheme.colorScheme.error
    BackgroundTaskState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}
