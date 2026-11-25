package dev.kair.app.core.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 「挂起等待用户决断」这一件事的**唯一实现**（`AGENTS.md` §10.4：一个概念只允许一个实现点）。
 *
 * 在此之前，审批闸门（`ApprovalGate`）与提问闸门（`AskUserGate`）各自实现了一遍，
 * 结构几乎逐行对应却分处两层（`core` 与 `ui/plugins`）：
 * 改「超时从何时开始计时」要改两处，改「会话结束时如何失效」也要改两处。
 *
 * 泛型 `Req` / `Ans` 是刻意不认识具体类型的：闸门本身不需要知道「审批」还是「提问」，
 * 它只管 lane（每展示会话一条串行链）、deferred、pending map、超时、清理、会话结束收尾。
 * 各自独有的部分留在各自那边（审批的规则层、提问的答案文本与插件侧展示）。
 *
 * ## 为什么这几个回调必须由调用方给
 *
 * 闸门不认识 `Req`，所以「这条请求展示给哪个会话」「它的 id 是什么」「超时了该返回什么」
 * 都只能由调用方回答。多四个构造参数，换来的是这份机制只有一份实现。
 */
internal class PendingUserGate<Req : Any, Ans>(
    /** 等待时限；`<= 0` 表示不限时。计时从**拿到会话锁、面板真正展示**开始，排队不计入。 */
    private val timeoutMillis: Long,
    /** 展示会话 id：同一会话串行、不同会话互不阻塞，都由它决定。 */
    private val displaySessionId: (Req) -> String,
    /** 待决项 id，供 [resolve] 匹配。 */
    private val idOf: (Req) -> String,
    /**
     * 超时 / 会话结束时的兜底答案。**必须给** —— `AGENTS.md` §7 要求等待用户的路径
     * 一定收尾并向模型说明「未执行 / 未作答」，否则无人值守的会话会永久挂起。
     */
    private val timeoutAnswer: (Req) -> Ans,
    /**
     * 拿到会话锁之后的短路判定：返回非 null 则直接返回、不挂起。
     * 审批用它做「已被放行规则覆盖则直接放行」（必须在锁内判，排队期间别处可能刚加了规则）；
     * 没有短路需求的闸门传 null。
     */
    private val fastPath: ((Req) -> Ans?)? = null,
    /**
     * 拿到终局答案后、**仍在会话锁内**执行的钩子（默认空）。
     *
     * 存在的理由只有一条：审批要在这里把「本会话放行」写进规则表。
     * 若把这件事挪到锁外（`await` 返回之后做），下一个同会话的排队请求就可能
     * 在规则生效前拿到锁 —— 于是明明刚选了「本会话都允许」，它又弹一次卡片。
     * 这是批次 5b 评审的 P1-1，改回来的就是这条串行化保证。
     */
    private val postDecision: suspend (Req, Ans) -> Unit = { _, _ -> },
) {

    /** 每个展示会话一条串行链；没有在途请求时自动回收，避免会话 id 永久累积。 */
    private class Lane {
        val lock = Mutex()
        var users = 0
    }

    private val sessionLanes = ConcurrentHashMap<String, Lane>()

    /** 展示会话 id -> 当前等待中的 deferred。 */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Ans>>()

    private val _pending = MutableStateFlow<Map<String, Req>>(emptyMap())

    /** 当前待决项，按**展示会话 id** 索引；UI 用自己会话的 id 取对应卡片。 */
    val pending: StateFlow<Map<String, Req>> = _pending.asStateFlow()

    /** 超时秒数（UI 提示用）；0 表示不限时。 */
    val timeoutSeconds: Long get() = if (timeoutMillis > 0) timeoutMillis / 1000 else 0

    /**
     * 挂起等待，直到 [resolve] 给出答案、超时、或被 [endSession] 收尾。
     *
     * 同一展示会话内按到达顺序逐条处理（排队等待**不计入**超时）；不同会话互不阻塞。
     */
    suspend fun await(req: Req): Ans {
        val owner = displaySessionId(req)
        val lane = acquireLane(owner)
        try {
            return lane.lock.withLock {
                // 短路必须在锁内判：排队期间别处可能刚加了一条放行规则。
                fastPath?.invoke(req)?.let { return@withLock it }

                val deferred = CompletableDeferred<Ans>()
                waiting[owner] = deferred
                _pending.update { it + (owner to req) }
                val answer = try {
                    if (timeoutMillis > 0) {
                        try {
                            withTimeout(timeoutMillis) { deferred.await() }
                        } catch (_: TimeoutCancellationException) {
                            timeoutAnswer(req)
                        }
                    } else {
                        deferred.await()
                    }
                } finally {
                    // 即便调用方被取消，这几行只是赋值，仍会执行，卡片不会残留。
                    _pending.update { current ->
                        if (current[owner]?.let(idOf) == idOf(req)) current - owner else current
                    }
                    waiting.remove(owner, deferred)
                }
                // 仍在会话锁内：让「记住本次决定」与「下一个排队请求看到它」串行化。
                postDecision(req, answer)
                answer
            }
        } finally {
            releaseLane(owner, lane)
        }
    }

    /** 用户对 [id] 作出决断；id 不匹配或当前无待决项时忽略（现有行为）。 */
    fun resolve(id: String, answer: Ans) {
        val entry = _pending.value.entries.firstOrNull { idOf(it.value) == id } ?: return
        waiting[entry.key]?.complete(answer)
    }

    /**
     * 会话结束：该会话的待决项**立即按兜底答案收尾**，不等超时。
     *
     * 会话都结束了，不会再有人作答 —— 让工具调用立刻带着「未执行 / 未作答」返回，
     * 比让它在后台多挂几分钟更符合 `AGENTS.md` §7。
     *
     * @return 收尾了几条（0 表示该会话没有待决项）。
     */
    fun endSession(sessionId: String): Int {
        val req = _pending.value[sessionId] ?: return 0
        val deferred = waiting[sessionId] ?: return 0
        // complete 对已完成的 deferred 返回 false；这里返回 false 也说明它已被 resolve 或超时收走。
        return if (deferred.complete(timeoutAnswer(req))) 1 else 0
    }

    /** 当前待决项快照（测试与诊断用）。 */
    fun pendingSnapshot(): Map<String, Req> = _pending.value

    private fun acquireLane(owner: String): Lane =
        // compute 是原子操作：getOrPut 在并发下可能各自创建一把锁，两条请求同时进入。
        sessionLanes.compute(owner) { _, current -> (current ?: Lane()).also { it.users += 1 } }!!

    private fun releaseLane(owner: String, lane: Lane) {
        sessionLanes.computeIfPresent(owner) { _, current ->
            if (current !== lane) {
                current
            } else {
                current.users -= 1
                if (current.users <= 0) null else current
            }
        }
    }
}
