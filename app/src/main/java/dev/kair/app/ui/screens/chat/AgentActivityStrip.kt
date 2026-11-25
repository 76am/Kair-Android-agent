package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.TurnState

/**
 * 运行态状态条的文案（U58 ②）；`null` = 不渲染。
 *
 * - 有待审批 / 待回答卡片时一律不渲染：卡片本身就是「在等你」，再写「正在运行」会误导；
 * - [TurnState.Idle] / [TurnState.Busy] 给「待机」而不是 `null`（2026-11-20 真机反馈）：
 *   状态条常驻才留得住一行高度，否则回合一开始输入区就被往下顶一下。「待机」也不暗示
 *   「agent 在跑」；[TurnState.Busy]（删消息这类非回合动作）仍不算回合在跑，只是那一行得有个东西；
 * - 压缩与停止是 [TurnState.Running] / [TurnState.Stopping] 的相位，各自换文案。
 *
 * 解码速度（U111）不改变这条基础判据：速度只是 [TurnState.Running] 的一个展示层
 * 相位，[SpeedPhase] 由消息快照推出，不进入状态机。
 */
internal fun activityLabelRes(turnState: TurnState, awaitingUser: Boolean): Int? = when {
    awaitingUser -> null
    turnState is TurnState.Running ->
        if (turnState.compacting) R.string.chat_compacting else R.string.chat_running
    turnState == TurnState.Stopping -> R.string.chat_stopping
    else -> R.string.chat_state_idle
}

/**
 * 状态条一行的组装：均速后缀是**常驻后缀**，只要有读数就拼上 —— 工具执行、压缩、停止
 * 这些「没有实时值」的中间状态也要带（U115 真机反馈：某个中间状态读数整段消失）。
 * 后缀内容是本会话均速（U115 返工）；实时值（正在生成 · N tok/s）仍由调用方前置，
 * 二者顺序固定为「状态 · 实时 · 本会话」。
 */
internal fun withSpeedSuffix(base: String, speedSuffix: String?): String =
    if (speedSuffix == null) base else "$base · $speedSuffix"

/**
 * 输入区上方的常驻运行态条（U58 ②）：14dp 指示位 + 一行状态文案。
 *
 * 为什么不挂在消息列表上：工具结果回来到下一段输出之间的空档里，列表里没有任何
 * 「进行中」的卡片；状态条贴在输入区，始终在可视区内，不依赖滚动、也不占 LazyColumn item。
 *
 * U111 起同一行承载解码速度；U115 起均速后缀是**所有状态**的常驻后缀（U115 返工：
 * 常驻的是本会话均速）：
 * - 解码中：`正在生成 · 32 tok/s · 本会话均速 27 tok/s`；
 * - 等首字 / 输出暂停：`正在生成… · 本会话均速 27 tok/s`（不给 0，也不留陈旧数字）；
 * - 工具执行 / 压缩 / 停止：`正在运行… · 本会话均速 27 tok/s` 这类，不再把读数掐掉；
 * - 待机（含回合结束后）：`待机 · 本会话均速 28 tok/s` —— 常驻到下一回合开始。
 *
 * 本会话还没有任何已观测响应时 [speedSuffix] 为 null，整段后缀省略；不拿 0 冒充。
 */
@Composable
internal fun AgentActivityStrip(
    turnState: TurnState,
    awaitingUser: Boolean,
    speedPhase: SpeedPhase = SpeedPhase.IDLE,
    liveSpeedText: String? = null,
    /** 均速后缀文案（带主语，本会话口径）；null = 没有可展示的读数（新会话 / 旧回合没 stats）。 */
    speedSuffix: String? = null,
) {
    if (awaitingUser) return
    val label = activityLabelRes(turnState, awaitingUser) ?: return

    val running = turnState as? TurnState.Running
    val decoding = running != null && !running.compacting
    val text = when {
        decoding && speedPhase == SpeedPhase.STREAMING && liveSpeedText != null ->
            stringResource(R.string.chat_speed_live, liveSpeedText)
        decoding && (speedPhase == SpeedPhase.STREAMING || speedPhase == SpeedPhase.WAITING) ->
            stringResource(R.string.chat_speed_waiting)
        else -> stringResource(label)
    }
    // 均速后缀在所有状态都拼：不再只在 Idle 时给（U115）。没有读数时保持原样，不写 0。
    val line = withSpeedSuffix(text, speedSuffix)
    val idle = turnState == TurnState.Idle || turnState == TurnState.Busy
    // 实时数字每 500ms 跳一次，不参与 liveRegion：只有状态切换（开始 / 工具 / 完成）才播报。
    val announce = speedPhase != SpeedPhase.STREAMING

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { if (announce) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (idle) {
            // 待机不转圈：一个 6dp 实心点占住 14dp 的指示位，与运行态的转圈同宽，
            // 文案起点不会在两种状态之间跳。
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            line,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
