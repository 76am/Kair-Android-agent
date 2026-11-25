package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part

/**
 * [matchToolResults] 的按结构缓存。
 *
 * 流式输出的每一帧都会换一个消息列表实例（末条正文在长），但**配对关系**不变。
 * 直接 `remember(messages)` 会让整张匹配表每帧重建：既白扫一遍转录，又让下游
 * （消息卡的 `matched` 参数、回合切分）拿到的永远是「不相等的新 Map」——
 * 已完成的回合跟着每帧重组，展开上百步时就是卡顿的一大半。
 *
 * 命中判据只认结构（id / 角色 / 工具调用与结果的 id、结果长度与错误位），
 * 不含正文：正文在流式期间变化不影响谁配谁，工具结果一旦落盘也不再变。
 */
internal class ToolResultIndex {
    private var signature = 0
    private var size = -1
    private var cached: Map<String, List<Part.ToolResult?>> = emptyMap()

    fun match(messages: List<ChatMessage>): Map<String, List<Part.ToolResult?>> {
        val sig = matchSignature(messages)
        if (sig == signature && messages.size == size) return cached
        return matchToolResults(messages).also {
            signature = sig
            size = messages.size
            cached = it
        }
    }
}

/**
 * 配对结构签名。逐条折叠（不建临时集合）：一条消息的身份 = id + 角色 +
 * 是否工具回图（决定它是否结束配对窗口）+ 它带的调用 / 结果的 id 顺序。
 * 结果再补「长度 + 是否错误」—— 同 id 被原地改写时（正常不会发生）也能失效。
 */
private fun matchSignature(messages: List<ChatMessage>): Int {
    var hash = 1
    var previous: ChatMessage? = null
    for (m in messages) {
        hash = 31 * hash + m.id.hashCode()
        hash = 31 * hash + m.role.ordinal
        hash = 31 * hash + if (isToolImageMessage(m, previous)) 1 else 0
        for (call in m.toolCalls) hash = 31 * hash + call.id.hashCode()
        for (result in m.toolResults) {
            hash = 31 * hash + result.toolCallId.hashCode()
            hash = 31 * hash + result.content.length
            hash = 31 * hash + if (result.isError) 1 else 0
        }
        previous = m
    }
    return hash
}
