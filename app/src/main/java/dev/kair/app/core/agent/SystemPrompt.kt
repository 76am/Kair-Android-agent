package dev.kair.app.core.agent

import dev.kair.app.core.storage.Delivery

/**
 * 系统提示装配器（内核）：把有序贡献点拼成最终正文，本身不含任何具体段落。
 *
 * 段落顺序由 [SystemPromptContributor.order] 决定，段间用空行分隔。
 * 同一逻辑状态必须产出同一字符串（缓存安全），见 `docs/架构与内核边界.md`。
 */
object SystemPromptBuilder {

    /**
     * 提示段的**用户覆写**：用自定义正文替换贡献点的产出。
     *
     * 为什么需要：身份写在 [PreambleContributor] 里，而此前用户只能追加（Addendum 段）
     * 不能替换 —— 想把 Kair 当别的东西用（例如"陪聊伙伴"）就会被开场白里的
     * "coding assistant" 锚死，模型坚持自己只会编程。
     *
     * 语义：[text] 非空 → 用它替代贡献点的产出（贡献点不再被调用，省一次计算）；
     * 空串 / null 视为"没有覆写"，用默认文案。不提供「整段删掉」：缺段的后果往往
     * 静默且难恢复，用户真正要的是换身份。
     *
     * **缓存**：覆写值是段落缓存键的一部分，改了立刻重算；覆写本身是确定性输入，
     * 因此不影响前缀契约。
     */
    data class SectionOverride(val text: String? = null)

    /**
     * **不可覆写**的段落，由 [BuiltinPromptSections] 的 `locked` 标记派生（不再单独维护）。
     *
     * 判据不是「重要性」而是「改错的后果是否静默且不可恢复」：见注册表里每个
     * `Entry` 的注释。
     *
     * 想改身份请在「提示词预设」面板给项目绑定预设。这条拦截在**内核**里做
     * （不在 UI 里），因为绑定是持久化的，UI 之外还有插件与未来的入口：
     * 能改 UI 就能绕过，等于没锁。
     */
    val LockedSectionIds: Set<String> = BuiltinPromptSections.lockedIds

    /**
     * 覆写来源。第二个参数是**项目 id**（可空）：项目绑定自己的身份预设；
     * `null` 表示没有项目上下文（子智能体兜底等），使用内置文案。
     */
    @Volatile
    private var overridesProvider: (String, String?) -> SectionOverride? = { _, _ -> null }

    /** 宿主注入覆写来源（读设置）。注入时清缓存：旧正文可能来自覆写前的状态。 */
    fun setOverridesProvider(provider: (String, String?) -> SectionOverride?) {
        overridesProvider = provider
        invalidate()
    }

    /**
     * 内置段落的**默认文案**（面板里「查看内置开场白」用）。
     *
     * 注意这是"尽力而为"：`cwd` 段依赖真实路径，`tools` 段依赖当前工具列表，
     * 这里用一个空上下文算出骨架。工具清单这类动态段落返回空串。
     */
    fun defaultSectionText(id: String): String {
        val contributor = BuiltinPromptSections.contributors.firstOrNull { it.id == id } ?: return ""
        val ctx = SystemPromptContext(cwd = "", tools = emptyList(), addendum = "", workspace = "")
        return runCatching { contributor.section(ctx) }.getOrNull().orEmpty()
    }

    /** 某段落当前生效的覆写（设置页展示 / 测试用）。 */
    fun overrideOf(id: String, projectId: String? = null): SectionOverride? =
        overridesProvider(id, projectId)

    /**
     * 一次装配的结果：正文 + 诊断 + 段落缓存读数。
     *
     * [issues] 与 [sectionCache] **都不参与正文**，也不改变任何一位输出字节 —— 前者说明「哪些注册过的
     * 段落这次没进来、为什么」，后者说明「这次有多少段落复用上了上次产出的正文」。
     * 宿主拿它们填诊断与缓存面板，面板只读。
     */
    class PromptAssembly(
        val text: String,
        val issues: List<SectionIssue>,
        val sectionCache: SectionCacheStats,
    )

    /**
     * 拼接各贡献段；跳过 null / 空串，段间以空行分隔。
     *
     * [projectId] 用于解析项目级身份预设；`null` 表示没有项目上下文（走全局设置）。
     */
    fun build(
        context: SystemPromptContext,
        contributors: List<SystemPromptContributor>,
        projectId: String? = null,
    ): String = buildDetailed(context, contributors, projectId).text

    /**
     * 与 [build] 同一条装配路径，另外带回诊断：哪些段落缺席、哪些抛了异常。
     *
     * 判据是「注册了就应当有产出」：遍历**传入的全部贡献点**，正文为空即记一条缺席。
     * 这也是「段落缺席自检」的落点 —— 注册清单与装配结果不一致时，只有诊断能看见。
     *
     * **例外：内置段落的空白不算缺席**（与 [SectionIssue.Kind.FAILED] 的边界对齐 ——
     * 内置抛异常直接冒泡、同样不进 `issues`）。内置段落有契约允许的合法空白
     * （[AddendumContributor]：用户没填「附加系统指令」就整段不出现），若记成缺席，
     * 默认配置下每次装配都会产出一条误报，诊断面板的「无异常」将永远不可达。
     * 内置段落的静默缺席属于内核逻辑 bug，由单测守，不该占用面向第三方代码的诊断面。
     */
    fun buildDetailed(
        context: SystemPromptContext,
        contributors: List<SystemPromptContributor>,
        projectId: String? = null,
    ): PromptAssembly {
        val issues = ArrayList<SectionIssue>()
        val parts = ArrayList<String>()
        var hits = 0
        var nothingToReuse = 0
        var inputChanged = 0
        var sections = 0
        contributors.sortedBy { it.order }.forEach { contributor ->
            sections++
            val before = issues.size
            val outcome = section(contributor, context, projectId, issues)
            when (outcome.miss) {
                SectionCacheMiss.NONE -> hits++
                SectionCacheMiss.NOTHING_TO_REUSE -> nothingToReuse++
                SectionCacheMiss.INPUT_CHANGED -> inputChanged++
            }
            val text = outcome.text?.trim()
            if (text.isNullOrEmpty()) {
                // 抛异常时 section() 已经记过一条，这里不再重复记缺席。
                if (issues.size == before && !BuiltinPromptSections.isBuiltin(contributor)) {
                    issues += SectionIssue(contributor.id, SectionIssue.Kind.ABSENT)
                }
            } else {
                parts += text
            }
        }
        return PromptAssembly(
            parts.joinToString("\n\n"),
            issues,
            SectionCacheStats(sections, hits, nothingToReuse, inputChanged),
        )
    }

    /** 某段的装配产出：正文（可空 = 缺席 / 失败）+ 这次缓存查找的归因。 */
    private class SectionOutcome(val text: String?, val miss: SectionCacheMiss)

    /** 取某段的正文：任一槽位的输入与本次一致时直接复用，否则调用贡献点重算并更新缓存。 */
    private fun section(
        contributor: SystemPromptContributor,
        context: SystemPromptContext,
        projectId: String?,
        issues: MutableList<SectionIssue>,
    ): SectionOutcome {
        // 代数必须在读任何输入**之前**取：`generation()` 顺带把本作用域登记进计数器，
        // 于是「取完代数之后才发生的那次失效」一定能把这条写回丢掉。反过来（先读覆写再读
        // 代数）会留一个窗口：失效正好落在两次读之间，这条就用新代数写回了旧输入算出的正文，
        // 而那次失效对这段而言等于没发生 —— 第 ② 笔把代数改成按作用域时顺手收掉的。
        val generationAtStart = PromptSectionCache.generation(context.workspace)
        // 锁定段落一律忽略覆写：绑定是持久化的，只靠 UI 拦不住。
        val override = overridesProvider(contributor.id, projectId)
            ?.takeIf { contributor.id !in LockedSectionIds }
        val lookup = PromptSectionCache.find(contributor, context, override, projectId)
        lookup.section?.let { return SectionOutcome(it, lookup.miss) }
        // 有覆写文案就不调用贡献点：用户既然整段替换了，贡献点的计算（可能读文件）属于白费。
        val compute = { override?.text?.takeIf { it.isNotBlank() } ?: contributor.section(context) }
        val produced = if (BuiltinPromptSections.isBuiltin(contributor)) {
            // 内置段落**不隔离**：它们抛异常是内核 bug，必须让当回合响亮地失败，
            // 而不是把「整段提示悄悄少了一节」变成一个更难查的静默缺陷。
            compute()
        } else {
            // 第三方贡献点（插件 / 读盘技能）：异常只能让「这一段缺席」，不能把整段
            // 系统提示构造拖垮 —— 装配每回合都会跑，异常若冒泡则该会话之后每回合都是 Error。
            // 隔离与可见必须成对：失败按 null 处理，同时留下带 id 与异常类型的诊断。
            runCatching { compute() }.getOrElse { e ->
                issues += SectionIssue(contributor.id, SectionIssue.Kind.FAILED, e.javaClass.simpleName)
                null
            }
        }
        if (produced == null) {
            // 失败/缺席不缓存：见 PromptSectionCache 的 KDoc，避免一次读失败被冻结成永久无段落。
            PromptSectionCache.forget(contributor, context, override, projectId)
            return SectionOutcome(null, lookup.miss)
        }
        PromptSectionCache.remember(contributor, context, produced, override, projectId, generationAtStart)
        return SectionOutcome(produced, lookup.miss)
    }

    /**
     * 清空段落缓存，强制下一回合重新调用各贡献点。
     *
     * 命中条件只覆盖「贡献点实例 / cwd / workspace / addendum / 工具列表」；贡献点若还依赖
     * 这些之外的外部状态（如记忆插件读工作区里的 `.kair/memory.md`），其变化不会
     * 让缓存失效 —— 段落会被冻结到某项输入变化为止。因此需要在这些「外部状态可能
     * 已变」的时机主动清缓存，让贡献点重新读取：
     * - 工具执行之后（工具可能写工作区文件，见 `TurnRunner.runLoop`；可用设置项关闭）；
     * - 文件编辑器保存之后（用户手动改了插件声明的文件）；
     * - 压缩历史（历史段本就要重写，此时刷新不额外增加缓存损失）。
     *
     * 没有执行工具的普通回合**不要**调用 —— 那会无谓地丢掉缓存、放大每回合的重复构造。
     *
     * 失效有两个粒度（U19 第 ② 笔）：带工作区路径的 [invalidate] 只清那个作用域的桶，
     * 别的会话当回合照旧命中；不带参数的这条清全表。分工见两个重载各自的注释。
     */
    fun invalidate() = PromptSectionCache.clear()

    /**
     * 只失效一个**工作区作用域**（= 装配时的 `SystemPromptContext.workspace`，宿主真实路径）。
     *
     * 用它的条件只有一个：调用点手上有「这次改动落在哪个工作区」。工具执行后与压缩后符合
     * （两处都拿得到 `host.workspace`），它们要刷新的外部状态本来就在被改的那个目录里；
     * 反例是「文件编辑器保存」—— 那里只有被存盘文件的绝对路径，推不出哪些会话的段落读它，
     * 于是走全局那条：清多了只丢一次命中率，清少了是旧正文一直留着，而用户存盘正是为了让它
     * 立刻生效（判断过程与代价登记在 `docs/handoff/维护-提示词段落缓存.md` §8）。
     */
    fun invalidate(workspaceScope: String) = PromptSectionCache.clear(workspaceScope)
}

// ---- 内置段落（由内置插件 `dev.kair.plugin.systemprompt` 注册） --------------------

/** 开场白，声明助手身份。 */
object PreambleContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.preamble"
    override val order = 0

    override fun section(ctx: SystemPromptContext): String =
        "You are an expert coding assistant operating inside Kair on Android, a coding agent harness. " +
            "You help users by reading files, executing commands, editing code, and writing new files."
}

/**
 * 交付路径：用户需要查看的文件必须写到共享目录，而不是只留在工作区里。
 *
 * 与身份段分开的理由：开场白可被「提示词预设」整段替换（纯聊天项目会换成别的身份），
 * 但「用户能拿到产出」是工具行为契约，不能随身份一起被替换掉，因此归入锁定段。
 */
object DeliveryContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.delivery"
    override val order = 2

    override fun section(ctx: SystemPromptContext): String =
        "Output files the user needs to see to ${Delivery.PATH}/ (shared path reachable from Android)."
}

/**
 * Kair 自身能力的自述：面板 / 可选依赖 / 插件等**只存在于 UI 侧**的信息。
 *
 * 工具清单（[ToolsContributor]）只说「有什么工具」，说不出「为什么某个工具没有」
 * 或「某个能力得由用户去面板里开」—— agent 缺了这段就会把 `container_shell` 缺席当成故障、
 * 反复试错，也想不到容器里起的服务能被浏览器打开。正文为常量，缓存前缀不受影响。
 */
object CapabilitiesContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.capabilities"
    override val order = 5

    override fun section(ctx: SystemPromptContext): String = """
        <kair>
        Kair runs on the user's Android device. Some capabilities live in the UI, not in your tool list:

        - Linux workspace: an Ubuntu container (proot) managed in the "Linux 工作区" panel. `container_shell` is registered only once its rootfs is ready — until then use `android_shell`, and ask the user to install it. The workspace parent is bind-mounted at `/projects`, so your project is a real subdirectory there: the host path and `/projects/<dir>` are the same files (`read` / `write` / `edit` / `ls` / `grep` / `find` take host paths, `container_shell` sees container paths). Other projects are visible under `/projects` — leave them alone. Host paths like `/data/user/0/<pkg>/...` do not exist inside the container; only `/projects`, `/trash` and `/sdcard` are mounted. `android_shell` is the Android device shell (toybox), not the container.
        - Persistent session: the "终端" plugin keeps one long-lived container process with its mounts alive across turns. It shares the app's network namespace, so services it starts are reachable from the device browser at http://127.0.0.1:<port>.
        - Graphical desktop (optional): the user starts an X11 desktop from the "图形桌面" panel; it owns `DISPLAY=:1` — never start or stop that display yourself, and never put a second server on `:1`. `scrot` is installed with it: to look at it, run `DISPLAY=:1 scrot shot.png` in the project directory through `container_shell`, then open the file with `read_image` using its host path. If it cannot open the display, ask the user to start the desktop. For headless UI debugging without the panel you may start a temporary `Xvfb :2` through `background_task` and stop it when done. `device_screen` captures the Android screen, not the container desktop.
        - Shared trash: files removed from a workspace go to one trash outside every workspace (`/trash`, also `${'$'}KAIR_TRASH`). Never create a trash directory inside a workspace.
        - App-private paths (`.kair/`, the trash, plugin data) belong to the app: use `read` / `write` / `edit` for them, not shell redirection.
        - Skills load only from plugin-declared roots: `plugins/<id>/skills/<name>/SKILL.md` plus `"skills": "skills"` in that plugin's manifest, then `install_plugin`. A `skills/` folder in the repo is never scanned.
        - Plugins and MCP: users manage plugins from the plugins screen; `install_plugin` / `uninstall_plugin` work from a workspace directory, and disabling one retracts its tools. MCP servers are managed with `mcp_manage` / `mcp_list`, tools named `mcp__<server>__<tool>`; HTTP/SSE servers authenticate with headers (no OAuth), stdio servers need the Linux workspace ready. Prefer an existing MCP server over writing a new plugin.
        - Some calls (writes, deletes, commands) pause for the user's approval — that is expected, not a failure. Third-party plugin tools without a category always pause.
        </kair>
    """.trimIndent()
}

/** 可用工具清单；只列出带 promptSnippet 的工具。 */
object ToolsContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.tools"
    override val order = 10

    override fun section(ctx: SystemPromptContext): String {
        val visible = ctx.tools.filter { it.promptSnippet.isNotBlank() }
        val listing = if (visible.isEmpty()) {
            "(none)"
        } else {
            visible.joinToString("\n") { "- ${it.name}: ${it.promptSnippet}" }
        }
        return "<tools>\n$listing\n</tools>"
    }
}

/** 工具自带守则去重后的行为规则，外加四条固定规则。 */
object RulesContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.rules"
    override val order = 20

    override fun section(ctx: SystemPromptContext): String {
        val rules = LinkedHashSet<String>()
        ctx.tools.forEach { t -> t.promptGuidelines.forEach { rules.add(it.trim()) } }
        rules.add("Be concise in your responses")
        rules.add("Show file paths clearly when working with files")
        rules.add(
            "Messages wrapped in <system-note> are generated by Kair itself (dates, toolset changes, " +
                "background task notices), not typed by the user",
        )
        // 手机是用户的私人设备：后台、前台点击、截图都算操控。桌面环境在容器里，可以自由操作。
        rules.add(
            "Never control the user's phone unless the user explicitly asked for it: no switching apps or " +
                "sending it to the background, no tapping / swiping the screen, and no device screenshots. " +
                "The graphical desktop inside the Linux container may be controlled freely.",
        )
        return "<rules>\n" + rules.filter { it.isNotEmpty() }.joinToString("\n") { "- $it" } + "\n</rules>"
    }
}

/** 用户在设置里追加的补充说明；为空则整段不贡献。 */
object AddendumContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.addendum"
    override val order = 30

    override fun section(ctx: SystemPromptContext): String {
        if (ctx.addendum.isBlank()) return ""
        return "<addendum>\n${ctx.addendum.trim()}\n</addendum>"
    }
}

/**
 * 当前工作目录。
 *
 * 同时给出两个路径，避免模型把容器内路径当成宿主可见的工作区：
 * - [SystemPromptContext.cwd]：命令**实际执行处**。容器生效时是容器内的
 *   `/projects/<目录名>`，App 进程里不存在这个路径（只存在于容器内）。
 * - [SystemPromptContext.workspace]：宿主侧**真实**工作区路径，App 进程可直接读写。
 *
 * 两者只在容器生效时不同（见 `SessionAssembly.agentSettings` 的解析，U31 ③-a 搬出）。
 */
object CwdContributor : SystemPromptContributor {
    override val id = "dev.kair.prompt.cwd"
    override val order = 40

    override fun section(ctx: SystemPromptContext): String {
        if (ctx.workspace == ctx.cwd) return "<cwd>\n${ctx.cwd}\n</cwd>"
        // 两段路径紧挨着给出映射规则：模型是看着 <workspace> 的宿主绝对路径去 container_shell 里拼路径的
        // （真机实测：wc 找 .kair/memory.md → No such file → find / 全盘扫吃满 300s 超时）。
        // 警告必须贴着错误发生的数据源，而不是放在几百 token 之外的能力段里。
        return "<workspace>\n${ctx.workspace}\n</workspace>\n\n" +
            "Both are the same directory: `<workspace>/x` is the host path and `<cwd>/x` is the " +
            "container path (what container_shell sees). File tools (read / write / edit / ls / grep / " +
            "find / read_image) accept both — `/projects/...` is mapped back to the host workspace. " +
            "Never pass the host path to container_shell — only /projects, /sdcard and /trash are mounted; " +
            "strip the <workspace> prefix and re-attach it under <cwd>.\n" +
            "<cwd>\n${ctx.cwd}\n</cwd>"
    }
}

/**
 * 内置段落注册表：**唯一来源**。
 *
 * 此前「注册进系统提示」「禁止覆写」「面板内置文案」是三份需要手动对齐的清单，漏一处
 * 不报错、只让段落静默缺席（交付路径段曾加进前两者却漏了内置插件里的
 * `ctx.systemPrompt(...)`，线上模型只能自己猜 `/sdcard/Download/`）。现在三者都从
 * 这里派生，新增段落只需在 [entries] 加一行。
 */
object BuiltinPromptSections {

    /**
     * @param locked 禁止被「提示词预设」覆写。判据不是「重要性」，而是「改错的后果是否
     *   静默且不可恢复」——理由随条目写在各 `Entry` 旁。
     */
    class Entry(val contributor: SystemPromptContributor, val locked: Boolean)

    val entries: List<Entry> = listOf(
        // 身份段：纯聊天项目会整段换成别的身份，必须可覆写。
        Entry(PreambleContributor, locked = false),
        // 改错 = 产出没落到用户能拿到的地方，且不会报错。
        Entry(DeliveryContributor, locked = true),
        // 容器是 proot（`/data/user/0/...` 在容器内是 rootfs 假路径）、技能只从插件声明的
        // 根加载、审批会暂停：说错会让模型静默写错地方或把失败当故障反复重试。
        Entry(CapabilitiesContributor, locked = true),
        // 清单与请求 schema 同源，改掉就与真实可用工具不一致。
        Entry(ToolsContributor, locked = true),
        // 含各工具自带的守则（模型输入契约，`AGENTS.md` §4）。
        Entry(RulesContributor, locked = true),
        // 用户补充说明，本就该能改。
        Entry(AddendumContributor, locked = false),
        // 路径写错 = 命令与文件工具落在别处。
        Entry(CwdContributor, locked = true),
    )

    init {
        // 段落缓存以 id 为键：重复 id 会让两段互相覆盖缓存槽，正文静默串味。
        val ids = entries.map { it.contributor.id }
        require(ids.distinct().size == ids.size) { "内置段落 id 重复：$ids" }
    }

    /** 内置段落的贡献点（注册顺序；实际拼接顺序由各自 [SystemPromptContributor.order] 决定）。 */
    val contributors: List<SystemPromptContributor> = entries.map { it.contributor }

    /**
     * 是否内置段落 —— [SystemPromptBuilder] 据此决定「隔离舱」的范围。
     *
     * 判据是**实例**而不是 id：插件重复注册一个与内置同名的 id 时，拿到的仍是外部
     * 贡献点，不会因为撞名而获得「抛了也不隔离」的待遇。
     */
    fun isBuiltin(contributor: SystemPromptContributor): Boolean =
        contributors.any { it === contributor }

    /** 锁定段落的 id 集，[SystemPromptBuilder.LockedSectionIds] 直接用。 */
    val lockedIds: Set<String> = entries.filter { it.locked }.map { it.contributor.id }.toSet()
}
