package dev.kair.app.ui.screens.chat

import dev.kair.app.core.agent.Compaction
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason

/** 当前是否在解码：只有 [STREAMING] 有实时速度，[WAITING] 显示「正在生成…」。 */
internal enum class SpeedPhase { IDLE, WAITING, STREAMING }

/** 一次模型响应的解码读数（不落盘）。 */
internal data class ResponseStats(
    val outputTokens: Int,
    val decodeMs: Long,
    /** 请求发出 → 首个输出 token；界面拿不到请求真实起点时以占位消息出现为起点。 */
    val firstTokenMs: Long?,
    /** 真实 usage 缺失、token 数为估算值。 */
    val estimated: Boolean,
)

/**
 * 从会话消息快照里还原「当前这一发响应」的解码速度（U111）。
 *
 * 为什么在界面层：内核没有暴露流式时间戳，而 `AgentSession.messages` 已经以 ~15fps
 * 推送完整快照 —— 用它算速度不需要给内核 / plugin-api 加字段，也不会让 `TurnExecutor`
 * 的文件体量再越线。代价是只观测**界面打开期间**的回合；速度本来就不落盘，
 * 切走 / 重启后历史回合不显示平均，与设计口径一致。
 *
 * 口径：
 * - 实时 = 最近 2 秒的估算 token 增量 / 时间；静默超过 1.5 秒视为 0（不显示）。
 * - 单发平均 = 最终 `usage.output` /（收口快照到达 − 首个 token）；usage 缺失时用估算。
 * - 错误 / 中止的响应不记 stats；解码不足 250ms 的短响应也不记（除出来的读数没有意义）。
 */
internal class ChatSpeedTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var currentId: String? = null
    private var currentMessage: ChatMessage? = null
    private var requestStartedAt = 0L
    private var firstTokenAt: Long? = null
    private var lastGrowthAt: Long? = null
    private var lastTokens = 0
    private var finalized = false
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private val completed = LinkedHashMap<String, ResponseStats>()

    /** 当前相位；回合内的工具执行阶段由消息侧停更，外层用 [TurnState] 决定文案。 */
    var phase: SpeedPhase = SpeedPhase.IDLE
        private set

    /** 实时速度（tok/s）；null = 还没有可用的窗口 / 已静默。 */
    var liveTps: Double? = null
        private set

    /** 已完成的单发响应，按 assistant 消息 id 归档。 */
    val stats: Map<String, ResponseStats> get() = completed

    fun onMessages(messages: List<ChatMessage>, now: Long = clock()) {
        val last = messages.lastOrNull { it.role == Role.ASSISTANT }
        if (last == null) {
            clear()
            return
        }
        if (last.id != currentId) {
            finalize(now)
            currentId = last.id
            requestStartedAt = now
            firstTokenAt = null
            lastGrowthAt = null
            lastTokens = 0
            samples.clear()
            finalized = false
            phase = SpeedPhase.WAITING
            liveTps = null
        }
        currentMessage = last
        // 错误 / 中止不记 stats，也不做 token 增长判定（ABORTED 的估算会被换成中断提示长度）。
        if (last.stopReason == StopReason.ERROR || last.stopReason == StopReason.ABORTED) {
            finalize(now)
            return
        }
        if (finalized) return

        val tokens = estimateOutputTokens(last)
        if (tokens > lastTokens) {
            if (firstTokenAt == null) firstTokenAt = now
            lastGrowthAt = now
            samples.addLast(now to tokens)
            while (samples.size > 2 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
            lastTokens = tokens
            phase = SpeedPhase.STREAMING
            liveTps = rollingTps()
        } else {
            val lastGrowth = lastGrowthAt
            if (lastGrowth != null && now - lastGrowth > SILENCE_MS) {
                // 模型暂时没有新输出（等工具结果 / 服务端停顿）：不要留一个陈旧的实时值。
                liveTps = null
            }
        }
        if (last.stopReason != null) finalize(now)
    }

    /** 定时补拍：输出静默超过 1.5 秒时把实时值清掉，避免状态条停在最后一个读数。 */
    fun onTick(now: Long = clock()) {
        val lastGrowth = lastGrowthAt
        if (phase == SpeedPhase.STREAMING && lastGrowth != null && now - lastGrowth > SILENCE_MS) {
            liveTps = null
        }
    }

    /**
     * 只算**真正生成的内容**：正文 / 思考原文 / 工具名与参数。
     *
     * 不复用 `Compaction.estimateTokens`：它把思考签名也算进体积（压缩预算需要），
     * 但签名不是模型逐 token 生成的 —— 末尾一次长签名到达会让实时速度假性飙升。
     */
    private fun estimateOutputTokens(message: ChatMessage): Int {
        var tokens = 0
        for (part in message.parts) {
            tokens += when (part) {
                is Part.Text -> Compaction.estimateTextTokens(part.text)
                is Part.Thinking -> Compaction.estimateTextTokens(part.thinking)
                is Part.ToolCall -> Compaction.estimateTextTokens(part.name + part.arguments)
                else -> 0
            }
        }
        return tokens
    }

    private fun rollingTps(): Double? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val dt = last.first - first.first
        if (dt < MIN_WINDOW_MS) return null
        val tokens = last.second - first.second
        if (tokens <= 0) return null
        return tokens * 1000.0 / dt
    }

    private fun finalize(now: Long) {
        if (finalized) return
        finalized = true
        phase = SpeedPhase.IDLE
        liveTps = null
        val id = currentId ?: return
        val message = currentMessage ?: return
        if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) return
        val first = firstTokenAt ?: return
        // 解码终点用「收口快照到达」的时间，而不是最后一个增长点：服务端在最后一个 token
        // 之后可能还有结束帧 / 短停顿，那段仍属于这一发的解码时长（design §3）。
        val decodeMs = now - first
        if (decodeMs < MIN_DECODE_MS) return
        val authoritative = message.usage?.output?.takeIf { it > 0 }
        val tokens = authoritative ?: lastTokens
        if (tokens <= 0) return
        completed[id] = ResponseStats(
            outputTokens = tokens,
            decodeMs = decodeMs,
            firstTokenMs = (first - requestStartedAt).coerceAtLeast(0L),
            estimated = authoritative == null,
        )
        if (completed.size > MAX_RESPONSES) completed.remove(completed.keys.first())
    }

    private fun clear() {
        currentId = null
        currentMessage = null
        firstTokenAt = null
        lastGrowthAt = null
        lastTokens = 0
        finalized = false
        samples.clear()
        phase = SpeedPhase.IDLE
        liveTps = null
    }

    private companion object {
        const val WINDOW_MS = 2_000L
        const val SILENCE_MS = 1_500L
        const val MIN_WINDOW_MS = 250L
        const val MIN_DECODE_MS = 250L
        const val MAX_RESPONSES = 500
    }
}
