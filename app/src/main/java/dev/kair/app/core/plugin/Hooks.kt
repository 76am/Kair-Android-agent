package dev.kair.app.core.plugin

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plugin hook points — the "middleware" layer.
 *
 * These mirror the subset of pi's extension events that are meaningful for an
 * in-process Android agent: user input, the context sent to the model, tool
 * calls/results, and provider request headers. Unlike [PluginContext] contributions
 * (which are unordered additions), hooks observe and can *rewrite* core data flow,
 * so they run as an ordered chain: each handler receives the value produced by
 * the previous handler and may return a replacement (`null` keeps the current value).
 *
 * **所有权**：每条钩子都记着注册它的插件 id。卸载 / 重装靠它回收 —— 否则插件停了
 * 钩子还在链上继续跑（审批 `tool_call` 会在插件禁用后继续弹窗）。宿主自己注册的钩子
 * 归 [HOST_OWNER]，不随任何插件卸载消失。
 *
 * **并发**：注册（`onXxx`）发生在插件 activate 期间，而调用（`runXxx`）发生在会话线程上；
 * 两者不在同一线程，所以五个链都是 `CopyOnWriteArrayList` —— 读多写少，读侧不加锁、也不会
 * 在遍历中途被注册打断（普通 `mutableListOf` 会抛 CME 或漏掉刚注册的钩子）。
 *
 * 钩子载荷类型（[ToolCallHook] / [ToolCallOutcome] / [ToolResultHook]）位于
 * `:plugin-api` 模块（`PluginContracts.kt`），供热插件与宿主共享同一份类。
 */
class HookBus {

    private data class OwnedHook<T>(val owner: String, val handler: T)

    private val inputHooks = CopyOnWriteArrayList<OwnedHook<(String) -> String?>>()
    private val contextHooks = CopyOnWriteArrayList<OwnedHook<(List<ChatMessage>) -> List<ChatMessage>?>>()
    private val toolCallHooks =
        CopyOnWriteArrayList<OwnedHook<suspend (ToolCallHook) -> ToolCallOutcome?>>()
    private val toolResultHooks = CopyOnWriteArrayList<OwnedHook<(ToolResultHook) -> ToolResult?>>()
    private val headerHooks = CopyOnWriteArrayList<OwnedHook<(Map<String, String>) -> Map<String, String>?>>()

    /** Total registered handlers; surfaced in the info panel. */
    val size: Int
        get() = inputHooks.size + contextHooks.size + toolCallHooks.size +
            toolResultHooks.size + headerHooks.size

    val isEmpty: Boolean get() = size == 0

    /** Rewrite an outbound user turn (pi: `input`). */
    fun onInput(handler: (String) -> String?) = onInput(HOST_OWNER, handler)

    internal fun onInput(owner: String, handler: (String) -> String?) {
        inputHooks += OwnedHook(owner, handler)
    }

    /** Rewrite the transcript for one request without persisting it (pi: `context`). */
    fun onContext(handler: (List<ChatMessage>) -> List<ChatMessage>?) = onContext(HOST_OWNER, handler)

    internal fun onContext(owner: String, handler: (List<ChatMessage>) -> List<ChatMessage>?) {
        contextHooks += OwnedHook(owner, handler)
    }

    /**
     * Inspect/rewrite a tool call, or block it (pi: `tool_call`).
     *
     * 与其余钩子不同，`tool_call` 处理器是**挂起**的：审批闸门需要在此暂停等待
     * 用户的决定。链式语义不变 —— 每个处理器拿到前一个改写后的事件，返回 `null`
     * 表示保持当前值。
     */
    fun onToolCall(handler: suspend (ToolCallHook) -> ToolCallOutcome?) = onToolCall(HOST_OWNER, handler)

    internal fun onToolCall(owner: String, handler: suspend (ToolCallHook) -> ToolCallOutcome?) {
        toolCallHooks += OwnedHook(owner, handler)
    }

    /** Inspect/rewrite a tool result (pi: `tool_result`). */
    fun onToolResult(handler: (ToolResultHook) -> ToolResult?) = onToolResult(HOST_OWNER, handler)

    internal fun onToolResult(owner: String, handler: (ToolResultHook) -> ToolResult?) {
        toolResultHooks += OwnedHook(owner, handler)
    }

    /** Add or rewrite provider request headers (pi: `before_provider_headers`). */
    fun onBeforeProviderHeaders(handler: (Map<String, String>) -> Map<String, String>?) =
        onBeforeProviderHeaders(HOST_OWNER, handler)

    internal fun onBeforeProviderHeaders(owner: String, handler: (Map<String, String>) -> Map<String, String>?) {
        headerHooks += OwnedHook(owner, handler)
    }

    /** 回收某插件登记的全部钩子（卸载 / 重装前调用）。进行中的链不会被打断：读侧拿的是快照。 */
    internal fun removeOwner(owner: String) {
        inputHooks.removeAll { it.owner == owner }
        contextHooks.removeAll { it.owner == owner }
        toolCallHooks.removeAll { it.owner == owner }
        toolResultHooks.removeAll { it.owner == owner }
        headerHooks.removeAll { it.owner == owner }
    }

    fun runInput(text: String): String {
        var value = text
        for (h in inputHooks) h.handler(value)?.let { value = it }
        return value
    }

    fun runContext(messages: List<ChatMessage>): List<ChatMessage> {
        var value = messages
        for (h in contextHooks) h.handler(value)?.let { value = it }
        return value
    }

    suspend fun runToolCall(
        name: String,
        arguments: JsonObject,
        sessionId: String? = null,
        workspace: String? = null,
    ): ToolCallOutcome {
        var outcome = ToolCallOutcome(arguments)
        for (h in toolCallHooks) {
            h.handler(ToolCallHook(name, outcome.arguments, sessionId, workspace))?.let { outcome = it }
        }
        return outcome
    }

    fun runToolResult(name: String, result: ToolResult): ToolResult {
        var value = result
        for (h in toolResultHooks) h.handler(ToolResultHook(name, value))?.let { value = it }
        return value
    }

    fun runHeaders(headers: Map<String, String>): Map<String, String> {
        var value = headers
        for (h in headerHooks) h.handler(value)?.let { value = it }
        return value
    }

    private companion object {
        const val HOST_OWNER = "<host>"
    }
}
