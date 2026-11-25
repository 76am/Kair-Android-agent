package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.dateNoteDisplayText
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.ui.components.CollapsibleSection
import dev.kair.app.ui.components.ImagePart
import dev.kair.app.ui.components.markdown.MarkdownParagraphs
import dev.kair.app.ui.theme.UserBubbleShape

@Composable
internal fun MessageCard(
    msg: ChatMessage,
    editable: Boolean,
    retryable: Boolean,
    deletable: Boolean,
    matched: List<Part.ToolResult?>,
    running: Boolean,
    groupedCallIds: Set<String>,
    onEdit: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
    onCopy: (ChatMessage) -> Unit,
    onSelectText: (ChatMessage) -> Unit,
    /** 引用式图片（本体在附件存储、不进日志）的取本体入口；由宿主注入。 */
    attachments: AttachmentStore? = null,
) {
    if (msg.isCompaction) {
        MessageTurn { CompactionCard(msg) }
        return
    }
    // 宿主注入的提示（如「工具集已变化」）：系统生成的 user 回合，渲染成提示条而非用户消息。
    if (msg.isSystemNote) {
        MessageTurn { SystemNoteCard(msg) }
        return
    }
    when (msg.role) {
        Role.USER -> MessageTurn {
            UserMessage(
                msg = msg,
                editable = editable,
                deletable = deletable,
                attachments = attachments,
                onEdit = onEdit,
                onCopy = onCopy,
                onSelectText = onSelectText,
                onDelete = onDelete,
            )
        }

        Role.TOOL_RESULT -> {
            // 已随所在回合折叠进工具分组块的输出不再单独渲染；只渲染孤儿结果
            // （如压缩切点把工具调用切掉、结果仍在），避免重复展示。
            val standalone = msg.toolResults.filterNot { it.toolCallId in groupedCallIds }
            if (standalone.isNotEmpty()) {
                // 工具卡也给同一套长按菜单（选取文字 / 重试 / 删除）：输出同样值得选取复制，
                // 而重试与删除落在**任意**一条消息上都成立。不给「编辑」——「编辑」只对
                // **用户消息**开放（改文本后重发）；工具结果没有可改写的正文，工具调用的
                // 参数属上方那条 assistant 回合，也不是就地能改的，放上去只会是个假入口。
                var menuOpen by remember { mutableStateOf(false) }
                MessageTurn {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(msg.id) {
                                detectTapGestures(onLongPress = { menuOpen = true })
                            },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        standalone.forEach { tr -> key(tr.toolCallId) { ToolResultCard(tr) } }
                    }
                }
                if (menuOpen) {
                    val selectTextLabel = stringResource(R.string.chat_select_text)
                    val retryLabel = stringResource(R.string.chat_retry)
                    val deleteLabel = stringResource(R.string.chat_delete)
                    MessageActionDialog(
                        actions = buildList {
                            // 工具结果没有正文，「复制」按既有规则只在有正文时出现（此处的全文
                            // 复制走「选取文字」弹窗，见 selectableMessageText）。
                            add(MessageAction(selectTextLabel) { onSelectText(msg) })
                            if (retryable) {
                                add(MessageAction(retryLabel) { onRetry(msg) })
                            }
                            if (deletable) {
                                add(MessageAction(deleteLabel, destructive = true) { onDelete(msg) })
                            }
                        },
                        onDismiss = { menuOpen = false },
                    )
                }
            }
        }

        else -> {
            // agent 气泡长按弹窗（选取文字 / 复制 / 重试），与用户气泡共用同一个弹窗组件。
            // 「重试」可落在**任意**一条 agent 消息上（只撤回这一条及其后续），因此纯工具
            // 回合也可重试。正文 / 思考 / 工具卡内部都没有内联选区了（见 ToolCallViews），
            // 所以长按气泡内**任意位置**只会出这一个弹窗 —— 不再与系统「全选 / 复制」工具条打架。
            var menuOpen by remember { mutableStateOf(false) }
            // 正文 / 思考是**计算属性**（每次访问都新建一个拼接串）。流式回合每帧都会重组，
            // 长思考下重复取用会成倍放大内存与文本排版开销 —— 这里各取一次复用。
            val text = msg.text
            val thinking = msg.thinking
            // 段切分也是计算属性，同样只取一次：正文渲染按段画，避免整串粘成一句。
            val paragraphs = msg.textParagraphs
            val hasContent = text.isNotBlank() || thinking.isNotBlank() || msg.toolCalls.isNotEmpty()
            // 空 agent 回合不渲染：旧版本在「服务端只回结束原因」或「错误文案为空」时落过盘，
            // 会在列表里留下一个永远空白的卡片。这类消息没有任何可读内容，也不该占位。
            if (msg.role == Role.ASSISTANT && !hasContent) return
            val actionable = hasContent || retryable || deletable
            MessageTurn {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (actionable) {
                                Modifier.pointerInput(msg.id) { detectTapGestures(onLongPress = { menuOpen = true }) }
                            } else Modifier,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WhoLine(
                        who = stringResource(R.string.chat_who_agent),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (thinking.isNotBlank()) {
                        ThinkingBlock(thinking)
                    }
                    // 正文按段渲染（U98）：一个回合里「说话 → 调工具 → 接着说」是常态，
                    // 把 `text` 整串画出来会让两段话粘成一句。段边界来自 `Part.Text` 的切分
                    // （见 `ChatMessage.textParagraphs`），间距由外层 Column 的 8dp 给。
                    // 2026-11-21：段内走 Markdown；user 消息仍是纯文本。
                    MarkdownParagraphs(
                        paragraphs = paragraphs,
                        streamingLast = running && msg.parts.lastOrNull() is Part.Text,
                        color = if (msg.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (msg.toolCalls.isNotEmpty()) {
                        // key 用消息 id：调用 id 在流式期间可能被收尾帧修正（占位 → 权威），
                        // 跟着它换 key 会把组内「已展开」的状态一起丢掉（见 ToolCallGroup）。
                        key(msg.id) {
                            ToolCallGroup(
                                calls = msg.toolCalls,
                                matched = matched,
                                running = running,
                                groupId = msg.id,
                            )
                        }
                    }
                    AgentFooter(
                        stopReason = msg.stopReason,
                        isError = msg.isError,
                        retryable = retryable,
                        // `⋯` 行**不跟 running 解耦就跳**：流式期间整行不渲染，输出一结束
                        // 「⋯」突然多出一行（24dp + 行距），列表在这一帧长高一截 —— 负责人
                        // 定位的跳动真因。常驻渲染后流式期间也能复制 / 选择文本，结束时
                        // 高度零变化（正常收尾本就不写结束原因，见 isNoteworthyStop）。
                        showEntry = actionable,
                        onRetry = { onRetry(msg) },
                        onMore = { menuOpen = true },
                    )
                }
            }
            if (menuOpen) {
                val selectTextLabel = stringResource(R.string.chat_select_text)
                val copyLabel = stringResource(R.string.chat_copy)
                val retryLabel = stringResource(R.string.chat_retry)
                val deleteLabel = stringResource(R.string.chat_delete)
                MessageActionDialog(
                    actions = buildList {
                        // 「选取文字」覆盖正文 + 思考 + 工具输入/输出（纯工具回合也有内容可选）；
                        // 「复制」仍只复制正文文本，避免把思考与工具载荷一起带走。
                        if (hasContent) {
                            add(MessageAction(selectTextLabel) { onSelectText(msg) })
                        }
                        if (text.isNotBlank()) {
                            add(MessageAction(copyLabel) { onCopy(msg) })
                        }
                        if (retryable) {
                            add(MessageAction(retryLabel) { onRetry(msg) })
                        }
                        if (deletable) {
                            add(MessageAction(deleteLabel, destructive = true) { onDelete(msg) })
                        }
                    },
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

/**
 * 用户回合：**靠右的一块**（负责人 2026-11-17 纠正 U97 的「与 agent 同侧」）。
 *
 * U97 曾把它改成与 agent 同侧、只留一条朱红竖线，理由是「气泡把话变成物体」。
 * 真机上这个理由不成立：整屏都是等宽小字与正文，一眼分不出哪句是自己说的 ——
 * 「你」与「它」的差别是**信息**，不是装饰，得靠位置与块面立住。
 *
 * 所以：整行靠右（轨道已于 73766246 删除，靠右由气泡自身的对齐承担）、
 * 底色 surfaceContainerHigh + 1dp 分界线 + 右下收口的 [UserBubbleShape]，
 * 宽度最多 92% —— 长句仍在右侧成块，但不会顶到左边把「这是回复」的错觉做出来。
 * 「谁 · 时间」用朱红等宽小字压在气泡上方、与气泡同侧，`⋯` 紧跟其后。
 *
 * 动作仍只有一套 —— 长按与点「⋯」弹的是同一个 [MessageActionDialog]，与 agent 共用。
 * 没有可执行动作时（既无正文又不可删除）既不挂长按手势、也不显示入口，否则是个假入口。
 */
@Composable
private fun UserMessage(
    msg: ChatMessage,
    editable: Boolean,
    deletable: Boolean,
    attachments: AttachmentStore?,
    onEdit: (ChatMessage) -> Unit,
    onCopy: (ChatMessage) -> Unit,
    onSelectText: (ChatMessage) -> Unit,
    onDelete: (ChatMessage) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actionable = msg.text.isNotBlank() || deletable
    val whoColor = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (actionable) {
                    Modifier.pointerInput(msg.id) {
                        detectTapGestures(onLongPress = { menuOpen = true })
                    }
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 「谁 · 时间」一行：与气泡同侧靠右（设计稿 `.who` 的等宽小字，位置改到右边）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // 靠右 + 4dp 间隔：不用 Spacer 推，`⋯` 才能紧跟时间（守卫按「if (actionable)」紧邻判定）。
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        ) {
            WhoLine(
                who = stringResource(R.string.chat_who_you) + " · " +
                    remember(msg.timestamp) { formatWhoTime(msg.timestamp) },
                color = whoColor,
            )
            if (actionable) {
                MoreActionsEntry(
                    onClick = { menuOpen = true },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 92% 宽封顶：长句不至于把气泡拉到最左，短句仍是一个贴着右边缘的块。
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(UserBubbleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, UserBubbleShape)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            msg.images.forEachIndexed { i, img ->
                key("${msg.id}_$i") {
                    ImagePart(img, Modifier.fillMaxWidth().heightIn(max = 260.dp), attachments)
                }
            }
            // 文字不做内联选区：内联 SelectionContainer 会抢先处理长按、还弹出系统
            // 「全选 / 复制」工具条，与上面的长按菜单叠成两个菜单。选字走「选取文字」。
            if (msg.text.isNotBlank()) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    if (menuOpen) {
        val selectTextLabel = stringResource(R.string.chat_select_text)
        val copyLabel = stringResource(R.string.chat_copy)
        val editLabel = stringResource(R.string.chat_edit)
        val deleteLabel = stringResource(R.string.chat_delete)
        MessageActionDialog(
            actions = buildList {
                if (msg.text.isNotBlank()) {
                    add(MessageAction(selectTextLabel) { onSelectText(msg) })
                    add(MessageAction(copyLabel) { onCopy(msg) })
                }
                if (editable && msg.text.isNotBlank()) {
                    add(MessageAction(editLabel) { onEdit(msg) })
                }
                // 「删除」与「编辑」同属改写历史，因此与它同一个空闲门槛。
                if (deletable) {
                    add(MessageAction(deleteLabel, destructive = true) { onDelete(msg) })
                }
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * 「选取文字」弹窗里的全文：正文 + 思考 + 每个工具调用的输入与结果。
 *
 * 工具卡内的内联选区已取消（会与外层长按菜单抢手势、并弹出系统「全选 / 复制」工具条），
 * 工具内容的选字 / 复制统一走这个弹窗，所以这里必须把工具部分也拼进来，且**不截断** ——
 * 卡片上的 4000 字截断只为展示，用户要复制的是全文。
 */
@Composable
internal fun selectableMessageText(msg: ChatMessage, matched: List<Part.ToolResult?>): String =
    assembleSelectableText(
        msg = msg,
        matched = matched,
        thinkingLabel = stringResource(R.string.chat_thinking),
        callFormat = stringResource(R.string.chat_tool_call),
        inputLabel = stringResource(R.string.chat_tool_input),
        outputLabel = stringResource(R.string.chat_tool_output),
    )

/** [selectableMessageText] 的纯函数部分：标签由调用方注入，便于单测（见 SelectableTextTest）。 */
internal fun assembleSelectableText(
    msg: ChatMessage,
    matched: List<Part.ToolResult?>,
    thinkingLabel: String,
    callFormat: String,
    inputLabel: String,
    outputLabel: String,
): String {
    val out = StringBuilder()
    if (msg.thinking.isNotBlank()) {
        out.append(thinkingLabel).append('\n').append(msg.thinking.trim()).append("\n\n")
    }
    if (msg.text.isNotBlank()) {
        out.append(msg.text.trim()).append("\n\n")
    }
    msg.toolCalls.forEachIndexed { i, call ->
        val result = matched.getOrNull(i)
        out.append(callFormat.format(call.name)).append('\n')
        out.append(inputLabel).append('\n').append(call.arguments.trim()).append('\n')
        if (result != null) {
            out.append(outputLabel).append('\n').append(result.content.trim()).append('\n')
        }
        out.append('\n')
    }
    return out.toString().trim()
}

/** A compaction checkpoint: the summarized prefix that replaced earlier turns. */
@Composable
private fun CompactionCard(msg: ChatMessage) {
    CollapsibleSection(
        title = stringResource(R.string.chat_compacted),
        titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.chat_compacted_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            SelectionContainer {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 宿主注入的系统提示（如后台任务完成）：分隔条形态，默认收起，展开见原文。 */
@Composable
private fun SystemNoteCard(msg: ChatMessage) {
    SystemNoteBar(dateNoteDisplayText(msg.text))
}

/**
 * 按转录顺序把工具结果匹配回所在回合的调用：assistant 消息 id → 与其 toolCalls
 * 顺序对齐的结果列表（无结果/尚未执行的槽位为 null）。不依赖 toolCallId 全局
 * 唯一——部分 OpenAI 兼容端点会跨回合复用 "call_0" 这类 id，同回合内按 id 依次
 * 填空槽即可；回合边界（下一条 USER/ASSISTANT 消息）之后的孤儿结果不认领，
 * 仍由独立卡片渲染（如压缩切点留下的结果）。
 */
internal fun matchToolResults(messages: List<ChatMessage>): Map<String, List<Part.ToolResult?>> {
    val out = mutableMapOf<String, List<Part.ToolResult?>>()
    var pendingCalls: List<Part.ToolCall>? = null
    var pendingSlots: MutableList<Part.ToolResult?>? = null
    var previous: ChatMessage? = null
    for (m in messages) {
        val toolImage = isToolImageMessage(m, previous)
        when {
            m.role == Role.ASSISTANT && m.toolCalls.isNotEmpty() -> {
                val slots = MutableList<Part.ToolResult?>(m.toolCalls.size) { null }
                out[m.id] = slots
                pendingCalls = m.toolCalls
                pendingSlots = slots
            }
            m.role == Role.TOOL_RESULT -> {
                val calls = pendingCalls
                val slots = pendingSlots
                if (calls != null && slots != null) {
                    for (tr in m.toolResults) {
                        var i = 0
                        while (i < calls.size) {
                            if (calls[i].id == tr.toolCallId && slots[i] == null) {
                                slots[i] = tr
                                break
                            }
                            i++
                        }
                    }
                }
            }
            // 工具回图不是回合结束（见 isToolImageMessage）——否则并行的多个工具里，
            // 排在图片之后的结果会全部变成没人认领的孤儿卡。
            m.role == Role.USER && !toolImage -> {
                // 回合结束：其后的结果不再归属于任何调用。
                pendingCalls = null
                pendingSlots = null
            }
        }
        previous = m
    }
    return out
}

/**
 * 撤回前检查：撤回点之后（编辑）或从撤回点起（重试）的 agent 回合里**调用过的工具名**
 * （去重、保持首次出现顺序）。
 *
 * 用于「编辑 / 重试」前提示用户：撤回只丢弃对话内容、不撤销磁盘 / 环境的实际改动 —— 只要
 * 有过工具调用（不只是 `write` / `edit`，`container_shell` 等同样有副作用）就值得提醒一次。
 *
 * [includeSelf]：编辑传 false（编辑的是用户消息，其后才是 agent 回合）；重试传 true
 * （撤回点本身就是被重试的那条 agent 消息，它自带的工具调用也要算进提示范围）。
 */
internal fun toolNamesForRewrite(
    messages: List<ChatMessage>,
    messageId: String,
    includeSelf: Boolean,
): List<String> {
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return emptyList()
    val start = if (includeSelf) index else index + 1
    if (start >= messages.size) return emptyList()
    return messages.subList(start, messages.size)
        .asSequence()
        .filter { it.role == Role.ASSISTANT }
        .flatMap { it.toolCalls.asSequence() }
        .map { it.name }
        .distinct()
        .toList()
}
