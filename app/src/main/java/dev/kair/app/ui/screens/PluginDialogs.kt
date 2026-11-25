package dev.kair.app.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.core.plugin.PluginData
import dev.kair.app.ui.components.DestructiveTextButton
import java.io.File

/**
 * 插件页的三个确认对话框（关核心插件 / 强制安装不兼容包 / 删除插件含数据）。
 * 状态与动作仍由 [PluginsScreen] 持有，这里只负责渲染与回调。
 */

/** 待「强制安装」确认的插件包：临时文件 + 不兼容原因列表。 */
internal class PendingInstall(val file: File, val reasons: List<String>)

/** 待确认的「删除插件（含数据）」：插件 id / 展示名（含资源通道）+ 待删数据项。 */
internal class PendingDelete(val id: String, val name: String, val nameRes: Int?, val data: List<PluginData>)

/** 关核心插件前确认：避免误关导致基础能力静默降级。 */
@Composable
internal fun CoreDisableDialog(
    pluginName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_core_disable_title, pluginName)) },
        text = { Text(stringResource(R.string.plugins_core_disable_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.plugins_core_disable_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 兼容检查失败：列出具体原因，并提供「强制安装」（用户自行承担风险）。 */
@Composable
internal fun IncompatibleInstallDialog(
    reasons: List<String>,
    onForce: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.plugins_compat_title)) },
        text = {
            Text(
                stringResource(
                    R.string.plugins_compat_message,
                    reasons.joinToString("\n"),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onForce) { Text(stringResource(R.string.plugins_compat_force)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 删除声明了数据的插件：列出待删数据项，确认后插件与数据一并清理（不进回收站）。 */
@Composable
internal fun DeletePluginDataDialog(
    pluginName: String,
    data: List<PluginData>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_delete_data_title, pluginName)) },
        text = {
            Text(
                stringResource(
                    R.string.plugins_delete_data_message,
                    data.joinToString("\n") { "  • ${it.path}（${it.label}）" },
                ),
            )
        },
        confirmButton = {
            DestructiveTextButton(stringResource(R.string.plugins_delete), onClick = onConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
