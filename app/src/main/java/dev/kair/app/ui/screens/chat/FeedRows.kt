package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore

/**
 * 列表的**行级**视图（性能批次）：把「一个回合 = 一个 LazyColumn 行」拆成
 * 「头部 / 每一步 / 最终思考 / 最终回答各占一行」。
 *
 * 为什么拆：展开上百步的回合时，旧实现把整段展开体塞进同一个 Lazy 行 ——
 * 一次组合就是上百个步骤卡、每帧重新 measure 一整块几千像素高的 item；
 * 流式期间每帧都会走到这条路，于是「展开着看、agent 还在输出」必卡。
 * 拆行后 LazyColumn 只组合可见的那几行，展开态本身提到 [FoldExpansion]，
 * 行组件不再各自持有展开状态。
 *
 * 视觉不变：折叠卡仍是一块 14dp 圆角的板，展开后只是被切成「头 / 中段 / 尾」若干
 * 连续的行 —— 背景与边框由 [foldSegment] 逐行拼接，交界处不画横线，行距全部由
 * 行内 padding 承担（数值与旧实现逐项一致）。
 */

/** 展开状态（按回合 key 保存）。提到列表层是因为行由 `buildRows` 生成，行内无法自持。 */
@Stable
internal class FoldExpansion internal constructor(private val state: MutableState<Set<String>>) {
    val keys: Set<String> get() = state.value

    fun toggle(key: String) {
        state.value = if (key in state.value) state.value - key else state.value + key
    }
}

/** 进程重建后仍保留「哪些回合是展开的」；回合 key 是消息 id，重进会话同样成立。 */
@Composable
internal fun rememberFoldExpansion(): FoldExpansion {
    val state = rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    return remember(state) { FoldExpansion(state) }
}

/**
 * LazyColumn 的一行。key 与 [FeedItem] 同源（回合头沿用 `t:<回合 key>`），
 * 收起 / 展开与「运行中 → 收口」切换时滚动锚点不跳。
 */
internal sealed interface FeedRow {
    val key: String

    /** 独立消息（用户消息、最终回答、检查点、系统提示）。 */
    data class Message(val msg: ChatMessage) : FeedRow {
        override val key: String get() = "m:${msg.id}"
    }

    /** 工具回图：纯图片的假用户消息，按图片块渲染，不套用户气泡。 */
    data class ToolImage(val msg: ChatMessage) : FeedRow {
        override val key: String get() = "i:${msg.id}"
    }

    /** 运行中回合的头（不可折叠）。其后的消息各自成行。 */
    data class Live(val round: TurnRound) : FeedRow {
        override val key: String get() = "t:${round.key}"
    }

    /** 折叠头。收起时它就是整张卡（上下都圆角）。 */
    data class FoldHeader(val round: TurnRound, val expanded: Boolean) : FeedRow {
        override val key: String get() = "t:${round.key}"
    }

    /** 展开体里的一步；[last] 决定谁画卡片的下圆角与下边框。 */
    data class FoldStep(
        val roundKey: String,
        val index: Int,
        val step: TurnStepGroup,
        val last: Boolean,
    ) : FeedRow {
        override val key: String get() = "t:$roundKey:s:$index"
    }

    /** 最终回答的思考：按时间顺序排在步骤之后，是折叠体的最后一段。 */
    data class FoldThinking(val roundKey: String, val text: String) : FeedRow {
        override val key: String get() = "t:$roundKey:th"
    }
}

/**
 * [FeedItem] → 行。只做切分，不碰 Compose：展开态由 [expanded] 决定，
 * 收起的回合只产出头与最终回答两行（与旧实现组合出的可见内容一致）。
 */
internal fun buildRows(items: List<FeedItem>, expanded: Set<String>): List<FeedRow> {
    val rows = mutableListOf<FeedRow>()
    for (item in items) {
        when (item) {
            is FeedMessage -> rows += FeedRow.Message(item.msg)
            is FeedTurn -> appendRound(rows, item.round, expanded)
        }
    }
    return rows
}

private fun appendRound(rows: MutableList<FeedRow>, round: TurnRound, expanded: Set<String>) {
    round.user?.let { rows += FeedRow.Message(it) }
    when {
        round.running -> {
            rows += FeedRow.Live(round)
            round.agent.forEach { rows += it.agentRow() }
        }
        round.foldable -> {
            val open = round.key in expanded
            rows += FeedRow.FoldHeader(round, open)
            if (open) {
                val thinking = round.finalAnswer?.thinking?.takeIf { it.isNotBlank() }
                round.stepGroups.forEachIndexed { i, step ->
                    rows += FeedRow.FoldStep(
                        roundKey = round.key,
                        index = i,
                        step = step,
                        last = i == round.stepGroups.lastIndex && thinking == null,
                    )
                }
                if (thinking != null) rows += FeedRow.FoldThinking(round.key, thinking)
            }
            round.finalAnswer?.let { rows += FeedRow.Message(it.withoutThinking()) }
        }
        else -> round.agent.forEach { rows += it.agentRow() }
    }
}

/** 回合内部 agent 段的 user 消息只有两种：工具回图（纯图片）与改道引导（有文字）。 */
private fun ChatMessage.agentRow(): FeedRow =
    if (role == Role.USER && text.isBlank()) FeedRow.ToolImage(this) else FeedRow.Message(this)

/**
 * 去掉思考部件后的副本：最终回答只要正文，思考已经折进折叠体。
 * 保留原 id 与其它部件 —— 消息卡上的删除 / 重试 / 选字仍认这一条。
 */
internal fun ChatMessage.withoutThinking(): ChatMessage =
    if (thinking.isBlank()) this else copy(parts = parts.filterNot { it is Part.Thinking })

/** 单行渲染入口。展开 / 收起只由 [FoldExpansion] 改，行内不再持有回合状态。 */
@Composable
internal fun FeedRowItem(
    row: FeedRow,
    expansion: FoldExpansion,
    busy: Boolean,
    matchedResults: Map<String, List<Part.ToolResult?>>,
    groupedCallIds: Set<String>,
    runningTurnId: String?,
    attachments: AttachmentStore?,
    onEdit: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onCopy: (String) -> Unit,
    onSelectText: (ChatMessage) -> Unit,
) {
    val card: @Composable (ChatMessage) -> Unit = { msg ->
        MessageCard(
            msg = msg,
            editable = !busy,
            retryable = !busy,
            deletable = !busy,
            matched = matchedResults[msg.id].orEmpty(),
            running = busy && msg.id == runningTurnId,
            groupedCallIds = groupedCallIds,
            onEdit = onEdit,
            onRetry = onRetry,
            onDelete = onDelete,
            onCopy = { onCopy(it.text) },
            onSelectText = onSelectText,
            attachments = attachments,
        )
    }
    when (row) {
        is FeedRow.Message -> card(row.msg)
        is FeedRow.ToolImage -> ToolImageBlock(row.msg, attachments)
        is FeedRow.Live -> LiveHead(row.round)
        is FeedRow.FoldHeader -> FoldSegment(roundedTop = true, roundedBottom = !row.expanded) {
            FoldHeader(
                round = row.round,
                open = row.expanded,
                onToggle = { expansion.toggle(row.round.key) },
            )
        }
        is FeedRow.FoldStep -> FoldSegment(roundedTop = false, roundedBottom = row.last) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (row.index == 0) 0.dp else 16.dp,
                        bottom = if (row.last) 10.dp else 0.dp,
                    ),
            ) {
                TurnStepSection(
                    index = row.index + 1,
                    step = row.step,
                    attachments = attachments,
                    matched = matchedResults[row.step.anchor.id].orEmpty(),
                    groupedCallIds = groupedCallIds,
                    busy = busy,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onCopy = onCopy,
                    onSelectText = onSelectText,
                    card = card,
                )
            }
        }
        is FeedRow.FoldThinking -> FoldSegment(roundedTop = false, roundedBottom = true) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            ) {
                ThinkingBlock(row.text)
            }
        }
    }
}

/**
 * 折叠卡的一段：铺底 + 只画该段该有的边。
 *
 * 中间段不画上下横线（交界处必须是连续的一整块板），头段画上半圈的圆角、
 * 尾段画下半圈；左右两条竖线每段都画，拼起来就是旧实现那张 14dp 圆角卡。
 */
@Composable
private fun FoldSegment(
    roundedTop: Boolean,
    roundedBottom: Boolean,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.surfaceContainer
    val border = MaterialTheme.colorScheme.outlineVariant
    // 与底色同形的裁剪：按下压层画在行内容上，不裁的话收起时会在头部的圆角外
    // 露出两个方角（旧实现由整卡的 clip 兜住，拆行后必须逐段自己裁）。
    val shape = RoundedCornerShape(
        topStart = if (roundedTop) 14.dp else 0.dp,
        topEnd = if (roundedTop) 14.dp else 0.dp,
        bottomStart = if (roundedBottom) 14.dp else 0.dp,
        bottomEnd = if (roundedBottom) 14.dp else 0.dp,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .foldSegment(background, border, roundedTop, roundedBottom),
    ) { content() }
}

private fun Modifier.foldSegment(
    background: Color,
    border: Color,
    roundedTop: Boolean,
    roundedBottom: Boolean,
): Modifier = drawWithCache {
    val stroke = 1.dp.toPx()
    val radius = 14.dp.toPx()
    val width = size.width
    val height = size.height
    val fill = Path().apply {
        if (roundedTop) {
            moveTo(0f, radius)
            arcTo(Rect(0f, 0f, radius * 2, radius * 2), 180f, 90f, false)
            lineTo(width - radius, 0f)
            arcTo(Rect(width - radius * 2, 0f, width, radius * 2), 270f, 90f, false)
        } else {
            moveTo(0f, 0f)
            lineTo(width, 0f)
        }
        if (roundedBottom) {
            lineTo(width, height - radius)
            arcTo(Rect(width - radius * 2, height - radius * 2, width, height), 0f, 90f, false)
            lineTo(radius, height)
            arcTo(Rect(0f, height - radius * 2, radius * 2, height), 90f, 90f, false)
        } else {
            lineTo(width, height)
            lineTo(0f, height)
        }
        close()
    }
    // 描边与 Modifier.border 同口径：路径内缩半个线宽，线画在 bounds 之内。
    val inset = stroke / 2
    val r = (radius - inset).coerceAtLeast(0f)
    val left = inset
    val top = inset
    val right = width - inset
    val bottom = height - inset
    val outline = Path()
    when {
        roundedTop -> {
            // 左竖线（自下而上）→ 上圆角 → 右竖线（自上而下）；需要收底时再绕回去。
            outline.moveTo(left, if (roundedBottom) bottom - r else height)
            outline.lineTo(left, top + r)
            outline.arcTo(Rect(left, top, left + r * 2, top + r * 2), 180f, 90f, false)
            outline.lineTo(right - r, top)
            outline.arcTo(Rect(right - r * 2, top, right, top + r * 2), 270f, 90f, false)
            outline.lineTo(right, if (roundedBottom) bottom - r else height)
            if (roundedBottom) {
                outline.arcTo(Rect(right - r * 2, bottom - r * 2, right, bottom), 0f, 90f, false)
                outline.lineTo(left + r, bottom)
                outline.arcTo(Rect(left, bottom - r * 2, left + r * 2, bottom), 90f, 90f, false)
            }
        }
        roundedBottom -> {
            // 两段只有下圆角：左竖线下到底、绕半圈、右竖线回到顶 —— 不画顶部横线。
            outline.moveTo(left, 0f)
            outline.lineTo(left, bottom - r)
            outline.arcTo(Rect(left, bottom - r * 2, left + r * 2, bottom), 180f, -90f, false)
            outline.lineTo(right - r, bottom)
            outline.arcTo(Rect(right - r * 2, bottom - r * 2, right, bottom), 90f, -90f, false)
            outline.lineTo(right, 0f)
        }
        else -> {
            outline.moveTo(left, 0f)
            outline.lineTo(left, height)
            outline.moveTo(right, 0f)
            outline.lineTo(right, height)
        }
    }
    onDrawBehind {
        drawPath(fill, background)
        drawPath(outline, border, style = Stroke(width = stroke))
    }
}
