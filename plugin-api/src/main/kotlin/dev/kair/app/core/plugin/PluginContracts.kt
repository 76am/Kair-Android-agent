package dev.kair.app.core.plugin

import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * `tool_call` 钩子载荷。[sessionId] 让审批插件看到发起调用的会话；
 * [workspace] 是该会话的工作区路径（审批预览据此解析相对 source）。
 */
data class ToolCallHook(
    val name: String,
    val arguments: JsonObject,
    val sessionId: String? = null,
    val workspace: String? = null,
)

/**
 * `tool_call` 钩子的裁决：改写后的 [arguments]，以及可选的 [blockReason]。
 * [blockReason] 非空时工具不执行，原因作为错误结果回给模型。
 */
data class ToolCallOutcome(val arguments: JsonObject, val blockReason: String? = null)

/** `tool_result` 钩子载荷。 */
data class ToolResultHook(val name: String, val result: ToolResult)
