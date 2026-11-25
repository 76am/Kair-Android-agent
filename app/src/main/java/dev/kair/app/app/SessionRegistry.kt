package dev.kair.app.app

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册表需要会话侧提供的最小信息。
 *
 * 只有三件：身份（[id]）、归属工作区（[workspace]，按项目批量关闭时据此匹配）、
 * 以及**此刻**能否静默卸载（[isIdle]）。注册表不感知会话内部 —— 这是它能放进
 * `app` 层、又能被单测构造的前提。
 */
data class SessionDescriptor(
    val id: String,
    val workspace: File,
    /**
     * 可静默卸载：不在跑、也没有排队消息。
     *
     * 这是**每次调用时现算**的值，不是快照 —— 会话刚从空闲转入运行，淘汰判据必须立刻变。
     */
    val isIdle: Boolean,
)

/**
 * 「按会话登记、要随会话关闭一起释放」的宿主资源。
 *
 * 这里每多一个方法，[SessionRegistry.Cleanup] 就得多一项、[SessionRegistry.cleanups]
 * 就得多一步 —— 三处都在 `app` 层，评审时一眼能看出配对。漏了会红
 * （[dev.kair.app.app.SessionRegistryTest.cleanupListCoversEveryStep]）。
 *
 * 实现方是 [AppContainer]：它持有这些登记表，注册表只负责「按什么顺序、清哪些」。
 */
interface SessionResources {
    /** 会话级放行规则（plugin-api 的「本会话」语义）随会话结束失效。 */
    fun releaseApprovalRules(id: String)

    /**
     * 该会话**未答的提问**立即按「跳过」收尾。
     *
     * 会话关了不会再有人作答 —— 不收尾的话那条工具调用会一直挂到超时。
     * 与 [releaseApprovalRules] 同源：两者都是 `PendingUserGate` 的会话级失效。
     */
    fun releasePendingQuestions(id: String)

    /** 父会话结束后，它派生的子智能体不能再跑；否则审批卡片挂到已不存在的会话上。 */
    fun abortSubagents(id: String)

    /** 该会话发起的后台工具任务：结束进程并停止完成通知（关闭会话时叫）。 */
    fun releaseBackgroundTasks(id: String)

    /** 取消该会话的状态收集协程，否则它会一直持有会话实例（长会话反复开关会累积）。 */
    fun cancelTrackJob(id: String)

    /** 从 `runningSessions`（会话列表的「运行中」转圈）里摘掉该会话。 */
    fun removeRunningMark(id: String)

    /** 缓存面板按会话保留的分段快照。 */
    fun releaseCacheRecords(id: String)

    /** 段落诊断按会话保留的最近一次装配结果。 */
    fun releaseDiagnostics(id: String)

    /**
     * 该会话的失败请求记录（`RequestCapture`，请求面板按父会话归组）。
     *
     * 只在**真正关闭**时清，淘汰不清 —— 请求记录是排查材料，用户回来时应当还在。
     * 注意这条以前只在 `deleteSession` 里清，`deleteProject` 那条路径漏了；
     * 归到 [SessionRegistry.release] 之后三条删除路径自动一致。
     */
    fun releaseRequestRecords(id: String)

    /**
     * 插件注入的限流窗口（[AppContainer] 里按会话建槽的时间戳表）。
     *
     * **只在真正关闭时清，淘汰绝不能清** —— 淘汰后会话 id 还在用，
     * 清掉槽等于把「每分钟 6 条」的额度重置，那是一条可以绕过的限流。
     * 关闭才清，因为那时会话 id 不会再出现（删除 / 项目删除）。
     */
    fun releaseSendWindow(id: String)
}

/**
 * 会话实例的**唯一持有者**与生命周期的**唯一入口**（ADR 0005）。
 *
 * 在此之前，这套逻辑散在 [AppContainer] 的四个方法里各自清一部分：`releaseSession` 清审批与
 * 子智能体，`unloadSession` 清任务/运行标记/实例/时间戳/缓存/诊断，`deleteProject` 自己拼装，
 * `deleteSession` 再多清一项请求记录。没有一处是「按会话登记的全部资源」的清单，
 * 于是新增第 7 个按会话登记的东西时，没有任何地方会提醒还要加清理 ——
 * `CacheInspector` 与 `ALLOW_SESSION` 就这样各漏过一次。
 *
 * 现在清理项是一份**有序枚举**（[Cleanup]），[release] 按序执行；枚举与清单的一致性由测试
 * 机械核验，所以「加了一项却忘了清」是红灯，不是靠记性。
 *
 * ## 泛型为什么存在
 *
 * [AppContainer] 里 `T` 是 `AgentSession` —— 22 个构造参数，单测里造不出来。
 * 测试里 `T` 是假类型。注册表本身不需要知道会话是什么，只需要 [describe] 给的三件事
 * 和 [discardAction] 这一个动作。这不是为扩展留口，是为可测性收窄依赖。
 *
 * ## 常驻容器会话不归这里管（结论，不是遗漏）
 *
 * `dev.kair.plugin.proot` 的常驻容器绑的是**工作区**而不是会话，所以它不在 [Cleanup] 清单里。
 * 会话关闭不能杀容器 —— 否则来回切会话就要反复重启 shell；项目删除时工作区随之消失，
 * 由容器侧自己感知。P3-1 要求把这条结论写出来，这里是它。
 */
class SessionRegistry<T : Any>(
    private val resources: SessionResources,
    private val describe: (T) -> SessionDescriptor,
    private val discardAction: (T) -> Unit,
    private val maxOpen: Int = DEFAULT_MAX_OPEN,
    /**
     * 时间源。可注入是为了把 LRU 顺序变成确定的 —— 同一毫秒内打开多个会话时，
     * 真实时钟分不出先后，`sortedBy` 会退化成哈希桶序，测试就会时红时绿。
     */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * `release()` 的清理清单，**顺序即执行顺序**。
     *
     * 前两项（审批规则、子智能体）必须在实例关闭**之前**：它们要读那个还活着的会话。
     * 后两项（缓存、诊断）必须在**之后**：会话自己可能还在往里写。
     * 中间三项是与实例状态绑定的（任务、运行标记、实例本身）。
     *
     * 本清单**不是** [unload] 的清单：淘汰 ≠ 会话结束，因此审批规则要留给用户
     * （「本会话放行」不能因为内存回收而失效），子智能体也不必中止（可淘汰的前提就是空闲）。
     */
    private val cleanups: List<Cleanup> = listOf(
        Cleanup.ApprovalRules,
        Cleanup.PendingQuestions,
        Cleanup.Subagents,
        Cleanup.BackgroundTasks,
        Cleanup.TrackJob,
        Cleanup.RunningMark,
        Cleanup.SessionInstance,
        Cleanup.AccessTime,
        Cleanup.CacheRecords,
        Cleanup.Diagnostics,
        Cleanup.RequestRecords,
        Cleanup.SendWindow,
    )

    /** 清理步骤。新增一项登记资源时在这里加一项 —— `when` 会强制你写清怎么释放。 */
    enum class Cleanup {
        ApprovalRules,

        /** 未答的提问（`AskUserGate`）。**只随关闭清，不随淘汰清** —— 与审批规则同理。 */
        PendingQuestions,
        Subagents,
        BackgroundTasks,
        TrackJob,
        RunningMark,

        /** 会话实例本身（`discard()` 置 `closed` 并 `abort()`）。 */
        SessionInstance,
        AccessTime,
        CacheRecords,
        Diagnostics,
        RequestRecords,

        /** 插件注入的限流窗口。**只随关闭清，不随淘汰清** —— 理由见 [SessionResources.releaseSendWindow]。 */
        SendWindow,
    }

    /** 供测试断言「枚举与清单完全一致」，从而让「加了步骤却忘了列进清单」变成红灯。 */
    internal val cleanupSteps: List<Cleanup> get() = cleanups

    /** id → 会话实例。**进程内唯一一份**，「还有哪些会话开着」只从这里读。 */
    private val sessions = ConcurrentHashMap<String, T>()

    /** 最近一次打开时间，[evict] 的 LRU 依据；与 [sessions] 同生命周期。 */
    private val accessedAt = ConcurrentHashMap<String, Long>()

    val size: Int get() = sessions.size

    val ids: Set<String> get() = sessions.keys.toSet()

    /** 只读取出。调用方拿到的是实例，不得据此改写注册表。 */
    fun get(id: String): T? = sessions[id]

    /** 当前全部实例的快照（按项目批量操作时用）。 */
    fun all(): List<T> = sessions.values.toList()

    /**
     * 取出（必要时创建）并登记。保持原 `getOrPut` 的**幂等**语义：
     * 已打开则原样返回，不会重复建实例、不会重置历史。
     *
     * @param keep 本轮**不得淘汰**的会话 id（通常是本次打开的会话与「当前会话」）。
     *   **没有默认值是有意的**：`AppContainer.evictIdleSessions` 原本自己排除了
     *   `activeSessionId`，接线时漏传就会静默把用户正在看的会话卸掉。
     *   不给默认值，漏传是编译错，不是运行时行为变化。
     */
    fun open(id: String, keep: Set<String>, create: () -> T): T {
        val session = sessions.getOrPut(id, create)
        accessedAt[id] = now()
        evict(keep = keep + id)
        return session
    }

    /**
     * 关闭并释放该会话的全部登记资源。**关闭的唯一入口**（ADR 0005 决策 2）。
     *
     * **即使实例不在册也会执行清单**：`deleteSession` 删除的可能是一个从未打开过的会话，
     * 而审批规则 / 子智能体 / 请求记录并不依赖实例存在，它们必须照样清（前实现
     * `releaseSession` 对未打开会话也调 `endSession` + `abortChildrenOf`，语义就靠这一点保住）。
     * 唯一按条件的是「关闭实例本身」—— 没有实例就跳过。
     *
     * @return 该会话此前是否开着。false 表示重复关闭或从未打开 —— 幂等，不报错：
     *   重复关闭在 UI 上会真实发生（列表刷新 + 用户点删），报错只会制造噪声。
     */
    fun release(id: String): Boolean {
        // 先摘表再清理：清理过程会回调到宿主（子智能体收尾、审批清理），
        // 那时不能让它们看到「还查得到、实际正在拆」的半死会话。
        val session = sessions.remove(id)
        cleanups.forEach { release(it, id, session) }
        return session != null
    }

    /**
     * 关闭所有匹配的会话（`deleteProject` / 删除项目用）。
     *
     * 筛选交给调用方：[AppContainer] 知道「项目 → 工作区」怎么算，注册表不知道。
     * 但**关闭动作必须回到 [release]** —— 这正是 ADR 0005 决策 4 要消灭的
     * 「deleteProject 自己拼装一套清理」。
     *
     * @return 关掉了几个。
     */
    fun releaseWhere(predicate: (SessionDescriptor) -> Boolean): Int {
        // 先取快照再逐个关：release 会改 sessions，直接遍历会跳项。
        val targets = sessions.values.map { describe(it) }.filter(predicate).map { it.id }
        targets.forEach { release(it) }
        return targets.size
    }

    /**
     * 匹配的会话里是否**此刻不能静默关闭**（运行中 / 排队 / 后台任务未完成）。
     *
     * 与 [releaseWhere] 配对使用：先问「忙不忙」再关，防止把正在跑的回合连同工作区目录
     * 一起搬走（项目改名）。[describe] 每次现算，读到的不是快照。
     */
    fun anyBusyWhere(predicate: (SessionDescriptor) -> Boolean): Boolean =
        sessions.values.any { session ->
            val descriptor = describe(session)
            predicate(descriptor) && !descriptor.isIdle
        }

    /**
     * 淘汰超出上限的**空闲**实例（LRU）。
     *
     * 淘汰与关闭的区别见 [cleanups] 的注释。**永不动 [keep] 里的会话**：
     * 「后台运行会话不可被卸载」在这里是一条断言，不是一个被反复抄写的布尔表达式。
     */
    fun evict(keep: Set<String>) {
        if (sessions.size <= maxOpen) return
        val victims = sessions.values
            .map { describe(it) }
            .filter { it.id !in keep }
            .filter { it.isIdle }
            .sortedBy { accessedAt[it.id] ?: 0L }
        for (victim in victims) {
            if (sessions.size <= maxOpen) return
            unload(victim.id)
        }
    }

    /**
     * 只卸载内存中的实例（历史仍可从存储重新载入），**不**结束会话级状态。
     *
     * 与 [release] 的差别是三项：不清审批规则、不中止子智能体、不清请求记录 ——
     * 淘汰只是内存回收，用户回来时应当还是原样。
     */
    private fun unload(id: String) {
        val descriptor = sessions[id]?.let(describe) ?: return
        // 竞态：筛选时空闲、此刻已转忙（用户刚发了一条消息）。
        // **跳过而不是卸载，更不是抛异常** —— 淘汰是「尽量回收」，没回收成只是内存多占一点；
        // 而在这里抛异常会让 openSession 在 UI 线程上崩掉，代价完全不对等。
        if (!descriptor.isIdle) return
        val session = sessions.remove(id) ?: return
        resources.cancelTrackJob(id)
        resources.removeRunningMark(id)
        discardAction(session)
        accessedAt.remove(id)
        resources.releaseCacheRecords(id)
        resources.releaseDiagnostics(id)
    }

    private fun release(step: Cleanup, id: String, session: T?) {
        when (step) {
            Cleanup.ApprovalRules -> resources.releaseApprovalRules(id)
            Cleanup.PendingQuestions -> resources.releasePendingQuestions(id)
            Cleanup.Subagents -> resources.abortSubagents(id)
            Cleanup.BackgroundTasks -> resources.releaseBackgroundTasks(id)
            Cleanup.TrackJob -> resources.cancelTrackJob(id)
            Cleanup.RunningMark -> resources.removeRunningMark(id)
            // 唯一按条件的一步：会话不在册（从未打开 / 已淘汰 / 重复关闭）就没有实例可关。
            Cleanup.SessionInstance -> session?.let(discardAction)
            Cleanup.AccessTime -> accessedAt.remove(id)
            Cleanup.CacheRecords -> resources.releaseCacheRecords(id)
            Cleanup.Diagnostics -> resources.releaseDiagnostics(id)
            Cleanup.RequestRecords -> resources.releaseRequestRecords(id)
            Cleanup.SendWindow -> resources.releaseSendWindow(id)
        }
    }

    companion object {
        /** 同时驻留内存的会话上限。超出的空闲实例按 LRU 卸载，可从存储重新载入。 */
        const val DEFAULT_MAX_OPEN = 8

        /**
         * 清单与枚举的一致性检查。返回 null 表示一致，否则给出差异。
         *
         * 抽成纯函数是为了**能验证它会红** —— 传一个少了一项的列表进去，它必须报错。
         * 否则「清单有机械约束力」只是一句话：测试绿只说明此刻恰好一致，
         * 不说明下次有人加步骤时会被拦住。
         */
        internal fun cleanupMismatch(listed: List<Cleanup>): String? {
            val missing = Cleanup.entries.filter { it !in listed }
            val extra = listed.filter { it !in Cleanup.entries }
            val duplicated = listed.groupBy { it }.filterValues { it.size > 1 }.keys
            if (missing.isEmpty() && extra.isEmpty() && duplicated.isEmpty()) return null
            return buildString {
                if (missing.isNotEmpty()) append("清单缺少步骤: ${missing.joinToString()}；")
                if (extra.isNotEmpty()) append("清单含有已删除的步骤: ${extra.joinToString()}；")
                if (duplicated.isNotEmpty()) append("清单重复列出步骤: ${duplicated.joinToString()}；")
            }.removeSuffix("；")
        }
    }
}
