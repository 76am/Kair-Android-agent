package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.StopReason

/**
 * Context-overflow detection, ported from pi-ai's `utils/overflow.ts`.
 *
 * Providers report an over-long prompt in three different ways:
 * 1. an explicit error message (most providers),
 * 2. a successful response whose input usage exceeds the context window (silent overflow),
 * 3. a `length` stop with zero output when the server truncated the input to fit.
 */
object OverflowDetector {

    private val OVERFLOW_PATTERNS = listOf(
        Regex("prompt (?:is )?too long", RegexOption.IGNORE_CASE),
        Regex("prompt exceeds max length", RegexOption.IGNORE_CASE),
        Regex("request_too_large", RegexOption.IGNORE_CASE),
        Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
        Regex("exceeds the context window", RegexOption.IGNORE_CASE),
        Regex("exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))", RegexOption.IGNORE_CASE),
        Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
        Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE),
        Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
        Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
        Regex("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?", RegexOption.IGNORE_CASE),
        Regex("input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)", RegexOption.IGNORE_CASE),
        Regex("exceeds the limit of \\d+", RegexOption.IGNORE_CASE),
        Regex("exceeds the available context size", RegexOption.IGNORE_CASE),
        Regex("greater than the context length", RegexOption.IGNORE_CASE),
        Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
        Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
        Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
        Regex("prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?", RegexOption.IGNORE_CASE),
        Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
        Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE),
        Regex("range of input length should be", RegexOption.IGNORE_CASE),
        Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
        Regex("too many tokens", RegexOption.IGNORE_CASE),
        Regex("token limit exceeded", RegexOption.IGNORE_CASE),
    )

    /** Non-overflow errors (throttling / rate limiting) that must not trigger compaction. */
    private val NON_OVERFLOW_PATTERNS = listOf(
        Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
    )

    private val CEREBRAS_BODYLESS_OVERFLOW = Regex("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", RegexOption.IGNORE_CASE)

    /**
     * True when [message] indicates the request exceeded the model's context window.
     * [contextWindow] enables the silent and length-stop detections.
     */
    fun isContextOverflow(message: ChatMessage, contextWindow: Int? = null): Boolean {
        val err = message.errorMessage
        if (message.stopReason == StopReason.ERROR && err != null) {
            val nonOverflow = NON_OVERFLOW_PATTERNS.any { it.containsMatchIn(err) }
            if (!nonOverflow && (OVERFLOW_PATTERNS.any { it.containsMatchIn(err) } || CEREBRAS_BODYLESS_OVERFLOW.containsMatchIn(err))) {
                return true
            }
        }

        val usage = message.usage
        if (contextWindow != null && usage != null) {
            // Case 2: silent overflow (z.ai style) - successful but usage exceeds context.
            // 输入侧总用量 = 未命中 + 命中缓存 + 写入缓存；漏掉 cacheWrite 会低估真实占用。
            // emptyResponse 与 STOP 同属「无错误信号的正常收尾」：空回合被 TurnExecutor
            // 归一为 emptyResponse（U48），越窗信号的判定不该被这次分类改写打瞎。
            if (message.stopReason == StopReason.STOP || message.stopReason == StopReason.EMPTY_RESPONSE) {
                if (usage.input + usage.cacheRead + usage.cacheWrite > contextWindow) return true
            }
            // Case 3: length-stop overflow (Xiaomi MiMo style) - input filled the window, no room for output.
            // LENGTH 的空回合同样被归一为 emptyResponse，但「零输出 + 输入顶满窗口」的
            // 信号形状不变，判定照旧成立。
            if (
                (message.stopReason == StopReason.LENGTH || message.stopReason == StopReason.EMPTY_RESPONSE) &&
                usage.output == 0
            ) {
                if (usage.input + usage.cacheRead + usage.cacheWrite >= contextWindow * 0.99) return true
            }
        }
        return false
    }

    /** A length stop that ended below the model's intended output limit may be context pressure. */
    fun isRecoverableLength(message: ChatMessage, desiredMaxOutput: Int): Boolean =
        message.stopReason == StopReason.LENGTH && desiredMaxOutput > 0 && (message.usage?.output ?: 0) < desiredMaxOutput
}
