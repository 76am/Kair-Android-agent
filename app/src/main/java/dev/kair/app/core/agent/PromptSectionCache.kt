package dev.kair.app.core.agent

import dev.kair.app.core.tools.Tool
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统提示段落缓存 —— [SystemPromptBuilder] 的**存储侧**（内核，进程级）。
 *
 * 本类只管一件事：「输入没变就复用上次产出的正文」。什么时候计算、谁的异常要隔离、
 * 缺席怎么记诊断，都留在 [SystemPromptBuilder]；覆写来源（`overridesProvider`）也从那里
 * 取好了再传进来 —— 存储层不读全局设置，否则「命中条件」会多出一个看不见的输入。
 *
 * 命中条件全等：**贡献点实例**、cwd、addendum、工具列表（按元素引用比对）、覆写与项目 id；
 * **workspace 走桶键**（见下面的「失效按作用域」），槽位里不再比第二次。
 *
 * - 贡献点实例用引用相等：内置段落是单例，稳定命中；插件被重装 / 换实现时是新实例，
 *   自动失效并重算，不会把旧正文当新内容缓存下来。
 * - 工具列表按元素引用比对：`Tool` 是长期存活的单例，宿主每回合只是重新过滤出子列表，
 *   引用相等即判定未变化。
 *
 * 贡献点的 [SystemPromptContributor.section] 契约要求确定性，因此按输入缓存不会让内容失真。
 *
 * **失效按作用域（U19 第 ② 笔，本体在这）**：桶键是 `(作用域, 贡献点 id)`，作用域取装配里
 * 已有的维度 —— `SystemPromptContext.workspace`（宿主工作区路径），**不新增输入**。
 * 此前 [clear] 是全表清：一个会话每回合的刷新会把别的工作区的段落一起打掉，只剩命中率损失
 * （输入不同就不可能读到别人的正文）。分作用域后那些会话当回合照旧命中。
 * 代数也一起按作用域走（见 [generation]）—— 只清桶不管代数等于只修了一半：
 * 「别的会话失效 → 我的写回被当作过期丢弃」这条隐性串扰会原样留着。
 * 同一工作区里的多个会话仍然共享失效（同一个桶），这是刻意的：它们读的就是同一份外部状态。
 *
 * **workspace 这一维只有桶键一个实现点**（`AGENTS.md` §10.4）。① 之前它在谓词里，分桶之后
 * `Entry.workspace` 由同一个 `context.workspace` 写入、[find] 又只在同键桶内比 —— 那条比较
 * 恒真（评审 V3a 实测：只删谓词里的 workspace 比较，五条用例全绿）。留着一个永不到场的分支
 * 就是给下一个人留「两套」，所以删掉；代价是「隔离」这件事从此**只由桶键守**，
 * 单点由用例 `different workspaces never read the same cached section` 钉（把桶键与代数键一起
 * 写死成常量必须红在它自己的「读到别人的正文」那一行，见交接 §9.2 的 V3′）。
 *
 * **null 不入缓存**：null 往往表示「这次读失败 / 暂时没有」，其成因可能落在缓存键之外
 * （如工作区文件被容器以 root 创建、应用进程 EACCES）。把 null 缓存下来会把一次瞬时失败
 * 冻结成永久缺席，且同工作区的新会话还会命中同一条缓存。null 从不写入，下次请求重算即可；
 * [forget] 只补一件事 —— 撤销「本次计算窗口里别的会话刚按同一组输入塞进去的那条」（单线程下它
 * 必然空转，理由见它自己的注释），所以这个撤销**必须**在**活表**的 `computeIfPresent` 里完成，
 * **不能**先取桶再往里删：若那条正文是在桶被 [clear] 摘走**之后**由别的会话塞进新建的桶里的，
 * 手里拿着旧桶引用的这次撤销就落在孤儿上 —— 落空得无声无息。
 *
 * 全局可变状态四项（`AGENTS.md` §10.5）：
 *
 * - **归属**：进程级，不属于任何会话；但**失效单元是工作区作用域**，调用方只清自己那一份。
 *   作用域只出现在桶键上。
 * - **生命周期**：随进程；[remember] 在装配产出后写入，[find] 命中即复用。不落盘 ——
 *   正文可由贡献点重新产出，进程重建后自然为空，没有「恢复」这一说。
 *   计数器 [generations] 的条目数 = 本进程见过的不同工作区数（项目规模），与槽位数无关。
 *   **只增不删是写回校验的前提**：剪掉一条会让下一次读把它当成新作用域登记回 0，而某个正在
 *   计算的会话手里的代数可能正是 0 —— 于是「跨过那次失效的写回」会被接受，校验自己先松掉。
 *   真要剪得连「先 bump 再剪」的顺序一起改；为省几字节引入这个缝不值。
 * - **清除时机**：[clear] 带作用域（工具执行后 / 压缩后 —— 这两处手上有工作区路径，
 *   见 [SystemPromptBuilder.invalidate] 的调用点；**删项目时也走这条**，`AppContainer.deleteProject`，
 *   价值是 §10.5 的清除面：那个路径再也无人读，桶留在进程里就是纯占内存。它**不是**正确性防线 ——
 *   谓词含 `projectId`，认领到同一路径的新项目必然 miss）、
 *   [clear] 不带参数（改预设 / 注入覆写来源 / 文件编辑器保存 —— 前两者改的是跨工作区输入，
 *   第三处推不出受影响的作用域，判断写在 `invalidate(workspaceScope)` 的 KDoc 与交接 §8）、
 *   [forget]（本次产出为 null 时只清匹配的那条 —— 单线程下它必然空转，见它自己的注释）、
 *   槽位超过 [SLOTS_PER_ID] 时按插入序淘汰最旧的一条。
 * - **并发语义**：允许多会话并发装配。锁序固定：外层桶表 bin 锁 → 内层 id 表 bin 锁 →
 *   槽位队列监视器；对 [generations] 只在**不持有任何桶表锁**时写，持锁期间只做一次读
 *   （[ConcurrentHashMap.get] 不加锁）—— 不是怕成环（[clear] 的两步不嵌套，凑不出反向持锁），
 *   是**不在持锁期间往另一张表里写**：那条写会延长临界区，还把「锁序」从一句话变成取决于调用点。
 *   「别人在我计算期间失效了这个作用域」由 [generation] 兜住：写回前比对代数，不一致即丢弃，
 *   且比对与塞桶放在**同一次**外层 `compute` 里（否则会出现「比对通过后桶被清、
 *   旧正文又塞进新建的空桶」）。
 */
internal object PromptSectionCache {

    /**
     * 一个槽位 = 一份「输入 → 产出」。输入的全部维度都在这里**或**在桶键上（workspace），
     * 缺一个维度就是「把别的上下文的正文当成自己的」——那是正确性问题，不是命中率问题。
     */
    private class Entry(
        val contributor: SystemPromptContributor,
        val cwd: String,
        val addendum: String,
        val tools: List<Tool>,
        /** 用户覆写（停用 / 替换文案）。覆写变了就必须重算，否则改动不生效。 */
        val override: SystemPromptBuilder.SectionOverride?,
        /** 解析覆写用的项目 id（项目可绑定自己的身份预设）；不同项目不得共享槽位。 */
        val projectId: String?,
        val section: String,
    )

    /**
     * 每个 `(作用域, 贡献点 id)` 保留的上下文槽位数：主会话与子智能体（不同 `toolFilter`）、
     * 或同工作区里不同 cwd 的会话交替构建提示时，单槽位会让两边互相覆盖、每次切换都 miss。
     * 第 ② 笔把桶拆到作用域之后，这条上限不再被「所有工作区共用 4 格」挤占
     * （旧的多会话互挤见看板 U19）。
     *
     * `internal` 是给 `PromptCacheScopeTest` 读的：那条互挤用例要「比上限多一个作用域」，
     * 写死 6 的话这个常量提到 ≥6 就静默空转（评审 P3）。
     */
    internal const val SLOTS_PER_ID = 4

    /**
     * 两层桶：作用域（= `SystemPromptContext.workspace`，宿主工作区路径）→ 贡献点 id →
     * 槽位队列。分两层是为了让 [clear] 按作用域整桶摘掉时不用遍历全表。
     */
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, ArrayDeque<Entry>>>()

    /**
     * 每个作用域的失效次数。**只在读代数的路径上登记**（[generation]），于是「正在计算的
     * 会话」一定有条目 —— [clear] 不带作用域那条要能把它一起 bump 掉。
     */
    private val generations = ConcurrentHashMap<String, Long>()

    /**
     * 本次计算开始时的代数，交给 [remember] 在写回前比对。作用域第一次读即登记为 0。
     */
    fun generation(scope: String): Long = generations.computeIfAbsent(scope) { 0L }

    /**
     * 持锁路径上的只读探针：`remember` 是在拿着外层桶锁的，那时候对 [generations] 只能读不能写
     * （理由不是怕成环 —— [clear] 的两步不嵌套，凑不出反向持锁 —— 而是别在临界区里跨表写，
     * 见类 KDoc 的「并发语义」）。
     */
    private fun currentGeneration(scope: String): Long = generations[scope] ?: 0L

    /**
     * 任一槽位的输入与本次一致时返回它的正文，否则给出**归因**（= 调用方去计算）。
     *
     * 归因只有两类，而且是刻意只分两类（看板 U30 的观测面）：
     * - [SectionCacheMiss.NOTHING_TO_REUSE]：这个作用域下压根没有该贡献点的槽位。
     *   「本进程第一次为这个工作区装配」与「刚被 [clear] 摘走」在存储侧看不出区别
     *   （摘走之后桶就是不存在，冷启动之前桶也不存在），要分就得为「见过但没有」再养一份
     *   常驻登记表 —— 为一个读数的精度新增全局可变状态不值（`AGENTS.md` §10.5 的门槛）。
     *   冷启动每个工作区每进程只发生一次，它污染不了「失效带走」这个趋势。
     * - [SectionCacheMiss.INPUT_CHANGED]：槽位在，但没有一条与本次输入相等 —— 与失效无关，
     *   是 cwd / addendum / 工具列表 / 覆写 / 项目 id 真的变了（或实例被换掉）。
     *
     * 这两类的比例就是判断「方案 B（`cacheFingerprint`，取消每回合重算）值不值得动插件 ABI」
     * 的唯一数：大头落在 NOTHING_TO_REUSE 才有肉，落在 INPUT_CHANGED 说明重算是必要的。
     */
    fun find(
        contributor: SystemPromptContributor,
        context: SystemPromptContext,
        override: SystemPromptBuilder.SectionOverride?,
        projectId: String?,
    ): SectionLookup {
        val slots = cache[context.workspace]?.get(contributor.id)
            ?: return SectionLookup(null, SectionCacheMiss.NOTHING_TO_REUSE)
        synchronized(slots) {
            val matched = slots.firstOrNull { it.matches(contributor, context, override, projectId) }
            return if (matched == null) {
                SectionLookup(null, SectionCacheMiss.INPUT_CHANGED)
            } else {
                SectionLookup(matched.section, SectionCacheMiss.NONE)
            }
        }
    }

    /** 写入一条产出；计算期间这个作用域失效过就丢弃，让下一回合重新计算。 */
    fun remember(
        contributor: SystemPromptContributor,
        context: SystemPromptContext,
        produced: String,
        override: SystemPromptBuilder.SectionOverride?,
        projectId: String?,
        generationAtStart: Long,
    ) {
        val scope = context.workspace
        cache.compute(scope) { _, existingById ->
            if (currentGeneration(scope) != generationAtStart) return@compute existingById
            val byId = existingById ?: ConcurrentHashMap()
            byId.compute(contributor.id) { _, existing ->
                val slots = existing ?: ArrayDeque()
                synchronized(slots) {
                    slots.removeAll { it.matches(contributor, context, override, projectId) }
                    slots.addLast(
                        Entry(
                            contributor, context.cwd, context.addendum,
                            ArrayList(context.tools), override, projectId, produced,
                        ),
                    )
                    while (slots.size > SLOTS_PER_ID) slots.removeFirst()
                }
                slots
            }
            byId
        }
    }

    /**
     * 只清掉与本次输入匹配的槽位，其它上下文的有效缓存保留。
     *
     * 先说清它**实际**管到什么：调用点只在「本次产出为 null」之后走到这里，而 null 的前提是
     * [find] 已经 miss —— 单线程下没有匹配槽位，这一条必然空转。前提写全一点：从 `find` miss
     * 到这里之间，**本线程**不会往缓存里写；唯一能把这个前提拆掉的形态是贡献点在 `section()`
     * 里再触发一次装配（现仓没有这种贡献点，插件契约也不鼓励）。
     * 它真正起作用的是并发窗口：同一作用域里别的会话刚按同一组输入算出正文塞了进来，
     * 而我这次读到的是缺席 / 失败，那就该把那条撤掉，让下一回合重新读。
     *
     * 所以整个动作必须在**活表**的 `computeIfPresent` 里完成 —— 先取桶再往桶里删的话，若那条正文
     * 是在桶被 [clear] 摘走**之后**由别的会话塞进新建桶的，手里的旧桶引用就撤不到它，落空得无声
     * 无息。顺带两个好处：锁序与 [remember] 同形（外层持锁再动内层），读的人也不必去推
     * 「这里到底可能拿着孤儿引用吗」。
     */
    fun forget(
        contributor: SystemPromptContributor,
        context: SystemPromptContext,
        override: SystemPromptBuilder.SectionOverride?,
        projectId: String?,
    ) {
        cache.computeIfPresent(context.workspace) { _, byId ->
            byId.computeIfPresent(contributor.id) { _, slots ->
                synchronized(slots) {
                    slots.removeAll { it.matches(contributor, context, override, projectId) }
                    if (slots.isEmpty()) null else slots
                }
            }
            byId
        }
    }

    /** 失效一个作用域：只清这个工作区的桶，别的工作区当回合仍命中。 */
    fun clear(scope: String) {
        // 先递增再摘桶：正在计算的会话写回时会看到新代数并丢弃旧结果。
        generations.compute(scope) { _, current -> (current ?: 0L) + 1L }
        cache.remove(scope)
    }

    /** 失效所有作用域：改的是跨工作区的输入（预设 / 覆写来源）或作用域推不出来时走这条。 */
    fun clear() {
        generations.replaceAll { _, current -> current + 1L }
        cache.clear()
    }

    private fun Entry.matches(
        contributor: SystemPromptContributor,
        context: SystemPromptContext,
        override: SystemPromptBuilder.SectionOverride?,
        projectId: String?,
    ): Boolean =
        this.contributor === contributor &&
            cwd == context.cwd &&
            addendum == context.addendum &&
            this.override == override &&
            this.projectId == projectId &&
            sameTools(tools, context.tools)

    /** 工具列表是否逐位引用相等（未增删 / 未换实现）。 */
    private fun sameTools(a: List<Tool>, b: List<Tool>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }
}

/** [PromptSectionCache.find] 未命中的归因；命中时为 [NONE]。 */
enum class SectionCacheMiss {
    /** 读到了缓存正文。 */
    NONE,

    /** 这个作用域下没有该贡献点的任何槽位：冷启动，或刚被失效摘走。 */
    NOTHING_TO_REUSE,

    /** 槽位在，但没有一条与本次输入相等：输入真的变了（含贡献点实例被换掉）。 */
    INPUT_CHANGED,
}

/**
 * 一次查找的结果。[section] 非 null 当且仅当 [miss] 是 [SectionCacheMiss.NONE] ——
 * 空串也算命中（贡献点可以合法产出空白，见 [SystemPromptBuilder.buildDetailed] 的缺席判定），
 * 所以调用方判「命中」必须看 [miss]，不能看 [section] 是否为空。
 */
data class SectionLookup(val section: String?, val miss: SectionCacheMiss) {
    val hit: Boolean get() = miss == SectionCacheMiss.NONE
}

/**
 * **一次装配**的段落缓存读数（观测面，看板 U30）。
 *
 * 它是装配的局部结果，随 [SystemPromptBuilder.PromptAssembly] 产生与销毁，**不是**常驻计数器：
 * 没有新增全局可变状态，所以 `AGENTS.md` §10.5 的归属 / 生命周期 / 清除时机 / 并发语义四项
 * 在这里等价于「这次调用返回之后就没人再拿着它」。面板要留一份最近读数，那份常驻的归宿主管
 * 是 [CacheReport.sectionCache]（由 `CacheInspector.record` 随它自己那次装配的产物一起记下，
 * 随会话回收）。
 *
 * 数字**不参与正文字节**，也不改变任何一位请求内容 —— 与 [SectionIssue] 同一条旁路。
 */
data class SectionCacheStats(
    /**
     * 本次遍历的贡献点总数（= 分母，含缺席与抛异常的段落）。
     *
     * 「算了分母」不等于「进了正文」：合法产出空白的段落照样被缓存、照样算命中，缺席与抛异常的
     * 段落也各计一份分母（它们走的是 [SectionIssue] 那条出口）。所以 [hits] + [misses] 恒等于
     * 本数，而**不等于**正文里段落条数 —— 面板两个数并排放时别把它们当同一件事读。
     */
    val sections: Int = 0,
    val hits: Int = 0,
    val missesNothingToReuse: Int = 0,
    val missesInputChanged: Int = 0,
) {
    /** 未命中总数：两类归因之和。 */
    val misses: Int get() = missesNothingToReuse + missesInputChanged
}
