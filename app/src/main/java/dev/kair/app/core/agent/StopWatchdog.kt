package dev.kair.app.core.agent

import dev.kair.app.core.ai.ConnectionPhases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*
 * 停止的账本与看门狗（U57 ③/④，2026-11-07）。
 *
 * 为什么单独一个文件：`AgentSession` 已在体量守卫的 ≤800 目标线上（A5 第二步），本批的
 * 账目逻辑有 ~70 行；放回去就是把「主循环 + 停止账本」焊在一起，下一次要动其中一半时
 * 没人敢改。会话只留接线（起表 / 完成回调 / 写入归属的查询），行为完全不变。
 *
 * 归属与生命周期（`AGENTS.md` §10.5）：一个会话一份，随会话对象创建与消失；不新增
 * 全局可变状态。进程重建后一切归零（停止收尾本来就是进程内的事，没有可恢复的语义）。
 */

/**
 * 一个会话的停止账本：谁还没收尾、哪一轮已被放弃、点停止之后到点强制收口（U57 ③）。
 *
 * 为什么需要它：取消是**协作式**的。`job.cancel()` 打不断阻塞在 DNS / TCP 前置阶段的
 * 调用，`TurnState.Stopping` 因此可能永久不退出（负责人真机实测：转圈约十分钟只能杀 App）。
 * 本类只做**止损**：到点把会话送回可发送，并把卡住的那一轮从账上除名 —— 否则之后每一轮
 * 结束计数都到不了 0，等于把「停不下来」换成「发不出去」。
 *
 * 它**不是**修复：修复是让网络前置阶段可取消、有上界（`core/ai/PreConnect.kt`）。
 * 也**不推翻** ADR 0003 —— 正常路径仍是「等取消收尾结束才 Idle」，这里的定时器只在
 * 收尾迟迟不结束时才响。
 */
internal class StopWatchdog(
    private val sessionId: String,
    private val limitMs: Long,
    private val inFlight: AtomicInteger,
    private val diagnostics: Diagnostics,
    private val scope: CoroutineScope,
    private val isStopping: () -> Boolean,
    private val isClosed: () -> Boolean,
) {

    /**
     * 停止看门狗；同一时刻最多一个（重复点停止只保留最后一个）。
     *
     * **读写都在 [lock] 里**：锁外读会拿到旧引用，该停的那个没停下来，之后就会有一个
     * 迟到的定时器去 [force] 一个早已结束的任务（见 [force] 里那条成对判据）。
     */
    private var timer: Job? = null

    /**
     * 已被强制收口、从账上除名的任务（它们在 [force] 里已经减过一次计数）。
     *
     * 集合本身就是「轮次归属」的答案（U77）：**任务身份 = 轮次身份**。会话的写历史入口拿
     * 当前持有串行闸门的那一个任务来问 [isAbandoned] ——「这一笔写入属于哪一轮」由此可判，
     * 不再是一个会话级的布尔量。**用布尔量会把下一轮的合法写入一起挡掉**，而且无法回答
     * 「被放弃那一轮的压缩提交算不算数」。
     *
     * 谁清：任务自己结束时 [onFinished] 把它移出（此后它再也不可能写）。
     */
    private val abandoned: MutableSet<Job> = Collections.newSetFromMap(ConcurrentHashMap())

    /** 账目锁：除名、减计数与 [timer] 的换表必须成对，而完成回调来自任意线程。 */
    private val lock = Any()

    /** [task] 那一轮是否已被强制放弃 —— 它的迟到写入一律丢弃。 */
    fun isAbandoned(task: Job?): Boolean = task != null && abandoned.contains(task)

    /**
     * 一个串行任务真正结束。**全部**结束才调用 [finish]（收口：置空闲 + 接队列）。
     *
     * 用统计而不是「最后一个」：被放弃的任务已在 [force] 里减过，这里必须先把它认出来，
     * 否则计数会少一、下一轮永远收不了口。
     */
    fun onFinished(task: Job, finish: () -> Unit) {
        synchronized(lock) {
            if (abandoned.remove(task)) return
            if (inFlight.decrementAndGet() != 0) return
            timer?.cancel()
            timer = null
            finish()
        }
    }

    /** 点停止之后起表（U57 ③）：到点仍停在 [TurnState.Stopping] 就 [force] 收口。 */
    fun arm(tasks: List<Job>, forceClose: () -> Unit) {
        synchronized(lock) {
            timer?.cancel()
            timer = scope.launch {
                delay(limitMs)
                force(tasks, forceClose)
            }
        }
    }

    /**
     * 到点强制收口：把**快照里所有还活着的任务**逐个除名，最后才 [forceClose]。
     *
     * 为什么是一组而不是一个（U77）：`inFlight` 计的是**每一个**串行任务（回合、历史编辑、
     * 队列 drain 都走 `launchSerial`），而 `abort()` 过去只认得其中一个。卡住的是别的任务时，
     * 到点没人替它收口 —— 硬上界对它根本没生效。
     */
    private fun force(tasks: List<Job>, forceClose: () -> Unit) {
        synchronized(lock) {
            if (!isStopping()) return
            var abandonedAny = false
            for (task in tasks) {
                // 迟到的定时器（B 审）：这一轮**已经自己结束**，计数由它的完成回调减过了一次。
                // 这里再减一次就是「计数少一」—— 之后每一轮都归不了零，等于把本类要消灭的
                // 「永不收口」换个入口重新引进来。
                if (task.isCompleted) continue
                // 已经除名，下面必须自己减：完成回调会因为 [onFinished] 的提前返回不再减。
                abandoned += task
                inFlight.decrementAndGet()
                abandonedAny = true
            }
            // 快照里没有一个还活着：没有可强收的任务，剩下的计数属于别人，由它们自己的完成
            // 回调收口（完成回调用 `invokeOnCompletion` 登记，一定会跑）。
            if (!abandonedAny) return
            timer = null
            // 关闭中的会话不能再往诊断里写：宿主已经 remove 过，写了就是没人清的残留。
            if (!isClosed()) diagnostics.recordStopTimeout(sessionId, detail())
            // 置空闲 + 接上停止期间排队的消息。它们会排在闸门后面，等卡住的那一轮放锁才真正开始。
            forceClose()
        }
    }

    /**
     * 强制收口给用户看的那句话。内核中文（随诊断持久化，不进 `strings.xml`，见 `AGENTS.md` §4）；
     * 必须说清「等了多久、卡在哪、还能不能发」，否则硬上界就把「卡住」换成了新的静默失败。
     */
    private fun detail(): String {
        val seconds = (limitMs / 1000).coerceAtLeast(1)
        // 有快照才说网络：`ConnectionPhases` 在每次调用结束 / 失败时会被摘掉（U57 B 审），
        // 所以「没有快照」现在真的意味着「这一刻没有进行中的网络调用」—— 那句止损话就据此说，
        // 不许把「工具或第三方传输层不响应取消」也念成网络卡住（那会把排查方向带偏）。
        val where = ConnectionPhases.snapshot(sessionId)?.let { phase ->
            val stuckSeconds = ((System.currentTimeMillis() - phase.sinceMs) / 1000).coerceAtLeast(1)
            "（网络请求停在「${phase.stage}」，已 $stuckSeconds 秒）"
        } ?: "（没停在网络前置阶段，多半是工具或不响应取消的传输实现卡住了）"
        return "停止收尾超过 $seconds 秒仍未完成$where，已回到可发送状态；卡住的那一轮不会再写入历史。"
    }
}
