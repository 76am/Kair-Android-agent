package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.ui.components.ImagePart

/**
 * 折叠体里的一条消息：只负责内容与长按手势的透传，菜单归所在「步」（见 TurnFold 的
 * `TurnStepSection`）。
 *
 * 长按落在任意一条上时弹出的是**整步**的菜单：删除 / 重试的作用范围就是这一组消息，
 * 逐条各弹一个菜单会让用户分不清删掉的是哪半截。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FoldedMessageBody(
    msg: ChatMessage,
    attachments: AttachmentStore?,
    /** 所在步的结果槽：步内只有锚点那条 assistant 带工具调用，不必传整张匹配表。 */
    matched: List<Part.ToolResult?>,
    groupedCallIds: Set<String>,
    onLongClick: () -> Unit,
    card: @Composable (ChatMessage) -> Unit,
) {
    // 纯图片的工具回图没有可选取的文字；用户消息本身靠右渲染，长按归用户气泡自己管。
    val selectable = msg.role != Role.USER
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    // 这个手势只服务长按菜单；水波纹会在按下的瞬间先亮一颗圆斑，
                    // 和折叠体的直角边不符，直接关掉（长按由弹窗给可见反馈）。
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        FoldedMessageContent(
            msg = msg,
            attachments = attachments,
            matched = matched,
            groupedCallIds = groupedCallIds,
            onLongClick = onLongClick,
            card = card,
        )
    }
}

@Composable
private fun FoldedMessageContent(
    msg: ChatMessage,
    attachments: AttachmentStore?,
    matched: List<Part.ToolResult?>,
    groupedCallIds: Set<String>,
    onLongClick: () -> Unit,
    card: @Composable (ChatMessage) -> Unit,
) {
    when (msg.role) {
        Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val thinking = msg.thinking
            if (thinking.isNotBlank()) ThinkingBlock(thinking, onLongClick = onLongClick)
            msg.textParagraphs.forEach { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            msg.toolCalls.forEachIndexed { i, tc ->
                ToolCallBlock(
                    tc = tc,
                    result = matched.getOrNull(i),
                    pending = false,
                    onLongClick = onLongClick,
                    anchor = "${msg.id}:$i",
                )
            }
        }
        Role.TOOL_RESULT -> {
            // 只补渲染孤儿结果（调用被压缩切点切掉的）；已配对进工具行的不重复出现。
            msg.toolResults.filterNot { it.toolCallId in groupedCallIds }.forEach { tr ->
                ToolResultCard(tr, onLongClick = onLongClick)
            }
        }
        // 回合内部的 user 消息：纯图片的是工具回图（步骤产物，按图片块渲染），
        // 有文字的是回合中改道的引导 —— 照常渲染成用户气泡，别让用户找不到自己说了什么。
        Role.USER -> if (msg.text.isBlank()) ToolImageBlock(msg, attachments) else card(msg)
        else -> Unit
    }
}

/**
 * 工具回带的图片（read_image / 截屏）：内核把它记成一条纯图片 user 消息，
 * 展示上属于 agent 的步骤产物 —— 没有「你」的署名，也不套用户气泡。
 */
@Composable
internal fun ToolImageBlock(msg: ChatMessage, attachments: AttachmentStore?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        msg.images.forEachIndexed { i, img ->
            key("${msg.id}_$i") {
                ImagePart(img, Modifier.fillMaxWidth().heightIn(max = 260.dp), attachments)
            }
        }
    }
}
