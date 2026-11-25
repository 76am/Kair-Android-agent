package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.QueueKind
import dev.kair.app.core.agent.QueuedMessage
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.plugin.CommandContribution
import dev.kair.app.core.plugin.SlotArea
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.plugins.DockChip
import dev.kair.app.ui.plugins.DockControlSize
import dev.kair.app.ui.plugins.DockGauge
import dev.kair.app.ui.plugins.DockGaugeSize
import dev.kair.app.ui.plugins.PluginSlotRow
import kotlin.math.roundToInt

/**
 * Messages queued while the agent is busy, shown above the composer with recall / remove actions.
 *
 * 系统通知不在这里露面（U18，负责人要求）：它们是 agent 自己的投递，按步并入历史，
 * 不是「用户在引导 agent」；挂在这里只会让人以为通知卡住了。
 *
 * 每条三个动作（U22 + 负责人 2026-11-01 的纠正）：
 * ① 铅笔把这条撤回输入框（文字 + 图片，随后从队列移除，避免重复发送）；
 * ② 三角把这条**改成立即插话**（原本默认是回合末发送）；只对尚未插话的行显示 ——
 *    对已经是插话的行再按一次只是把它挪到队尾，纯损耗；
 * ③ 垃圾桶直接丢弃。
 *
 * 插话入口在这里而不在输入行：常驻两个「发送」形状的按钮，用户分不清按下去是等回合还是插话。
 * 两个动作都只用既有的 [AgentSession.removeQueued] + `steer`，不新增内核方法。
 */
@Composable
internal fun QueueSection(
    queue: List<QueuedMessage>,
    onRemove: (String) -> Unit,
    onRecall: (QueuedMessage) -> Unit,
    onPromote: (QueuedMessage) -> Unit,
) {
    val visible = visibleQueue(queue)
    if (visible.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.chat_queue_title, visible.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        visible.forEach { q ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        q.message.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 两种队列的排空时机不同，提示必须说真话（U18 之前对 FOLLOW_UP 说谎）。
                        stringResource(
                            if (q.kind == QueueKind.STEER) {
                                R.string.chat_queue_hint
                            } else {
                                R.string.chat_queue_hint_follow_up
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRecall(q) }) {
                    Icon(
                        Icons.Filled.Edit,
                        stringResource(R.string.chat_queue_recall),
                        modifier = Modifier.size(16.dp),
                    )
                }
                // 三角只对「回合末发送」的行显示：已经是插话的再按一次只会挪到队尾。
                if (canPromote(q.kind)) {
                    IconButton(onClick = { onPromote(q) }) {
                        Icon(
                            painterResource(R.drawable.ic_queue_steer),
                            stringResource(R.string.chat_queue_promote),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(onClick = { onRemove(q.message.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        stringResource(R.string.chat_queue_remove),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 「引导中」只列用户自己的排队消息；系统通知按步并入，不在面板露面。 */
internal fun visibleQueue(queue: List<QueuedMessage>): List<QueuedMessage> =
    queue.filterNot { it.message.isSystemNote }

/** 这条能不能改成立即插话：已经在 STEER 队列里的再按一次只会挪到队尾，不给她入口。 */
internal fun canPromote(kind: QueueKind): Boolean = kind != QueueKind.STEER

/**
 * △ 的落点：把这条排队消息从「回合末发送」改道成「立即插话」。
 *
 * **先移除、再 steer** —— 少移除就是「队列里还有 + 又插一条」= 重复发送（与撤回同一条不变量）。
 * 动手前先确认那条**仍在队列里**：`queue` 是 `collectAsState` 的委托，读的是实况而非旧帧 ——
 * 若这一条已经在回合末被排空发出，removeQueued 会静默无效，此时再 steer 就等于当场重发一遍。
 *
 * 代价（负责人选的就是这条便宜路径，不碰内核）：这条会落到 STEER 队列**末尾**，
 * 同时挂着多条插话时它们的相对顺序会变。要保序就得给内核加「改 kind」的方法，属另一档改动。
 */
internal fun promoteToSteer(session: AgentSession, queue: List<QueuedMessage>, queued: QueuedMessage) {
    if (queue.none { it.message.id == queued.message.id }) return
    val payload = recallPayload(queued.message)
    session.removeQueued(queued.message.id)
    session.steer(payload.text, listOfNotNull(payload.image))
}

/**
 * 输入区解析出的动作：已注册命令 / 普通消息 / 无内容可发（命令不消费图片，图会留下）。
 *
 * 「发送」只有一个落点：默认等回合结束（`send` → `FOLLOW_UP`）。插话（`steer`）不再是
 * 第二个发送键，而是排队区每条的改道动作（见 [promoteToSteer]）—— 常驻两个「发送」形状的
 * 按钮，用户分不清按下去是等回合还是插话。原来的 `SubmitMode` 随第二个键一起删掉。
 */
internal sealed interface SubmitAction {
    data class Command(val command: CommandContribution, val args: String) : SubmitAction
    data class Message(val text: String, val image: Part.Image?) : SubmitAction
    data object Ignore : SubmitAction
}

/** 撤回一条排队消息时要填回输入框的内容（文字 + 最多一张图，当前输入区只支持单张）。 */
internal data class RecallPayload(val text: String, val image: Part.Image?)

/**
 * 解析一次「发送」：只有命中已注册命令的「/name …」才当命令执行；否则一律当普通消息
 * （以 `/` 开头的路径、或本就该由模型处理的文字）。
 *
 * 命令不消费图片（既有行为）：「发送」= 消费整个输入区，图由调用方一并清掉。
 */
internal fun resolveSubmit(
    text: String,
    image: Part.Image?,
    commands: List<CommandContribution>,
): SubmitAction {
    val trimmed = text.trim()
    val cmd = if (trimmed.startsWith("/")) {
        val name = trimmed.drop(1).substringBefore(' ').trim()
        commands.firstOrNull { it.name.equals(name, ignoreCase = true) }
    } else {
        null
    }
    return when {
        cmd != null -> SubmitAction.Command(cmd, trimmed.drop(1).substringAfter(' ', "").trim())
        trimmed.isNotEmpty() || image != null -> SubmitAction.Message(trimmed, image)
        else -> SubmitAction.Ignore
    }
}

/**
 * 撤回一条排队消息的内容。图片必须一起带回输入框：排队消息可能带 `Part.Image`，
 * 只回填文字会让用户附的图静默消失（U22 §4.3）。
 */
internal fun recallPayload(message: ChatMessage): RecallPayload =
    RecallPayload(
        text = message.text,
        image = message.parts.filterIsInstance<Part.Image>().firstOrNull(),
    )

/** Plugin-contributed slash commands matching what the user has typed so far. */
@Composable
internal fun CommandSuggestions(
    commands: List<CommandContribution>,
    onPick: (CommandContribution) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.chat_commands),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        commands.forEach { cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "/${cmd.name}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    cmd.title,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 底部 UI 区当前展开的面板（一次最多一个，避免多个浮层互相遮挡）。 */
internal enum class DockPanel { NONE, MODEL, REASONING, CONTEXT }

/**
 * 输入框底部 UI 区：当前展开的面板（模型 / 思考强度 / 上下文详情）。
 *
 * 2026-11-18（设计稿 v2）：**面板坞已撤掉** —— 面板入口从「输入板上方横滑 chip」
 * 挪到顶栏九宫格按钮拉起的底部 sheet（见 [QuickPanelSheet]），输入区少一层、
 * 拇指区还给输入板。这里只剩「展开面板内联渲染」的职责。
 *
 * 展开面板**内联**渲染在坞下方、各占一列（不是 Popup 浮层），因此与相邻控件不会
 * 互相遮挡；切换模型 / 思考强度只作用于当前会话或当前模型配置。
 *
 * 控件行（模型 / 思考强度 / 插件 slot / 上下文圆环）不在这里 —— 它画在输入板内部，
 * 见 [ComposerControlRow]。展开状态由调用方持有：面板在板上方、控件行在板里，
 * 两个组件必须共享同一个 [open]，否则点 chip 弹不出那条面板。
 */
@Composable
internal fun ComposerDock(
    container: AppContainer,
    sessionId: String,
    used: Int,
    windowProvider: () -> Int,
    cacheHit: Double?,
    compacting: Boolean,
    compactVisible: Boolean,
    compactEnabled: Boolean,
    /** U111：最近一个回合的解码速度（没有 stats 时为 null）。 */
    turnSpeed: TurnSpeed? = null,
    /** U111：会话累计平均速度（上下文面板）。 */
    sessionAverageTps: Double? = null,
    open: DockPanel,
    onOpenChange: (DockPanel) -> Unit,
    onCompact: () -> Unit,
    onOpenCache: () -> Unit,
) {
    val model = container.sessionModel(sessionId)
    // 上下文窗口只随当前模型变化：只在模型变化时重算（切换模型会经 ModelPanel 的
    // onDone 触发本组件重组、从而重读到新 model），避免每次重组都解析一遍设置。
    val window = remember(model?.id, model?.model, model?.contextWindow) { windowProvider() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 10dp 与输入板、消息区同一条边：面板、板、气泡右边缘上下对齐成一条线。
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (open != DockPanel.NONE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (open) {
                    DockPanel.MODEL -> ModelPanel(container, sessionId, Modifier.weight(1f)) {
                        onOpenChange(DockPanel.NONE)
                    }
                    DockPanel.REASONING -> ReasoningPanel(container, model, Modifier.weight(1f)) {
                        onOpenChange(DockPanel.NONE)
                    }
                    DockPanel.CONTEXT -> ContextPanel(
                        container = container,
                        used = used,
                        window = window,
                        cacheHit = cacheHit,
                        compacting = compacting,
                        compactVisible = compactVisible,
                        compactEnabled = compactEnabled,
                        turnSpeed = turnSpeed,
                        sessionAverageTps = sessionAverageTps,
                        onCompact = onCompact,
                        onOpenCache = onOpenCache,
                        modifier = Modifier.weight(1f),
                    ) { onOpenChange(DockPanel.NONE) }
                    DockPanel.NONE -> Unit
                }
            }
        }
    }
}

/**
 * 输入板下沿的控件行：模型 / 思考强度 / 插件 slot（左，可横滑）+ 上下文圆环（右，钉死）。
 *
 * 这一行**画在输入板里面**（负责人 2026-11-17：「模型名那片区域太大了，应该跟输入框融一起」）。
 * 之前它是板外独立的一行、圆环 56dp、chip 带「模型 / 思考」小标题占两行 —— 底部被切成
 * 「坞 / 输入板 / 控件行」三层，光控件行就吃掉 60dp。收进板里之后只剩两层，
 * chip 只留值那一行（26dp），圆环 26dp，整行高度不到原来的一半。
 *
 * 小标题没有丢：读屏仍听到「模型 · Sonnet 4.6」（[DockChip] 的 `description`），
 * 思考强度那一格多一枚四角星当记号 —— 界面上靠位置和记号认，不靠重复的文字。
 */
@Composable
internal fun ComposerControlRow(
    container: AppContainer,
    sessionId: String,
    workspace: String,
    used: Int,
    windowProvider: () -> Int,
    compacting: Boolean,
    open: DockPanel,
    onOpenChange: (DockPanel) -> Unit,
    onOpenPanel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = container.sessionModel(sessionId)
    val window = remember(model?.id, model?.model, model?.contextWindow) { windowProvider() }
    val contextFraction = if (window > 0) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val modelName = model?.name?.ifBlank { model.model } ?: stringResource(R.string.chat_model_empty)
    val modelLabel = stringResource(R.string.chat_model_label)
    val contextLabel = stringResource(R.string.chat_context_title)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧控件簇整体横向滚动：插件多到挤不下时左右滑动，而不是被压缩变形。
        // 右侧圆环固定钉在最右，不参与滚动。
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DockChip(
                value = modelName,
                description = "$modelLabel $modelName",
                size = DockControlSize,
                active = open == DockPanel.MODEL,
            ) {
                if (model == null) {
                    onOpenSettings()
                } else {
                    onOpenChange(if (open == DockPanel.MODEL) DockPanel.NONE else DockPanel.MODEL)
                }
            }
            if (model != null && model.isReasoningModel) {
                val effort = reasoningLabel(model.reasoningEffort)
                DockChip(
                    value = effort,
                    icon = PluginGlyph.Spark,
                    description = stringResource(R.string.chat_reasoning_label) + " " + effort,
                    size = DockControlSize,
                    active = open == DockPanel.REASONING,
                ) {
                    onOpenChange(if (open == DockPanel.REASONING) DockPanel.NONE else DockPanel.REASONING)
                }
            }
            // 底部插件槽位：第三方插件声明的控件，与内置控件并排、随行横向滚动。
            // 传入本会话的 id / 工作区，回调据此把操作限定在触发它的这个会话。
            PluginSlotRow(container, SlotArea.BOTTOM, sessionId, workspace, onOpenPanel)
        }
        DockGauge(
            fraction = contextFraction,
            // 环中心直接给百分比（U105 返工：负责人要求恢复中心数字）。
            centerText = "${(contextFraction * 100).roundToInt()}%",
            compacting = compacting,
            size = DockGaugeSize,
            contentDescription = "$contextLabel ${(contextFraction * 100).roundToInt()}%",
        ) {
            onOpenChange(if (open == DockPanel.CONTEXT) DockPanel.NONE else DockPanel.CONTEXT)
        }
    }
}
