package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.model.newId
import dev.kair.app.core.proot.Mount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent 的后台工具任务：在**持久进程**里起一条长命令，立刻把控制权还给回合；
 * 完成结果以 `isSystemNote` 的 user 消息回注：会话空闲时唤醒新回合，忙时入 STEER 队列、
 * 下一步请求前并入历史（U18，不再等回合结束）。
 *
 * 与既有概念的分工：一次性 `container_shell` 结束即回收，不能承载后台；[ContainerSession] 负责
 * 进程存活与终端（本类只借用它执行）；SchedulerPlugin 的用户定时任务不参与工具调用。
 *
 * 关键约束：
 * - 任务**不能**作为第二次 tool result（一次 tool_call 只能配一个结果），完成通知走
 *   `AgentSession.steer(systemNote = true)`。
 * - 通知进了队列就算送达：[dev.kair.app.core.agent.TurnQueue.clear]（abort / 删除消息）
 *   保留系统通知，所以忙时投递不会被停止收尾吞掉；会话关闭则不投递（任务随关闭取消）。
 * - 日志与退出码写在 `<workspace>/.kair/tasks/`：宿主与容器共享该目录，完成检测只看
 *   宿主侧 rc 文件，不轮询会话 shell，也不污染终端转录。
 *
 * 全局可变状态四项（AGENTS.md §10.5）：
 * - 归属：应用级管理器，任务按 [BackgroundTask.sessionId] 归属会话；
 * - 生命周期：随 AppContainer 创建；任务只在内存，进程重建后清空（日志文件仍在）；
 * - 清除：会话关闭走 `SessionRegistry.Cleanup.BackgroundTasks` → [cancelAll]；
 * - 并发：同一会话最多 [maxRunning] 个运行中任务；状态流转用 [MutableStateFlow] 的 CAS。
 */
class BackgroundTaskManager internal constructor(
    private val scope: CoroutineScope,
    /**
     * 宿主侧起一个**不等待**的容器进程（U60）。
     *
     * 返回时进程已拉起；实现不得获取会话 `execLock` —— 会话 shell 卡死时任务仍要能启动。
     */
    private val launch: suspend (DetachSpec) -> DetachResult,
    /**
     * 持久进程当前挂载的清单；null = 会话未运行。
     *
     * 挂的是工作区**父目录**（容器 `/projects`），一次挂载后项目增删改即时可见，
     * 所以这里不需要「先同步再取」。[containerPathOf] 按父挂载前缀解析出具体项目路径。
     */
    private val mounts: suspend () -> List<Mount>?,
    /** 该 id 是否是仍开着的普通会话（子智能体 / 已关闭会话返回 false）。 */
    private val sessionExists: (String) -> Boolean,
    /** 完成通知投递（宿主按 steer 语义写 system note）；测试注入记录器。 */
    private val deliver: suspend (sessionId: String, taskId: String, note: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = DEFAULT_POLL_MS,
    private val maxRunning: Int = MAX_RUNNING,
    private val idFactory: () -> String = { "bt-" + newId().replace("-", "").take(8) },
) {

    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())

    /** 最近的任务（新的在前）；UI 与 `isIdle` / 保活共用这一份。 */
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** 已拉起、尚未结束的宿主侧进程（取消 / 超时走它收尾，不再经会话 exec）。 */
    private val processes = ConcurrentHashMap<String, DetachedProcess>()

    /** 写脚本 / 等 launch / 未入表的 id；[pruneFiles] 的第二个 keep 来源。 */
    private val claiming = ConcurrentHashMap.newKeySet<String>()

    /** 该会话是否还有运行中或待投递的后台任务（淘汰与保活据此把会话视为「忙」）。 */
    fun hasRunning(sessionId: String): Boolean =
        _tasks.value.any { it.sessionId == sessionId && (it.running || it.delivering) }

    fun list(sessionId: String): List<BackgroundTask> =
        _tasks.value.filter { it.sessionId == sessionId }

    fun find(sessionId: String, taskId: String): BackgroundTask? =
        _tasks.value.firstOrNull { it.sessionId == sessionId && it.id == taskId }

    /** 启动一条后台命令：返回时任务已进入运行态（或明确失败），调用方（工具）立刻返回。 */
    suspend fun start(
        sessionId: String,
        hostWorkspace: File,
        command: String,
        cwd: String? = null,
        /** 超时秒数；**0 = 不限时**（常驻服务：无完成通知，随取消 / 会话关闭而止）。 */
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): StartResult {
        val cmd = command.trim()
        if (cmd.isEmpty()) return StartResult.Failure(TASK_ERR_EMPTY)
        // 子智能体会话不在注册表里：完成通知没有接收方，直接拒绝而不是白跑。
        if (!sessionExists(sessionId)) return StartResult.Failure(TASK_ERR_NOT_SESSION)
        val mounted = mounts() ?: return StartResult.Failure(TASK_ERR_NO_SESSION)
        val containerRoot = containerPathOf(hostWorkspace, mounted)
            ?: return StartResult.Failure(TASK_ERR_NOT_MOUNTED)
        val relativeCwd = normalizeRelativeCwd(cwd) ?: return StartResult.Failure(TASK_ERR_BAD_CWD)
        if (_tasks.value.count { it.sessionId == sessionId && it.running } >= maxRunning) {
            return StartResult.Failure(TASK_ERR_LIMIT.format(maxRunning))
        }

        val id = idFactory()
        // 认领必须早于写脚本：同目录的另一个 start 调 pruneFiles 时，会把它当 keep，
        // 不会删掉还在 launch 里、尚未入表的这条任务文件（见 pruneFiles 的注释）。
        claiming += id
        try {
            val dir = File(hostWorkspace, TASKS_DIR).apply { mkdirs() }
            val containerDir = "$containerRoot/$TASKS_DIR"
            val containerCwd = if (relativeCwd.isEmpty()) containerRoot else "$containerRoot/$relativeCwd"
            val script = File(dir, "$id.sh")
            val runner = File(dir, "$id.run.sh")
            runCatching {
                script.writeText(taskScript(id, containerCwd, cmd, "$containerDir/$id.rc"))
                runner.writeText(runnerScript("$containerDir/$id.sh", "$containerDir/$id.log"))
            }.onFailure {
                deleteLaunchFiles(dir, id)
                return StartResult.Failure("Cannot write the task scripts: ${it.message}")
            }

            val spec = DetachSpec(
                id = id,
                hostWorkspace = hostWorkspace,
                hostDir = dir,
                containerScript = "$containerDir/$id.sh",
                containerRunner = "$containerDir/$id.run.sh",
                hostRc = File(dir, "$id.rc"),
                hostPid = File(dir, "$id.pid"),
            )
            val launched = try {
                launch(spec)
            } catch (e: Throwable) {
                // 回合被取消 / 执行器抛错：进程收尾由执行器自己负责（它持有 Process 句柄），
                // 这里只清不再需要的脚手架文件。
                deleteLaunchFiles(dir, id)
                throw e
            }
            val process = when (launched) {
                is DetachResult.Failed -> {
                    deleteLaunchFiles(dir, id)
                    return StartResult.Failure("Failed to start the background task: ${launched.message}")
                }
                is DetachResult.Started -> launched.process
            }

            val task = BackgroundTask(
                id = id,
                sessionId = sessionId,
                command = cmd,
                cwd = relativeCwd,
                state = BackgroundTaskState.RUNNING,
                startedAt = now(),
                pid = process.pid,
                timeoutSeconds = timeoutSeconds,
                dir = dir,
            )
            val next = insertTask(_tasks, task)
            if (next == null) {
                // 进程已经起来了：先收掉再报错，别留下一个没人认领的后台进程。
                withContext(NonCancellable) { runCatching { process.stop(force = true) } }
                deleteLaunchFiles(dir, id)
                return StartResult.Failure(TASK_ERR_FULL.format(MAX_TASKS))
            }
            processes[id] = process
            // 会话可能在 launch 期间关闭：那时 cancelAll 读到的还是旧表，收不到刚插入的这条。
            // 关闭顺序是「先摘注册表、再 cancelAll」，所以这里复查 false 必然意味着它没被收过。
            if (!sessionExists(sessionId)) {
                transitionTask(_tasks, id, BackgroundTaskState.CANCELLED, null, now())
                processes.remove(id)
                withContext(NonCancellable) { runCatching { process.stop(force = true) } }
                deleteLaunchFiles(dir, id)
                return StartResult.Failure(TASK_ERR_NOT_SESSION)
            }
            pruneFiles(dir)
            jobs[id] = scope.launch { watch(task) }
            return StartResult.Started(task)
        } finally {
            // 入表后表里已有它，认领可以释放；失败路径同样释放，避免集合无限增长。
            claiming -= id
        }
    }

    /**
     * 用户 / 工具取消：先抢终态，抢到才停 watcher。反过来的话，watcher 若已抢到终态、
     * 正在投递，通知会被掐掉而本次 settle 又因终态已落返回 false —— 谁也没收到通知。
     *
     * 通知的投递不再等会话空闲（U18）：忙时入 STEER 队列、下一步请求前并入，
     * 所以工具路径在 `Running` 的回合里调用也不会自锁。
     */
    suspend fun cancel(sessionId: String, taskId: String): Boolean {
        val current = find(sessionId, taskId) ?: return false
        if (!current.running) return false
        val cancelled = settle(taskId, BackgroundTaskState.CANCELLED, null, killFirst = true)
        if (cancelled) jobs.remove(taskId)?.cancel()
        return cancelled
    }

    /** 面板入口：取消会发信号并投递通知，放到应用作用域跑，不阻塞组合。 */
    fun cancelAsync(sessionId: String, taskId: String) {
        scope.launch { cancel(sessionId, taskId) }
    }

    /** 会话关闭：全部取消，**不投递**（用户已经关掉了会话）。 */
    fun cancelAll(sessionId: String) {
        // 也取消「已完成、通知还在投递 / 排队」的任务：会话已经关了，通知不该再叫醒它。
        _tasks.value.filter { it.sessionId == sessionId }.forEach { task ->
            val cancelled = transition(task.id, BackgroundTaskState.CANCELLED, null) != null
            // 先摘句柄再取消 watcher：它的 finally 也摘同一把，取消后再摘会扑空、漏杀进程。
            val handle = if (cancelled) processes.remove(task.id) else null
            jobs.remove(task.id)?.cancel()
            handle?.let(::killAsync)
        }
    }

    /** 面板查看输出：手动读取宿主侧日志的尾部。 */
    fun logTail(taskId: String): String =
        _tasks.value.firstOrNull { it.id == taskId }?.let { tailText(it.logFile, PANEL_TAIL_CHARS, PANEL_TAIL_LINES) }.orEmpty()

    // ---- 内部 -----------------------------------------------------------------

    private suspend fun watch(task: BackgroundTask) {
        try {
            while (true) {
                delay(pollMillis)
                val current = _tasks.value.firstOrNull { it.id == task.id } ?: return
                if (!current.running) return
                val code = readExit(current.rcFile)
                if (code != null) {
                    val state = if (code == 0) BackgroundTaskState.SUCCEEDED else BackgroundTaskState.FAILED
                    settle(current.id, state, code, killFirst = false)
                    return
                }
                // 判定顺序固定为「.rc → 存活 → 超时」：先判存活会把刚正常退出的任务误判成异常终止，
                // 先判超时则会让「进程已死」被报成超时。
                if (!isAlive(current.id)) {
                    // 「进程退出 ↔ .rc 落盘」之间有一个窗口（trap 在退出前才写）：等一拍再读一次，
                    // 仍然没有才判异常终止 —— 宁可晚一个轮询周期，也不把正常完成记成 TERMINATED。
                    delay(pollMillis)
                    val late = readExit(current.rcFile)
                    if (late != null) {
                        val state = if (late == 0) BackgroundTaskState.SUCCEEDED else BackgroundTaskState.FAILED
                        settle(current.id, state, late, killFirst = false)
                        return
                    }
                    // 这一步不看 timeoutSeconds：不限时（0）的常驻任务同样要能落终态。
                    settle(current.id, BackgroundTaskState.TERMINATED, null, killFirst = false)
                    return
                }
                if (current.timeoutSeconds > 0 && now() - current.startedAt >= current.timeoutSeconds * 1000) {
                    // 守卫必须带 `>0`：0 = 不限时（常驻服务），`elapsed >= 0` 恒真，
                    // 少了它第一条不限时任务会在**第一次轮询**就被超时杀掉。
                    settle(current.id, BackgroundTaskState.TIMED_OUT, null, killFirst = true)
                    return
                }
            }
        } finally {
            jobs.remove(task.id)
            processes.remove(task.id)
        }
    }

    /**
     * 该任务的宿主进程是否仍存活（U49）。
     *
     * 句柄已被摘走（取消 / 超时收尾的 `kill`）时返回 true：这种路径下 watcher 马上就会退出，
     * 不能把「没有句柄」当成「进程死了」—— 那会把用户取消报成异常终止。
     */
    private fun isAlive(id: String): Boolean = processes[id]?.alive?.invoke() ?: true

    /**
     * 落终态 →（必要时）杀进程组 → 投递通知。`delivering` 与终态在同一次 CAS 落表
     * （见 [transitionTask]）：投递期间仍被 `hasRunning` 算「忙」，也不会被截断丢掉；
     * 投递结束（含异常 / 取消）由 `finally` 原子摘掉。
     *
     * 投递本身不再等会话空闲（U18）：忙时通知入 STEER 队列、下一步请求前并入历史，
     * 所以这里同步投也不会把调用方（含工具路径）锁在跑着的回合里。
     *
     * @return 本次是否推进了终态（已被取消 / 已结束时返回 false，幂等）。
     */
    private suspend fun settle(
        id: String,
        state: BackgroundTaskState,
        exitCode: Int?,
        killFirst: Boolean,
    ): Boolean {
        val done = transition(id, state, exitCode, delivering = true) ?: return false
        val note = try {
            if (killFirst) kill(done)
            val tail = if (state == BackgroundTaskState.SUCCEEDED || state == BackgroundTaskState.FAILED) {
                tailText(done.logFile, NOTE_TAIL_CHARS, NOTE_TAIL_LINES)
            } else {
                ""
            }
            taskNote(done, tail)
        } catch (e: Throwable) {
            markDelivered(_tasks, id)
            throw e
        }
        deliverAndMark(done, note)
        return true
    }

    private suspend fun deliverAndMark(task: BackgroundTask, note: String) {
        try {
            deliver(task.sessionId, task.id, note)
        } finally {
            markDelivered(_tasks, task.id)
        }
    }

    /** 原子地把运行中的任务推进到终态；已被取消 / 已完成时返回 null（幂等）。 */
    private fun transition(
        id: String,
        state: BackgroundTaskState,
        exitCode: Int?,
        delivering: Boolean = false,
    ): BackgroundTask? = transitionTask(_tasks, id, state, exitCode, now(), delivering)

    /**
     * 收掉宿主侧进程：先礼后兵（TERM → 宽限 → 强制收树），与调用方的取消解耦
     * （用户取消 / 关闭会话必须真的发出去）。进程句柄已从 [processes] 摘走，延迟的强制收尾
     * 仍持有它，不受后续 start / cancel 影响。
     */
    private suspend fun kill(task: BackgroundTask) {
        val process = processes.remove(task.id) ?: return
        // 已死只摘句柄、不发信号（U49）：pid 一旦被复用就会打到无关进程。
        if (!process.alive()) return
        withContext(NonCancellable) { runCatching { process.stop(force = false) } }
        scope.launch {
            delay(KILL_GRACE_MS)
            withContext(NonCancellable) { runCatching { process.stop(force = true) } }
        }
    }

    /** 非挂起入口（会话关闭路径）：TERM → 宽限 → 强制收树。 */
    private fun killAsync(process: DetachedProcess) {
        // 同上：进程已经没了就别再发信号。
        if (!process.alive()) return
        scope.launch {
            withContext(NonCancellable) { runCatching { process.stop(force = false) } }
            delay(KILL_GRACE_MS)
            withContext(NonCancellable) { runCatching { process.stop(force = true) } }
        }
    }

    /** 确定这条启动不会再有进程后，清掉启动期文件；日志 / 退出码 / detach 脚本留给下次 prune。 */
    private fun deleteLaunchFiles(dir: File, id: String) {
        listOf("sh", "run.sh", "pid").forEach { ext ->
            runCatching { File(dir, "$id.$ext").delete() }
        }
    }

    /**
     * 删掉目录里不再被认领的文件（trim 挤出的旧任务 + 上一次进程的孤儿）。
     *
     * **先列文件，再取 keep**（当前表 ∪ claiming）：写文件的 start 都已先认领 id，反过来
     * 会把快照之后新写出的脚本误删；也不能用 insertTask 返回的快照 —— 那里没有并发插入的
     * 对方任务。任务仍在表里时，它的 `.sh` / `.run.sh` / `.log` / `.rc` 都受保护。
     */
    private fun pruneFiles(dir: File) {
        val files = dir.listFiles() ?: return
        val keep = HashSet<String>()
        _tasks.value.mapTo(keep) { it.id }
        keep += claiming
        files.forEach { file ->
            val id = file.name.substringBefore('.')
            if (id in keep) return@forEach
            runCatching { file.delete() }
        }
    }
}

/** 一个后台任务的状态快照。 */
data class BackgroundTask(
    val id: String,
    val sessionId: String,
    val command: String,
    /** 相对工作区的目录；空串 = 工作区根。 */
    val cwd: String,
    val state: BackgroundTaskState,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    /** 宿主侧进程 pid（proot 本体）；仅展示与收尾用。 */
    val pid: Long,
    val timeoutSeconds: Long,
    /** 脚本 / 输出 / 退出码所在目录（宿主侧）。 */
    val dir: File,
    /** 终态已落、完成通知还没投递完；投递期间仍算「忙」，也不会被表截断丢掉。 */
    val delivering: Boolean = false,
) {
    val logFile: File get() = File(dir, "$id.log")
    val rcFile: File get() = File(dir, "$id.rc")
    val running: Boolean get() = state == BackgroundTaskState.RUNNING
}

/**
 * [TERMINATED] 是 U49 新增的终态：宿主进程死了、而容器内 `trap rc EXIT` 没来得及写退出码
 * （SIGKILL / 崩溃 / 任务自杀）。它不是 [FAILED]（没有退出码），也不是 [CANCELLED]（不是用户干的）。
 */
enum class BackgroundTaskState { RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, TERMINATED }

/** [BackgroundTaskManager.start] 的结果：成功时任务已进入运行态。 */
sealed interface StartResult {
    data class Started(val task: BackgroundTask) : StartResult

    data class Failure(val message: String) : StartResult
}

/** 后台任务目录（相对工作区，宿主与容器共享）。 */
internal const val TASKS_DIR = ".kair/tasks"

internal const val DEFAULT_TIMEOUT_SECONDS = 1_800L
internal const val MAX_TASKS = 20
internal const val MAX_RUNNING = 2

internal const val TASK_ERR_EMPTY = "The command is empty."
internal const val TASK_ERR_NOT_SESSION =
    "Background tasks are only available in a normal coding session, not in a subagent session."
internal const val TASK_ERR_NO_SESSION =
    "The persistent Linux process is not ready. It starts automatically; ask the user to check the Linux workspace panel if this persists."
internal const val TASK_ERR_NOT_MOUNTED = "This workspace is not mounted in the persistent Linux process."
internal const val TASK_ERR_BAD_CWD = "cwd must be a relative path inside the workspace."
internal const val TASK_ERR_LIMIT = "Too many background tasks are running in this session (limit %d). " +
    "List them and cancel one to free a slot (a task started with timeout=0 never finishes on its own)."
internal const val TASK_ERR_FULL = "Too many background tasks are kept in memory (limit %d) and none can be " +
    "dropped yet (they are running or their notices are still being delivered). Cancel one to free a slot."

private const val DEFAULT_POLL_MS = 1_000L
private const val KILL_GRACE_MS = 2_000L

/** 退出码文件可能正在写（空内容）：读不到整数就当作尚未结束。 */
internal fun readExit(file: File): Int? =
    runCatching { file.readText().trim().toIntOrNull() }.getOrNull()

/**
 * 投递完成通知（U18 起按 steer 语义）：空闲时唤醒新回合，忙时入 STEER 队列、下一步
 * 请求前并入历史；队列对系统通知丢不掉（abort 的 clear 保留它们），所以忙时投也安全。
 * 用户点停止后仍会唤醒 —— 这是本特性的明确语义；会话关闭则 [provider] 返回 null。
 *
 * 「去重 / 补投」的细节在 `BackgroundTaskDelivery.kt`：那里只吃一个可注入的 [NoteSink]，
 * 所以投递语义能单测，不必构造真会话。
 */
internal suspend fun deliverToSession(
    provider: () -> AgentSession?,
    taskId: String,
    note: String,
) {
    deliverNote({ provider()?.let(::sessionNoteSink) }, taskId, note)
}

/** AppContainer 接线：把持久进程、会话注册表与投递器拼成管理器。 */
internal fun backgroundTasksOf(container: AppContainer): BackgroundTaskManager = BackgroundTaskManager(
    scope = container.scope,
    launch = { spec ->
        val session = container.containerSessionHost.session.value
        if (session == null) DetachResult.Failed(TASK_ERR_NO_SESSION) else launchDetachedTask(session, spec)
    },
    mounts = {
        // 挂的是工作区父目录，项目增删改即时可见 —— 不再需要执行前同步挂载。
        container.containerSessionHost.session.value
            ?.takeIf { it.state.value is dev.kair.app.core.proot.SessionState.Running }
            ?.mountedMounts
    },
    sessionExists = { sessionId -> container.sessions.get(sessionId) != null },
    deliver = { sessionId, taskId, note ->
        // 投递前现查一次：从任务完成到把通知交出去之间会话可能已被关闭（查不到就不投）。
        deliverToSession({ container.sessions.get(sessionId) }, taskId, note)
    },
)
