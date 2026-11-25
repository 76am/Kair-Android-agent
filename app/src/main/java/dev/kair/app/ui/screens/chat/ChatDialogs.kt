package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part

/**
 * 撤回类动作（U22）：编辑（改用户消息文本）或重试（撤回某条 agent 消息及其后续）。
 * 从 ChatScreen 拆到这里（U102，方法长度守卫）：类型与确认弹窗是纯展示件，
 * 判定逻辑（`toolNamesForRewrite` / `requestRewrite`）留在会话页。
 */
internal sealed interface HistoryRewrite {
    val messageId: String

    data class Edit(override val messageId: String, val newText: String) : HistoryRewrite

    data class Retry(override val messageId: String) : HistoryRewrite
}

/** 撤回前确认：把「撤回 ≠ 撤销磁盘 / 环境改动」讲清楚，列出撤回范围内调用过的工具。 */
@Composable
internal fun RewriteConfirmDialog(
    tools: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rewrite_title)) },
        text = {
            Text(stringResource(R.string.chat_rewrite_message, tools.joinToString("\n") { "• $it" }))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_rewrite_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 删除尾部确认：删除语义是「从这条到末尾」，条数必须写进标题 —— 否则用户以为只删一条。
 * 确认后仍有撤销窗（[MessageDeleteGate]），这里只负责把范围讲清楚。
 */
@Composable
internal fun DeleteTailConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_delete_from_title, count)) },
        text = { Text(stringResource(R.string.chat_delete_from_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 手动压缩确认：顶栏入口是个图标按钮，不熟悉的人容易误按，先解释清楚再执行。 */
@Composable
internal fun CompactConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_compact_confirm_title)) },
        text = { Text(stringResource(R.string.chat_compact_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.chat_compact_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 选取文字弹窗：气泡内长按已被弹窗菜单占用，选字统一在这里做（正文 + 思考 + 工具全文）。 */
@Composable
internal fun SelectTextDialog(
    message: ChatMessage,
    matched: List<Part.ToolResult?>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_select_text_title)) },
        text = {
            SelectionContainer {
                Text(
                    selectableMessageText(message, matched),
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
