package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.PendingUserGate
import dev.kair.app.core.model.newId
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolException
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.arrayProp
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一条待用户回答的提问（`ask_user` 的挂起载荷）。 */
data class AskUserRequest(
    val id: String,
    /** 问题正文；模型用用户的语言书写。 */
    val question: String,
    /** 可选项；为空表示只要求自由输入。 */
    val options: List<String>,
    /** 发起该提问的会话 id；UI 据此只展示当前会话的面板，避免跨会话串台。 */
    val sessionId: String?,
    /** 应展示该提问的会话 id（子智能体提问时是顶层父会话）；null 时按 [sessionId] 归属。 */
    val ownerSessionId: String? = null,
    /** 发起者标签（子智能体名）；主会话为 null。 */
    val originLabel: String? = null,
)

/**
 * 提问闸门：`ask_user` 工具挂起在此，UI 观察 [pending] 弹出选择 / 输入面板，
 * 用户作答后由 [resolve] 唤醒。与审批闸门同为「一次挂起换一个用户决定」的原语。
 *
 * 约定：
 * - **按会话并行**：每个展示会话（[AskUserRequest.ownerSessionId] 优先，否则
 *   [AskUserRequest.sessionId]）各自维护一条待答请求；不同会话可同时作答。
 *   同一会话内并发提问按到达顺序排队，面板一次只面对一个问题。
 * - 超时只覆盖「面板已展示后的等待」，排队等上一条的时间不计入。
 * - [resolve] 传 null 表示用户跳过 / 取消，工具据此回给模型一条错误结果。
 * - 会话被中止（用户点停止）会取消挂起的协程，待答状态随之清空，不会留下僵死面板。
 */
class AskUserGate(
    /**
     * 等待用户作答的超时。超时按「跳过」返回 null，让工具调用结束 ——
     * 否则一条没人看得到的提问（例如用户已切走会话）会让后续提问永久排队。
     */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /**
     * 「挂起等待用户作答」这套机制本身在 [gate] 里 ——
     * **与审批闸门共用同一份实现**（`AGENTS.md` §10.4：一个概念只允许一个实现点）。
     * 本类只留提问独有的部分：答案文本与插件侧展示由 [AskUserPlugin] 负责。
     */
    private val gate = PendingUserGate<AskUserRequest, String?>(
        timeoutMillis = timeoutMillis,
        displaySessionId = { it.ownerSessionId ?: it.sessionId ?: "*" },
        idOf = { it.id },
        // 超时按「跳过」返回 null，与改前 withTimeoutOrNull 的行为一致。
        timeoutAnswer = { null },
    )

    /** 当前待答的提问，按**展示会话 id** 索引；空表示无。 */
    val pending: StateFlow<Map<String, AskUserRequest>> = gate.pending

    /** 超时秒数（UI 提示用）；0 表示不限时。 */
    val timeoutSeconds: Long get() = gate.timeoutSeconds

    /**
     * 挂起提问，直到 [resolve] 给出答案、超时、或被 [endSession] 收尾；
     * 同一会话内按到达顺序逐条处理（排队等待**不计入**超时）。
     */
    suspend fun ask(req: AskUserRequest): String? = gate.await(req)

    /** 用户作答；id 不匹配或当前无待答请求时忽略。 */
    fun resolve(id: String, answer: String?) = gate.resolve(id, answer)

    /**
     * 会话结束：该会话未答的提问**立即按「跳过」收尾**。
     *
     * 改前本类**没有这个方法** —— 关闭会话后那条提问不会收尾，只能干等满 3 分钟超时。
     * 会话都关了不会再有人作答，立刻带着 null 返回更符合 `AGENTS.md` §7。
     */
    fun endSession(sessionId: String) = gate.endSession(sessionId)

    companion object {
        /** 默认 3 分钟，与审批闸门一致。 */
        const val DEFAULT_TIMEOUT_MILLIS = 180_000L
    }
}

/**
 * `ask_user`：向用户抛出一个问题并**挂起等待回答**，回答后原样回灌给模型。
 *
 * 这是「模型缺的不是知识而是你的决定」时的唯一出口：不给它就只能瞎猜或提前收工。
 * 面板由宿主在会话页按 [AskUserGate.pending] 渲染（见 ChatScreen），工具本身不碰 UI。
 */
class AskUserTool(
    private val gate: AskUserGate,
    /** 用于解析子智能体归属；为 null 时（测试）一律按当前会话展示。 */
    private val container: AppContainer? = null,
) : Tool {
    override val name = "ask_user"
    override val category = ToolCategory.READ
    override val promptSnippet = "Ask the user a question and wait for their answer"

    // 闸门内部按到达顺序串行展示，并发调用安全（排队的提问在上一条作答后弹出）。
    override val isParallelSafe = true
    override val promptGuidelines = listOf(
        "Use ask_user only when the answer is the user's call (choice, preference, missing context) — not for facts you can look up.",
        "One question per call; the call blocks until the user answers, so do not pair it with other work.",
    )
    override val description =
        "Ask the user a question and wait for their answer. The call blocks until they respond. " +
            "Provide 2-6 short options to offer a choice, or omit options to request free-form input. " +
            "Returns the user's answer as plain text. If the user skips, returns an error result — " +
            "continue with your best judgment instead of asking again."
    override val parameters = schemaOf(
        props(
            "question" to strProp("The question to ask, written in the user's language"),
            "options" to arrayProp(strProp("One answer option"), "Answer options to offer; omit for free-form input."),
        ),
        "question",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val question = args.reqStr("question").trim()
        if (question.isEmpty()) throw ToolException("Missing required parameter: question")
        val options = (args["options"] as? JsonArray)
            ?.mapNotNull { el ->
                (el as? JsonPrimitive)
                    ?.takeIf { it !is JsonNull }
                    ?.content?.trim()?.takeIf { it.isNotEmpty() }
            }
            // 输入来自模型：封顶防止异常参数把面板撑爆。
            .orEmpty()
            .take(MAX_OPTIONS)

        // 子智能体提问：挂到顶层父会话，否则父会话按自己的 id 过滤时看不到面板，
        // 子智能体只能等超时。
        val subagent = ctx.sessionId?.let { container?.subagents?.info(it) }
        val owner = subagent?.let { container?.subagents?.rootSessionOf(it.id) ?: container?.activeSessionId }
        val answer = gate.ask(
            AskUserRequest(
                id = newId(),
                question = question,
                options = options,
                sessionId = ctx.sessionId,
                ownerSessionId = owner,
                originLabel = subagent?.name,
            ),
        )
        return if (answer.isNullOrBlank()) {
            ToolResult(
                "The user did not answer (skipped or timed out). Do not ask again; " +
                    "proceed with your best judgment.",
                isError = true,
            )
        } else {
            ToolResult(answer)
        }
    }

    companion object {
        /** 一次提问最多给出的选项数；模型输入是系统边界，这里封顶。 */
        const val MAX_OPTIONS = 10
    }
}

/**
 * 提问插件：贡献 `ask_user` 工具。闸门实例挂在宿主上（[AppContainer.askUserGate]），
 * 因此会话页能渲染面板 —— 工具只管挂起，UI 只管观察，两者经闸门解耦。
 */
class AskUserPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.askuser"
    override val name = "向我提问"
    override val description = "ask_user 工具：向用户提问并挂起等待回答。"

    override fun activate(ctx: PluginContext) {
        ctx.tool(AskUserTool(container.askUserGate, container))
    }
}
