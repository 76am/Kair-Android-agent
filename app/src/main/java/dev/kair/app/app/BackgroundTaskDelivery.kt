package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 完成通知的投递落点：只暴露投递真正需要的三个观察点。
 *
 * 存在的理由是**可测** —— [deliverNote] 若直接吃 [AgentSession]，就得构造真会话才能覆盖；
 * 而 `AgentSession` 已到体积棘轮上限，不能为测试改形。有了这层，投递语义（去重 / 补投）
 * 可以用假实现逐条验证。
 */
internal interface NoteSink {
    val messages: StateFlow<List<ChatMessage>>

    /** 队列当前快照：只用于「通知是否已经排上」的判据，不需要流。 */
    fun queuedMessages(): List<ChatMessage>

    fun sendNote(text: String)
}

/**
 * 真实会话的落点：走 [AgentSession.steer] 的 systemNote 通道 ——
 * 空闲时等价 `send(systemNote = true)`（唤醒），忙时入 STEER、下一步请求前并入历史。
 */
internal fun sessionNoteSink(session: AgentSession): NoteSink = object : NoteSink {
    override val messages: StateFlow<List<ChatMessage>> = session.messages
    override fun queuedMessages(): List<ChatMessage> = session.queue.value.map { it.message }
    override fun sendNote(text: String) = session.steer(text, systemNote = true)
}

/**
 * 投递完成通知：交给 [NoteSink.sendNote]，然后核对它真的落地（历史或队列里）。
 *
 * **不再等会话空闲**（U18）：等 `Idle` 会把通知扣到回合结束 —— 真机实测一个连发工具的
 * 长回合把三条通知扣了 3 分钟；更糟的是空闲点上多条通知同时释放时只有第一条能起唤醒
 * 回合，其余进 FOLLOW_UP，而那个队列只在「本回合要结束」时排空，于是滞留。
 * 现在忙时入 STEER：下一步请求前就并入历史，通知跟着任务完成走，不跟着回合结束走。
 *
 * 「进了队列」算送达的前提是队列丢不掉系统通知：[dev.kair.app.core.agent.TurnQueue.clear]
 * 保留它们；会话关闭则 [sink] 返回 null，任务随关闭取消、本就不会投。
 *
 * 核对 + 补投仍保留：投递与 abort 收尾撞车等极端情形下通知可能没落地，最多补 3 次。
 *
 * @return 通知是否已落地（历史或队列里有它）。
 */
internal suspend fun deliverNote(
    sink: () -> NoteSink?,
    taskId: String,
    note: String,
): Boolean {
    repeat(3) {
        val target = sink() ?: return false
        if (hasNote(target.messages.value, target.queuedMessages(), taskId)) return true
        target.sendNote(note)
        // 忙时通知进队列：这一步同步就能确认。空闲时 send 起新回合，落历史是异步的，等一拍。
        if (hasNote(target.messages.value, target.queuedMessages(), taskId)) return true
        val landed = withTimeoutOrNull(2_000) {
            target.messages.first { hasNote(it, target.queuedMessages(), taskId) }
        }
        if (landed != null) return true
    }
    return false
}

/**
 * 完成通知是否已进入历史或仍在队列里（纯判据，便于单测）。
 *
 * 按通知的**头部** `[background task <id>:` 判，不按「正文里含 id」判（B 审 P2）：
 * 正文带日志尾部，而 `ls .kair/tasks` 这类命令的输出里天然会出现别的任务 id ——
 * 用子串就会把「别的任务的通知」误判成本条已送达，于是这条通知被静默丢掉，
 * 且 `markDelivered` 已经打了，没有任何重试。口径也与系统提示的 wire 判重（`504d4f5`，
 * 按完整正文而非片段）一致。
 */
internal fun hasNote(
    messages: List<ChatMessage>,
    queued: List<ChatMessage>,
    taskId: String,
): Boolean {
    val marker = "[background task $taskId:"
    return messages.any { it.isSystemNote && it.text.startsWith(marker) } ||
        queued.any { it.isSystemNote && it.text.startsWith(marker) }
}
