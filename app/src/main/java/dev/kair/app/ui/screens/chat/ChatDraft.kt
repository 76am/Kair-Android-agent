package dev.kair.app.ui.screens.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part

/**
 * 会话输入区的草稿状态（U54）。
 *
 * 为什么单独一个类：`ChatScreen` 的文件体量与方法体量两条棘轮都已顶格，重建后要保留的状态
 * 只能收进新文件。存什么与不存什么：
 * - [input] 原文逐字恢复；
 * - [editingId] / [selectingId] 只存消息 id，恢复时按 id 从历史现算（消息被删 / 撤回 → 安全关闭）；
 * - [hadImage] 只存「保存那一刻待发区有没有图」，图片本体**不进** SavedState
 *   （base64 可达数 MB，塞进去会 `TransactionTooLargeException`）。
 */
internal class ChatDraft {
    var input by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)
    var selectingId by mutableStateOf<String?>(null)
    var hadImage by mutableStateOf(false)
}

/** `input` 与三个 id / 标记都进 Saver；图片本体不进（见 [ChatDraft] 的 KDoc）。 */
internal val ChatDraftSaver: Saver<ChatDraft, List<Any?>> = Saver(
    save = { draft -> listOf(draft.input, draft.editingId, draft.selectingId, draft.hadImage) },
    restore = { saved ->
        ChatDraft().apply {
            input = saved[0] as String
            editingId = saved[1] as String?
            selectingId = saved[2] as String?
            hadImage = saved[3] as Boolean
        }
    },
)

@Composable
internal fun rememberChatDraft(): ChatDraft = rememberSaveable(saver = ChatDraftSaver) { ChatDraft() }

/** 按 id 找回消息；已被删 / 撤回 → null（等于安全关闭编辑或选文弹窗）。 */
internal fun resolveDraftTarget(messages: List<ChatMessage>, id: String?): ChatMessage? =
    id?.let { wanted -> messages.firstOrNull { it.id == wanted } }

/** 保存时有图、恢复后没图 → 必须提示一次；判据是「保存时有」，不是「现在没有」。 */
internal fun imageDraftLost(hadImage: Boolean, pendingImage: Part.Image?): Boolean =
    hadImage && pendingImage == null

/**
 * 草稿恢复后的两件善后（`ChatScreen` 只留一行调用）。
 *
 * 1. **清掉解析不出来的 id**：编辑 / 选文的目标消息可能已被删 / 撤回，界面已经退回无弹窗态，
 *    但 `editingId` 还留着 —— 不清就是一个永远不会命中的残留 id。
 * 2. **丢图提示一次**：图片本体不进 SavedState，恢复后 `pendingImage` 必为 null。
 *    判据在首次组合时定格（`remember` 无 key），否则提示还没弹就会被 `SideEffect` 清掉；
 *    `SideEffect` 是 `hadImage` 的唯一同步点，保存那一刻即为真值。
 */
@Composable
internal fun ChatDraftRestoreEffects(
    draft: ChatDraft,
    editing: ChatMessage?,
    selecting: ChatMessage?,
    pendingImage: Part.Image?,
    snackbar: SnackbarHostState,
) {
    LaunchedEffect(draft.editingId, draft.selectingId, editing, selecting) {
        if (draft.editingId != null && editing == null) draft.editingId = null
        if (draft.selectingId != null && selecting == null) draft.selectingId = null
    }
    val lost = remember { imageDraftLost(draft.hadImage, pendingImage) }
    val message = stringResource(R.string.chat_draft_image_lost)
    LaunchedEffect(lost) {
        if (!lost) return@LaunchedEffect
        draft.hadImage = false
        snackbar.showSnackbar(message)
    }
    SideEffect { draft.hadImage = pendingImage != null }
}

/**
 * 草稿会话的首条消息自动命名（U102）：取消息原文的前 12 个字符（连续空白折叠成单格）
 * 做会话档案名。没有可用内容（纯空白）返回 null，调用方保持原名。
 *
 * 纯函数，无 Composable 依赖 —— 「发消息即建档」的命名规则收在这里，会话页只留调用点。
 */
internal fun deriveSessionTitle(raw: String): String? =
    raw.replace(Regex("\\s+"), " ").trim().take(12).takeIf { it.isNotEmpty() }
