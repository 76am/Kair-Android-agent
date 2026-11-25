package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role

/**
 * 用户侧的历史改写（A5 第二步拆出）：编辑重发 / 重试某条 / 删除某条。
 *
 * 三者的共同前提是**历史必须严格一一配对**（否则 provider 直接拒答），所以都经由
 * [HistorySanitizer] 修一遍；共同的做法是**排到轮次闸门后面**执行 —— 取消中的旧轮还在
 * 往历史里写收尾内容，这里必须等它彻底结束（见会话的 `runGate` 与 `launchSerial`）。
 *
 * 语义与搬迁前逐字一致：这里只搬位置，不改行为。
 */
internal class HistoryEdits(private val host: Host) {

    internal data class Host(
        val messages: () -> List<ChatMessage>,
        /** 整份重写历史；返回 false 表示**被放弃的轮次**拒绝改写（U77，调用方按失败处理）。 */
        val replaceHistory: (List<ChatMessage>) -> Boolean,
        val clearQueue: () -> Unit,
        val runUser: suspend (ChatMessage?) -> Unit,
        /** 新建一条用户消息（会话的入会话唯一入口，含控制字符剔除与钩子链）。 */
        val newUserMessage: (String) -> ChatMessage,
        /** 现在能不能改历史：回合在跑（且不是停止收尾）时不能。 */
        val isEditable: () -> Boolean,
        /** 排到闸门后、**产生回合**的入口（会置 Running 并登记 job）。 */
        val launchRun: (suspend () -> Unit) -> Unit,
        /** 排到闸门后、**只改历史**的入口（不产生回合，状态最多到 Busy）。 */
        val launchSerial: (suspend () -> Unit) -> Unit,
    )

    /**
     * Rewrite an earlier user message and re-run from that point. Everything after it
     * is dropped from the transcript; file edits already made on disk are not undone
     * (same caveat as pi's in-place edit).
     */
    fun editUserMessage(messageId: String, newText: String) {
        // 停止收尾期间允许排到闸门后（新一轮必须等旧轮彻底结束才动手），
        // 与 abort 之前「同步置空闲」时的行为保持一致。
        if (!host.isEditable()) return
        // 历史改写必须与「上一轮（可能正在取消收尾）」串行：旧轮收尾会往历史里
        // 写半截回合 / 修工具配对，若与新一次改写并发，两边都会看到对方写了一半的历史。
        host.launchRun {
            val list = host.messages()
            val index = list.indexOfFirst {
                it.id == messageId && it.role == Role.USER && !it.isCompaction && !it.isSystemNote
            }
            if (index < 0) return@launchRun
            val replacement = host.newUserMessage(newText)
            // 撤回点之前若还残留一条因切断而悬空的 tool_call（其 tool_result 本就在被丢弃的
            // 后半段里），就地清掉，保证重新生成前的历史仍严格一一配对
            // （见 [HistorySanitizer.repairToolPairing]）。
            host.replaceHistory(HistorySanitizer.repairHistory(list.take(index) + replacement))
            host.clearQueue()
            host.runUser(null)
        }
    }

    /**
     * Retry a single transcript entry: drop [messageId] **and everything after it**, then
     * let the agent re-generate from the new tail.
     *
     * 与 [editUserMessage] 的区别：本方法**不改写**任何内容，只把这条已生成的消息连同
     * 其后的消息一起撤回，让模型基于剩下的结尾重新生成 —— 即「重试这一条」。因此它可以
     * 落在**任意**一条消息上（最后一条工具结果 / 最终回复 / 被用户中断的回合），而不是只能
     * 落到触发整轮循环的那条用户消息上。撤回点之前的消息（含其所属的用户回合）原样保留。
     *
     * 磁盘上已经发生的工具副作用不还原（与 pi 的 in-place 编辑同款 caveat）；调用方应在
     * 撤回前提示用户。
     */
    fun retryFrom(messageId: String) {
        if (!host.isEditable()) return
        host.launchRun {
            val list = host.messages()
            val index = list.indexOfFirst { it.id == messageId }
            // index==0 时撤回后什么都不剩，无处可退；至少要留一条消息作为结尾。
            if (index <= 0) return@launchRun
            // 撤回点落在工具配对**中间**时（例如重试一条孤儿工具结果），留下的悬空调用就地
            // 清掉 —— 否则下一回合请求会被 provider 直接拒绝（见 [HistorySanitizer.repairToolPairing]）。
            host.replaceHistory(HistorySanitizer.repairToolPairing(list.take(index)))
            host.clearQueue()
            host.runUser(null)
        }
    }

    /**
     * 从 [messageId] 起删除到末尾（长按「删除这条及之后」）。不撤回、不重跑。
     *
     * 为什么不是只删一条：对话是因果链，中间抽掉一条会让模型看到断裂的前因后果；工具调用
     * 与结果是严格配对的，单删一侧还得就地改写另一侧。截断到末尾没有这两类问题，且与
     * [retryFrom] 共用同一条「撤回点」语义 —— 区别只是不重新生成。
     *
     * 撤回点恰好落在工具配对中间时（直接删一条工具结果、或删掉带调用的 assistant 消息），
     * 前缀里会留下没有结果的调用；[HistorySanitizer.repairToolPairing] 会把它换成中断提示，
     * 请求仍合法。与 [retryFrom] 一致：只在空闲时改历史，磁盘 / 环境上已经发生的实际改动
     * 不撤销；删除点之后的前缀失效一次（缓存命中率会掉），这是用户主动改历史的代价。
     */
    fun deleteMessage(messageId: String) {
        if (!host.isEditable()) return
        // 与 editUserMessage 同理：排到闸门后，等取消中的旧轮收尾完再改历史。
        host.launchSerial {
            val list = host.messages()
            val index = list.indexOfFirst { it.id == messageId }
            if (index < 0) return@launchSerial
            host.replaceHistory(HistorySanitizer.repairToolPairing(list.take(index)))
        }
    }
}
