package dev.kair.app.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.ui.components.formatSpeed
import kotlinx.coroutines.delay

/** 界面直接消费的速度快照（U111）：实时相位 / 文案、按响应归档的 stats、会话均速。 */
internal data class ChatSpeedState(
    val phase: SpeedPhase = SpeedPhase.IDLE,
    val liveText: String? = null,
    val stats: Map<String, ResponseStats> = emptyMap(),
    val sessionAverageTps: Double? = null,
)

/**
 * 把消息快照接成速度状态（U111）。
 *
 * 两个触发源：
 * - 消息变化（~15fps）：推进 tracker；这一帧同时产出 stats（回合完成时追加一条）。
 * - 500ms 补拍：输出静默时把实时值清掉；不补拍的话「模型停住」会一直显示旧读数。
 */
@Composable
internal fun rememberChatSpeed(session: AgentSession, messages: List<ChatMessage>): ChatSpeedState {
    val tracker = remember(session.id) { ChatSpeedTracker() }
    var state by remember(session.id) { mutableStateOf(ChatSpeedState()) }

    LaunchedEffect(messages) {
        tracker.onMessages(messages)
        val next = tracker.toState()
        // 只在可见读数真的变化时写 state：速度取整后多数帧相同，不必每 66ms 重组一次。
        if (next != state) state = next
    }
    LaunchedEffect(tracker.phase) {
        if (tracker.phase != SpeedPhase.STREAMING) return@LaunchedEffect
        while (true) {
            delay(SPEED_TICK_MS)
            tracker.onTick()
            val next = tracker.toState()
            if (next != state) state = next
            if (tracker.phase != SpeedPhase.STREAMING) break
        }
    }
    return state
}

private fun ChatSpeedTracker.toState(): ChatSpeedState = ChatSpeedState(
    phase = phase,
    liveText = liveTps?.let(::formatSpeed),
    stats = stats.toMap(),
    sessionAverageTps = sessionAverageTps(stats),
)

/** 会话均速：所有已完成响应的 output token 之和 ÷ 解码耗时之和。 */
internal fun sessionAverageTps(stats: Map<String, ResponseStats>): Double? {
    val rows = stats.values.filter { it.outputTokens > 0 && it.decodeMs > 0 }
    if (rows.isEmpty()) return null
    return rows.sumOf { it.outputTokens } * 1000.0 / rows.sumOf { it.decodeMs }
}

/** 回合均速的展示串：估算值加 `~`，与实时值区分。 */
internal fun formatTurnSpeed(speed: TurnSpeed): String =
    (if (speed.estimated) "~" else "") + formatSpeed(speed.averageTps)

/**
 * 状态条后缀：本会话均速（跨本会话所有已观测响应，Σ输出 ÷ Σ解码）。
 *
 * U115 返工：真机要的是「本会话均速」常驻，不是本轮那个数 —— 本轮均速仍在回合折叠头
 * （`chat_speed_turn_avg`）与上下文面板（`chat_speed_turn_avg_label`），不丢数据。
 * 没有可展示读数时给 null，整段后缀省略（不拿 0 冒充）。
 */
@Composable
internal fun sessionSpeedText(sessionAverageTps: Double?): String? = sessionAverageTps?.let {
    stringResource(R.string.chat_speed_session_avg_label, formatSpeed(it))
}

/**
 * 最近一个回合的均速（上下文面板用）；null = 最近回合没有可展示的速度。
 *
 * 状态条的后缀不再走这里，改走 [sessionSpeedText]（U115 返工）。单独成函数是为了
 * 压住 `ChatScreen` 的方法体量（MethodSizeGuard 的棘轮）。
 */
@Composable
internal fun rememberCompletedSpeed(feedItems: List<FeedItem>): TurnSpeed? =
    (feedItems.lastOrNull { it is FeedTurn } as? FeedTurn)?.round?.speed

private const val SPEED_TICK_MS = 500L
