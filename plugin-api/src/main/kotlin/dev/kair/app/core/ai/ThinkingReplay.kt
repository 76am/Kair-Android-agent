package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage

/**
 * 历史思考能不能回传给这一发请求 —— **唯一判据点**（U76）。
 *
 * 来源键是三元组 `(providerId, providerHost, model)`：只有产出这条消息的端点与模型
 * 跟本发请求完全一致，才把 `Part.Thinking` 回传。任一侧任一字段缺失（老会话没有前两个
 * 字段）一律按 foreign 处理 —— 保守不回传，不猜 baseUrl、不按模型名反推厂商。
 *
 * 为什么不能只比 `model`：同一个模型串可以挂在不同中转上，而 Kair 里这些中转都记为
 * `providerId = "openai"` 的「自定义」条目 —— 只比模型串会把上家的思考当自己的发出去。
 * 为什么不能只比 `providerId`：它是**目录条目 id**（`ModelCatalog` 只有 openai / longcat /
 * gemini / anthropic 四个内置值），区分不了同一条目下的不同端点，所以还要比主机名。
 *
 * 六处调用点必须都用它：`RequestAssembler` 的 canonical / readable 两处（前缀摘要），
 * 以及 OpenAI Chat / OpenAI Responses / Anthropic / Gemini 四个传输层。任一处漏改，
 * 摘要就会与真实请求字节不一致，缓存命中率观测随之失真。
 */
fun ChatMessage.replaysThinkingTo(request: ChatRequest): Boolean {
    val originProvider = providerId ?: return false
    val originHost = providerHost ?: return false
    val originModel = model ?: return false
    val targetProvider = request.providerId ?: return false
    val targetHost = request.providerHost ?: return false
    return originProvider == targetProvider && originHost == targetHost && originModel == request.model
}
