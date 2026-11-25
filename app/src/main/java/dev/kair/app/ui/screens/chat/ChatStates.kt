package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.storage.SessionMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话页的加载态与空态（U105 从 `ChatScreen.kt` 拆出）。
 *
 * 拆分理由是体量守卫：`ChatScreen` 已经贴在 596 行的登记值上，
 * 两个状态各带一套布局，塞回去会让它越过棘轮；这里只依赖字符串与主题，不带会话状态。
 */

/**
 * 加载态：转圈 + 一句说明；真正「未找到会话」时不转圈。
 *
 * 原先 Loading / Missing 都只是一行居中文字，首屏看起来像页面没准备好；
 * 加载超过 1s 必须有进度反馈（progressive-loading），未找到则是终态、不该转。
 */
@Composable
internal fun ChatLoadingState(missing: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!missing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                stringResource(if (missing) R.string.chat_missing else R.string.files_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 空会话：一句说明 + 三个建议入口。
 *
 * 建议只**填入输入框**、不直接发送 —— 用户还能改词，也不会在没配置模型时白跑一次。
 * `EmptyState` 仍留给纯提示的场景；这里需要动作，所以单独一个组合。
 */
@Composable
internal fun ChatEmptyState(
    hint: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.size(18.dp))
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                R.string.chat_suggestion_files,
                R.string.chat_suggestion_tools,
                R.string.chat_suggestion_explain,
            ).forEach { res ->
                val text = stringResource(res)
                OutlinedButton(
                    onClick = { onPick(text) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(text, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * 删除整段尾部的撤销窗（U105，2026-11-24 起删除语义改为「从这条到末尾」）。
 *
 * **不新增内核 API**：删除先只生效于界面 —— [visible] 把待删的那段尾部过滤掉，
 * Snackbar 的 Short 窗内点「撤销」就把它放回来；窗结束 / 用户发新消息 / 回合开始
 * 才调用 [commit] 真正改写历史。窗内切走界面或进程被杀，删除自动作废（消息留下），
 * 宁可少删也不误删。
 *
 * 一次删除只提交一次（提交点 = 尾部第一条），不再按消息逐条提交：截断是一件事，
 * 逐条调删除会把「从这条起」重复算到后面的每一条上。
 */
internal class MessageDeleteGate {
    private val pending = mutableStateOf<List<ChatMessage>>(emptyList())
    private var pendingCommit: (() -> Unit)? = null
    private var token = 0

    /** 渲染用的消息列表；待删的那段尾部在撤销窗内不可见。 */
    fun visible(messages: List<ChatMessage>): List<ChatMessage> {
        val p = pending.value
        if (p.isEmpty()) return messages
        val ids = p.mapTo(HashSet()) { it.id }
        return messages.filterNot { it.id in ids }
    }

    fun request(
        removed: List<ChatMessage>,
        scope: CoroutineScope,
        snackbar: SnackbarHostState,
        notice: String,
        actionLabel: String,
        commit: () -> Unit,
    ) {
        if (removed.isEmpty()) return
        // 上一条还在撤销窗内：先按它原本的去向落盘，再开始这一组。
        flush()
        pending.value = removed
        pendingCommit = commit
        val requestToken = ++token
        scope.launch {
            val result = snackbar.showSnackbar(
                message = notice,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short,
            )
            if (requestToken != token) return@launch
            if (result != SnackbarResult.ActionPerformed) flushCommitOnly()
            pending.value = emptyList()
            pendingCommit = null
            token++
        }
    }

    /** 取消撤销窗内的删除（发新消息 / 回合开始 / 切换焦点时调用）；没有待删项时不动。 */
    fun cancel(snackbar: SnackbarHostState? = null) {
        if (pending.value.isEmpty()) return
        pending.value = emptyList()
        pendingCommit = null
        token++
        snackbar?.currentSnackbarData?.dismiss()
    }

    /** 把上一段待删的落盘（不取消当前窗）。 */
    private fun flush() {
        flushCommitOnly()
        pending.value = emptyList()
        pendingCommit = null
        token++
    }

    private fun flushCommitOnly() {
        pendingCommit?.invoke()
        pendingCommit = null
    }
}

/**
 * 会话加载结果（U52）。`Loading` 与 `Missing` 必须分开：若都用 `null` 表示，
 * 异步读盘期间会先闪一下「未找到会话」再出现内容 —— 用户读成数据丢了。
 */
internal sealed interface ChatSessionState {
    data object Loading : ChatSessionState
    data object Missing : ChatSessionState

    /** 已解析到项目与会话索引，并打开了会话实例。 */
    class Ready(val meta: SessionMeta, val session: AgentSession) : ChatSessionState
}
