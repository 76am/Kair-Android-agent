package dev.kair.app.app

import android.content.Context
import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.ApprovalGate
import dev.kair.app.core.agent.CacheInspector
import dev.kair.app.core.agent.CompactionSettings
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.agent.PreambleContributor
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.agent.TurnState
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.RequestCapture
import dev.kair.app.core.maintenance.PermissionRepairReport
import dev.kair.app.core.mcp.McpManager
import dev.kair.app.core.mcp.McpProcessHost
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.plugin.SessionRef
import dev.kair.app.core.plugin.dynamic.DynamicPluginHost
import dev.kair.app.core.plugin.dynamic.InstallPluginTool
import dev.kair.app.core.plugin.dynamic.UninstallPluginTool
import dev.kair.app.core.proot.ContainerRunner
import dev.kair.app.core.proot.ContainerSessionHost
import dev.kair.app.core.proot.ProtectedProcessStore
import dev.kair.app.core.settings.ContextPluginSettingsStore
import dev.kair.app.core.settings.ModelResolution
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.settings.SharedPrefsPluginSettingsStore
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.storage.LocalStorage
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.PromptPresetStore
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.subagent.SubagentManager
import dev.kair.app.core.subagent.SubagentType
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.builtinTools
import dev.kair.app.ui.browser.browserRunGate
import dev.kair.app.ui.plugins.builtin.AskUserGate
import dev.kair.app.ui.plugins.builtin.ContextPlugin
import dev.kair.app.ui.plugins.builtin.McpPlugin
import dev.kair.app.ui.plugins.builtin.builtinPlugins
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Wires the pi core (tools, sessions, providers, plugins, subagents) into a
 * single object the Compose UI talks to. This replaces pi-web's server/session
 * daemon split with an in-process container.
 */
class AppContainer(
    private val env: AppEnvironment,
    val settings: SettingsStore,
    /** 时间源（G1 的 Clock 槽位）：可注入假时钟；计时点统一走它，不直接读系统钟。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 生产入口：Android Context 派生环境与设置存储（`KairApplication` 用这把）。 */
    constructor(context: Context) : this(AppEnvironment.from(context), SettingsStore(context))

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 应用上下文：前台服务 / 跳系统设置页等平台操作用（core 不感知 Context）。
     * fail-fast 取值器：JVM 里碰即抛 —— 平台操作不该发生在单测里，静默拿到 null 才是事故。
     */
    val appContext: Context
        get() = checkNotNull(env.appContext) { "平台操作需要 Android Context" }

    /** 存储根目录：存储后端与工作区都位于其下；插件可据此放置本地数据。 */
    val storageRoot: File = env.storageRoot.apply { mkdirs() }

    /**
     * 全局回收站：**所有工作区之外**的一份共享目录（`<storageRoot>/trash`）。
     * 放在这里而不是工作区里，是因为工作区常常就是用户的 git 仓库根：放里面会污染
     * 仓库（未跟踪目录、`git clean -xdf` 还会连它一起清掉），删项目时也会被一起删掉。
     * 容器两条通道把它挂到容器 `/trash`，模型经 `$KAIR_TRASH` 引用。
     */
    val trashDir = File(storageRoot, "trash").apply { mkdirs() }

    /** 原生库目录与主 ABI：proot 插件定位 libproot_*.so 用；core 不感知 Context。 */
    val nativeLibraryDir: String = env.nativeLibraryDir
    val primaryAbi: String? = env.primaryAbi

    val plugins = PluginRegistry { pluginId ->
        val fallback = SharedPrefsPluginSettingsStore(settings, pluginId)
        // 上下文压缩插件的设置面板与「设置」页共用同一份数据；其余插件走通用前缀存储。
        if (pluginId == ContextPlugin.ID) ContextPluginSettingsStore(settings, fallback) else fallback
    }
    val tools = ToolRegistry()

    /** 存储后端：由插件提供；无插件时回退到内置本地文件实现。 */
    val storage: StorageBackend get() = plugins.storage() ?: fallbackStorage
    private val fallbackStorage: StorageBackend by lazy { LocalStorage(storageRoot) { android.util.Log.w("KairStorage", it) } }

    /**
     * 取走 [sessionId] 的会话读取失败原因（权限 / IO）；界面据此弹提示。
     *
     * 只有内置本地后端会记录这类失败：插件后端有自己的错误通道，而且读失败时同样
     * 返回空历史 —— 不提示的话用户会以为会话被清空了。
     */
    fun takeStorageLoadError(sessionId: String): String? =
        (storage as? LocalStorage)?.takeLoadError(sessionId)

    /** 命令执行后端：由插件提供；无插件时回退到内置本地 shell（toybox）。 */
    val commandRunner: CommandRunner get() = plugins.commandRunner() ?: fallbackCommandRunner
    private val fallbackCommandRunner: CommandRunner by lazy { LocalCommandRunner() }

    /**
     * 常驻容器会话宿主：ProotPlugin 生产、终端插件消费；音频接收端（U89 §E · B1）
     * 由宿主层注入（AudioTrack 过不了 core 的架构守卫）；生命周期见 [ContainerSessionHost]。
     *
     * U89M 起换成 [AndroidRtpAudioSink]：带 RTP 时间戳，才能做抖动缓冲与迟到丢弃；
     * 旧的裸 PCM 版本保留在同目录（[AndroidSessionAudioSink]）作为回退参照。
     */
    val containerSessionHost = ContainerSessionHost(scope, AndroidRtpAudioSink(scope))

    /**
     * stdio MCP 子进程工厂：ProotPlugin 提供（跑在容器 rootfs 里），MCP 管理器消费。
     * 属于整个应用；容器插件禁用 / rootfs 卸载时清空。
     */
    val mcpProcessHost = McpProcessHost()

    /**
     * MCP 连接管理器：配置来自 [settings]，连接真态在这里；工具集变化经
     * [resyncTools] 重建，已打开的会话下一回合即见（同工具开关语义）。
     */
    val mcp = McpManager(
        scope = scope,
        configsProvider = { settings.mcpServers },
        saveConfigs = { settings.mcpServers = it },
        processHost = mcpProcessHost,
        // 装配层唯一允许读「当前会话」的地方，且必须经 activeScope()（不读裸字段）；
        // 拿不到作用域就给 null —— stdio 侧据此 fail-fast，绝不回退 scratch。
        workspace = { activeScope()?.workspace },
        // 还没有打开的会话时别拉起 stdio：进程 cwd 会被永久绑到临时目录。
        stdioWorkspaceReady = { activeScope() != null },
        onToolsChanged = ::requestResync,
    )

    /** 请求重建工具终序。直接重建不跳主线程：注册表已并发安全（B9），跳主线程只是被证伪的旧约定，还让装配层在 JVM 上不可测。 */
    private fun requestResync() {
        resyncTools()
    }

    /**
     * 容器 rootfs 是否就绪（由 ProotPlugin 汇报）。`container_shell` 只在就绪且容器后端在场时
     * 注册，否则由始终注册的 `android_shell` 覆盖基础命令 —— 见 [resyncTools]。
     */
    @Volatile
    private var containerReady = false

    /** ProotPlugin 汇报 rootfs 就绪状态；变化即重建工具终序（rootfs 装 / 卸即时生效）。 */
    fun setContainerReady(ready: Boolean) {
        val becameReady = ready && !containerReady
        containerReady = ready
        requestResync()
        // rootfs 刚装好：之前因容器不可用而失败的 stdio 服务器立即重试。
        if (becameReady) reconnectMcpStdio()
        // rootfs 被卸载：容器内的 stdio 子进程已无意义，先断开再等下次就绪。
        if (!ready) mcp.closeStdioSessions()
    }

    /** stdio MCP 只在 MCP 插件启用时才有意义；统一走这里，避免禁用后仍偷偷连服务器。 */
    private fun reconnectMcpStdio() {
        if (isPluginEnabled(McpPlugin.ID)) mcp.reconnectStdio()
    }

    /**
     * `container_shell` 当前是否可用：rootfs 就绪 **且** 容器命令后端在场（proot 插件已装载）。
     * 两条缺一都不注册 —— 容器不在时 container_shell 只会退化成本地 shell，与其和
     * `android_shell` 重复，不如不给模型。
     */
    private fun bashAvailable(): Boolean =
        containerReady && plugins.commandRunner() is ContainerRunner

    /** 缓存观测器：所有会话共享，供缓存检查面板读取最近一次请求。 */
    val cacheInspector = CacheInspector()

    /**
     * 诊断记录：段落缺席 / 贡献点异常，按**会话**归属。
     *
     * 归属与清理：条目只由 [SessionAssembly.agentSettings] 在装配系统提示时覆盖式写入；随会话卸载
     * （[SessionRegistry.close]）删除，与 `cacheInspector` 同一时机 —— 不随进程存活无限增长。
     * 并发：不同会话的装配可能并发，[Diagnostics] 内部用 `StateFlow.update` 原子替换。
     */
    val diagnostics = Diagnostics()

    /** 技能注册表：skills/ 根 + 内置技能；索引进系统提示，正文按需加载。 */
    val skills = SkillRegistry()

    /**
     * 提示词预设库（身份段）+ 项目绑定。归属整个应用（文件在 [storageRoot] 下）；
     * 项目级解析见 [installPromptOverrides]，生效时机与全局覆写一致。
     */
    val promptPresets = PromptPresetStore(storageRoot)

    /**
     * 受保护的容器进程登记（启动清扫跳过 + 会话就绪后按需拉起；Kair 死亡由收尸人收掉）。
     *
     * 归属整个应用：容器进程活在 `/projects` 这一层，不归属单个项目，所以没有「按项目」
     * 的维度；文件在 [storageRoot] 下，与 `projects.json` 同级。
     */
    val protectedProcesses = ProtectedProcessStore(storageRoot)

    /** 审批闸门：需审批的工具调用在此挂起等待用户决定；永久放行规则落盘。 */
    val approvalGate = ApprovalGate(
        initialAlwaysAllowed = settings.approvalAlwaysAllowed,
        persistAlways = { ids -> settings.approvalAlwaysAllowed = ids },
    )

    /** 提问闸门：`ask_user` 工具挂起等待回答；会话页观察它渲染选择 / 输入面板。 */
    val askUserGate = AskUserGate()

    /** 动态插件宿主：plugins/ 下发现并装载 script / dex（.kplug）两类插件。 */
    val dynamicPlugins: DynamicPluginHost

    val subagents: SubagentManager

    /** 当前活跃会话的只读视图；写权在 [SessionLifecycle]（U31 ③-b 搬出）。 */
    val activeSessionId: String? get() = sessionLifecycle.activeSessionId

    internal val activeSessionMeta: SessionMeta? get() = sessionLifecycle.activeSessionMeta

    /**
     * 会话实例的**唯一持有者**（ADR 0005）。会话页每次进入都 [openSession]（幂等），
     * 离开时不关闭；不设上限则内存随「访问过的会话数 × 历史长度」增长。
     * 超出 [SessionRegistry.DEFAULT_MAX_OPEN] 时按最近打开时间淘汰**空闲**实例
     * （可从存储重新载入）—— 运行中 / 有排队消息 / 当前会话不淘汰。
     *
     * 「按会话登记了什么、关闭时怎么清」的完整清单在 [SessionRegistry.Cleanup]，
     * 不再散落在本文件的四个方法里（背景见 ADR 0005）。
     */
    internal val sessions = SessionRegistry<AgentSession>(
        // 用内部类而不是在这里写匿名 object：`subagents` / `runningJobs` / `pluginAgentBridge`
        // （其内是限流窗口 `pluginSendLog`）都声明在本行之后，构造时求值会拿到未初始化的引用。内部类到运行时才读，
        // 同时把「按会话登记了什么」这份清单从装配主体里挪了出去。
        resources = SessionResourceBinder(),
        describe = { s -> SessionDescriptor(s.id, s.workspace, !s.isBusy && s.queue.value.isEmpty() && !backgroundTasks.hasRunning(s.id)) },
        discardAction = { it.discard() },
    )

    /** Agent 的后台工具任务（完成时回注；有任务在跑的会话不算空闲，见 [sessions]）。 */
    val backgroundTasks: BackgroundTaskManager by lazy { backgroundTasksOf(this) }

    /**
     * 「首次打开了真实会话」的一次性信号：常驻容器会话的自动启动等它，
     * 避免在还没有打开任何会话（`activeScope()` 为 null）时就把工作区绑进容器。
     * 归属整个应用；进程内只完成一次，之后一直保持完成态。
     */
    val firstSessionOpened = CompletableDeferred<Unit>()

    /**
     * 每个已打开会话的状态收集任务。会话释放（[releaseSession]）时必须取消，否则
     * 收集协程会一直持有该 AgentSession 的引用 —— 长会话反复开关会累积泄漏。
     */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * 正在运行的会话 id 集合：会话列表据此给对应条目显示「运行中」转圈。
     * 由 [trackRunning] 观察每个已打开会话的状态流实时维护。
     */
    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions

    /**
     * 「后台常驻」开关：由 `dev.kair.plugin.keepalive` 插件面板驱动，落盘在该插件设置里；
     * 这里保存进程内镜像，供插件把它与 [runningSessions] 合成后决定前台服务起停。
     */
    private val _keepAlive = MutableStateFlow(false)
    val keepAlive: StateFlow<Boolean> = _keepAlive

    fun setKeepAliveEnabled(enabled: Boolean) {
        _keepAlive.value = enabled
    }

    /** 观察一个会话的运行状态，实时同步到 [runningSessions]。 */
    private fun trackRunning(session: AgentSession) {
        // 同一会话重开会话时先撤掉旧任务，避免重复收集。
        runningJobs.remove(session.id)?.cancel()
        runningJobs[session.id] = scope.launch {
            session.turnState.collect { state ->
                // 回合在跑（含停止收尾）才算「运行中」；只改历史的串行动作（Busy）不算。
                val running = state is TurnState.Running || state == TurnState.Stopping
                _runningSessions.update { cur ->
                    if (running) cur + session.id else cur - session.id
                }
            }
        }
    }

    /**
     * 内置插件启用 / 禁用与动态插件删除：B 段整体搬进 [PluginLifecycleOps]，
     * 这里只留接线。
     *
     * **必须声明在 init 之前**：init 里的 `resyncTools()` 会读 [isPluginEnabled]，
     * 字段晚于 init 声明时会拿到未初始化的 `Lazy`（`AppContainerWiringTest` 会红）。
     *
     * `builtinPlugins` 传的是对顶层函数的调用，不是 [builtinPluginDefs] ——
     * 后者已经是本类的委托，绕回去会递归。
     */
    private val pluginLifecycleOps: PluginLifecycleOps by lazy {
        PluginLifecycleOps(
            plugins = plugins,
            dynamicPlugins = dynamicPlugins,
            settings = settings,
            scope = scope,
            containerSessionHost = containerSessionHost,
            mcpProcessHost = mcpProcessHost,
            mcp = mcp,
            setContainerReady = ::setContainerReady,
            resyncTools = ::resyncTools,
            builtinPlugins = { builtinPlugins(this) },
        )
    }

    init {
        // 先做一次性迁移，保证后续读到的是新的多模型列表（旧单模型配置 → 一条 SavedModel）。
        settings.migrateLegacyModelConfigIfNeeded()
        tools.registerAll(builtinTools(bashAvailable()))
        // 子智能体的工作区按**发起委派的会话**反查注册表：只认会话实例自己的工作区。
        // 解析不到就返回 null 让 SubagentManager 失败 —— **不回退**「最近活跃工作区」：
        // 那会让 A 会话发起的委派落进 B 的项目里（AGENTS.md §10.4 明令禁止的形态）。
        // 嵌套委派的上级工作区由 SubagentManager 自己优先解析，不走这里。
        subagents = SubagentManager(::createSubAgentSession, workspaceOf = { id -> id?.let { sessions.get(it)?.workspace } },
            browserGate = browserRunGate(env.appContext))
        // subagent 工具由 SubagentsPlugin 经 ctx.tool() 贡献，
        // 走插件贡献点，resyncTools() 重建终序时会重新装回（不在此直接注册）。

        // 动态插件宿主要先于内置插件装载就位：ProotPlugin.activate 里汇报 rootfs 就绪
        // 会触发 resyncTools()，而 resyncTools 需要 dynamicPlugins（宿主工具依赖它）。
        // **激活本身放到第二个 init**：运行时桥 / 插件数据根在那时才注入（U39）。
        dynamicPlugins = DynamicPluginHost(
            root = File(storageRoot, "plugins"),
            registry = plugins,
            tools = tools,
            skills = skills,
            resync = ::resyncTools,
            // 安装前用内置插件 id 快照检测第三方插件 id 冲突。
            builtinPluginIds = { builtinPluginDefs.map { it.id }.toSet() },
            // 严格 / 跳过模式：由设置页开关驱动（默认严格）。
            strictCompat = { settings.pluginCompatStrict },
        )
    }

    /**
     * 重建执行注册表的最终顺序：内置 → 插件贡献（动态可覆盖内置）→ 宿主工具
     * （install/uninstall 不可被覆盖 —— 它们是信任边界）。
     * 先清空再注册，保证卸载插件后其独有工具不残留。
     *
     * 两处过滤（都等价于「未注册」，不进请求 schema 与系统提示）：
     * - 用户关闭的工具（[isToolEnabled]）：开关在插件页 / 插件面板内，落盘持久化；
     * - `container_shell` 的「容器就绪」前置条件（[bashAvailable]），否则由 `android_shell` 兜底。
     */
    private fun resyncTools() {
        // 先构建完整列表，再一次性原子替换：读侧（会话 / 系统提示）不会看到空表或半张表。
        val next = buildList {
            addAll(builtinTools(bashAvailable()).filter { isToolEnabled(it.name) })
            addAll(plugins.tools().filter { isToolEnabled(it.name) })
            // MCP 工具是动态集合（随服务器连接 / 断开变化），不进插件注册表：
            // 插件自己在 activate 时贡献的只有 mcp_list / mcp_manage 两个管理入口。
            if (isPluginEnabled(McpPlugin.ID)) {
                addAll(mcp.tools().filter { isToolEnabled(it.name) })
            }
            // 宿主工具是信任边界：不可被插件覆盖，也不参与工具开关（否则可被永久关死）。
            add(InstallPluginTool(dynamicPlugins))
            add(UninstallPluginTool(dynamicPlugins))
        }
        tools.replaceAll(next)
    }

    // ---- tool enable / disable --------------------------------------------

    /** 某工具当前是否开启（用户开关，落盘持久化）；关闭后不注册给 agent。 */
    fun isToolEnabled(name: String): Boolean = name !in settings.disabledTools

    /**
     * 开 / 关一个工具：落盘并立即重建工具终序，已打开的会话下一回合即生效。
     * 工具列表进入缓存稳定前缀，开关变化会作废该会话此前的前缀（已知代价，
     * 与「切换是否图像模型」同类，改动低频）。
     */
    fun setToolEnabled(name: String, enabled: Boolean) {
        val disabled = settings.disabledTools
        settings.disabledTools = if (enabled) disabled - name else disabled + name
        resyncTools()
    }

    /**
     * 某会话对模型可见的工具集指纹（按注册顺序取工具名）。与请求 schema / 系统提示同源，
     * 用于判定会话「上次看到的工具集」是否变化 —— 见 AgentSession 的工具集变化提示。
     *
     * 实现见 [SessionAssembly.toolsetFingerprint]。
     */
    private fun toolsetFingerprint(sessionId: String?): String =
        sessionAssembly.toolsetFingerprint(sessionId)

    /** 一条可开关的工具及其归属；[pluginId] 为 null 表示内核内置工具。 */
    data class ToolOwner(val tool: Tool, val pluginId: String?)

    /**
     * 可开关的工具清单（内置 + 各插件贡献），供插件页与插件面板渲染开关。
     * 宿主工具（install / uninstall）不在其中 —— 它们是信任边界，不参与开关。
     *
     * 归属只带 id：展示名一律由渲染点经 `pluginDisplayText` 解析，否则「声明了资源通道的
     * 内置插件」会在这里被取成空串 —— 装配期拿不到 `Context`，本来就不该拼展示名。
     */
    fun toolCatalog(): List<ToolOwner> {
        val builtin = builtinTools(bashAvailable()).map { ToolOwner(it, null) }
        val contributed = plugins.toolEntries().map { (pluginId, tool) -> ToolOwner(tool, pluginId) }
        return builtin + contributed
    }

    /**
     * 身份段（开场白）的项目级覆写：项目绑定的预设生效时替换开场白。
     *
     * 在 init 里注入一次：预设面板改动后清 `SystemPromptBuilder` 的段落缓存，
     * 下一回合即生效。语义见 `SystemPromptBuilder.SectionOverride`。
     */
    private fun installPromptOverrides() {
        SystemPromptBuilder.setOverridesProvider { id, projectId ->
            // 只有身份段能被项目预设替换；停用 / 正文为空则回落到内置身份。
            if (id != PreambleContributor.id) return@setOverridesProvider null
            val preset = projectId?.let { promptPresets.boundPreset(it) } ?: return@setOverridesProvider null
            preset.preamble.takeIf { preset.enabled && it.isNotBlank() }
                ?.let { SystemPromptBuilder.SectionOverride(text = it) }
        }
    }

    // ---- 插件运行时桥（会话 / 子智能体）-------------------------------------

    /**
     * 交给插件的桥：限流窗口 + 四个 `AgentBridge` 方法整体搬进
     * [PluginRuntimeBridge]，这里只留接线。
     *
     * 依赖一律显式传：`get()` 型传 lambda、`private` 方法传函数引用 ——
     * 不反向持有容器，也不为搬家抬高 [sessionFor] 的可见性。
     */
    private val pluginAgentBridge: PluginRuntimeBridge by lazy {
        PluginRuntimeBridge(
            clock = clock,
            storage = { storage },
            diagnostics = diagnostics,
            sessions = sessions,
            subagents = subagents,
            activeSessionId = { activeSessionId },
            openSession = ::openSession,
            sessionFor = ::sessionFor,
        )
    }

    /** 附件回收本体（U31 第二刀搬出）；容器只留委托与触发点。 */
    private val attachmentGc: AttachmentGc by lazy {
        AttachmentGc({ storage }, sessions, scope) { subagents.liveSessions() } // U50：末位是子智能体
    }

    /** 工作区文件访问本体（U31 3a 搬出）；容器只留两个委托，装配段直接调 [WorkspaceFileAccess.filesFor]。 */
    internal val workspaceFiles: WorkspaceFileAccess by lazy {
        WorkspaceFileAccess(plugins = plugins, trashDir = trashDir, storage = { storage })
    }

    /** 会话装配的解析本体（U31 ③-a 搬出）；容器只留同名委托。 */
    private val sessionAssembly: SessionAssembly by lazy {
        SessionAssembly(
            settings = settings,
            tools = tools,
            plugins = plugins,
            diagnostics = diagnostics,
            commandRunner = { commandRunner },
        )
    }

    /** 会话编排本体（U31 ③-b 搬出）；容器只留同名委托。 */
    private val sessionLifecycle: SessionLifecycle by lazy {
        SessionLifecycle(
            storage = { storage },
            settings = settings,
            sessions = sessions,
            subagents = { subagents },
            assembly = sessionAssembly,
            cacheInspector = cacheInspector,
            diagnostics = diagnostics,
            storageRoot = storageRoot,
            filesFor = { workspaceFiles.filesFor(it) },
            activeScopeWorkspace = { activeScope()?.workspace?.absolutePath },
            reconnectMcpStdio = ::reconnectMcpStdio,
            trackRunning = ::trackRunning,
            scheduleAttachmentGc = ::scheduleAttachmentGc,
            gcAttachments = ::gcAttachments,
            firstSessionOpened = firstSessionOpened,
        )
    }

    init {
        // 插件运行时能力：会话/子智能体桥 + 私有数据目录。必须早于任何 activate ——
        // activate 只发生一次，晚注入不会自愈（SchedulerPlugin 存 null 桥后每 30 秒投递失败，U39）。
        plugins.setAgentBridge(pluginAgentBridge)
        plugins.setPluginDataRoot(File(storageRoot, "plugin-data"))
        installPromptOverrides()

        // 先就绪、后激活：内置 → 重建工具终序 → 动态 → 重建工具终序。
        plugins.installAll(builtinPlugins(this).filterNot { it.id in settings.disabledBuiltinPlugins })
        resyncTools()
        // 装载磁盘上已存在的动态插件（跳过已禁用者），并重建工具终序。
        dynamicPlugins.discover()
        resyncTools()
        // stdio MCP 的工作目录绑定容器工作区：启动时没有打开的会话，等首次打开真实会话再连。
        scope.launch {
            firstSessionOpened.await()
            reconnectMcpStdio()
        }
    }

    // ---- plugin enable / disable -------------------------------------------

    /** 内置插件完整清单（含被禁用的），供插件页展示与重新启用。 */
    val builtinPluginDefs: List<Plugin> get() = pluginLifecycleOps.builtinPluginDefs()

    /** 某插件当前是否已装载（在注册表内）。 */
    fun isPluginEnabled(id: String): Boolean = pluginLifecycleOps.isPluginEnabled(id)

    /** 某插件的设置存储，供 UI 渲染声明式设置面板（开关 / 数值 / 文本 / 下拉）。 */
    fun pluginSettings(id: String): dev.kair.app.core.plugin.PluginSettingsStore =
        pluginLifecycleOps.pluginSettings(id)

    /** 删除动态插件；参数语义见 [PluginLifecycleOps.deleteDynamicPlugin]。 */
    fun deleteDynamicPlugin(
        id: String,
        deleteData: Boolean = false,
        dataWorkspace: File? = null,
    ): Boolean = pluginLifecycleOps.deleteDynamicPlugin(id, deleteData, dataWorkspace)

    /** 启用 / 禁用一个内置插件；语义见 [PluginLifecycleOps.setPluginEnabled]。 */
    fun setPluginEnabled(id: String, enabled: Boolean) {
        pluginLifecycleOps.setPluginEnabled(id, enabled)
    }

    // ---- provider / agent config -------------------------------------------

    /** 实现见 [SessionAssembly.sessionModel]。 */
    fun sessionModel(sessionId: String?, runOverrideId: String? = null): SavedModel? =
        sessionAssembly.sessionModel(sessionId, runOverrideId)

    /** 实现见 [SessionAssembly.subagentModel]。 */
    fun subagentModel(parentSessionId: String?, runOverrideId: String? = null): SavedModel? =
        sessionAssembly.subagentModel(parentSessionId, runOverrideId)

    /** 供声明式设置面板的「动态选项源」使用：当前所有会话（最近活动在前）。 */
    fun pluginSessions(): List<SessionRef> = pluginAgentBridge.sessions()

    /** 实现见 [SessionAssembly.activeModel]。 */
    fun activeModel(): SavedModel? = sessionAssembly.activeModel()

    /** 设置页保存后调用：把该模型设为全局激活项（未做会话级覆盖的会话下一回合即生效）。 */
    fun activateModel(id: String) {
        settings.activeModelId = id
    }

    /** 会话底部切换模型：只影响这一个会话，不动全局设置。 */
    fun setSessionModel(sessionId: String, modelId: String) {
        settings.setSessionModelId(sessionId, modelId)
    }

    /** 实现见 [SessionAssembly.toolEnabled]。 */
    fun toolEnabled(model: SavedModel?): (Tool) -> Boolean = sessionAssembly.toolEnabled(model)

    /** 实现见 [SessionAssembly.providerConfigFor]。 */
    fun providerConfigFor(model: SavedModel?): ProviderConfig = sessionAssembly.providerConfigFor(model)

    /**
     * 会话生效的 Agent 配置。[toolFilter] 决定系统提示里列出的工具清单；默认按会话
     * 可见性解析，只读子智能体（`subagent` 的 `search` 类型）则传入「只保留只读工具」
     * 的过滤器，保证系统提示与请求 schema 的工具集一致。
     *
     * 实现见 [SessionAssembly.agentSettings]（设置快照 / 工具过滤 / 诊断三件接线都在那边）。
     */
    fun agentSettings(
        model: SavedModel?,
        workspace: File,
        toolFilter: (Tool) -> Boolean = toolEnabled(model),
        projectId: String? = null,
        sessionId: String? = null,
    ): AgentAssembly = sessionAssembly.agentSettings(model, workspace, toolFilter, projectId, sessionId)

    // ---- session lifecycle -------------------------------------------------

    /**
     * 用户打开会话：切换「当前项目 / 当前会话」，并打开对应的 agent 实例。
     *
     * 这些全局切换是**用户意图**的副作用，后台注入（定时任务等）不得触发；
     * 那条路径走 [sessionFor]。
     */
    fun openSession(project: Project, meta: SessionMeta): AgentSession =
        sessionLifecycle.openSession(project, meta)

    /**
     * 取出（必要时创建）会话实例，**不改**「当前项目 / 当前会话」、不触发容器自动启动
     * 与 MCP 重连。
     *
     * 后台注入用：目标会话可能属于另一个项目，静默把用户正在看的工作区切走
     * （并重连 MCP、改掉缓存面板的会话）是不可接受的副作用。
     */
    private fun sessionFor(project: Project, meta: SessionMeta): AgentSession =
        sessionLifecycle.sessionFor(project, meta)

    /** 实现见 [SessionAssembly.compactionSettings]。生产侧已无调用点 —— 留着的唯一理由是 golden 经反射取它（账在 ③-b 交接 §6.3）。 */
    private fun compactionSettings(): CompactionSettings = sessionAssembly.compactionSettings()

    /** 实现见 [SessionAssembly.resolveContextWindow]。留下的理由与 [compactionSettings] 同一条（golden 的反射点，别当死代码删）。 */
    private fun resolveContextWindow(model: SavedModel?, fallbackName: String): Int =
        sessionAssembly.resolveContextWindow(model, fallbackName)

    /**
     * 关闭会话并释放它的全部登记资源（审批规则、子智能体、任务、缓存、诊断、请求记录）。
     *
     * **唯一入口**（ADR 0005 决策 2）。清哪些、按什么顺序全在 [SessionRegistry.Cleanup]，
     * 这里只保留方法名 —— UI 与插件侧按它调用。对从未打开过的会话同样生效。
     */
    fun releaseSession(id: String) = sessionLifecycle.releaseSession(id)

    /**
     * 删除项目：先关闭该项目下所有已打开会话与子智能体，再删除存储（含工作区）。
     * 若不先关闭，会话会继续在已被递归删除的目录上读写，审批卡片也会挂到不存在的会话。
     */
    fun deleteProject(project: Project) = sessionLifecycle.deleteProject(project)

    /** New session for a project, added to the store and opened immediately. */
    fun newSession(project: Project, name: String = "新会话"): SessionMeta =
        sessionLifecycle.newSession(project, name)

    /**
     * 子智能体会话继承**父会话**的模型配置、上下文窗口与工具可见性，但可以被两层覆盖：
     * 设置项「子智能体模型」（长期偏好）与本次运行的 [modelId]（调用方为这次任务选）。
     * 解析优先级见 [subagentModel] 与 [ModelResolution]。
     *
     * [parentSessionId] 可能是嵌套委派中的上级子智能体：沿委派链找到顶层会话再解析，
     * 这样「父会话在输入框切了模型 → 子智能体」仍然一致，不会回退到全局激活模型。
     *
     * 模型在**创建时解析一次**：子智能体是短命会话，中途改设置不应该把它的鉴权与窗口
     * 半路换掉（那会让同一个会话前后两个请求发往不同端点）。
     */
    private fun createSubAgentSession(
        id: String,
        workspace: File,
        type: SubagentType,
        parentSessionId: String?,
        modelId: String?,
    ): AgentSession = sessionLifecycle.createSubAgentSession(id, workspace, type, parentSessionId, modelId)

    /**
     * 当前活跃会话的工作区；没有打开的会话时为 null。
     * 需要「真实项目」的路径解析（插件数据删除 / 设置页文件编辑）必须用这个，
     * **不要**回退到任何「最近活跃工作区」—— 进程初值没有项目，会删错 / 写错项目。
     */
    fun activeSessionWorkspace(): File? = sessionLifecycle.activeSessionWorkspace()

    /** 会话实例的工作区（注册表快照）。给 [activeScope] 按**同一个 id** 取值：两次独立读会拼出混搭三元组。 */
    internal fun openWorkspaceOf(sessionId: String): File? = sessionLifecycle.openWorkspaceOf(sessionId)

    /** 供 UI 复用文件后端（与工具同源）；实现见 [WorkspaceFileAccess.fileBackend]（U31 3a 搬出）。 */
    fun fileBackend(workspace: File): FileBackend = workspaceFiles.fileBackend(workspace)

    /**
     * 工作区权限自检 / 修复，实现见 [WorkspaceFileAccess.repairWorkspacePermissions]。
     * [scope] 仍由调用方显式传入（UI 传 `activeSessionWorkspace()`），本函数不自己读「当前会话」（§10.4）。
     */
    suspend fun repairWorkspacePermissions(
        scope: File?,
        fullWorkspace: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): PermissionRepairReport = workspaceFiles.repairWorkspacePermissions(scope, fullWorkspace, onProgress)

    // ---- 附件回收（维护） ---------------------------------------------------

    /**
     * 附件回收实现见 [AttachmentGc]（U31 第二刀搬出）：引用集合是日志 ∪ 在场会话内存历史，
     * 新近本体有豁免窗口。触发点不变：[scheduleAttachmentGc]（历史改写后去抖）、
     * [deleteSession]（删会话后立即）、以及 `/gc` 维护命令手动调用。
     *
     * @return 实际删除的文件数
     */
    fun gcAttachments(project: Project): Int = attachmentGc.gcAttachments(project)

    /** 回收某会话所属项目下无人引用的附件；会话不在任何已登记项目里时为 0。 */
    fun gcAttachmentsFor(session: AgentSession): Int = attachmentGc.gcAttachmentsFor(session)

    /** 去抖入口：同一项目连续改写只留一个后台回收 job（窗口与豁免见 [AttachmentGc]）。 */
    private fun scheduleAttachmentGc(project: Project) = attachmentGc.schedule(project)

    /** 删除一个会话：关闭 → 删除落盘日志 → 回收因此失去引用的附件。 */
    fun deleteSession(project: Project, id: String) = sessionLifecycle.deleteSession(project, id)

    /**
     * 从文件管理器删除会话日志时走这条**联动**路径：关闭已打开会话 → 删索引项 → 回收附件。
     *
     * 只删文件会留下一条「点开是空」的会话记录，用户看到的就是「删了但没删」；
     * 反之直接删索引又会留下孤儿文件。两侧必须一起动。
     *
     * @return 是否按联动处理；false 表示该路径不是受管的会话日志（调用方按普通文件删除）。
     */
    fun deleteSessionLog(path: String): Boolean = sessionLifecycle.deleteSessionLog(path)

    /**
     * `SessionRegistry` 要的宿主资源清理动作 —— 「按会话登记了什么」的实际清单。
     *
     * 做成内部类（而不是在 [sessions] 处写匿名 object）是为了**运行时**才读
     * [subagents] / [runningJobs] / [pluginAgentBridge]（其内是限流窗口 `pluginSendLog`）：
     * 它们都声明在 [sessions] **之后**，装配时求值会拿到未初始化的引用。顺带把这份清单挪出了装配主体。
     */
    private inner class SessionResourceBinder : SessionResources {
        override fun releaseApprovalRules(id: String) { approvalGate.endSession(id) }
        override fun releasePendingQuestions(id: String) { askUserGate.endSession(id) }
        override fun abortSubagents(id: String) = subagents.abortChildrenOf(id)
        override fun releaseBackgroundTasks(id: String) = backgroundTasks.cancelAll(id)
        override fun cancelTrackJob(id: String) { runningJobs.remove(id)?.cancel() }

        override fun removeRunningMark(id: String) = _runningSessions.update { it - id }
        override fun releaseCacheRecords(id: String) = cacheInspector.remove(id)
        override fun releaseDiagnostics(id: String) = diagnostics.remove(id)
        override fun releaseRequestRecords(id: String) = RequestCapture.clear(id)
        override fun releaseSendWindow(id: String) { pluginAgentBridge.releaseSendWindow(id) }
    }
}
