package dev.kair.app.core.agent

import dev.kair.app.core.model.Usage
import dev.kair.app.core.model.cacheHitRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 相对上一回合新增的片段，**装配时就算好**并只保留展示预算内的尾部。
 *
 * 面板若自己去取「上一回合全文 + 本次全文」再做 `startsWith` / `removePrefix`，等价于
 * 在点击详情的一刻再复制一份整段历史；长会话下这正是主线程 OOM 的来源。
 */
data class AppendedSlice(
    /** 精确的新增字节数：UTF-8 下「前缀之差」等于「新增文本编码后的长度」。 */
    val bytes: Int,
    /** 给人看的可读片段。 */
    val text: String,
    /** 与线上字节一致的原始片段（控制字符已转义），供面板逐字核对。 */
    val raw: String = text,
)

/**
 * 最近一次请求装配的观测记录：分段快照 + 与同会话**上一次装配**的差异 + 观测到的命中率。
 * 由「命中率检查面板」消费，把缓存命中率从直觉变成可观测、可回归的指标。
 */
data class CacheReport(
    val sessionId: String,
    val model: String,
    val segments: List<RequestSegment>,
    val changes: Map<SegmentKind, SegmentChange>,
    /** 追加段中「本次相对上次新增」的部分，供面板展示。 */
    val appended: Map<SegmentKind, AppendedSlice> = emptyMap(),
    /**
     * 本会话的**累计**命中率读数（按 token 加权），由 [CacheInspector.recordUsage] 填。
     * 尚未收到任何 usage 时为 null。
     */
    val hitTally: HitRateTally? = null,
    /**
     * 本会话最近 [CacheInspector.historyLimit] 个**逐发**命中率样本（旧的在前），供趋势折线。
     *
     * 独立于 [hitTally] 的数：折线看的是**逐发的抖动**（哪一发掉了），加权看的是**总体省了多少**。
     * 单发比值只存在这里 —— 面板不再为它单列一行（负责人 2026-11-04：那一行既刺眼又最容易被
     * 当结论读，命中率留加权一个数就够）。
     */
    val samples: List<CacheSample> = emptyList(),
    /**
     * **产生这一发请求的那次系统提示装配**的段落缓存读数（本地段落缓存，与上面的
     * 提供商侧加权口径不是一回事）。由 [CacheInspector.record] 的入参填，值来自 [AgentAssembly.sectionCache]。
     *
     * 为 null 的情形：装配方没给（不经 `buildAgentSettings` 的测试宿主）、以及该会话还没有
     * 任何请求。注意「回合中途又来了一次装配」**不会**改写已经生成的这份报告 ——
     * 这正是它不做成「观测器里最近记到的那份」的原因，见类 KDoc 里 U30 第一轮 B 审 P1 那段。
     */
    val sectionCache: SectionCacheStats? = null,
    val timestamp: Long,
)

/**
 * 本会话的提供商侧命中率**累计**读数（看板 U30）。
 *
 * 口径必须写成按 token 加权（`sum(cacheRead) / sum(prompt)`），**不是逐发比值的平均**：
 * 平均会被几发只带少量输入的请求带偏（一发 100 token 的 10% 与一发 5 万 token 的 90%
 * 在平均里各占一半，在真实花费里根本不是）。
 *
 * **计数单位是「发」，不是「回合」**：一个回合最多 `maxSteps` 发请求，每发响应回来时
 * `streamAssistant` 调一次 [recordUsage]（第三轮 B 审 P2：字段原名带 `Turns` 就是这个单位说反了，
 * 已改名 `ratedRequests` / `unratedRequests`）。
 *
 * [unratedRequests] 是「**回了 usage、但那些数字凑不出比值**」（`input + cacheRead + cacheWrite <= 0`）
 * 的**发数**。**必须显形**：样本只在拿得到比值时落一条，否则图上那些点是**静默消失**的，
 * 用户会把「只剩两个点」读成「缓存只剩两个点的历史」。
 *
 * **本批评不到的那半边要写清**（第二轮 B 审 P2）：提供商**完全没有回 usage 事件**的那些发
 * （Gemini / OpenAI 那几条 `usage?.let { emit(...) }` 路径），以及请求失败 / 中途停止的那一发，
 * 走不到 [CacheInspector.recordUsage]，因此既不进 [ratedRequests] 也不进 [unratedRequests] —— 它们
 * 仍然只表现为「图上少点」。要把这半边也显形，得让「发出了几发请求」自己成为一个计数
 * （`record` 侧），本批未做，登记在交接 §11.3（第三轮 B 审判定：留在已知缺口，本批不加）。
 */
data class HitRateTally(
    /** 拿到命中率的**发数**（加权分母里的每一发都在这里）。 */
    val ratedRequests: Int,
    /** 回了 usage、但那些数字凑不出比值的**发数**（`input + cacheRead + cacheWrite <= 0`）。 */
    val unratedRequests: Int,
    /** 各发 `cacheRead` 之和。 */
    val cachedTokensSum: Long,
    /** 各发 `input + cacheRead + cacheWrite` 之和。 */
    val promptTokensSum: Long,
) {
    /** 加权累计命中率；一发可用的 usage 都没有时为 null（不能报 0%，那是「全没命中」）。 */
    val weightedRate: Double?
        get() = if (promptTokensSum <= 0L) null else cachedTokensSum.toDouble() / promptTokensSum
}

/**
 * 一次观测到的缓存命中率样本：用于把「最近若干发的命中率」画成趋势折线。
 *
 * 没有 `sessionId` 字段 —— 样本挂在**该会话自己那份** [CacheReport.samples] 上，会话身份由
 * 外层报告的键给出；元素里再存一份就是同一概念两个实现点（`AGENTS.md` §10.4），而且两份会漂。
 */
data class CacheSample(
    val model: String,
    /** 命中率 0..1。 */
    val rate: Double,
    val timestamp: Long,
)

/**
 * 缓存观测器：每次请求装配时记录分段快照，与同会话上一次装配比对（`previousBySession` 存的就是
 * 那份装配的 segments —— 一个回合里会装配好几次，所以比的是「上一次装配」而不是「上一回合」，
 * 第四轮增量复核 P3 把这句旧措辞改准），
 * 并通过 [latest] 暴露给面板。属于内核观测面，不是插件；插件只读它。
 *
 * [latest] 按**会话 id 索引**：每个会话各自持有最近一次观测，面板据此只显示**当前会话**
 * 的数据，而不是「最后一个发出请求的会话」的（后者会让多会话并发时面板张冠李戴）。
 *
 * 命中率趋势样本**每会话各留 [historyLimit] 条**（看板 U30 修的正是这条）：此前是一条进程
 * 共用的 `List` + `takeLast(60)`，而面板只筛当前会话 —— 多会话并发时别的会话把当前会话的点
 * 挤出去，用户看到的就是「缓存好像只剩最近两发的历史」。**累计、样本、段落读数三项都存在
 * 该会话那一份报告里**（[CacheReport.samples]），不再另立一张按会话的读数表：读数表要有第二份
 * 回收义务，而「会话已关闭却又迟到一个 usage」会把那条目悄悄重建出来，再没人删。
 * 观测器里剩下的那张按会话的表只有段落快照 [previousBySession]（本批之前就有的比对基线），
 * 它的回收与报告同在 [remove] 里，由变异 M8b 钉住。
 *
 * 三个数各有各的口径，混起来读就是错的。面板**只显后两个**：逐发比值不再单列一行
 * （负责人 2026-11-04 —— 「最近命中率 0%」既刺眼又最容易被当结论读，命中率留加权一个数就够），
 * 它只作为折线上的点出现：
 * - [CacheReport.samples]：**逐发**比值的序列（天生在两端跳：重写前缀那一发低、下一发几乎全命中），
 *   只进趋势折线；
 * - [CacheReport.hitTally]：本会话**按 token 加权**的累计（回答「一共省了多少」）—— 面板上那个命中率；
 * - [CacheReport.sectionCache]：**产生这一发请求的那次系统提示装配**的命中与归因
 *   （一个 token 都不省，省的是重算 —— 这正是 U19 第 ② 笔的效果读数）。
 *
 * **段落读数为什么走 [record] 的入参、而不是「观测器存最近一份」**：一个回合里装配会被调
 * 好几次（`TurnRunner.kt:213` 取 maxSteps、`:235` 取 model、`CompactionRunner.kt:83/89`
 * 估 token、`AgentSession.contextWindow()` 给界面取上下文窗口）。若这里只存「最近记到的那份」，
 * `TurnRunner.kt:235` 那次**在请求之后**的装配就会把当前报告改写成「全命中」，而那一发请求
 * 实际全未命中 —— 读数与事实正好相反（U30 第一轮 B 审 P1）。
 *
 * 全局可变状态四项（`AGENTS.md` §10.5）：
 * - **归属**：都按会话，不属于项目也不属于进程；
 * - **生命周期**：随会话，[record] / [recordUsage] 写入、[remove] 摘除；不落盘，进程重建从空开始；
 * - **清除时机**：会话关闭 / 删除 / 空闲卸载 → [remove]（由 `AppContainer.releaseCacheRecords`
 *   接线，与 `Diagnostics.remove` 同一处、同一时机）。**子智能体用临时实例**
 *   （`AppContainer.createSubAgentSession`）：它不进 `SessionRegistry`，没人调它的 [remove]；
 * - **并发语义**：全部 `@Synchronized`，读方只收集 [StateFlow] 快照，不碰内部表。
 */
class CacheInspector(private val historyLimit: Int = 60) {

    /** 各会话最近一次请求装配的观测记录；面板按当前会话 id 取用。 */
    private val _latest = MutableStateFlow<Map<String, CacheReport>>(emptyMap())
    val latest: StateFlow<Map<String, CacheReport>> = _latest.asStateFlow()

    private val previousBySession = HashMap<String, List<RequestSegment>>()

    /**
     * 记一次请求装配。
     *
     * [sectionCache] 必须是**这次请求所依据的那次提示装配**的读数（由调用方从
     * [AgentAssembly.sectionCache] 取），不是「观测器最近记到的」—— 理由见类 KDoc。
     * 不传就是 null，**不沿用上一发**：沿用的话就是把别的装配的数字挂到这一发请求上。
     */
    @Synchronized
    fun record(
        sessionId: String,
        model: String,
        assembly: RequestAssembly,
        sectionCache: SectionCacheStats? = null,
    ) {
        val previous = previousBySession[sessionId]
        val changes = assembly.segments.associate { seg ->
            seg.kind to diffSegment(previous?.firstOrNull { it.kind == seg.kind }, seg)
        }
        val appended = LinkedHashMap<SegmentKind, AppendedSlice>()
        assembly.segments.forEach { seg ->
            if (changes[seg.kind] != SegmentChange.APPENDED) return@forEach
            val prev = previous?.firstOrNull { it.kind == seg.kind } ?: return@forEach
            // 只截取尾部预算：先算起点再 substring，新增很长时也不会复制整段。
            // 历史段才截；另三段有界，整段给出（预算规则在 SegmentKind.displayBudget，
            // 与详情页整段共用同一个判据）。
            val limit = seg.kind.displayBudget(APPENDED_LIMIT)
            val begin = maxOf(prev.canonical.length, seg.canonical.length - limit)
            val tail = seg.canonical.substring(begin)
            appended[seg.kind] = AppendedSlice(
                bytes = seg.bytes - prev.bytes,
                text = readableTail(tail),
                raw = escapeControls(tail).take(limit),
            )
        }
        previousBySession[sessionId] = assembly.segments
        // 新一轮装配时沿用累计读数与样本：usage 在本轮响应结束后才回来，
        // 不能因为「刚发出请求」就让面板上的提供商口径闪成空值，也不能让趋势图断成一格。
        val last = _latest.value[sessionId]
        val report = CacheReport(
            sessionId = sessionId,
            model = model,
            segments = assembly.segments,
            changes = changes,
            appended = appended,
            hitTally = last?.hitTally,
            samples = last?.samples ?: emptyList(),
            sectionCache = sectionCache,
            timestamp = System.currentTimeMillis(),
        )
        _latest.update { it + (sessionId to report) }
    }

    /**
     * 记一发响应带回来的 usage。累计只写在**该会话那份报告**上（`copy` 出来就是新的），
     * 没有并行表可漏删；报告不在（会话已回收、这发是迟到的）就整条不记。
     */
    @Synchronized
    fun recordUsage(sessionId: String, usage: Usage) {
        val rate = usage.cacheHitRate
        // 提供商口径与对话页底部圆环一致：prompt = input + cacheRead + cacheWrite。
        val promptTokens = usage.input + usage.cacheRead + usage.cacheWrite
        _latest.update { map ->
            val report = map[sessionId] ?: return@update map
            val base = report.hitTally ?: HitRateTally(0, 0, 0L, 0L)
            val tally = base.copy(
                ratedRequests = base.ratedRequests + if (rate == null) 0 else 1,
                // 这发拿不到比值（提供商未回 cache 字段）。**必须计数**：样本只收拿得到的那些，
                // 于是图上会少点而面板一句不说，用户就把「少点」读成「缓存只有这么短的历史」。
                unratedRequests = base.unratedRequests + if (rate == null) 1 else 0,
                cachedTokensSum = base.cachedTokensSum + usage.cacheRead,
                promptTokensSum = base.promptTokensSum + promptTokens,
            )
            val samples = if (rate == null) {
                report.samples
            } else {
                (report.samples + CacheSample(report.model, rate, System.currentTimeMillis()))
                    .takeLast(historyLimit)
            }
            map + (sessionId to report.copy(
                hitTally = tally,
                samples = samples,
            ))
        }
    }

    @Synchronized
    fun reset() {
        previousBySession.clear()
        _latest.value = emptyMap()
    }

    /**
     * 丢弃某会话的观测记录（会话关闭 / 删除 / 空闲卸载时调用）。
     *
     * [latest] 与 [previousBySession] 都按会话保留整段 `segments` 快照；会话页进入时
     * `getOrPut`、离开时不关闭，若不显式回收，内存会随「访问过的会话数 × 历史长度」增长。
     * 趋势样本与加权累计（U30 新增的读数）都挂在那份报告上，**跟着它一起走** —— 回收路径
     * 与改动前同一条。旧注释写的「history 有全局上限、不在此清理」靠的是那条进程共用的
     * `takeLast(60)`，它连同那个共用池一起失效（见删除清单，交接 §3.3）。
     */
    @Synchronized
    fun remove(sessionId: String) {
        previousBySession.remove(sessionId)
        _latest.update { it - sessionId }
    }
}

/**
 * 面板「本次新增」的展示预算（字符），只卡随回合无限增长的历史段：
 * 详情页排的是引文，额度比整段（[RequestSegment.DISPLAY_LIMIT]）更小。
 * 另三段有界、整段给出 —— 判据在 [SegmentKind.displayBudget]，这里只填额度。
 */
private const val APPENDED_LIMIT = 4_000

/**
 * 把 canonical 的分隔符换成可读形式：`\u0000` 是字段分隔、`\u0001` 是部件分隔、
 * `\u0002` 是消息分隔；其余不可打印字符直接丢掉，面板里不再出现「乱码」。
 */
private fun readableTail(raw: String): String = buildString(raw.length) {
    raw.forEach { c ->
        when (c) {
            '\u0000' -> append(' ')
            '\u0001', '\u0002' -> append('\n')
            else -> if (c >= ' ' || c == '\n' || c == '\r' || c == '\t') append(c)
        }
    }
}.trim()
