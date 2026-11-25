package dev.kair.app.core.agent

import dev.kair.app.core.ai.OverflowDetector
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDate

/**
 * 一个用户回合的执行器（A5 第二步拆出）：`runUser` 的外壳 + `runLoop` 的步进循环 +
 * 工具两阶段执行 + 队列排空 + 日期 / 工具集提示。
 *
 * 与 [TurnExecutor] / [CompactionRunner] 同一个套路：**不持有 [AgentSession]**，只吃显式
 * 依赖面 [Host]。这一步是纯搬家，**不改任何行为** —— 搬家前后 `ChatRequest` 序列化逐字节相同。
 *
 * 为什么不把 `runLoop` 留在会话里：那 198 行是中止 / 截断 / 溢出三条语义的汇合点，
 * 每改一处都得在 1200 行里找落点，且只能靠整会话 + 假传输层断言。搬出来之后
 * `TurnRunnerTest` 可以用假 host 直接钉住这三条。
 */
internal class TurnRunner(private val host: Host) {

    /**
     * 依赖面：全部是会话已有的能力，**没有新增写入点**（历史写入仍经由 [Host.append] /
     * [Host.removeMessage] / [Host.restoreMessage]）。状态机不在这里 —— 会话的
     * `transition()` 仍是唯一写入点。
     */
    internal data class Host(
        val sessionId: String,
        val workspace: File,
        /** 当前输入快照（按需现取）：工具面 / maxSteps 与请求侧同一解析口径（U42）。 */
        val inputs: () -> ResolvedRequestInputs,
        /** 当前历史（只读）：执行器不回写，写入全部经下面的写入面。 */
        val messages: () -> List<ChatMessage>,
        val tools: ToolRegistry,
        val hooks: HookBus,
        val turnQueue: TurnQueue,
        /** 一个 assistant 回合。**用函数类型**，不要持有 [TurnExecutor] 实例 —— 否则它没法被 fake。 */
        val streamAssistant: suspend () -> RequestOutcome,
        /** 每回合是否清系统提示段落缓存（见 `SettingsStore.refreshPromptEveryTurn`）。 */
        val refreshPrompt: () -> Boolean,
        val contextStrategy: () -> ContextStrategy?,
        val commandRunner: () -> CommandRunner,
        val files: () -> FileBackend,
        // ---- 写入面（全部是会话已有的能力，不新增写入点）----
        val append: suspend (ChatMessage) -> Unit,
        val toolResultMessage: (toolCallId: String, toolName: String, content: String, isError: Boolean) -> ChatMessage,
        val parseArgs: (String) -> JsonObject?,
        val removeMessage: (String) -> ChatMessage?,
        /** 把压缩前摘下的那条助手消息放回原位（`_messages + removed`，不落盘）。 */
        val restoreMessage: (ChatMessage) -> Unit,
        val clearQueue: () -> Unit,
        val onEvent: (AgentEvent) -> Unit,
        val turnCount: () -> Int,
        val isActive: suspend () -> Boolean,
        /** 会话是否已关闭（U48 ③）：区分「用户中止」与「关闭 / 淘汰」的终止原因。 */
        val isSessionClosed: () -> Boolean,
        val sanitizeInterrupted: () -> Unit,
        val today: () -> LocalDate,
        /**
         * Root 授权最近一次探测结论：null = 未探测；true = 已授权；false = 拒绝 / 无 su / 超时。
         * 只由 [notifyRootNoteIfNeeded] 读；宿主接 `RootAccess`，测试注入假值。
         */
        val rootGranted: () -> Boolean? = { null },
        /** 工具集指纹基线读写；两端都非 null 时才启用「工具集变化提示」。 */
        val toolsetBaselineGet: (() -> String?)? = null,
        val toolsetBaselineSet: ((String) -> Unit)? = null,
        /** 压缩控制面（[CompactionControl]）：阈值 / 溢出 / 模型主动请求共用一条路径。 */
        val compaction: CompactionControl,
    )

    suspend fun runUser(userMessage: ChatMessage?) = withTurnShell {
        if (userMessage != null) host.append(userMessage)
        runTurnBody()
    }

    /**
     * 队首作本回合的**首条**用户消息（U21）：「取出 → 提交」走队列唯一出口
     * [TurnQueue.submitNext]（提交被取消即归还），壳与 [runUser] 同构 ——
     * 提交期的非取消异常不许逃逸 `launchRun`（逃逸 = 未捕获崩溃，TurnEnd / 中断收尾全丢）。
     */
    suspend fun runUserFromQueue(kind: QueueKind) = withTurnShell {
        // 队列竞态出口（abort 清空窗口 / 消息被别的提交者取走）：没跑成回合就返回 null ——
        // 一次模型调用都没发生，任何 stopReason 都是编的；报「stop」是个幻影回合，
        // 插件「每 N 回合」计数会多算（U48 B 审）。null = 没有回合，回合壳不广播 TurnEnd。
        if (host.turnQueue.submitNext(kind) { host.append(it) }) runTurnBody() else null
    }

    /**
     * 回合主体：跑 [runLoop]，队列里还有存货就继续扫。返回**本回合的终止原因**（U48）——
     * 显式携带，不许反查历史：空回合被丢弃后历史里根本没有它的痕迹，反查会把
     * 「emptyResponse」读成上一回合的原因（或读成含义不明的 STOP）。
     */
    private suspend fun runTurnBody(): String {
        notifyDateIfNeeded()
        notifyToolsetChangeIfNeeded()
        notifyRootNoteIfNeeded()
        // runLoop drains queued follow-ups itself; these extra sweeps close the window
        // between the last drain and going idle, and cover the step-limit exit.
        var reason = runLoop()
        while (!host.turnQueue.isEmpty && host.isActive()) {
            reason = runLoop()
        }
        // 步数用尽的可见提示（U48 ①）：只在回合**真正终止于**步数上限时追加 —— 队列里
        // 还有存货把回合接了下去（sweep 又跑一轮并正常收尾）时，回合并没有停在上限，
        // 「可发送『继续』」的建议也就不成立。isSystemNote 用户回合，只追加到历史尾部，
        // 不进缓存前缀（追加点在尾部，相对上一请求前缀逐字节不变）。
        if (reason == StopReason.STEP_LIMIT) {
            appendSystemNote(stepLimitNoteText(host.inputs().assembly.settings.maxSteps))
        }
        // 空回复重试后仍为空：给用户一条可见说明（中止 / 失败有自己的错误气泡，不加这条）。
        if (reason == StopReason.EMPTY_RESPONSE) appendSystemNote(emptyResponseNoteText())
        return reason
    }

    /** 追加一条用户可见、模型也看得到的系统提示（只落在历史尾部，不改缓存前缀）。 */
    private suspend fun appendSystemNote(text: String) =
        host.append(ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)), isSystemNote = true))

    /**
     * 回合壳（[runUser] 与 [runUserFromQueue] 共用）：异常 → 错误气泡；finally → 收尾广播 + 中断收尾。
     * body 返回 `null` 表示**没跑成回合**（队列竞态出口）：不广播 TurnEnd —— 幻影回合事件
     * 比漏掉更糟，计数偏了还找不到是哪一回合（U48 B 审）。
     */
    private suspend fun withTurnShell(body: suspend () -> String?) {
        var abortReason: String? = null
        var reason: String? = null
        try {
            reason = body()
        } catch (e: CancellationException) {
            // U48 ③：中止也要分清「用户停止」与「会话已关闭」—— 关闭先置 closed 再
            // cancel，此刻判 closed 就能区分；两种中止的收尾行为完全一致（V4 不变）。
            abortReason = if (host.isSessionClosed()) StopReason.SESSION_CLOSED else StopReason.ABORTED
            throw e
        } catch (e: Exception) {
            reason = StopReason.ERROR
            host.append(
                ChatMessage(
                    role = Role.ASSISTANT,
                    parts = listOf(Part.Text("Error: ${e.message}")),
                    stopReason = StopReason.ERROR,
                    errorMessage = e.message,
                    isError = true,
                ),
            )
        } finally {
            // 回合收尾广播：正常收尾、出错、被用户中止都算一回合结束。放在 finally 里是
            // 因为「每 N 回合做一次」这类插件逻辑最怕漏掉中止的回合 —— 漏多了计数就偏。
            // 终止原因显式携带（U48）：不许反查历史 —— 空回合在历史里没有痕迹。
            val end = abortReason ?: reason
            if (end != null) emitTurnEnd(end)
            // 取消（用户点停止）时收尾：当前回合可能留下残缺 / 无结果的 tool_call，
            // 下一回合上传会让 provider 报错。见会话侧的 sanitizeInterruptedTurn。
            // 状态由会话的 launchRun / prompt 统一收口，这里不再写 —— 否则收尾会把下一轮
            // 刚置上的 RUNNING 覆盖回 IDLE，界面于是误以为空闲并允许再开一轮。
            if (abortReason != null) host.sanitizeInterrupted()
        }
    }

    /**
     * 广播「回合结束」。
     *
     * 放在历史落盘之后（`runUser` 的 finally）：插件此时读会话存档能看到本回合完整内容。
     * 只读通知，不参与装配，因此不影响缓存前缀。
     */
    private fun emitTurnEnd(stopReason: String) {
        val msgs = host.messages()
        // 只统计本回合（最后一条用户回合之后）的工具名，去重排序以保证插件侧可确定性处理。
        val from = msgs.indexOfLast { it.role == Role.USER && !it.isSystemNote }.coerceAtLeast(0)
        val tools = msgs.drop(from)
            .flatMap { m -> m.toolCalls.map { it.name } }
            .distinct()
            .sorted()
        host.onEvent(
            AgentEvent.TurnEnd(
                sessionId = host.sessionId,
                workspace = host.workspace.path,
                turnCount = host.turnCount(),
                stopReason = stopReason,
                tools = tools,
            ),
        )
    }

    /** 本会话对模型可见的工具集（注册顺序）：指纹与 root note 的适用性都从这里派生。 */
    private fun visibleTools(): List<Tool> = host.inputs().tools

    /** 当前会话对模型可见的工具集指纹：按注册顺序取工具名（与请求 schema 同源同序）。 */
    private fun currentToolsetFingerprint(): String =
        visibleTools().joinToString(",") { it.name }

    /** 模型手上有没有 `android_shell`：root 事实只对着能用它提权的人说。 */
    private fun androidShellIsVisible(): Boolean =
        visibleTools().any { it.name == ANDROID_SHELL_TOOL_NAME }

    /**
     * 「今天几号」提示：模型没有日期来源，会以为现在还是训练数据的时间。
     *
     * 每回合开始前判一次：历史里有没有**今天**的提示。没有就在尾部**追加**一条
     * `isSystemNote` 的 user 回合；有就不加。判据是「历史里有没有」，不是记基线 ——
     * 提示被压缩吞掉后当天还会自动补回来（一天最多补几次，可接受）。
     *
     * 只追加、不改写，符合缓存「稳定前缀」契约：追加点在历史尾部，相对上一回合仍是
     * 前缀包含。日期取注入时钟的本地时区，不用 UTC。
     */
    private suspend fun notifyDateIfNeeded() {
        val today = host.today()
        val note = dateNoteText(today)
        // 只认完整正文：前缀匹配会把「以今天日期开头」的插件 note 误当成日期提示。
        // 旧版本存的是中文格式，升级当天也不能重复插一条。
        val legacy = legacyDateNoteText(today)
        val exists = host.messages().any {
            it.role == Role.USER && it.isSystemNote && (it.text == note || it.text == legacy)
        }
        if (exists) return
        host.append(
            ChatMessage(
                role = Role.USER,
                parts = listOf(Part.Text(note)),
                isSystemNote = true,
            ),
        )
    }

    /**
     * 工具集变化提示：旧会话的历史可能是在**上一版本的工具集**下产生的，模型会锚定自身
     * 那些「当时还没有某工具」的旧回合，从而忽略当前已注入的工具清单（表现为坚称某工具不
     * 存在）。检测到本会话的工具集指纹与上次记录不一致 —— 或从未记录（早于本特性创建的
     * 旧会话）—— 时，在历史尾部**追加**一条英文提示，并更新基线，保证每次变化只提示一次。
     *
     * 只追加、不改写，符合缓存「稳定前缀」契约：追加点之后的回合都在它之后，相对上一回合
     * 仍是前缀包含。宿主未注入基线读写时不启用。
     */
    private suspend fun notifyToolsetChangeIfNeeded() {
        val get = host.toolsetBaselineGet ?: return
        val set = host.toolsetBaselineSet ?: return
        val current = currentToolsetFingerprint()
        if (get() == current) return
        set(current)
        host.append(
            ChatMessage(
                role = Role.USER,
                parts = listOf(Part.Text(AgentSession.TOOLSET_CHANGED_NOTICE)),
                isSystemNote = true,
            ),
        )
    }

    /**
     * Root 授权事实（U116）：用户在设置页手动探测 su 后，把**最近一次探测的结论**告诉模型。
     *
     * 为什么不进系统提示前缀：授权状态会变（用户在 Root 管理器里允许 / 收回），
     * 进前缀等于每次变化都打飞会话缓存。改为在历史尾部**追加**一条 `isSystemNote` 的 user 回合。
     *
     * 判据是「历史里**最后一条** root note 是否逐字等于当前结论」：不等才补。所以：
     * - 同一结论重复探测不会重复写；
     * - 授权被收回（granted → denied）会补一条否定 note，纠正历史里的旧事实；
     * - 被压缩吞掉后下一回合补回来（同日期 note 的行为）。
     *
     * 两个适用性前置：
     * - **模型手上得有 `android_shell`**：root 提权只能经它；工具被禁用时写这条 note 只会误导。
     * - **必须探测过**（[TurnRunner.Host.rootGranted] 非 null）：没探测就不假设，也不自动弹授权。
     */
    private suspend fun notifyRootNoteIfNeeded() {
        val granted = host.rootGranted() ?: return
        if (!androidShellIsVisible()) return
        val note = rootNoteText(granted)
        if (lastRootNote(host.messages()) == note) return
        host.append(
            ChatMessage(
                role = Role.USER,
                parts = listOf(Part.Text(note)),
                isSystemNote = true,
            ),
        )
    }

    /**
     * 步进循环，返回**本轮循环的终止原因**（U48，[StopReason] 常量）：正常收尾取回合自带的
     * stopReason，四个静默出口各自携带可枚举原因 —— 不许 null、不许含义不明的 STOP。
     */
    private suspend fun runLoop(): String {
        var step = 0
        var compactions = 0
        val maxSteps = host.inputs().assembly.settings.maxSteps
        val maxCompactions = 3
        var termination = StopReason.STOP

        outer@ while (true) {
            inner@ while (true) {
                if (!host.isActive()) {
                    // U48 ③：会话被取消也不许静默 return —— 关闭 / 淘汰报 sessionClosed，
                    // 用户中止报 aborted（关闭先置 closed 再 cancel，判 closed 即可区分）。
                    return if (host.isSessionClosed()) StopReason.SESSION_CLOSED else StopReason.ABORTED
                }
                if (step >= maxSteps) {
                    termination = StopReason.STEP_LIMIT
                    break@inner
                }

                // Steering messages typed while the previous turn ran join the next request.
                appendQueued(QueueKind.STEER)

                if (compactions < maxCompactions && host.compaction.compactIfNeeded()) compactions++

                // 每回合重算系统提示段：段落缓存的命中条件只含「贡献点实例 / cwd /
                // workspace / addendum / 工具列表」，看不到贡献点依赖的外部状态（典型：
                // 记忆插件读 `.kair/memory.md`）。早期只在工具执行后清，纯聊天时后台写作
                // 的记忆永远读不到；现在连同纯聊天回合一起清 —— 只清本会话工作区那个作用域（U19②）。
                // 不破坏前缀契约：贡献点契约要求确定性，外部状态未变时重算出的字节与上次
                // 逐字相同，缓存前缀不受影响。
                if (host.refreshPrompt()) SystemPromptBuilder.invalidate(host.workspace.path)

                val outcome = streamWithEmptyRetry(host.streamAssistant)
                val assistant = outcome.assistant

                if (assistant.stopReason == StopReason.ABORTED) {
                    host.clearQueue()
                    return StopReason.ABORTED
                }

                // 溢出判定必须在「按 stopReason 提前退出」之前：三种信号共用同一入口 ——
                // ERROR 的错误关键字、STOP 但用量越窗（静默溢出）、LENGTH 且零输出。
                // 此前判定嵌在 ERROR 分支里，后两种永远不可达，`forcedPlan` 逃生舱随之失效。
                // 窗口与模型取本发请求的快照（U42）：响应后回读设置拿到的可能是下一发的值。
                val strategy = host.contextStrategy()
                if (strategy != null &&
                    compactions < maxCompactions &&
                    OverflowDetector.isContextOverflow(assistant, outcome.inputs.contextWindow)
                ) {
                    val removed = host.removeMessage(assistant.id)
                    if (host.compaction.compactOnOverflow(strategy)) {
                        compactions++
                        continue@inner
                    }
                    if (removed != null) host.restoreMessage(removed)
                }

                if (assistant.stopReason == StopReason.ERROR) {
                    // 失败回合可能留下参数不完整 / 没有 tool_result 的悬空 tool_call。
                    // 不修复的话下一回合请求会被 provider 直接拒绝，只能重启或重开会话。
                    host.sanitizeInterrupted()
                    termination = StopReason.ERROR
                    break@inner
                }

                val toolCalls = assistant.toolCalls
                if (toolCalls.isEmpty()) {
                    // 终止原因取本轮 assistant **自带**的 stopReason（U48）：空回合已被
                    // TurnExecutor 丢弃，历史里查不到它，反查历史是错的。
                    termination = assistant.stopReason ?: StopReason.STOP
                    break@inner
                }

                // A "length" stop means the message was cut off; tool arguments may be truncated.
                if (assistant.stopReason == StopReason.LENGTH) {
                    for (tc in toolCalls) {
                        host.append(host.toolResultMessage(tc.id, tc.name, TRUNCATED_TOOL_CALL, true))
                    }
                    termination = StopReason.LENGTH
                    break@inner
                }

                // 两阶段执行本回合的多个工具调用：
                // 1) 串行跑 `tool_call` 钩子 —— 审批闸门假定同一时刻只有一个待批请求，
                //    钩子必须串行，否则并发审批会互相顶掉；
                // 2) 并发跑工具本体 —— 但只对声明了 [Tool.isParallelSafe] 的工具放开：
                //    只读类与 `subagent` 各自独立等待，多个委派真正并行；
                //    未声明的（默认，含 write / edit / container_shell 等有副作用的）共用一把互斥锁
                //    按提交顺序串行 —— 否则同一回合对同一文件的「读-改-写」会互相覆盖
                //    丢失更新。结果仍按原调用顺序追加，序列确定、缓存安全。
                val prepared = ArrayList<Pair<Part.ToolCall, PreparedCall>>(toolCalls.size)
                try {
                    for (tc in toolCalls) {
                        // 用抛取消而不是 return：return 是正常结束，runUser 里 aborted 仍为 false，
                        // 中断收尾（补齐 tool_result / 配对修复）就不会执行，历史里会留下悬空 tool_call。
                        if (!host.isActive()) throw CancellationException("aborted before tool execution")
                        val parsed = host.parseArgs(tc.arguments)
                        val outcome = if (parsed == null) {
                            null
                        } else {
                            host.hooks.runToolCall(tc.name, parsed, host.sessionId, host.workspace.path)
                        }
                        val blockReason = outcome?.blockReason
                        prepared += if (blockReason != null) {
                            tc to PreparedCall.Blocked(blockReason)
                        } else {
                            val effective = if (outcome != null) tc.copy(arguments = outcome.arguments.toString()) else tc
                            tc to PreparedCall.Run(effective)
                        }
                    }
                } catch (e: CancellationException) {
                    // 中止发生在工具执行前（典型：审批弹窗还挂着）：同样给每个调用补一条
                    // 中止结果再收尾。否则配对修复会把它们当悬空调用删掉 —— 界面上的
                    // 工具卡凭空消失，模型下一轮也不知道这些调用是被用户中止的。
                    withContext(NonCancellable) {
                        toolCalls.forEach {
                            host.append(host.toolResultMessage(it.id, it.name, AgentSession.TOOL_ABORTED, true))
                        }
                    }
                    throw e
                }

                // 已完成的结果按调用下标暂存：取消时要能区分「已回结果」与「尚未回结果」，
                // 前者保留真实输出，后者补中止提示。
                val results = arrayOfNulls<ToolResult>(prepared.size)

                /** 把一个调用的结局写进转录（正常路径与中止收尾共用，保证两条路径一致）。 */
                suspend fun persist(tc: Part.ToolCall, prep: PreparedCall, outcome: ToolResult?) {
                    when (prep) {
                        is PreparedCall.Blocked ->
                            host.append(host.toolResultMessage(tc.id, tc.name, prep.reason, true))

                        is PreparedCall.Run -> {
                            val finished = outcome ?: ToolResult(AgentSession.TOOL_ABORTED, true)
                            // tool_result hook: may rewrite what the model sees.
                            val hooked = host.hooks.runToolResult(tc.name, finished)
                            host.append(host.toolResultMessage(tc.id, tc.name, hooked.content, hooked.isError))
                            // 工具回带的图片（如 read_image）另作为一条 user 消息追加：vision 模型据此
                            // 真正「看见」图片。仅追加不改写历史，符合缓存稳定前缀契约。
                            if (hooked.images.isNotEmpty()) {
                                host.append(ChatMessage(role = Role.USER, parts = hooked.images))
                            }
                        }
                    }
                }

                try {
                    coroutineScope {
                        // 非并行安全工具的执行锁：async 仍按原顺序创建，锁保证它们不交错。
                        val serialLock = Mutex()
                        prepared.mapIndexed { i, (_, prep) ->
                            async {
                                results[i] = when (prep) {
                                    is PreparedCall.Blocked -> null
                                    is PreparedCall.Run -> {
                                        // 协作式取消：第三方工具在长任务里轮询 isAborted()。abort()
                                        // 取消的是本回合的 Job，这个 async 是其子 Job —— 取消后
                                        // isActive 即为 false，不能像之前那样传常量 false。
                                        val runJob = coroutineContext[Job]
                                        val ctx = ToolContext(
                                            host.workspace, host.sessionId, host.commandRunner(), host.files(),
                                        ) { runJob?.isActive == false }
                                        // 只置位、下一步才压：工具执行中同步改写历史会拆散本轮调用/结果配对。
                                        ctx.requestCompact = host.compaction::requestCompaction
                                        val parallelSafe = host.tools.get(prep.call.name)?.isParallelSafe == true
                                        if (parallelSafe) {
                                            host.tools.execute(prep.call, ctx)
                                        } else {
                                            serialLock.withLock { host.tools.execute(prep.call, ctx) }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                } catch (e: CancellationException) {
                    // 用户中止正在执行的工具：进程由 CommandRunner 的 killTree 终止；
                    // 这里负责**历史收尾** —— 每个调用都要有配对结果，已完成的保留真实输出，
                    // 没来得及回结果的写明「被用户中止」。不能用 suspend 之外的清理：协程已
                    // 取消，append 必须在 NonCancellable 里跑完，否则收尾自身会被再次取消。
                    withContext(NonCancellable) {
                        prepared.forEachIndexed { i, (tc, prep) ->
                            persist(tc, prep, results[i])
                            if (prep is PreparedCall.Run) {
                                host.onEvent(
                                    AgentEvent.ToolExecuted(
                                        host.sessionId, host.workspace.path, host.turnCount(), prep.call.name,
                                        results[i]?.isError == true,
                                    ),
                                )
                            }
                        }
                    }
                    throw e
                }

                prepared.forEachIndexed { i, (tc, prep) ->
                    persist(tc, prep, results[i])
                    if (prep is PreparedCall.Run) {
                        host.onEvent(
                            AgentEvent.ToolExecuted(
                                host.sessionId, host.workspace.path, host.turnCount(), prep.call.name,
                                results[i]?.isError == true,
                            ),
                        )
                    }
                }
                step++
            }

            // The agent would stop here: run any queued follow-up messages as new turns.
            if (!host.turnQueue.submitPending(QueueKind.FOLLOW_UP) { host.append(it) }) return termination
            continue@outer
        }
    }

    private suspend fun appendQueued(kind: QueueKind) {
        // 「取出 → 提交」是队列侧的唯一出口（提交被取消即归还，U21）；写进历史仍走会话侧的
        // append（落盘 / 图片落地）—— host 对队列零义务，不需要记得撤出。
        host.turnQueue.submitPending(kind) { host.append(it) }
    }

    private companion object {
        private const val TRUNCATED_TOOL_CALL =
            "Tool call arguments were truncated by the output token limit. Re-issue the call."
    }
}

/**
 * 步数用尽提示（U48 ①）正文。中文（`AGENTS.md` §4：随会话持久化的状态文本由内核直出中文，
 * UI 不得二次翻译，同 `stalledUpstreamMessage` 先例）；作为 `isSystemNote` 用户回合追加到
 * 历史尾部 —— 用户在转录里看到建议，模型在下一次请求尾部看到「上一回合为什么停」。
 */
private fun stepLimitNoteText(maxSteps: Int): String =
    "已达本回合步数上限（$maxSteps 步），本回合已停止。可发送「继续」让 agent 接着完成剩余工作。"

/** 空回复提示：说明不是中止/网络错误，并给排查方向（内核直出中文，同步数提示先例）。 */
private fun emptyResponseNoteText(): String =
    "模型没有返回正文（空回复）。可以再发一次或换模型；若反复出现，检查端点是否丢弃了 content。"

/** 本回合某个工具调用的执行前状态：被钩子阻断，还是可执行（带可能被改写的参数）。 */
internal sealed interface PreparedCall {
    data class Blocked(val reason: String) : PreparedCall
    data class Run(val call: Part.ToolCall) : PreparedCall
}
