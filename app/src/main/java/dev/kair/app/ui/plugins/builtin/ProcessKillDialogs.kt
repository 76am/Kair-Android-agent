package dev.kair.app.ui.plugins.builtin

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.core.proot.ContainerProcess

/**
 * 两个结束确认框：单进程与「全部结束」只差标题、正文与确认回调。
 *
 * 从 `ProcessPanel.kt` 拆出来：那边已贴着 B8 的 500 行阈值，这里只有展示。
 */
@Composable
internal fun KillDialogs(
    pendingKill: ContainerProcess?,
    confirmingKillAll: Boolean,
    killAllCount: Int,
    onClearKill: () -> Unit,
    onConfirmKill: (ContainerProcess) -> Unit,
    onClearKillAll: () -> Unit,
    onConfirmKillAll: () -> Unit,
) {
    pendingKill?.let { process ->
        KillDialog(
            title = stringResource(R.string.proot_process_kill_title),
            message = stringResource(
                R.string.proot_process_kill_message,
                process.pid,
                process.command.ifBlank { "-" },
            ),
            onDismiss = onClearKill,
            onConfirm = { onConfirmKill(process) },
        )
    }

    if (confirmingKillAll) {
        KillDialog(
            title = stringResource(R.string.proot_process_kill_all_title),
            message = stringResource(R.string.proot_process_kill_all_message, killAllCount),
            onDismiss = onClearKillAll,
            onConfirm = onConfirmKillAll,
        )
    }
}

/** 单进程结束与「全部结束」共用的确认框：两处只差标题、正文与确认文案。 */
@Composable
private fun KillDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.proot_process_kill))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
