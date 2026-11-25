package dev.kair.app.core.agent

import dev.kair.app.core.ai.OverflowDetector
import dev.kair.app.core.model.StopReason

/**
 * 空回复重试只对「**很快**就空」的发次生效：慢回复是模型 / 端点的行为（长思考、长 prefill、
 * 中转站掐流），重发只会再等一轮、还让用户以为回合被打断。10 秒是经验线：本地网关那种
 * 推理后直接 EOS 的空回复在 1-2 秒内就结束；云端超过 10 秒才吐结束帧的，不再自动重发。
 */
internal const val EmptyRetryFastWindowMs = 10_000L

/** 这一发是否值得自动重发：空回复本身由调用方判定，这里只管时间窗。 */
internal fun shouldRetryEmpty(elapsedMs: Long): Boolean = elapsedMs in 0..EmptyRetryFastWindowMs

/**
 * 空回复自动重试：模型干净收尾、但既无正文也无工具调用时，重发同一份请求一次。
 *
 * 为什么单独成文件：`runLoop` 的体量被历史上的拆分计划锁住（下一次往它加逻辑要先拆），
 * 而这条策略只依赖「一发请求」的结果，包在流调用外面比塞进循环更小、也更好测。
 *
 * 只认 [StopReason.EMPTY_RESPONSE]：用户中止走取消异常（不会走到这里），网络 / 协议失败
 * 是 [StopReason.ERROR] 且已由传输层自己的瞬时重试兜过 —— 两者都不该被当成空回复重发。
 * 慢发次（超过 [EmptyRetryFastWindowMs]）也不重发：见常量注释。
 */
internal suspend fun streamWithEmptyRetry(stream: suspend () -> RequestOutcome): RequestOutcome {
    val started = System.nanoTime()
    val first = stream()
    if (first.assistant.stopReason != StopReason.EMPTY_RESPONSE) return first
    // 空回合也可能是「静默溢出」的形态（输入顶满窗口、服务端只回结束原因）：那要交给
    // 循环里的溢出救援（压缩后重发），不能在这里先重发一次、把越窗信号和脚本回合吃掉。
    if (OverflowDetector.isContextOverflow(first.assistant, first.inputs.contextWindow)) return first
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    if (!shouldRetryEmpty(elapsedMs)) return first
    return stream()
}
