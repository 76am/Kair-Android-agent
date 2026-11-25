package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.model.StopReason
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon

/** 长按菜单的一项。[label] 由调用方先 `stringResource` 解析好，便于在普通函数里组装动作列表。 */
internal data class MessageAction(
    val label: String,
    /** 破坏性动作（删除）用 error 色，和复制 / 重试拉开距离。 */
    val destructive: Boolean = false,
    val onPick: () -> Unit,
)

/**
 * 长按动作弹窗：用户气泡、agent 气泡与工具卡**共用**的那一套（形态统一为弹窗式，不锚定）。
 *
 * 为什么不用锚定气泡：长按可能落在正文、思考块或工具卡内部，锚定位置没有明确归属；
 * 弹窗式还能在「选取文字」里放全文滚动，各处行为完全一致。
 *
 * 动作列表为空时**什么都不弹** —— 所以可见入口（[MoreActionsEntry]）与长按手势都要按
 * 「有没有动作」来门控，不然点下去没反应，是个假入口。
 */
@Composable
internal fun MessageActionDialog(
    actions: List<MessageAction>,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.chat_message_actions),
) {
    if (actions.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                actions.forEach { action ->
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                onDismiss()
                                action.onPick()
                            }
                            // U105：长按菜单的每一项也是可点目标，14dp 上下留白 ≈ 52dp。
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** 「⋯」入口的边长；用户气泡的时间行与 agent 气泡的 `AgentFooter` 行都按它排版。 */
internal val MoreActionsEntrySize = 24.dp

/**
 * 「⋯」的命中区（U105）：视觉仍是 [MoreActionsEntrySize] 的圆，外圈补到 48dp。
 *
 * 24dp 是 2026-11-05 负责人拍板的视觉尺寸（48dp 塞进紫底里让一行字的气泡过大），
 * 但「视觉小」不等于「命中区小」—— Android 的 48dp 下限管的是可点区域。
 * 外圈只承载点击与语义；**圆底画在本体上**（2026-11-20）：此前本体无底色、按下是一块
 * 方形涟漪，用户既看不出这里能点、也不知道按到了什么。
 */
internal val MoreActionsHitSize = 48.dp

/**
 * 卡片动作的**可见入口**「⋯」：全仓只此一处实现，用户气泡与 agent 气泡都调它
 * （`MessageCardActionsGuardTest` 钉住：`MessageList.kt` 里不许再出现裸的「⋯」字形）。
 *
 * 点击区取 48dp 命中盒、视觉仍是 24dp 圆形（U105）：24dp 塞进紫底里不会让一行字的气泡
 * 看起来挂了个大方框，而命中面积满足 Android 的 48dp 下限。形状跟插件页 `IconButton` 的
 * 圆形涟漪统一，字形仍是 `titleMedium`，并带 `chat_more_actions` 的 `contentDescription`。
 *
 * 摆放位置由调用方决定（所以 [modifier] 由外部传入）：agent 气泡排在 `AgentFooter` 底部行，
 * 用户气泡与发送时间排在同一行。[color] 也交给调用方，因为它落在哪种卡片底色上要由卡片说：
 * 用户气泡现在是中性纸面，用默认的中性色会压成灰蒙蒙一片。
 */
@Composable
internal fun MoreActionsEntry(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.chat_more_actions)
    // 按下反馈走 PressLayer 口径：命中区仍是 48dp，但压层只画在本体那个圆上（被同一个
    // clip 裁住），不会鼓成一块盖到邻位文字的方块。
    val interaction = remember { MutableInteractionSource() }
    val layer = rememberPressLayer(interaction)
    val layerColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(MoreActionsHitSize)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MoreActionsEntrySize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceDim)
                .pressLayer(layer, layerColor),
            contentAlignment = Alignment.Center,
        ) {
            // U105：换成与坞 / 工具卡同一套线稿，不再用文字字形 ——
            // 文字字形受设备字体影响，是底部这屏最后一种「图标语言」。
            SvgPathIcon(PluginGlyph.More, color, Modifier.size(18.dp))
        }
    }
}

/**
 * 终止原因的可读标签（U48）：`stopReason` 是持久化的枚举值（`plugin-api` 的 `StopReason`），
 * 面向用户展示一律走这里。
 *
 * 未知值包成「已结束（原始值）」而不是原样丢出去（U98）：`stopReason` 是**协议字段**，
 * 万一以后某个 provider 漏了映射、或旧日志里留着别的拼法（如 `end_turn`），
 * 界面上也不该出现一整条英文。带上原值是为了真出问题时仍能一眼认出它是什么。
 */
@Composable
private fun stopReasonLabel(stopReason: String): String = when (stopReason) {
    StopReason.STOP -> stringResource(R.string.stop_reason_stop)
    StopReason.TOOL_USE -> stringResource(R.string.stop_reason_tool_use)
    StopReason.LENGTH -> stringResource(R.string.stop_reason_length)
    StopReason.ERROR -> stringResource(R.string.stop_reason_error)
    StopReason.ABORTED -> stringResource(R.string.stop_reason_aborted)
    StopReason.STEP_LIMIT -> stringResource(R.string.stop_reason_step_limit)
    StopReason.EMPTY_RESPONSE -> stringResource(R.string.stop_reason_empty_response)
    StopReason.SESSION_CLOSED -> stringResource(R.string.stop_reason_session_closed)
    else -> stringResource(R.string.stop_reason_other, stopReason)
}

/**
 * 这条结束原因值不值得在气泡底部占一行（U98）。
 *
 * `stop` / `toolUse` 是**正常收尾** —— 绝大多数回合都是这么结束的，把它们标出来
 * 等于给每一回合都盖一个「已结束」的章，而设计稿的底部行只有状态与 `⋯`、没有这一行。
 *
 * 这与 U48 不冲突：U48 要求可见的是**四条静默出口**（步数用尽 / 空回合 / 会话关闭 / 中止）
 * 加截断与出错 —— 这些一条都不少，收起的只是「一切正常」的那两种。
 */
internal fun isNoteworthyStop(stopReason: String): Boolean =
    stopReason != StopReason.STOP && stopReason != StopReason.TOOL_USE

/**
 * agent 气泡的底部行：结束原因 / 错误重试靠左，「⋯」入口靠右 —— 与用户气泡的
 * 「发送时间 + ⋯」同一口径，入口按内容内边距离圆角 12dp，不贴在角上。
 * 三样都没有时整行不渲染，不白占高度。
 */
@Composable
internal fun AgentFooter(
    stopReason: String?,
    isError: Boolean,
    retryable: Boolean,
    showEntry: Boolean,
    onRetry: () -> Unit,
    onMore: () -> Unit,
) {
    val showRetry = isError && retryable
    val reason = stopReason?.takeIf { isNoteworthyStop(it) }
    if (reason == null && !showRetry && !showEntry) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reason?.let { Text(stopReasonLabel(it), style = MaterialTheme.typography.labelSmall) }
        // 出错回合不要求用户猜「要长按才能重试」：把重试直接摆出来。
        if (showRetry) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
        }
        Spacer(Modifier.weight(1f))
        if (showEntry) {
            MoreActionsEntry(onClick = onMore, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
