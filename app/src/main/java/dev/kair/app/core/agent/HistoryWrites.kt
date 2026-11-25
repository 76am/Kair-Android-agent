package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * 历史写入面（U77，2026-11-12 从 `AgentSession` 搬出）。
 *
 * 为什么单独一个文件：`AgentSession` 在体量守卫上**已被明令不许再上调**（`SourceSizeGuardTest`
 * 的条目注释：「下一批动本文件的必须先拆」），而本批要改的恰好就是写入面 —— 搬出来既是守卫
 * 要求的「先拆」，也让「这一笔历史属于哪一轮」只有一处判据（`AGENTS.md` §10.4）。
 *
 * 归属与生命周期（§10.5）：一个会话一份，随会话对象创建与消失；不新增全局可变状态。
 *
 * 五条通路分两类，**只有后一类会被「被放弃的轮次」挡下**：
 *  · 修复 [repair]：保留全部消息、只补悬空工具配对 —— 取消收尾必须跑（ADR 0003）；
 *  · 新增 / 覆盖 / 重写 [updateAssistant] [append] [replace]：被放弃的轮次一律丢。
 * 删除 [remove] 不在两者之内：它只减消息，不会把用户没看见的内容写进前缀。
 */

/**
 * 会话历史的**唯一写入面**：内存副本与落盘入口成对，闸门只有一处。
 *
 * @param messages 会话持有的内存副本（唯一副本；这里不另存一份）
 * @param writer 落盘屏障（作废判断与提交在同一把锁里，见 [HistoryWriter]）
 * @param isSuppressed 这一笔写入是否属于**已被强制放弃的轮次**（归属判据在建会话侧）
 */
internal class HistoryWrites(
    private val messages: MutableStateFlow<List<ChatMessage>>,
    private val writer: HistoryWriter,
    private val attachments: AttachmentStore,
    private val imageScaler: ImageDownscaler.Scaler,
    private val imageLog: (String) -> Unit,
    private val providerConfig: () -> ProviderConfig,
    private val isSuppressed: () -> Boolean,
) {

    /**
     * 新增或覆盖一条 assistant 消息（流式增量落点）。
     *
     * 挡在这里而不是在执行器里：`TurnExecutor` / `TurnRunner` 的取消收尾都在
     * `withContext(NonCancellable)` 里，取消检查在那里失效；若每个写入点各自判一次，
     * 就会漏掉下一个（`AGENTS.md` §10.4）。挡在这里，被放弃那一轮的**迟到新增**都丢。
     */
    fun updateAssistant(id: String, message: ChatMessage) {
        if (isSuppressed()) return
        messages.update { list ->
            if (list.any { it.id == id }) list.map { if (it.id == id) message else it }
            else list + message
        }
    }

    /** 追加一条消息（用户 / 工具结果 / 助手回合），图片本体先落地为内容寻址附件。 */
    suspend fun append(message: ChatMessage) {
        if (isSuppressed()) return
        // 见 [ImageDownscaler.attach]：纯文字路径原样返回，字节不变。
        val persisted = ImageDownscaler.attach(message, providerConfig, attachments, imageScaler, imageLog)
        messages.update { it + persisted }
        writer.append(persisted)
    }

    /** 只减不增：被放弃的轮次也能安全执行（它不会把没看见的内容写进前缀）。 */
    fun remove(id: String): ChatMessage? {
        val removed = messages.value.firstOrNull { it.id == id } ?: return null
        messages.update { list -> list.filterNot { it.id == id } }
        return removed
    }

    /**
     * 整份**重写**历史：压缩把前缀换成摘要；编辑 / 重试 / 删除由用户发起。
     *
     * 被强制放弃的轮次不许走这里（U77 的 0.3）：它的压缩提交在用户已经看到「回到可发送
     * 状态」之后才醒来，此时把历史换成「摘要 + 保留段」= 用户没同意、也没看见的改写。
     * 与 [repair] 分开，正是因为**修复不该被挡、重写该被挡**。
     *
     * @return 真的改写了没有 —— 压缩据此决定要不要报「已压缩 X → Y」，不然界面会报一次
     *   什么都没发生的成功。
     */
    fun replace(history: List<ChatMessage>): Boolean {
        if (isSuppressed()) return false
        apply(history)
        return true
    }

    /**
     * 整份**修复**历史：保留全部消息、只修悬空 `tool_call` 与非法参数（[HistorySanitizer]）。
     *
     * **被放弃的轮次也要跑**（ADR 0003）：取消收尾必须把残缺的工具配对补齐，否则下一发请求
     * 会被端点整轮拒收 —— 而「补齐」不是「改写内容」，用户看不到任何东西消失。
     */
    fun repair(history: List<ChatMessage>) = apply(history)

    private fun apply(history: List<ChatMessage>) {
        messages.value = history
        writer.replaceAll(history)
    }
}
