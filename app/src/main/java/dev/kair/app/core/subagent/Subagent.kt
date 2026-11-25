package dev.kair.app.core.subagent

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.SessionAbortedException
import dev.kair.app.core.ai.RequestCapture
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.newId
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.int
import dev.kair.app.core.tools.numProp
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.str
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Tracked status of a spawned subagent session, mirroring pi-web's session tracking. */
@Serializable
data class SubagentInfo(
    val id: String,
    val parentId: String?,
    val name: String,
    val prompt: String,
    /** "working" | "idle" | "error" */
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val error: String? = null,
)

/**
 * 子智能体类型：
 * - [CODING] 继承父会话**全部**工具，可读写文件、跑命令（默认，保持既有行为）；
 * - [SEARCH] 只保留**只读**工具（read / ls / grep / find 等），适合检索定位而不改动任何东西；
 * - [BROWSER] 只保留 `browser_*` 与 read / read_image，用于驱动会话内浏览器。浏览器是
 *   进程级单例（一个 WebView），所以同类型子智能体在**全局**串行：跨父会话排队，等太久
 *   明确失败，见 [SubagentManager.spawnAndWait]。
 */
enum class SubagentType {
    CODING,
    SEARCH,
    BROWSER;

    companion object {
        /** 解析 `subagent` 工具传入的 `type`（大小写不敏感）；未知 / 缺省一律回退 [CODING]。 */
        fun from(value: String?): SubagentType = when (value?.trim()?.lowercase()) {
            "search" -> SEARCH
            "browser" -> BROWSER
            else -> CODING
        }
    }
}

/**
 * Spawns and tracks child agent sessions. This is the Android analogue of
 * pi-web's `spawn_session` / `spawn_subsession` session tools.
 *
 * 委派式语义：调用方 [spawnAndWait] 会**阻塞等待**子智能体跑完并拿到它的最终输出，
 * 像调用普通工具一样；不再需要后台轮询。面板仍可查看历史与 kill。
 */
class SubagentManager(
    /**
     * 创建子智能体会话。`parentSessionId` 是发起委派的会话（嵌套委派时是上级子智能体），
     * 宿主据此让子智能体继承父会话的模型 / 上下文窗口 / 工具可见性。
     */
    private val createSession: (id: String, workspace: File, type: SubagentType, parentSessionId: String?, modelId: String?) -> AgentSession,
    /**
     * 解析**发起委派的会话**的工作区：宿主按会话 id 反查该会话自己的工作区。
     * 因此子智能体继承的是**触发它的那个会话**的工作区，而不是全局「最近打开的会话」
     * —— 多会话并发时后者会把 A 会话发起的子智能体落到 B 会话的工作区。
     *
     * **拿不到就返回 null**：本类不做任何「取不到回退全局」的兜底，直接让这次委派失败
     * （`AGENTS.md` §10.4）。回退到一个「最近活跃工作区」正是「写进上一个项目」的入口，
     * 而子智能体的产出会直接写文件，错了不可恢复。
     */
    private val workspaceOf: (sessionId: String?) -> File?,
    /**
     * `browser` 子智能体的排队上限。浏览器是进程级单例（`BrowserHost` 只有一个 WebView），
     * 同一时刻只允许一个子智能体驱动它；等超过这个时长就明确失败，不无限挂起。
     * 生产用默认值，测试注入短值以毫秒级验证串行与超时。
     */
    private val browserLockWaitMillis: Long = BROWSER_LOCK_WAIT_MS,
    /** browser 子智能体的进 / 出闸门：跑时由 agent 接管，跑完把页面交还用户并关掉。 */
    private val browserGate: BrowserRunGate = BrowserRunGate(),
) {
    private val _agents = MutableStateFlow<List<SubagentInfo>>(emptyList())
    val agents: StateFlow<List<SubagentInfo>> = _agents.asStateFlow()

    /**
     * 任务条目：生命周期（会话实例 + 阶段）在这里，[SubagentInfo] 只是**展示投影**（U45）。
     *
     * 之前是「展示状态兼任生命周期判据」，两头都出错：`kill` 立刻把 status 写成 `error`，
     * 回收便按 `status != "working"` 把它当成已完成丢掉（那一刻本轮可能还在收尾）；
     * 而 `createSession` 抛错留下的 `working` 记录**没有会话实例**，按会话表计数永远
     * 算不出超额 —— 清不掉。
     */
    private val tasks = ConcurrentHashMap<String, SubagentTask>()

    /**
     * 任务阶段。
     *
     * **只有 [Finished] 参与判据**（回收 + 「谁还持有会话」都只看它）；[Starting] / [Running] 是
     * 给读代码的人标明位置用的，没有任何一个消费者按它分流。所以这里**不要**再加「看起来该有」
     * 的中间态（B 审实测：曾经有一个 `Cancelling`，删掉它 0 条用例变红 —— 它与 `Running` 行为
     * 完全等价，只是多一个没人读的写法）。真需要区分「已请求终止 / 收尾中」时，先让
     * `AgentSession.abort()` 返回可等待的收尾句柄（见交接 §8），再让状态有行为。
     */
    private enum class Phase {
        /** 记录已发布，会话实例还没登记（解析工作区 / `createSession` 进行中）。 */
        Starting,

        /** 会话已登记，回合在跑（含「已请求终止、本轮还在收尾」那段时间）。 */
        Running,

        /** 本轮已结束，条目可被回收。**只由** [spawnAndWait] 的 `finally` 置位。 */
        Finished,
    }

    private class SubagentTask(val id: String) {
        @Volatile
        var session: AgentSession? = null

        @Volatile
        var phase: Phase = Phase.Starting
    }

    /**
     * 已请求终止的子智能体 id。
     *
     * 记录（[SubagentInfo]）在 session 登记之前就已发布，用户可能在这个窗口点「终止」：
     * 那时 [AgentSession.abort] 还找不到 Job，只留下一个会被 `prompt()` 开头清掉的标记。
     * 这里保留「已请求终止」本身，让 [spawnAndWait] 在启动回合前检查并直接收尾。
     */
    private val killed = ConcurrentHashMap.newKeySet<String>()

    /**
     * `browser` 子智能体的全局串行锁：一个进程只有一个 WebView，两个会话同时驱动会互相
     * 点对方页面 / 抢同一个 URL。锁在 [spawnAndWait] 里跨父会话生效，任务结束时释放。
     */
    private val browserLock = Mutex()

    /**
     * 解析本次委派使用的工作区：优先取**上级子智能体**（嵌套委派）自己的工作区，
     * 否则交由宿主的 [workspaceOf] 按父会话 id 反查。
     */
    private fun resolveWorkspace(parentId: String?): File? =
        parentId?.let { tasks[it]?.session?.workspace } ?: workspaceOf(parentId)

    /**
     * 跑一个子智能体任务并等它结束，返回它的最终输出。
     *
     * - [timeoutMillis] 为 null 表示不限时；超时会中止子智能体并返回错误文本。
     * - [type] 决定子智能体拿到的工具集（见 [SubagentType]）。
     * - 上层协程被取消（用户点停止 / 会话 abort）时，先 abort 子智能体再把取消向上抛，
     *   不吞掉 CancellationException —— 否则父会话的中断收尾会被破坏。
     * - [modelId] 指定本次子智能体用的模型（null = 按设置项 / 父会话解析，见
     *   `SessionAssembly.subagentModel`；容器上的同名成员只是委托）。典型用途：整理记忆这类低难度任务用便宜模型。
     * - 返回文本以 [ERROR_PREFIX] 开头即表示失败（子智能体报错 / 超时 / 无输出）。
     * - 请求缓冲不在收尾时清空：子智能体的失败请求按「只留最近 2 条」保留到
     *   `remove` / `prune`，供「请求诊断」面板回溯；总量由 `RequestCapture` 的全局
     *   预算兜底，不会随委派次数无限增长。
     */
    suspend fun spawnAndWait(
        parentId: String?,
        prompt: String,
        name: String,
        timeoutMillis: Long?,
        type: SubagentType = SubagentType.CODING,
        modelId: String? = null,
    ): String {
        val id = newId()
        val now = System.currentTimeMillis()
        val info = SubagentInfo(id, parentId, name.trim().ifBlank { "子智能体" }, prompt, "working", now, now)
        val task = SubagentTask(id)
        tasks[id] = task
        _agents.update { it + info }
        // 每新增一个子智能体就裁剪一次：超出上限时丢弃最旧的**已结束**条目及其
        // session 对象（含完整消息历史），避免长会话里堆积大量闲置会话造成内存增长。
        pruneHistory()

        fun finish(status: String, error: String?) =
            patch(id) { it.copy(status = status, error = error, updatedAt = System.currentTimeMillis()) }

        // 是否真的拿到了浏览器锁：只有拿到的那次才允许解锁（排队超时的那次不碰锁）。
        var browserLocked = false
        return try {
            // 工作区解析不到就失败，**绝不回退**「最近活跃工作区」：子智能体的读写会落进别人的项目。
            val workspace = resolveWorkspace(parentId) ?: run {
                finish("error", "no workspace")
                return "$ERROR_PREFIX no workspace: the delegating session is not open. Open a session and retry."
            }
            // 浏览器是进程级单例：跨父会话排队。等太久就明确失败，别让父回合无限挂起。
            if (type == SubagentType.BROWSER) {
                browserLocked = withTimeoutOrNull(browserLockWaitMillis) { browserLock.lock() } != null
                if (!browserLocked) {
                    finish("error", "browser busy")
                    return "$ERROR_PREFIX the browser is busy in another session (waited " +
                        "${browserLockWaitMillis / 1000}s). Retry after that browser subagent finishes; " +
                        "only one browser subagent can run at a time."
                }
                browserGate.enter()
            }
            // 启动阶段的任何异常都不能漏给调用方：父回合会因此直接崩，而留下的 `working`
            // 记录没有会话实例，按会话表计数永远算不出超额（U45 的 V1/V2）。下面这个 try 必须
            // 同时覆盖工作区解析与 createSession —— 别把它们挪到 try 之外。
            val session = createSession(id, workspace, type, parentId, modelId)
            task.session = session
            task.phase = Phase.Running

            // 登记 session 之前就已收到「终止」（面板点击或父会话关闭）：不再启动回合。
            if (id in killed) {
                finish("error", "killed")
                return "$ERROR_PREFIX stopped by user."
            }

            if (timeoutMillis != null) withTimeout(timeoutMillis) { session.prompt(prompt) } else session.prompt(prompt)
            val err = session.messages.value.lastOrNull()?.takeIf { it.isError }?.errorMessage
            if (err != null) {
                finish("error", err)
                "$ERROR_PREFIX $err"
            } else {
                finish("idle", null)
                finalOutput(session)
            }
        } catch (e: SessionAbortedException) {
            // 用户从面板「终止」：只让本次委派失败，不取消父回合。
            finish("error", "killed")
            "$ERROR_PREFIX stopped by user."
        } catch (e: TimeoutCancellationException) {
            task.session?.abort()
            finish("error", "timeout")
            "$ERROR_PREFIX timed out after ${(timeoutMillis ?: 0) / 1000}s and was stopped. " +
                "Retry with a larger `timeout` (seconds), or 0 for no limit."
        } catch (e: CancellationException) {
            task.session?.abort()
            finish("error", "cancelled")
            throw e
        } catch (e: Exception) {
            finish("error", e.message)
            "$ERROR_PREFIX ${e.message ?: e.javaClass.simpleName}"
        } finally {
            // 先关页再放锁：下一个排队的 browser 子智能体拿到锁时，浏览器是干净的。
            if (browserLocked) {
                browserGate.exit()
                browserLock.unlock()
            }
            // 本次委派已收尾：不再需要「已请求终止」标记。请求缓冲不在这里清，
            // 保留规则见 spawnAndWait 的 KDoc。
            killed.remove(id)
            // 本轮到此结束（调用方已拿到结果），条目可以被回收。这是**唯一**把条目置为
            // 可回收状态的地方：被请求终止的任务在此之前一直是 Running，回收必须让开。
            task.phase = Phase.Finished
        }
    }

    fun kill(id: String) {
        // 先登记终止意图，再尝试中止：记录可能已发布而 session 尚未登记（见 [killed]）。
        killed.add(id)
        // **不动阶段**：`abort()` 只是把取消递进去，本轮的结束仍只由 [spawnAndWait] 的 `finally`
        // 判定。这里若顺手把阶段标成「已完成」式的终态，回收就会把还在收尾的任务丢掉（U45）；
        // 而标成任何「非 Running」的中间态都不会有任何消费者看见（见 [Phase] 的注释）。
        tasks[id]?.session?.abort()
        patch(id) { it.copy(status = "error", error = "killed", updatedAt = System.currentTimeMillis()) }
    }

    fun sessionOf(id: String): AgentSession? = tasks[id]?.session

    /**
     * 当前仍持有会话实例的子智能体（U50：附件回收的额外引用来源）。
     *
     * 语义是「**仍持有** `AgentSession`」而不是「仍在运行」：回合结束后实例还留在表里供面板
     * 回看历史，那段时间它的消息仍引用着图片；把已结束的排除掉，回收就会把仍在被引用的本体
     * 当孤儿删掉（用户看到的是图片变「不可用」，没有报错）。实例只在记录被回收
     * （[pruneHistory] / [remove] / [clear]）时才释放。
     */
    fun liveSessions(): List<AgentSession> = tasks.values.mapNotNull { it.session }

    /**
     * 终止某个父会话派生出的**全部**子智能体（含嵌套委派）。
     *
     * 父会话被关闭 / 删除时必须调用：否则子智能体继续跑，它的审批 ownerSessionId
     * 指向一个已不存在的会话，卡片永远不显示，只能等超时。
     */
    fun abortChildrenOf(parentSessionId: String) {
        val ids = _agents.value
            .filter { rootSessionOf(it.id) == parentSessionId }
            .map { it.id }
        ids.forEach { id ->
            killed.add(id)
            // 同 [kill]：只递取消，不动阶段 —— 本轮的结束只由 `spawnAndWait` 的 `finally` 判定。
            tasks[id]?.session?.abort()
            patch(id) {
                it.copy(status = "error", error = "parent session closed", updatedAt = System.currentTimeMillis())
            }
        }
    }

    /** 按 id 查子智能体记录；不是子智能体时返回 null。 */
    fun info(id: String): SubagentInfo? = _agents.value.firstOrNull { it.id == id }

    /**
     * 从子智能体 [subagentId] 沿 [SubagentInfo.parentId] 向上找到**顶层会话 id**。
     * 嵌套委派（子智能体再派生子智能体）会一路走到最外层；不是子智能体 / 没有父会话
     * 时返回 null。审批卡片据此显示在父会话而不是子会话里。
     */
    fun rootSessionOf(subagentId: String): String? = rootSessionOf(_agents.value, subagentId)

    fun lastText(id: String): String? = tasks[id]?.session?.messages?.value?.lastOrNull()?.text

    fun clear() {
        tasks.keys.forEach { RequestCapture.clear(it) }
        tasks.values.forEach { it.session?.abort() }
        tasks.clear()
        killed.clear()
        _agents.value = emptyList()
    }

    /**
     * 移除单条子智能体记录（连同其 session 对象与消息历史）。
     * 面板的逐条「清除」入口：运行中的应先 kill（活跃条目只显示「终止」），
     * 这里对任何状态都兜底先中止再移除，避免留下孤儿会话。
     */
    fun remove(id: String) {
        // 与 [kill] 同理：记录可能已发布而 session 尚未登记，标记终止避免回合照常启动。
        killed.add(id)
        tasks.remove(id)?.session?.abort()
        RequestCapture.clear(id)
        _agents.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * 修剪历史：保留最近 [MAX_HISTORY] 个任务条目。
     *
     * 计数按**任务表**而不是会话表：启动就失败、`createSession` 抛错的条目没有会话实例，
     * 按会话表算永远算不出超额，那些记录会无限增长（U45 的 V2）。
     *
     * 只回收 [Phase.Finished] 的条目：`Starting` / `Running` 都还在产生引用或收尾，提前丢掉会把
     * 仍在跑的任务从面板抹掉。展示用的 `SubagentInfo.status` 不再参与判据 ——
     * `kill` 写的 `error` 只是文案，不代表执行结束。
     */
    private fun pruneHistory() {
        val excess = tasks.size - MAX_HISTORY
        if (excess <= 0) return
        val stale = _agents.value
            .filter { info -> tasks[info.id]?.phase == Phase.Finished }
            .take(excess)
        stale.forEach { info ->
            tasks.remove(info.id)
            RequestCapture.clear(info.id)
            _agents.update { list -> list.filterNot { it.id == info.id } }
        }
    }

    /** 子智能体的最终输出：最后一条有内容的 assistant 文本。 */
    private fun finalOutput(session: AgentSession): String =
        session.messages.value
            .lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
            ?.text?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: "(subagent produced no output)"

    private fun patch(id: String, transform: (SubagentInfo) -> SubagentInfo) {
        _agents.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        /** 失败文本前缀：`subagent` 工具据此把结果标记为 error 交回模型。 */
        const val ERROR_PREFIX = "Subagent failed:"

        /** 保留的子智能体历史上限：超出后丢弃最旧的已完成会话，避免内存无限增长。 */
        const val MAX_HISTORY = 50

        /**
         * `browser` 子智能体排队上限（10 分钟）。浏览器只有一个 WebView，必须串行；
         * 等这么久还没轮到，说明前一个任务卡住了，明确失败比让父回合无限挂起更好。
         */
        const val BROWSER_LOCK_WAIT_MS = 10 * 60 * 1000L

        /**
         * [rootSessionOf] 的纯函数版本：沿 parentId 走到最外层会话。
         * 顺手用 [seen] 防御异常数据造成的环，避免死循环。
         */
        internal fun rootSessionOf(agents: List<SubagentInfo>, subagentId: String): String? {
            val byId = agents.associateBy { it.id }
            var current = byId[subagentId] ?: return null
            val seen = mutableSetOf(current.id)
            while (true) {
                val parent = current.parentId ?: return null
                val parentAgent = byId[parent] ?: return parent
                if (!seen.add(parent)) return parent
                current = parentAgent
            }
        }
    }
}

/**
 * `subagent` 工具：把一个任务委派给子智能体并**等待它完成**，返回最终输出。
 *
 * 子智能体分三种类型（[SubagentType]）：`coding` 拥有父会话的全部工具，`search` 只保留
 * 只读工具（read / ls / grep / find 等），`browser` 只保留 `browser_*` 与 read / read_image，
 * 用来驱动会话内浏览器。`browser` **不负责起服务**：先由主会话把 dev server 跑起来，
 * 再把 URL 写进 prompt。浏览器是进程级单例，同类型子智能体跨父会话串行。
 *
 * 与旧的「后台 spawn + 轮询 check」不同，模型像调用普通工具一样拿到结果。同一回合内
 * 发出多个 `subagent` 调用时，内核会并发执行它们（各自等自己的子智能体），可以真正并行。
 */
class SubagentTool(private val manager: SubagentManager) : Tool {
    override val name = "subagent"
    override val category = ToolCategory.EXECUTE
    override val promptSnippet =
        "Delegate a task to a subagent and wait for its final result (default timeout 3600s = one hour)"

    // 每个委派是独立会话，互不共享状态：同回合多个 subagent 是并行收益的主要来源。
    override val isParallelSafe = true
    override val description =
        "Run a task in a separate subagent session that shares the same workspace, then wait " +
            "for it to finish and return its final output. Choose the subagent `type`: \"coding\" " +
            "(default) inherits the full toolset and can edit files and run commands; \"search\" is " +
            "restricted to read-only tools (read / ls / grep / find) for locating files, symbols, or " +
            "usages without side effects; \"browser\" gets browser_* plus read / read_image to drive " +
            "the in-app dev browser. It cannot start servers: start your dev server first (for " +
            "example with background_task or container_shell) and pass the URL in the prompt. Only " +
            "one browser subagent runs at a time across sessions. To parallelize independent " +
            "subtasks, issue several subagent calls in one turn and they run concurrently. Real " +
            "tasks usually need at least one hour (3600s) — pass a larger `timeout` for longer work. " +
            "Use `model` to pick the model for this run (e.g. a cheaper one for light tasks)."
    override val parameters = schemaOf(
        props(
            "prompt" to strProp("The task for the subagent to perform"),
            "name" to strProp("Short human-readable name for the subagent (optional)"),
            "type" to strProp(
                "Subagent capability: \"coding\" (default) has the full toolset and may modify files " +
                    "or run commands; \"search\" is limited to read-only tools (read / ls / grep / find) " +
                    "and should be used when you only need to locate files, symbols, or usages; " +
                    "\"browser\" drives the in-app browser (browser_* + read / read_image) and needs " +
                    "an already-running URL — start the dev server yourself first.",
            ),
            "timeout" to numProp(
                "Timeout in seconds (default $DEFAULT_TIMEOUT_SECONDS = one hour; 0 disables). " +
                    "Give real tasks at least one hour (3600) unless the work is genuinely small.",
            ),
            "model" to strProp(
                "Optional model id for this subagent run (e.g. a cheaper model for light tasks). " +
                    "Omit to use the configured subagent model or inherit the parent session's model.",
            ),
        ),
        "prompt",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val prompt = args.reqStr("prompt")
        val name = args.str("name") ?: "子智能体"
        val type = SubagentType.from(args.str("type"))
        val timeoutSec = subagentTimeoutSeconds(args.int("timeout"))
        val timeoutMillis = timeoutSec.takeIf { it > 0 }?.toLong()?.times(1000)
        val output = manager.spawnAndWait(
            ctx.sessionId, prompt, name, timeoutMillis, type,
            modelId = args.str("model")?.takeIf { it.isNotBlank() },
        )
        return ToolResult(output, isError = output.startsWith(SubagentManager.ERROR_PREFIX))
    }

    companion object {
        /**
         * 默认超时一小时（U58 ③）：长任务（读大仓、跑测试、写多文件）是常态，
         * 5 分钟会把它们反复掐断，模型看到超时后往往改小范围重试而不是调大。
         * 只改子智能体 —— `container_shell` 的 300s 不动（那是单条命令的合理上界）。
         */
        const val DEFAULT_TIMEOUT_SECONDS = 3600
    }
}

/**
 * `timeout` 参数的解析：缺省取 [SubagentTool.DEFAULT_TIMEOUT_SECONDS]，显式值原样保留
 * （`0` = 不限时）。抽成纯函数供 JVM 用例直接判「缺省 / 不限时 / 显式」三态（U58 ③）。
 */
internal fun subagentTimeoutSeconds(raw: Int?): Int = raw ?: SubagentTool.DEFAULT_TIMEOUT_SECONDS
