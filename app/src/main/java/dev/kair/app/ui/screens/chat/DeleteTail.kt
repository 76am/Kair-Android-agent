package dev.kair.app.ui.screens.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.core.model.ChatMessage
import kotlinx.coroutines.CoroutineScope

/**
 * 「删除这条及之后」的确认框 + 撤销窗接线（从 ChatScreen 拆出，方法长度守卫）。
 *
 * 状态仍归会话页（只存撤回点 id）：这里只做「算尾部条数 → 确认框 → 交给
 * [MessageDeleteGate] 先隐藏、窗后再截断」这一串纯展示接线。
 */
@Composable
internal fun DeleteTailHost(
    cut: ChatMessage?,
    messages: List<ChatMessage>,
    busy: Boolean,
    gate: MessageDeleteGate,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val target = cut ?: return
    val tail = messages.dropWhile { it.id != target.id }
    if (tail.isEmpty()) return
    val notice = stringResource(R.string.chat_deleted_count, tail.size)
    val undoLabel = stringResource(R.string.action_undo)
    DeleteTailConfirmDialog(
        count = tail.size,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            // 运行中不落盘（deleteMessage 会因 isEditable=false 静默失效），消息保持原样。
            if (!busy) gate.request(tail, scope, snackbar, notice, undoLabel) { onDelete(target.id) }
        },
    )
}
