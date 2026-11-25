package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 历史持久化的唯一受控入口（U43）：**「是否已作废」的判断与提交在同一把锁里**。
 *
 * 为什么不能只在调用方加 `if (!closed)`：判断与提交之间还有一个窗口 —— 恰好在两者
 * 之间到来的 [markClosed] 会让这一笔绕过屏障。把判断与写入收进同一个临界区后，
 * [markClosed] 返回时，任何尚未进入临界区的提交都无法再开始；已进入的提交完成或
 * 在临界区内被拒绝，但不会在置位之后开始新的写入。
 *
 * 不做的事：不等待取消收尾、不持有协程 Job、不区分正常 / 取消收尾 —— 屏障只回答
 * 「还允许写吗」，写什么由调用方决定。`TurnExecutor` 拿到的是包了这一层的回调，
 * 因此它不再能绕开这道闸（任务 §1 的「执行器持有原始回调」只保留受控入口）。
 */
internal class HistoryWriter(
    private val persist: (ChatMessage) -> Unit,
    private val replace: (List<ChatMessage>) -> Unit,
) {
    private val lock = ReentrantLock()

    /**
     * 已作废。写侧在 [lock] 内置位，读侧可能来自执行器 / 看门狗协程，故 `@Volatile`；
     * 它也供回合壳做快速判断，但真正的写入闸门在锁内再判一次。
     */
    @Volatile
    var isClosed: Boolean = false
        private set

    /** 追加一条消息；已作废时丢弃。 */
    fun append(message: ChatMessage) {
        lock.withLock {
            if (!isClosed) persist(message)
        }
    }

    /** 整份替换历史（取消收尾修悬空调用用）：同样受作废屏障约束。 */
    fun replaceAll(history: List<ChatMessage>) {
        lock.withLock {
            if (!isClosed) replace(history)
        }
    }

    /**
     * 置位作废：与提交共用 [lock]，返回后不会再有新的提交开始。
     * 只置位、不等待任何取消收尾 —— 调用方可能是主线程（任务 §3 不变量 3）。
     */
    fun markClosed() {
        lock.withLock { isClosed = true }
    }
}
