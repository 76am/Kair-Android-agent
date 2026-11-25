package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kair.app.R
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon

/**
 * 展开体里的一「步」：分隔线 + 该步的消息。
 *
 * 长按步内任意一条、或点分隔线右端的「⋯」，弹的都是**步级**菜单 —— 标题写明
 * 「第 N 步 · M 条消息」，删除 / 重试作用于这一整组，不会只删掉半截工具结果。
 */
@Composable
internal fun TurnStepSection(
    index: Int,
    step: TurnStepGroup,
    attachments: AttachmentStore?,
    /** 本步锚点（步内唯一的 assistant）名下的工具结果槽，顺序与它的 toolCalls 对齐。 */
    matched: List<Part.ToolResult?>,
    groupedCallIds: Set<String>,
    busy: Boolean,
    onRetry: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onCopy: (String) -> Unit,
    onSelectText: (ChatMessage) -> Unit,
    card: @Composable (ChatMessage) -> Unit,
) {
    var menuOpen by remember(step.key) { mutableStateOf(false) }
    var menuFor by remember(step.key) { mutableStateOf<ChatMessage?>(null) }
    Column(Modifier.fillMaxWidth()) {
        StepDivider(index, step.failed) {
            menuFor = null
            menuOpen = true
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            step.messages.forEach { msg ->
                FoldedMessageBody(
                    msg = msg,
                    attachments = attachments,
                    matched = matched,
                    groupedCallIds = groupedCallIds,
                    onLongClick = {
                        menuFor = msg
                        menuOpen = true
                    },
                    card = card,
                )
            }
        }
    }
    if (menuOpen) {
        val title = stringResource(R.string.chat_step_title, index, step.messages.size)
        val retryLabel = stringResource(R.string.chat_step_retry)
        val deleteLabel = stringResource(R.string.chat_step_delete)
        val copyStepLabel = stringResource(R.string.chat_step_copy)
        val selectTextLabel = stringResource(R.string.chat_select_text)
        val copyLabel = stringResource(R.string.chat_copy)
        val body = step.messages.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }
            .joinToString("\n\n")
        MessageActionDialog(
            title = title,
            actions = buildList {
                if (!busy) add(MessageAction(retryLabel) { onRetry(step.anchor) })
                // 从步的锚点（该步第一条 assistant 消息）截断到末尾：删一条工具结果会留下
                // 没有结果的调用，统一回溯到承载它的那条消息，整组一起走。
                if (!busy) add(MessageAction(deleteLabel, destructive = true) { onDelete(step.anchor) })
                if (body.isNotBlank()) add(MessageAction(copyStepLabel) { onCopy(body) })
                menuFor?.let { target ->
                    // 单条动作留在菜单后半段：长按的是哪条，选字 / 复制就作用于哪条。
                    if (target.role != Role.USER) add(MessageAction(selectTextLabel) { onSelectText(target) })
                    if (target.text.isNotBlank()) add(MessageAction(copyLabel) { onCopy(target.text) })
                }
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** 步分隔线：`第 N 步 ──────── ⋯`；失败步的步号转朱红。 */
@Composable
private fun StepDivider(index: Int, failed: Boolean, onMenu: () -> Unit) {
    val label = if (failed) {
        stringResource(R.string.chat_step_label_failed, index)
    } else {
        stringResource(R.string.chat_step_label, index)
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                letterSpacing = 0.09.em,
            ),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // 视觉 24dp 圆底、命中盒 48dp（U105 口径，与 MoreActionsEntry 同一个尺寸常量）。
        // 行高固定 20dp，size() 会被约束夹回 20，必须 requiredSize 越出；
        // Row 已 CenterVertically，触点上下各越出 14dp、不裁剪。
        // 按下反馈走 PressLayer 口径（2026-11-20）：以前是 6dp 圆角方块涟漪、盖在分隔线上，
        // 现在压层只出现在本体那个圆里。圆底不铺满 48dp，是为了不在分隔行上鼓出一块。
        val stepMenuInteraction = remember { MutableInteractionSource() }
        val stepMenuLayer = rememberPressLayer(stepMenuInteraction)
        val stepMenuLayerColor = MaterialTheme.colorScheme.onSurface
        Box(
            modifier = Modifier
                .requiredSize(MoreActionsHitSize)
                .clickable(
                    interactionSource = stepMenuInteraction,
                    indication = null,
                    onClick = onMenu,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(MoreActionsEntrySize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceDim)
                    .pressLayer(stepMenuLayer, stepMenuLayerColor),
                contentAlignment = Alignment.Center,
            ) {
                SvgPathIcon(
                    pathData = PluginGlyph.More,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
