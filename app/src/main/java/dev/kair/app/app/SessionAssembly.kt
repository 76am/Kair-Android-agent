package dev.kair.app.app

import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.CacheInspector
import dev.kair.app.core.agent.CompactionSettings
import dev.kair.app.core.agent.CompactionTarget
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.agent.ResolvedRequestInputs
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.browser.BROWSER_TOOL_PREFIX
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.proot.RootAccess
import dev.kair.app.core.settings.ModelResolution
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.settings.withReasoningOverride
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.ReadImageToolName
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolRegistry
import java.io.File

/**
 * 一次 [SessionAssembly.agentSession] 的输入：把宿主 / 子智能体两处构造的差异**显式**摆出来，
 * 装配侧不再出现 `if (type == SUBAGENT)` 这类分叉（③-b 议题三）。
 *
 * 两处调用今天只有这些差异：
 * - [modelProvider]：宿主每次现解析（设置热变更即时生效）；子智能体在创建时解析一次并固定。
 * - [assemblyToolFilterProvider]：宿主在装配时按**当时**解析出的模型现算，系统提示与请求 schema
 *   共用装配产出的同一份工具列表（U42）；`search` 子智能体固定为创建时的「可见性 + 只读」过滤器。
 * - [assemblyWorkspaceProvider] / [filesProvider]：宿主每次现取 `storage.workspaceDir(project)`；
 *   子智能体固定为传入的工作区。
 * - [assemblySessionId]：宿主把装配诊断记到自己名下；子智能体不记（为 null）。
 * - [cacheInspector] / [diagnostics]：宿主共用容器持有的那一份；子智能体传临时实例。
 * - [onPersist] / [onReplaceHistory] / [toolsetBaseline*]：宿主接持久化、附件回收与工具集基线；
 *   子智能体不接（默认空实现 / null）。
 */
internal class SessionSpec(
    val id: String,
    val workspace: File,
    val modelProvider: () -> SavedModel?,
    val assemblyToolFilterProvider: (SavedModel?) -> (Tool) -> Boolean,
    val assemblyWorkspaceProvider: () -> File = { workspace },
    val filesProvider: () -> FileBackend,
    val projectId: String?,
    val assemblySessionId: String?,
    val cacheInspector: CacheInspector,
    val diagnostics: Diagnostics,
    val onPersist: (ChatMessage) -> Unit = {},
    val onReplaceHistory: (List<ChatMessage>) -> Unit = {},
    val toolsetBaselineGet: (() -> String?)? = null,
    val toolsetBaselineSet: ((String) -> Unit)? = null,
)

/**
 * 会话「解析族」本体（U31 ③-a 从 [AppContainer] 搬出）。
 *
 * 它承担的是**解析**：把设置项解成模型、鉴权、工具可见性、上下文窗口、压缩参数与采样，
 * 一次装配出 [AgentAssembly]。之所以这九个成员必须在一处，是因为五处（模型、鉴权、
 * 工具过滤、窗口、采样）各自解一次就会出现「提示用 A、鉴权走 B、窗口按 C、视觉工具按 D」
 * 的错位（`AGENTS.md` §10.4；`ModelResolution.kt` 记着这条历史教训）。
 *
 * **它不做什么**：不建会话、不持 `activeSession`、不碰持久化 —— 那是容器与 ③-b 的编排。
 * 依赖一律显式注入；[commandRunner] 是 lambda（`get()` 型属性，每次调用现取），
 * 插件热替换 runner 后下一回合立即对齐。
 */
internal class SessionAssembly(
    private val settings: SettingsStore,
    private val tools: ToolRegistry,
    private val plugins: PluginRegistry,
    private val diagnostics: Diagnostics,
    /** `AppContainer.commandRunner` 是 `get()` 型属性：传 lambda 保持「每次调用现取」。 */
    private val commandRunner: () -> CommandRunner,
) {
    internal fun toolsetFingerprint(sessionId: String?): String =
        tools.all().filter(toolEnabled(sessionModel(sessionId))).joinToString(",") { it.name }

    /**
     * 会话生效的模型配置；[runOverrideId] 是本次运行级覆盖（子智能体）。
     *
     * 解析优先级见 [ModelResolution]：本次运行 > 子智能体默认 > 会话级 > 全局默认。
     * 返回 null 表示用户还没配置过任何模型，UI 应引导去设置页添加。
     *
     * **这是唯一解析模型的地方**：其余四处（providerConfigFor / toolEnabled /
     * resolveContextWindow / agentSettings）都只接受这里算出的结果，
     * 否则五处各自解一次就会出现「提示用 A、鉴权走 B、窗口按 C、视觉工具按 D」的错位
     * （见 `AGENTS.md` §10.4）。
     */
    fun sessionModel(sessionId: String?, runOverrideId: String? = null): SavedModel? =
        ModelResolution.resolve(
            saved = settings.savedModels,
            runOverrideId = runOverrideId,
            sessionOverrideId = sessionId?.let { settings.sessionModelId(it) },
            appDefaultId = settings.activeModelId,
        )

    /**
     * 子智能体生效的模型：本次运行参数 > 设置项「子智能体模型」> 父会话模型，
     * 最后叠加设置页的「子智能体思考强度」覆盖（只改解析结果，不写回模型配置）；
     * 三层分开放：默认值是用户偏好，本次覆盖是调用方的选择，不写回设置项。
     */
    fun subagentModel(parentSessionId: String?, runOverrideId: String? = null): SavedModel? =
        ModelResolution.resolve(
            saved = settings.savedModels,
            runOverrideId = runOverrideId,
            subagentDefaultId = settings.subagentModelId,
            sessionOverrideId = parentSessionId?.let { settings.sessionModelId(it) },
            appDefaultId = settings.activeModelId,
        )?.withReasoningOverride(settings.subagentReasoningEffort)

    /** 全局激活的模型配置（设置页 / 信息面板展示用）。 */
    fun activeModel(): SavedModel? = settings.activeModel()

    /**
     * 按生效模型解析工具可见性：
     * - `read_image` 只有该模型勾选「图像模型」时才发给模型（未勾选时不进请求 schema 与
     *   系统提示，等价于未注册，避免浪费 token、也避免模型调用一个看不了图的工具）；
     * - `browser_*` 一律不进会话工具面：它们只给 `browser` 子智能体，主会话用 `subagent`
     *   一个入口委派，省下 10 个工具 schema 的常驻 token（见 `SubagentType.BROWSER`）。
     *
     * 只接受已解析的模型（见 [sessionModel]），子智能体被覆盖成非视觉模型时同样生效。
     */
    fun toolEnabled(model: SavedModel?): (Tool) -> Boolean = { tool ->
        when {
            tool.name.startsWith(BROWSER_TOOL_PREFIX) -> false
            tool.name == ReadImageToolName -> model?.isVisionModel == true
            else -> true
        }
    }

    fun providerConfigFor(model: SavedModel?): ProviderConfig {
        val def = ModelCatalog.byId(model?.providerId ?: ModelCatalog.providers.first().id)
        return ProviderConfig(
            providerId = def.id,
            // 自定义提供商可在模型配置里覆盖协议（Chat Completions / Responses / Anthropic）。
            transportId = model?.transportId?.takeIf { it.isNotBlank() } ?: def.transportId,
            baseUrl = model?.baseUrl?.ifBlank { null } ?: def.defaultBaseUrl,
            apiKey = model?.apiKey.orEmpty(),
        )
    }

    /**
     * 会话生效的 Agent 配置。[toolFilter] 决定系统提示里列出的工具清单；默认按会话
     * 可见性解析，只读子智能体（`subagent` 的 `search` 类型）则传入「只保留只读工具」
     * 的过滤器，保证系统提示与请求 schema 的工具集一致。
     *
     * [model] 由调用方解析后传入（不在这里再解一次）：调用方拿同一份结果去算
     * providerConfig / toolFilter / contextWindow，四处才不会错位。
     *
     * [projectId] 用于解析项目绑定的身份预设；没有项目上下文（如兜底）时为 null。
     *
     * [sessionId] 仅用于把本次装配的诊断（段落缺席 / 贡献点异常）记到该会话名下，
     * **不参与正文**；为 null 时不记录。
     *
     * 装配本体是纯函数 [buildAgentSettings]（P4-1 / A2）：这里只做三件副作用接线 ——
     * 读设置快照、过滤工具、记诊断。接线错（设置项没传到）由 `AgentSettingsFactoryTest` 红。
     *
     * **段落缓存读数不在这里记**（看板 U30 第一轮 B 审 P1）：一个回合里这个函数会被调
     * 好几次（取 maxSteps / 取 model / 压缩估 token / 界面读上下文窗口），请求之后那一次
     * 几乎必然全命中。在这里「顺手记一份」记的是**最后那次**装配的数，而面板要显示的是
     * **这一发请求所依据的那次**装配的数 —— 两者可以正好相反。读数因此只跟着返回的
     * [AgentAssembly] 走，由真正发出请求的 `TurnExecutor` 交给 `CacheInspector.record`。
     */
    fun agentSettings(
        model: SavedModel?,
        workspace: File,
        toolFilter: (Tool) -> Boolean = toolEnabled(model),
        projectId: String? = null,
        sessionId: String? = null,
    ): AgentAssembly {
        val built = buildAgentSettings(
            snapshot = SettingsSnapshot(
                maxSteps = settings.maxSteps,
                systemPromptAddendum = settings.systemPromptAddendum,
            ),
            model = model,
            workspace = workspace,
            containerCwd = commandRunner().containerWorkspacePathFor(workspace),
            // 工具列表按会话过滤：与发给模型的 schema 保持一致（如未勾选视觉模型时
            // 不列 read_image），否则系统提示与请求 schema 会自相矛盾。
            tools = tools.all().filter(toolFilter),
            contributors = plugins.systemPromptContributors(),
            projectId = projectId,
        )
        // 诊断是只读旁路：正文来自同一次装配，记录与否不改变任何一位输出字节。
        sessionId?.let { diagnostics.recordAssembly(it, built.issues) }
        return built
    }

    /**
     * 两处 `AgentSession` 构造的**唯一装配点**（③-b 议题三）：宿主 [SessionSpec] 与子智能体
     * [SessionSpec] 都走这里。装配差异全部由 spec 的显式字段承载，本函数不按「是不是子智能体」
     * 分叉；`imageScaler` / `imageLog` / `filesProvider` 等宿主接线也只在这里出现一次。
     */
    internal fun agentSession(spec: SessionSpec): AgentSession = AgentSession(
        id = spec.id,
        workspace = spec.workspace,
        tools = tools,
        // 一次解析产出本发全部输入（U42）：模型只解一次，鉴权 / 工具 / 窗口 / 采样同源。
        inputsProvider = {
            val model = spec.modelProvider()
            val built = agentSettings(
                model,
                spec.assemblyWorkspaceProvider(),
                spec.assemblyToolFilterProvider(model),
                spec.projectId,
                spec.assemblySessionId,
            )
            // 压缩摘要的目标与主请求同源解析：设置项「压缩模型」生效时换成它自己的模型与端点，
            // 空 / 已删除时仍用本会话模型。压缩阈值不跟着换（窗口按主请求算）。
            val compaction = ModelResolution.resolveCompaction(
                settings.savedModels,
                settings.compactionModelId,
                model,
            )
            ResolvedRequestInputs(
                model = model,
                providerConfig = providerConfigFor(model),
                contextWindow = resolveContextWindow(model, built.settings.model),
                assembly = built,
                compactionTarget = CompactionTarget(
                    model = compaction?.model ?: built.settings.model,
                    config = providerConfigFor(compaction ?: model),
                ),
            )
        },
        compactionProvider = { compactionSettings() },
        // 实时解析：插件热变更（装/卸/换）对已存在会话即时生效。
        contextStrategyProvider = { plugins.contextStrategy() },
        cacheEnabled = { settings.promptCaching },
        refreshPromptEveryTurn = { settings.refreshPromptEveryTurn },
        hooks = plugins.hooks,
        onEvent = { plugins.emitEvent(it) },
        cacheInspector = spec.cacheInspector,
        diagnostics = spec.diagnostics,
        imageScaler = BitmapImageScaler,
        imageLog = ::logImageScale,
        commandRunner = { commandRunner() },
        filesProvider = spec.filesProvider,
        toolsetBaselineGet = spec.toolsetBaselineGet,
        toolsetBaselineSet = spec.toolsetBaselineSet,
        onPersist = spec.onPersist,
        onReplaceHistory = spec.onReplaceHistory,
        // 设置页手动探测的 root 结论；子智能体没有 android_shell，TurnRunner 会自行跳过。
        rootGranted = { RootAccess.granted },
    )

    internal fun compactionSettings(): CompactionSettings {
        val defaults = CompactionSettings()
        return defaults.copy(
            enabled = settings.autoCompaction,
            reserveTokens = settings.compactionReserveTokens,
            keepRecentTokens = settings.compactionKeepRecentTokens,
        )
    }

    /**
     * 会话生效的上下文窗口：优先用当前模型配置里填的 [SavedModel.contextWindow]，
     * 否则回退到按模型名推断（[ModelCatalog.contextWindow]）。
     */
    internal fun resolveContextWindow(model: SavedModel?, fallbackName: String): Int {
        val fromModel = model?.contextWindow
        if (fromModel != null && fromModel > 0) return fromModel
        return ModelCatalog.contextWindow(fallbackName)
    }
}
