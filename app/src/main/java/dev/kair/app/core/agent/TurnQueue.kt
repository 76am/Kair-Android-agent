package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a message queued while the agent is busy is delivered (pi: steer vs followUp). */
enum class QueueKind { STEER, FOLLOW_UP }

/** A user message waiting to be delivered. */
data class QueuedMessage(val message: ChatMessage, val kind: QueueKind)

/**
 * 「会话忙 / 正在停止」期间的消息队列（内核，`AgentSession` 专用）。
 *
 * 为什么单独成文件：队列决定两件最容易写错的事 ——
 * ① 「取出 → 提交」必须是**一个出口内**的配对动作：取出即受托，提交没成（取消）就归还，
 * 消息要么进历史、要么还在队列（U21 的丢失窗口就是「取出即离队、提交前被 abort」）；
 * ② 停止收尾结束后**谁接上**：队首是 [QueueKind.FOLLOW_UP] 就直接起新回合，是
 * [QueueKind.STEER] 就留在队列里、由 `runLoop` 开头统一并入。
 * 这两条此前埋在 1700 行的会话对象里，只能通过起会话、卡时序才能验证；
 * 搬成独立类型后可直接单测（`TurnQueueTest`）。
 *
 * **不持有协程、不加锁**：所有读写都发生在会话侧 `runGate` 之内（回合串行），
 * [MutableStateFlow.update] 自身的 CAS 只用来保证「取出 + 移除」不被撕裂。
 */
internal class TurnQueue {

    private val _messages = MutableStateFlow<List<QueuedMessage>>(emptyList())

    /** UI 订阅它渲染「排队中」的条目；只读投影，写入口全在本类内部。 */
    val messages: StateFlow<List<QueuedMessage>> = _messages.asStateFlow()

    fun enqueue(message: ChatMessage, kind: QueueKind) {
        _messages.update { it + QueuedMessage(message, kind) }
    }

    /** 按消息 id 撤掉一条排队中的消息（界面上的「取消」）。 */
    fun remove(messageId: String) {
        _messages.update { list -> list.filterNot { it.message.id == messageId } }
    }

    fun clear() {
        // 清的是「用户还没说出口的话」：停止 / 删除消息时不该把用户的输入带进下一回合。
        // 系统通知**保留** —— 它是 agent 自己的投递（U18 起按步并入），清掉就等于把
        // 完成通知弄丢，而投递方已经认为「进了队列 = 送达」。
        _messages.update { list -> list.filter { it.message.isSystemNote } }
    }

    val isEmpty: Boolean get() = _messages.value.isEmpty()

    /**
     * 原子取出一条 [kind] 的队首（无则 null）。取出即「起用」：在途条目不在 [messages] 里，
     * 界面撤回判据「在队列 = 还没起用」保持成立。
     */
    private fun takeOne(kind: QueueKind): QueuedMessage? {
        var taken: QueuedMessage? = null
        _messages.update { list ->
            val index = list.indexOfFirst { it.kind == kind }
            if (index < 0) {
                list
            } else {
                taken = list[index]
                list.toMutableList().also { it.removeAt(index) }
            }
        }
        return taken
    }

    /** [takeOne] 的配对归还：提交被取消时放回队首（它先于在途期间新入队的任何消息），不许「既不进历史又不在队列」。 */
    private fun putBack(queued: QueuedMessage) {
        _messages.update { list -> listOf(queued) + list }
    }

    /**
     * 取出并提交**一条**（[submitPending] 的单条版）。「取出 → 提交」的配对**只此一处**：
     * 提交被取消（abort 落进提交窗口）→ 归还队首；正常返回 = 已进历史，非取消异常的命运
     * 跟随提交方自身的原子性（不归还、不重复）。返回 true = 提交过一条。
     */
    suspend fun submitNext(kind: QueueKind, submit: suspend (ChatMessage) -> Unit): Boolean {
        val taken = takeOne(kind) ?: return false
        try {
            submit(taken.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            putBack(taken)
            throw e
        }
        return true
    }

    /**
     * 唯一的「取出 → 提交」出口（[submitNext] 的循环版）：把某类待投消息逐条提交进历史。
     * 返回 true = 提交过至少一条。
     */
    suspend fun submitPending(kind: QueueKind, submit: suspend (ChatMessage) -> Unit): Boolean {
        var delivered = false
        while (submitNext(kind, submit)) delivered = true
        return delivered
    }

    /**
     * 停止收尾结束后该怎么接上（纯决策，**只读不取** —— 取出发生在 [submitNext] 里）。
     * 队列空时返回 null，会话侧什么都不做。
     *
     * 不在这里判「会话是否已关闭」：那是会话侧的状态，队列看不到，也不该看
     * （`AGENTS.md` §10.4 禁止执行层回读隐式全局状态）。
     */
    fun planNextAfterStop(): NextRun? {
        val next = _messages.value.firstOrNull() ?: return null
        return if (next.kind != QueueKind.FOLLOW_UP) NextRun.ContinueTranscript
        else NextRun.StartWith(next.message)
    }

    /** [TurnQueue.planNextAfterStop] 的结果：会话侧据此决定起哪一轮。 */
    internal sealed interface NextRun {
        /** 队首是 FOLLOW_UP：直接把它作为新的用户回合起跑（否则这一轮会先发一次不含它的请求，白跑）。 */
        data class StartWith(val message: ChatMessage) : NextRun

        /** 队首是 STEER：留在队列里不动，空跑一轮由 `runLoop` 开头统一并入。 */
        data object ContinueTranscript : NextRun
    }
}
