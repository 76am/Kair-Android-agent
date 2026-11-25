package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.serialization.json.JsonObject

/**
 * U58 ①：让模型在循环里**主动**请求压缩上下文（`compact_context`）。
 *
 * 只置位「下一次请求之前压缩」，**不在工具执行中同步改写历史** —— 此刻本轮
 * `assistant(tool_call)` 已在历史里、`tool_result` 还没写，同步压缩会切掉调用却留下结果
 * （孤儿 `tool_result`，端点直接 400）。真正的压缩发生在回合循环的下一步开头。
 *
 * 归属：随 `ContextPlugin` 一起装载 / 卸载 —— 插件被禁用时工具一并消失，内核不知道
 * 「主动压缩」这个概念。`enabled` 由插件传入（读用户设置里的「自动压缩」开关）。
 */
internal class CompactContextTool(private val enabled: () -> Boolean) : Tool {
    override val name = "compact_context"

    // 无副作用（只置位内存标记），不弹审批，可与同回合其他工具并行。
    override val category = ToolCategory.READ
    override val isParallelSafe = true

    override val promptSnippet =
        "Compact the conversation into a summary checkpoint and continue " +
            "(call proactively when the context grows long)"

    override val description =
        "Summarize earlier turns into a checkpoint while keeping the most recent turns verbatim, " +
            "then continue with a smaller context on the next request. Call this proactively when " +
            "tool outputs are piling up and the conversation is getting long. Does nothing when " +
            "there is not enough history to compact."

    override val parameters: JsonObject = schemaOf(props())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!enabled()) {
            // 明确告诉模型「没压」：静默返回成功会让它以为省下了上下文，继续堆输出。
            return ToolResult(
                "Context compaction is disabled in settings; continuing without compaction.",
                isError = true,
            )
        }
        ctx.requestCompact()
        return ToolResult(
            "Compaction requested. The conversation will be compacted before the next request; " +
                "earlier turns may be replaced by a summary checkpoint.",
        )
    }
}
