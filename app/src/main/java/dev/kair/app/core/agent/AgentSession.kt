package dev.kair.app.core.agent

import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.LocalFileBackend
import dev.kair.app.core.tools.ReadImageToolName
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.parseToolArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TurnState] 的**只读投影**：只表达「回合在跑 / 不在跑」这一个布尔事实。
 *
 * 不再是状态量 —— 值由 [AgentSession.status] 从 `TurnState` 现算，没有第二个可写的副本。
 * 保留这个类型是因为 `AgentSessionInterruptTest` 的既有断言直接读它（ADR 0003 的结论不可改）。
 */
enum class SessionStatus { IDLE, RUNNING }

/**
 * [AgentSession.prompt] 被 [AgentSession.abort] 主动中止时抛出。
 *
 * 刻意**不是** [CancellationException]：子智能体被面板「终止」只应让这次委派失败，
 * 不能把等待它的父回合一起取消。
 */
class SessionAbortedException : Exception("session aborted")

/**
 * 一次上下文压缩的结果快照，供 UI 提示用户压缩已发生及其效果（token 变化）。
 * [seq] 单调递增：状态流按值去重，seq 保证每次压缩都能触发一次独立提示。
 */
data class CompactionResult(
    val seq: Long,
    val tokensBefore: Int,
    val tokensAfter: Int,
)

/** Immutable agent configuration resolved at request time. */
data class AgentSettings(
    val model: String,
    val systemPrompt: String,
    val maxSteps: Int = 200,
    /** 请求尾部参数（温度 / 输出上限 / 思考强度 / 采样参数），见 [SamplingOptions]。 */
    val sampling: SamplingOptions = SamplingOptions(),
    /** 是否把历史回合的思考内容回传给模型（见 `SavedModel.replayThinking`）。 */
    val replayThinking: Boolean = true,
)

/**
 * A single agent conversation. Ports pi's agent loop (streaming assistant turn →
 * execute tool calls → feed results back → repeat) with the compaction, prompt-queue,
 * and history-rewrite machinery.
 */
class AgentSession(
    val id: String,
    val workspace: File,
    private val tools: ToolRegistry,
    /**
     * 本会话输入的唯一解析入口（U42）：模型 / 鉴权 / 工具面 / 采样 / 窗口与装配产物
     * 出自同一次调用。请求路径（[TurnExecutor]）每发只调一次；回合循环 / 压缩 / 界面
     * 按需现取（设置热更新对下一发生效）。子智能体由宿主注入创建时固定的实现。
     */
    private val inputsProvider: () -> ResolvedRequestInputs,
    private val compactionProvider: () -> CompactionSettings = { CompactionSettings() },
    /**
     * 当前生效的上下文/压缩策略。由插件贡献，**每次请求实时解析**——插件装的
     * 晚、或运行期被换掉，已存在的会话也能立即生效（热变更）；返回 null 时内核
     * 不压缩（降级）。
     */
    private val contextStrategyProvider: () -> ContextStrategy? = { null },
    private val cacheEnabled: () -> Boolean = { true },
    /**
     * 每回合是否清系统提示段落缓存（见 `SettingsStore.refreshPromptEveryTurn`）。
     * 关闭后只在压缩历史时刷新 —— 依赖外部文件的插件贡献点会长期读到旧值。
     */
    private val refreshPromptEveryTurn: () -> Boolean = { true },
    /** Middleware hooks contributed by plugins; empty by default. */
    private val hooks: HookBus = HookBus(),
    /**
     * 智能体事件广播（只读通知，插件用来做「每 N 回合整理一次」这类副作用）。
     *
     * 与 [hooks] 的分工：钩子参与装配、契约要求纯函数；事件只告知发生了什么，允许副作用。
     * 宿主的实现会包住每个处理器各自的异常，这里不做额外的 try/catch。
     * 默认空实现 —— 单测与子智能体以外的调用方不需要关心。
     */
    private val onEvent: (AgentEvent) -> Unit = {},
    /** 缓存观测器，记录每次请求装配的分段快照；面板只读它。 */
    private val cacheInspector: CacheInspector = CacheInspector(),
    /**
     * 诊断面（压缩失败等），由**宿主按会话**注入同一个实例，随 [id] 一起被清理。
     * **无默认值**（V3 的 P2-a）：漏传就是编译错误 —— 旧的 `= Diagnostics()` 让「宿主
     * 忘了注入共享实例」编译过、面板永远空且无测试红。临时会话显式传自己的实例。
     */
    private val diagnostics: Diagnostics,
    /**
     * 图片入库前的缩放能力（U23）：内核不引 Android 图形类型，位图实现由宿主注入
     * （`app/app/BitmapImageScaler.kt`）。默认值是「不缩放」的兜底，漏接会让超限大图退回 400 ——
     * 生产接线由 `ImageScalingGuardTest` 钉住，不靠这里保证。
     */
    private val imageScaler: ImageDownscaler.Scaler = ImageDownscaler.None,
    /**
     * 缩放决策日志的落点（内核不引 Android，宿主接 logcat）；漏接 = 真机上缩放降级重新变不可观测，
     * 接线由 `ImageScalingGuardTest` 钉住。为什么每条决策都必须留痕，见 `ImageDownscaler.decisionLine`。
     */
    private val imageLog: (String) -> Unit = ImageDownscaler.NoLog,
    /** 命令执行后端；由内核按插件解析后注入，`container_shell` 工具通过它跑命令。 */
    private val commandRunner: () -> CommandRunner = { LocalCommandRunner() },
    /** 文件访问后端；容器在场时注入容器后端，文件工具因此能改容器（root）建的文件。 */
    private val filesProvider: () -> FileBackend = { LocalFileBackend(workspace.path) },
    /**
     * 工具集指纹基线读写（宿主按会话注入）：读上次记录、写新记录。两端都非 null 时才
     * 启用「工具集变化提示」（见 `TurnRunner.notifyToolsetChangeIfNeeded`）；子智能体等临时会话不
     * 注入，避免在这类短命会话里反复提示。
     */
    private val toolsetBaselineGet: (() -> String?)? = null,
    private val toolsetBaselineSet: ((String) -> Unit)? = null,
    private val onPersist: (ChatMessage) -> Unit = {},
    private val onReplaceHistory: (List<ChatMessage>) -> Unit = {},
    /**
     * 附件存储：图片本体落盘处（会话日志只存 [AttachmentRef] 引用，本体不进日志）。
     * 默认落在**项目工作区** `.kair/attachments/v1`，由该项目所有会话共享（跨会话去重）；
     * 随项目删除而清理，无人引用时由宿主回收（`AppContainer.gcAttachments`）。
     */
    private val attachments: AttachmentStore = AttachmentStore.forWorkspace(workspace),
    /** 日期注入：测试传固定日期，避免跨零点 flaky（U14）；默认设备本地时区。 */
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    /** Root 授权最近一次探测结论（null = 未探测）；宿主接 `RootAccess`，测试注入假值。 */
    private val rootGranted: () -> Boolean? = { null },
    /**
     * 停止收尾的硬上界（U57，语义见 [StopHardLimitMs]）。只有测试会传更小的值 ——
     * 生产口径的唯一来源是那个常量，调用点不许另拍数字。
     */
    private val stopHardLimitMs: Long = StopHardLimitMs,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** 附件存储；UI 渲染「引用式」图片 / 排查时读回本体。 */
    val attachmentStore: AttachmentStore get() = attachments

    /** 历史写入的唯一受控入口（U43）：[discard] 与提交共用同一把锁，不再散落 `if (!closed)`。 */
    private val historyWriter = HistoryWriter(onPersist, onReplaceHistory)

    /** 历史写入面（U77 从本类搬出，见 [HistoryWrites]）：内存与落盘成对，归属判据只有一处。 */
    private val writes = HistoryWrites(
        messages = _messages,
        writer = historyWriter,
        attachments = attachments,
        imageScaler = imageScaler,
        imageLog = imageLog,
        providerConfig = ::currentProviderConfig,
        isSuppressed = { writeSuppressed },
    )

    /**
     * 一个 assistant 回合的「请求装配 + 流式消费」（A5 拆出，见 `TurnExecutor`）。
     * 依赖面全是本会话已有的能力：执行器不持有状态，历史写入仍全部回到会话侧。
     */
    private val turnExecutor = TurnExecutor(
        TurnExecutor.Host(
            sessionId = id,
            // 执行器要的是**这一发请求所依据的那次解析**的完整产物（含段落缓存读数），
            // 每发只调一次（U42）；其余三处（回合循环 / 压缩 / 界面）按需现取 ——
            // 见 AgentAssembly 的 KDoc。
            inputs = inputsProvider,
            hooks = hooks,
            history = { _messages.value },
            cacheEnabled = cacheEnabled,
            cacheInspector = cacheInspector,
            attachments = attachments,
            diagnostics = diagnostics,
            onPersist = { if (!writeSuppressed) historyWriter.append(it) },
            updateAssistant = writes::updateAssistant,
            removeMessage = writes::remove,
        ),
    )

    /**
     * 唯一的会话运行状态。写入点只有 [transition]，每次转移都要过 [canTransitionTo] 的表：
     * 非法转移只记录、保持现状，不静默覆盖（ADR 0004）。
     */
    private val _turnState = MutableStateFlow<TurnState>(TurnState.Idle)
    val turnState: StateFlow<TurnState> = _turnState.asStateFlow()

    private val rejectedTransitionsCount = AtomicInteger(0)

    /** 被拒绝的非法转移次数（诊断 / 用例据此确认「不静默覆盖」）。 */
    val rejectedTransitions: Int get() = rejectedTransitionsCount.get()

    /**
     * [turnState] 的只读投影（`Running` / `Stopping` 对外都是「回合在跑」）。
     * 保留它只为既有断言与调用方，不是第二个状态量。
     */
    val status: StateFlow<SessionStatus> = ProjectedStateFlow(turnState) { state ->
        when (state) {
            TurnState.Idle, TurnState.Busy -> SessionStatus.IDLE
            is TurnState.Running, TurnState.Stopping -> SessionStatus.RUNNING
        }
    }

    /**
     * [turnState] 的只读投影：用户已点「停止」、但取消收尾还没跑完（杀进程 / 修复工具
     * 配对 / 落盘）。取消是异步的，这段窗口里必须继续表现为忙 —— 否则新一轮会和仍在
     * 收尾的旧轮抢历史（见 [runGate]）。对外表现为「发送入队」而不是「禁止输入」：
     * 收尾若因工具不响应取消而挂住，禁发会把用户永久锁在输入框外（见 `ChatScreen`）。
     */
    val stopping: StateFlow<Boolean> = ProjectedStateFlow(turnState) { it == TurnState.Stopping }

    /** 队列的读写只发生在 [runGate] 之内（回合串行），本类不另加锁。 */
    private val turnQueue = TurnQueue()
    val queue: StateFlow<List<QueuedMessage>> = turnQueue.messages

    private val _lastCompaction = MutableStateFlow<CompactionResult?>(null)

    /** 最近一次压缩的结果；UI 订阅它来弹出「已压缩 X → Y」提示。 */
    val lastCompaction: StateFlow<CompactionResult?> = _lastCompaction.asStateFlow()

    /**
     * 消费最近一次压缩提示：UI 弹出后调用，把它置空，保证「每次压缩只提示一次」。
     *
     * 去重不能只靠界面级的 remembered 状态：会话对象长期存活（缓存在 AppContainer，
     * 离开 / 重进会话时仍在），而界面会被重建、界面级状态随之丢失 —— 那样 `seq`
     * 一直非空、去重计数却归零，每次重进都会重弹。把「已提示」落在会话侧后，
     * 事件只被消费一次，与界面重建无关。
     */
    fun consumeLastCompaction() {
        _lastCompaction.value = null
    }

    private var compactionSeq = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 已登记、尚未结束的串行任务（回合 / 压缩 / 历史编辑 / 队列 drain）。
     * 为什么不是单个 `job` 指针（U77 的 0.1）：它有 7 处赋值，[abort] 只取消得掉**最后**被赋上的
     * 那一个 —— 那才是「`send × drain` 双起」的真实形状。登记 / 注销都在 [launchSerial] 里成对。
     * 必须是 [CopyOnWriteArraySet]：CHM 的 key 迭代器并发注销时会抛 NSE（U89H，见交接）。
     */
    private val active: MutableSet<Job> = CopyOnWriteArraySet()

    /**
     * 当前**持有串行闸门**的任务（无人持有为 null）——「这一笔历史属于哪一轮」的答案（U77 的 0.3）。
     */
    @Volatile
    private var gateHolder: Job? = null

    /**
     * [prompt] 本轮是否被 [abort] 主动中止。
     * 子智能体面板的「终止」发生在**另一个协程**里，不能靠取消父回合来表达，
     * 否则会把父会话的工具调用一起取消；这里记一个标记，让 prompt 以普通失败返回。
     */
    @Volatile
    private var promptAborted = false

    /**
     * 轮次闸门：同一会话的任何一轮（用户回合 / 手动压缩）**严格串行**。
     *
     * 为什么必须有这层：中断是异步的 —— [abort] 取消 Job 之后，旧协程还要跑完取消收尾
     * （回收占位气泡 / 落盘半截回合 / 修复工具配对）。此前 [abort] 会同步把状态置为空闲，
     * 用户紧接着「编辑重试」或再发一条时，新一轮与**仍在收尾的旧轮**会同时改 `_messages`：
     * 旧轮把已经撤回的回复重新 append 回来、把新一轮的 RUNNING 状态覆盖成 IDLE（界面于是
     * 又显示发送键，可以再开一轮）—— 结果就是界面上出现两段互不相干的模型回复。
     *
     * 闸门保证后一轮要等前一轮（含取消收尾）彻底结束后才开始，历史写入因此永远是单线程的。
     */
    private val runGate = Mutex()

    /**
     * 已排队 / 正在跑 / 正在收尾的串行任务数。
     *
     * **不参与任何对外语义**（`isRunning` / `isBusy` / 转移合法性都只看 [turnState]），
     * 只回答一个问题：最后一个任务何时结束（那一刻才是「停止成功」）。ADR 0004 决策 4
     * 允许它作为状态机的内部实现细节存在。
     */
    private val inFlight = AtomicInteger(0)

    /**
     * 停止的账本与看门狗（U57 ③④）：`abort()` 之后最多等 [stopHardLimitMs] 就必须回到 Idle，
     * 被强制收口的轮次不许再写历史。账目逻辑在 `StopWatchdog.kt`，这里只有接线。
     */
    private val stopWatchdog = StopWatchdog(
        sessionId = id,
        limitMs = stopHardLimitMs,
        inFlight = inFlight,
        diagnostics = diagnostics,
        scope = scope,
        isStopping = { _turnState.value == TurnState.Stopping },
        isClosed = { historyWriter.isClosed },
    )

    /**
     * 唯一的状态写入点：合法转移才落值，非法转移记录一次并保持现状。
     *
     * 用 CAS 循环而不是直接赋值：完成回调在任意线程，直接 `value =` 会丢掉并发转移。
     */
    private fun transition(next: TurnState) {
        while (true) {
            val current = _turnState.value
            if (!current.canTransitionTo(next)) {
                rejectedTransitionsCount.incrementAndGet()
                return
            }
            if (current == next || _turnState.compareAndSet(current, next)) return
        }
    }

    /**
     * 清掉压缩相位。只在「仍是回合且在压缩」时改写：压缩被取消（`Stopping`）或收尾
     * 已跑完（`Idle`）时，相位由那一轮的状态转移负责，这里不该再写一次 ——
     * 否则会凭空产出一次被拒绝的转移。
     */
    private fun clearCompactionPhase() {
        if ((_turnState.value as? TurnState.Running)?.compacting == true) transition(TurnState.Running())
    }

    /**
     * 启动一轮：登记、进入 [TurnState.Running]、在闸门内执行 [block]，全部结束
     * （含取消收尾）后若没有别的任务在排队才回到 [TurnState.Idle]。
     */
    private fun launchRun(block: suspend () -> Unit): Job = launchSerial(markRunning = true, block = block)

    /**
     * 排到闸门后面执行：用于「只改历史、不产生回合」的动作（删除消息）。
     * 取消中的旧轮收尾期间，这类动作也必须在闸门内执行，否则会与收尾同时写 `_messages`。
     */
    private fun launchSerial(markRunning: Boolean = false, block: suspend () -> Unit): Job {
        // 先登记再转移：反过来的话，上一轮恰好在这一瞬间收尾会把计数减到 0 并回到
        // Idle，新的这一轮就在「显示空闲」的状态下跑起来了。
        inFlight.incrementAndGet()
        when (_turnState.value) {
            TurnState.Idle -> transition(if (markRunning) TurnState.Running() else TurnState.Busy)
            // 已有串行动作在跑：回合排在其后时补上「回合在跑」，只是改历史的动作则并入。
            TurnState.Busy -> if (markRunning) transition(TurnState.Running()) else Unit
            // 已经在回合里（含停止收尾）：新任务排到 runGate 后面，状态保持不变。
            is TurnState.Running, TurnState.Stopping -> Unit
        }
        val launched = scope.launch {
            runGate.withLock {
                // 写入归属：从拿到闸门到放锁，本任务就是「这一轮」。
                gateHolder = kotlinx.coroutines.currentCoroutineContext()[Job]
                try { block() } finally { gateHolder = null }
            }
        }
        // 先入集合再挂完成回调：反过来的话，任务若在登记前就结束，注销会落空、留下死引用。
        active += launched
        // 用 invokeOnCompletion 而不是 try/finally：协程可能还没开始跑就被取消，
        // 那样函数体里的 finally 根本不会执行，计数就永远漏掉一次、会话卡在「忙」。
        launched.invokeOnCompletion {
            active -= launched
            stopWatchdog.onFinished(launched, ::finishSerialWork)
        }
        return launched
    }

    /**
     * 一个串行任务（含取消收尾）真正结束。只有**全部**结束才回到空闲 ——
     * 这是「停止成功」的唯一时刻。排队消息也在这里接上，否则会一直滞留到用户下次手动发送。
     *
     * 排空条件是「停止过 **或** 队列非空」：收尾广播 `TurnEnd` 跑在本回合最后一次排空之后、
     * 置空闲之前，那个窗口里通知只会入队、而投递方已返回「已送达」—— 没人取就是静默丢通知，
     * 外加会话永不判空闲（`AppContainer.describe` 判 `queue.isEmpty`）。机制与实证变异见
     * `docs/handoff/维护-后台通知投递时机.md` §2.6。
     *
     * 账目（谁还没收尾 / 被放弃的轮次怎么除名）在 [StopWatchdog]：这里只在「全部结束」时收口。
     */
    private fun finishSerialWork() {
        val wasStopping = _turnState.value == TurnState.Stopping
        transition(TurnState.Idle)
        if (wasStopping || !turnQueue.isEmpty) drainQueueAfterStop()
    }

    /**
     * 收尾结束后把队列里排着的东西接上，避免滞留到用户下次手动发送。
     * 接法是 [TurnQueue.planNextAfterStop] 的纯决策（只读不取），「取出 → 提交」走队列
     * 唯一出口 [TurnQueue.submitNext]（提交被取消即归还，U21），这里只做「起哪一轮」。
     * 判 `isRunning`：「置空闲 → 读队列」不原子，中间插进来的 `send` 可能已起轮 —— 再起只会空转一次。
     */
    private fun drainQueueAfterStop() {
        if (historyWriter.isClosed || isRunning) return
        when (turnQueue.planNextAfterStop()) {
            null -> return
            // 队首作本回合首条用户消息（同旧 runUser(队首)）：提交在回合壳内走队列唯一出口
            // （B 审 P2：提交期异常不许逃逸 launchRun），提交前被取消则归还、本轮不跑。
            is TurnQueue.NextRun.StartWith -> launchRun { runUserFromQueue(QueueKind.FOLLOW_UP) }
            TurnQueue.NextRun.ContinueTranscript -> launchRun { runUser(null) }
        }
    }

    fun loadHistory(history: List<ChatMessage>) {
        // 修复旧数据：悬空 tool_call / 无主 tool_result（历史版本中断收尾不完整，或端点
        // 不回 id 时占位 id 与结果对不上）、非法 JSON 的工具参数、用户粘贴进对话的控制字符。
        // 若历史本就干净，本步是恒等变换、不写盘；有脏数据时修好并落盘 ——
        // 这样「重启会话仍报错」的旧会话也能自愈。
        val repaired = HistorySanitizer.sanitizeUserText(HistorySanitizer.repairInterrupted(history))
        _messages.value = repaired
        if (repaired != history) onReplaceHistory(repaired)
    }

    /** 回合在跑（含停止收尾：ADR 0003 要求停止期间对外表现为忙）。 */
    val isRunning: Boolean get() = when (turnState.value) {
        is TurnState.Running, TurnState.Stopping -> true
        TurnState.Idle, TurnState.Busy -> false
    }

    /** 有任何串行工作在跑（含停止收尾与非回合动作）。 */
    val isBusy: Boolean get() = turnState.value != TurnState.Idle

    // ---- sending ------------------------------------------------------------

    /**
     * Send a user turn. When the agent is already busy the message is queued as a
     * follow-up and runs as soon as the current run would otherwise stop.
     *
     * [images] 为用户随文字附加的图片（多模态）；纯文字时保持原字节路径。
     */
    fun send(text: String, images: List<Part.Image> = emptyList(), systemNote: Boolean = false) {
        // 插件注入的消息不经过 onInput（那是用户输入改写点），也不是用户说的话。
        val message = inbound(text, images, systemNote)
        // 一律先入 FOLLOW_UP 队列受托，起用走 [TurnQueue.submitNext] 唯一出口（取消即归还）。
        // 旧写法把消息关在 launchRun 的 lambda 里，abort 落进「没起跑 / 提交前」窗口时它
        // 既不在历史也不在队列、静默消失；收尾结束后由 [drainQueueAfterStop] 接上。
        turnQueue.enqueue(message, QueueKind.FOLLOW_UP)
        // 忙（含「已点停止、收尾还没跑完」）时排队等接；Idle 直接起跑、Busy 排到 runGate 后面。
        when (_turnState.value) {
            is TurnState.Running, TurnState.Stopping -> return
            TurnState.Idle, TurnState.Busy -> Unit
        }
        launchRun { runUserFromQueue(QueueKind.FOLLOW_UP) }
    }

    /**
     * Queue a message that is injected after the current assistant turn, before it stops.
     *
     * [systemNote] 给后台任务的完成通知用（U18）：空闲时等价 [send] 的唤醒；忙时入 STEER，
     * **下一步请求前**就并入历史 —— 通知不再等「回合结束」，也不会在长回合里滞留。
     */
    fun steer(text: String, images: List<Part.Image> = emptyList(), systemNote: Boolean = false) {
        if (!isRunning) {
            send(text, images, systemNote)
            return
        }
        turnQueue.enqueue(inbound(text, images, systemNote), QueueKind.STEER)
    }

    /** 入站消息的统一构造：系统通知不走用户输入改写点（与 [send] 的老规矩一致）。 */
    private fun inbound(text: String, images: List<Part.Image>, systemNote: Boolean): ChatMessage =
        userMessage(if (systemNote) text else hooks.runInput(text), images, systemNote)

    /** Continue from the existing transcript without adding a user message. */
    fun continueRun() {
        if (isBusy || _messages.value.isEmpty()) return
        launchRun { runUser(null) }
    }

    /**
     * 中止当前轮。**不**同步置空闲：取消收尾（杀容器进程 / 修复工具配对 / 落盘）仍在跑，
     * 真正的收口在 [launchSerial] 的完成回调里。停止期间对外保持忙碌，界面把停止键换成
     * 转圈；期间发出的消息进队列，收尾结束后自动接上（见 [drainQueueAfterStop]）。
     *
     * **取消 [active] 里的全部任务，不是「最后一个」**（U77 的 0.1）：单指针被后起的任务覆盖后，
     * 真正在跑的那一轮没被取消 —— 会话就停在 `Stopping` 等它，谁也不动手。
     */
    fun abort() {
        promptAborted = true
        clearQueue()
        val running = active.toList() // 快照：登记 / 注销来自任意线程
        // 只有「确实有一轮在跑」时转移表才接受 Stopping。空闲 / 只有非回合动作在跑时
        // 这次尝试会被拒绝并记录（状态不变）—— 旧实现里那个可写的 `stopping` 标记，
        // 正是「空闲 + 停止中」这个非法组合能被写出来的入口。
        transition(TurnState.Stopping)
        if (running.isEmpty() || running.all { it.isCompleted }) {
            // 没有可取消的轮次：只有确实无人在收尾时才声称空闲。重复调用 abort()
            // 不能把上一轮仍在进行的收尾「宣布」成结束 —— 那时计数 > 0，状态保持 Stopping，
            // 由最后一个任务的完成回调收口。
            if (inFlight.get() == 0) transition(TurnState.Idle)
            return
        }
        running.forEach { it.cancel() }
        // 取消是协作式的：它打不断阻塞在网络前置阶段（DNS / TCP）的调用。兜底 —— 到点强制收口。
        // 这是**止损**，不是「问题已解决」：主修是让前置阶段可取消、有上界（`core/ai/PreConnect.kt`）。
        // 交给看门狗的是整份快照：卡住的可能是其中任何一个，只盯一个等于没兜住（U77 的 0.2）。
        if (_turnState.value == TurnState.Stopping) stopWatchdog.arm(running, ::forceClose)
    }

    /**
     * 强制收口要做的状态动作（U57 ③）：回到可发送，并接上停止期间排队的消息。
     *
     * 账目（把卡住的那一轮从 [inFlight] 除名、留诊断）在 [StopWatchdog.force] —— 两者必须
     * 在同一把锁里成对，否则计数会少一、之后每一轮都收不了口。
     * 不杀进程 / 不重复取消：`killTree` 已在取消路径上跑过。
     */
    private fun forceClose() {
        transition(TurnState.Idle)
        drainQueueAfterStop()
    }

    /**
     * 作废本实例（**不删数据**，删数据见 `SessionLifecycle.deleteSession`）：先置作废标记再中止；
     * 置位与提交共用 [HistoryWriter] 的锁，返回后不会再有新写入开始（取消收尾仍跑完，但不落盘）。
     * **不等待取消收尾**（不在锁内 `join`）：调用方可能在主线程。
     */
    fun discard() {
        historyWriter.markClosed()
        abort()
    }

    // ---- queue management ---------------------------------------------------

    fun removeQueued(messageId: String) {
        turnQueue.remove(messageId)
    }

    fun clearQueue() {
        turnQueue.clear()
    }

    // ---- history rewriting (retract / edit) ---------------------------------

    /**
     * 用户侧历史改写（A5 第二步拆出，见 [HistoryEdits]）：编辑重发 / 重试某条 / 删除某条。
     * 三者的共同前提是「历史严格一一配对」与「排队到闸门后执行」，细节见该类注释。
     */
    private val historyEdits = HistoryEdits(
        HistoryEdits.Host(
            messages = { _messages.value },
            replaceHistory = { writes.replace(it) },
            clearQueue = { turnQueue.clear() },
            runUser = { runUser(it) },
            newUserMessage = { userMessage(hooks.runInput(it)) },
            isEditable = { !isRunning || _turnState.value == TurnState.Stopping },
            // 起轮一律走会话的 launchSerial：abort() 靠活动集合取消，不再靠某个指针。
            launchRun = { block -> launchRun(block) },
            launchSerial = { block -> launchSerial(block = block) },
        ),
    )

    fun editUserMessage(messageId: String, newText: String) = historyEdits.editUserMessage(messageId, newText)

    fun retryFrom(messageId: String) = historyEdits.retryFrom(messageId)

    fun deleteMessage(messageId: String) = historyEdits.deleteMessage(messageId)

    // ---- compaction ---------------------------------------------------------

    /** 压缩执行器（A5 第二步拆出，见 [CompactionRunner]）：本类只保留对外入口与状态。 */
    private val compaction = CompactionRunner(
        CompactionRunner.Host(
            sessionId = id,
            workspace = workspace,
            assembly = { inputsProvider().assembly },
            compactionSettings = compactionProvider,
            contextStrategy = contextStrategyProvider,
            contextWindow = { inputsProvider().contextWindow },
            target = { inputsProvider().compactionTarget },
            hooks = hooks,
            messages = { _messages.value },
            replaceHistory = { writes.replace(it) },
            demotedMessages = ::demotedMessages,
            markCompacting = { transition(TurnState.Running(compacting = true)) },
            clearCompacting = ::clearCompactionPhase,
            onEvent = onEvent,
            turnCount = ::currentTurnCount,
            diagnostics = diagnostics,
            onCompacted = { before, after ->
                _lastCompaction.value = CompactionResult(++compactionSeq, before, after)
            },
        ),
    )

    /** Manually compact the transcript into a checkpoint summary. */
    fun compactNow() {
        val strategy = contextStrategyProvider() ?: return
        // 停止收尾期间允许排到闸门后（新一轮必须等旧轮彻底结束才动手），
        // 与 [abort] 之前「同步置空闲」时的行为保持一致。
        if (isRunning && _turnState.value != TurnState.Stopping) return
        launchRun {
            try {
                compaction.compactNow(strategy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 手动压缩整体失败：**历史保持原样（图片也不降级）** —— 只有压缩提交了才
                // 会产生副作用。失败原因必须留下：静默的 catch 只会让用户觉得按钮没反应。
                diagnostics.recordCompactionFailure(id, "${e.javaClass.simpleName}: ${e.message ?: "压缩失败"}")
            }
            // 压缩期间排进队列的消息不在这里补消费：`compactNow` 自己就跑在 `launchRun` 里，收尾会走
            // [finishSerialWork] 的「队列非空就排空」按 planNextAfterStop 起回合（FOLLOW_UP 队首作首条用户消息）。
        }
    }

    /** Run a user turn inline and suspend until it completes (used by subagents). */
    suspend fun prompt(text: String) {
        promptAborted = false
        // 与用户回合走同一条路径：登记 inFlight、经 runGate、由完成回调收口。
        // 子智能会话通常只有这一轮，因此「跑完即空闲」与旧实现等价；但若它排在某次
        // 删除 / 压缩之后，状态不会被这一轮提前宣布为空闲（旧实现让几个状态量各写各的，
        // 正是「状态不同步」的来源）。
        val turn = launchRun { runUser(userMessage(hooks.runInput(text))) }
        try {
            turn.join()
        } catch (e: CancellationException) {
            // 调用方被取消（超时 / 父会话 abort）：把子智能体一并停掉再向上抛。
            turn.cancel()
            throw e
        }
        // join() 正常返回只说明 turn 结束了，不区分「跑完」与「被 abort 取消」。
        if (promptAborted) throw SessionAbortedException()
    }

    // ---- context introspection ---------------------------------------------

    fun contextWindow(): Int = inputsProvider().contextWindow

    fun estimatedContextTokens(): Int = Compaction.estimateContextTokens(_messages.value)

    // ---- internals ----------------------------------------------------------

    /** 用户回合数（不含宿主/插件注入的 system note 回合）。压缩历史不会让它倒退。 */
    private fun currentTurnCount(): Int =
        _messages.value.count { it.role == Role.USER && !it.isSystemNote }

    private fun userMessage(
        text: String,
        images: List<Part.Image> = emptyList(),
        systemNote: Boolean = false,
    ) =
        ChatMessage(
            role = Role.USER,
            // 宿主/插件注入的提示（定时任务、工具集变化）标成 system note：界面渲染成提示卡片，
            // 模型侧也能区分「这不是用户说的」。同时不计入用户的回合数。
            isSystemNote = systemNote,
            parts = buildList {
                // 用户输入可能带着从终端 / 日志里复制来的控制字符（NUL、ESC 等）。它们在
                // JSON 里虽然合法转义，却既不可读、又会随历史永久回灌给模型 —— 在**入会话
                // 的唯一入口**剔掉，保证日志、请求前缀与界面三处一致（见 stripControlChars）。
                val clean = stripControlChars(text)
                // 纯文字（或无图片）时仍保留单个 Part.Text，字节路径与改动前一致。
                if (clean.isNotEmpty() || images.isEmpty()) add(Part.Text(clean))
                addAll(images)
            },
        )

    /**
     * 中断收尾的**会话侧**：修复本体是 [HistorySanitizer.repairInterrupted]（全历史、幂等，
     * 含尾条工具结果封口）；这里只回答「要不要写盘」。
     *
     * 本就干净时不写：多余的 `onReplaceHistory` 会重写整份会话日志，而字节一个都没变。
     * 走 [HistoryWrites.repair] 而不是直接调 `onReplaceHistory`：会话已关闭时不再落盘，
     * 否则取消收尾会把已删除的日志重新写出来。**修复与重写是两条规则**，见 [HistoryWrites]。
     */
    private fun sanitizeInterruptedTurn() {
        val current = _messages.value
        val repaired = HistorySanitizer.repairInterrupted(current)
        if (repaired == current) return // 本就干净：不改写、不写盘
        writes.repair(repaired)
    }

    /**
     * 回合执行器（A5 第二步拆出，见 [TurnRunner]）：`runUser` / `runLoop` / 工具两阶段执行
     * 搬出去，本类只保留状态、写入面与对外 API。**语义零变化** —— 搬家是纯位移。
     */
    private val turnRunner = TurnRunner(
        TurnRunner.Host(
            sessionId = id,
            workspace = workspace,
            inputs = inputsProvider,
            messages = { _messages.value },
            tools = tools,
            hooks = hooks,
            turnQueue = turnQueue,
            // 用函数类型而不是 TurnExecutor 实例：执行器要能被 fake（见 TurnRunnerTest）。
            streamAssistant = { turnExecutor.streamAssistant() },
            refreshPrompt = refreshPromptEveryTurn,
            contextStrategy = contextStrategyProvider,
            commandRunner = commandRunner,
            files = filesProvider,
            append = { writes.append(it) },
            toolResultMessage = { callId, name, content, isError ->
                toolResultMessage(callId, name, content, isError)
            },
            parseArgs = ::parseArgs,
            removeMessage = writes::remove,
            restoreMessage = { if (!writeSuppressed) _messages.update { list -> list + it } },
            clearQueue = { turnQueue.clear() },
            onEvent = onEvent,
            turnCount = ::currentTurnCount,
            isActive = ::isActive,
            // U48 ③：关闭状态供 runLoop / 回合壳区分「会话已关闭」与「用户中止」（会话私有）。
            isSessionClosed = { historyWriter.isClosed },
            sanitizeInterrupted = ::sanitizeInterruptedTurn,
            today = todayProvider,
            rootGranted = rootGranted,
            toolsetBaselineGet = toolsetBaselineGet,
            toolsetBaselineSet = toolsetBaselineSet,
            compaction = compaction,
        ),
    )

    /** 一个用户回合（见 [TurnRunner]）：状态与写入面留在本类，循环搬出去。 */
    private suspend fun runUser(userMessage: ChatMessage?) = turnRunner.runUser(userMessage)
    private suspend fun runUserFromQueue(kind: QueueKind) = turnRunner.runUserFromQueue(kind)

    /**
     * 这一笔写入是否属于**已被强制放弃的轮次**（U57 ④ + U77）。不能用会话级布尔量：那会把
     * **下一轮**的合法写入一起挡掉，也回答不了「被放弃那一轮的压缩提交算不算数」。
     */
    private val writeSuppressed: Boolean get() = stopWatchdog.isAbandoned(gateHolder)

    /** 图片入库取端点像素上限的配置入口：与请求侧同一解析口径（U42），按需现取。 */
    private fun currentProviderConfig() = inputsProvider().providerConfig

    /**
     * 自救链第一步的**候选**：更早的图片降级为占位后返回，**不写历史** —— 为什么必须
     * 等到提交点才生效见 `CompactionRunner.run`。幂等：没有更早的图片时原样返回。
     */
    private fun demotedMessages(): List<ChatMessage> {
        val current = _messages.value
        return Compaction.demoteOlderImages(current) { i -> imageSourcePath(current, i) }
    }

    /**
     * 推导某条图片消息的来源路径：图片消息由内核紧跟在 `read_image` 的 tool_result 之后追加
     * （见 `TurnRunner.runLoop`），因此用前一条 tool_result 的 toolCallId 反查那次调用的 `path` 参数，
     * 不会猜错到同一回合的其它调用。用户直接附加的图片没有来源路径，返回 null。
     */
    private fun imageSourcePath(messages: List<ChatMessage>, index: Int): String? {
        val callId = messages.getOrNull(index - 1)
            ?.parts?.filterIsInstance<Part.ToolResult>()?.lastOrNull()?.toolCallId
            ?: return null
        for (i in index - 1 downTo 0) {
            val m = messages[i]
            if (m.role != Role.ASSISTANT) continue
            val call = m.toolCalls.firstOrNull { it.id == callId } ?: continue
            if (call.name != ReadImageToolName) return null
            return parseArgs(call.arguments)?.get("path")?.jsonPrimitive?.content
        }
        return null
    }

    private fun toolResultMessage(toolCallId: String, toolName: String, content: String, isError: Boolean): ChatMessage =
        ChatMessage(
            role = Role.TOOL_RESULT,
            parts = listOf(Part.ToolResult(toolCallId, toolName, content, isError)),
            isError = isError,
        )

    /** Parse a tool call's raw arguments for the hook chain, or null when not strict JSON. */
    private fun parseArgs(raw: String): JsonObject? = parseToolArguments(raw)

    private suspend fun isActive(): Boolean = kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive ?: true

    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 128_000

        /**
         * 用户中止时写进转录的工具结果（面向模型，界面也会显示）。
         * 用英文：它随历史回灌给模型，属于模型输入契约。
         */
        internal const val TOOL_ABORTED = "Tool execution was aborted by the user before it finished."

        /**
         * 用户中断命令后写进转录的提示（面向模型，同时会在历史里显示给用户）。
         * 按语言规则用英文：它随历史回灌给模型，属于模型输入契约。
         */
        internal const val INTERRUPTED_NOTICE = "(interrupted by the user)"

        /**
         * 工具集变化提示：旧会话的历史可能是在**上一版本的工具集**下产生的，模型会锚定
         * 自身旧回合（当时它还「没有」某些工具），进而忽略当前已注入的工具清单。按语言
         * 规则用英文（随历史回灌给模型，属模型输入契约）。
         */
        internal const val TOOLSET_CHANGED_NOTICE =
            "Note: the set of available tools has changed since earlier in this " +
                "conversation. Re-read the current tool list above; a tool you previously " +
                "reported as unavailable may now be available."
    }
}
