package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.CacheInspector
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.ai.RequestCapture
import dev.kair.app.core.browser.BROWSER_TOOL_PREFIX
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.subagent.SubagentManager
import dev.kair.app.core.subagent.SubagentType
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.ReadImageToolName
import dev.kair.app.core.tools.Tool
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * 会话编排本体（U31 ③-b 从 [AppContainer] 搬出）：打开 / 关闭 / 删除 / 新建会话、
 * 子智能体会话、当前会话指针与会话日志联动。
 *
 * **它不持有容器**：依赖一律显式注入；[storage] 是 `get()` 型属性，传 lambda 保持
 * 「每次调用现取」；[subagents] 同理传 lambda —— 它在容器的 `init` 里才赋值，
 * wiring 求值时还读不到，推迟到调用时才是安全的。两处 `AgentSession` 构造本身不在本类：
 * 这里只组 [SessionSpec]，统一交给 [SessionAssembly.agentSession]（一处装配、两处调用）。
 *
 * `activeSession` 的写权只在本类：`openSession` 写入、`deleteProject` 清空；
 * 容器只保留只读委托，插件桥 / UI 不再有写入口。
 */
internal class SessionLifecycle(
    private val storage: () -> StorageBackend,
    private val settings: SettingsStore,
    private val sessions: SessionRegistry<AgentSession>,
    private val subagents: () -> SubagentManager,
    private val assembly: SessionAssembly,
    private val cacheInspector: CacheInspector,
    private val diagnostics: Diagnostics,
    private val storageRoot: File,
    private val filesFor: (File) -> FileBackend,
    private val activeScopeWorkspace: () -> String?,
    private val reconnectMcpStdio: () -> Unit,
    private val trackRunning: (AgentSession) -> Unit,
    private val scheduleAttachmentGc: (Project) -> Unit,
    private val gcAttachments: (Project) -> Int,
    private val firstSessionOpened: CompletableDeferred<Unit>,
) {
    /** 最近打开的会话（[openSession] 更新）；只读观测面板据此过滤。未打开时为 null。 */
    @Volatile
    private var activeSession: SessionMeta? = null

    internal val activeSessionId: String? get() = activeSession?.id

    internal val activeSessionMeta: SessionMeta? get() = activeSession

    /**
     * 用户打开会话：切换「当前项目 / 当前会话」，并打开对应的 agent 实例。
     *
     * 这些全局切换是**用户意图**的副作用，后台注入（定时任务等）不得触发；
     * 那条路径走 [sessionFor]。
     */
    internal fun openSession(project: Project, meta: SessionMeta): AgentSession {
        val workspace = storage().workspaceDir(project)
        // 切换前的作用域（单次快照）：用来判断是否换了项目，而不是靠一个「最近活跃工作区」字段。
        val previous = activeScopeWorkspace()
        // 记录当前会话：缓存面板据此只显示本会话的请求数据。
        activeSession = meta
        // stdio MCP 的 cwd 绑在工作区挂载上：切项目后必须重连，否则读到旧项目。
        if (previous != workspace.absolutePath) reconnectMcpStdio()
        val opened = sessionFor(project, meta)
        // 必须晚于 sessionFor：容器醒来即读 activeScope()（查注册表，open 先构造再插入），早发一步即永久失败。
        firstSessionOpened.complete(Unit)
        return opened
    }

    /**
     * 取出（必要时创建）会话实例，**不改**「当前项目 / 当前会话」、不触发容器自动启动
     * 与 MCP 重连。
     *
     * 后台注入用：目标会话可能属于另一个项目，静默把用户正在看的工作区切走
     * （并重连 MCP、改掉缓存面板的会话）是不可接受的副作用。
     */
    internal fun sessionFor(project: Project, meta: SessionMeta): AgentSession {
        // keep 必须同时含「本次打开的会话」与「当前会话」—— 后者可能属于另一个项目，
        // 用户正看着它。原 evictIdleSessions 自己排除了 activeSessionId，现在由这里显式给出；
        // 漏传就会静默把用户正在看的会话卸掉，所以 SessionRegistry.open 的 keep 没有默认值。
        return sessions.open(meta.id, keep = setOfNotNull(meta.id, activeSessionId)) {
            val created = assembly.agentSession(
                SessionSpec(
                    id = meta.id,
                    workspace = storage().workspaceDir(project),
                    modelProvider = { assembly.sessionModel(meta.id) },
                    // 装配时按「当时」解析出的模型现算：系统提示与请求 schema 共用这一份（U42），
                    // 设置热变更后两侧对下一发请求一起生效。
                    assemblyToolFilterProvider = { model -> assembly.toolEnabled(model) },
                    assemblyWorkspaceProvider = { storage().workspaceDir(project) },
                    filesProvider = { filesFor(storage().workspaceDir(project)) },
                    projectId = project.id,
                    assemblySessionId = meta.id,
                    cacheInspector = cacheInspector,
                    diagnostics = diagnostics,
                    // 工具集变化提示的基线读写：记录本会话上次看到的工具集指纹。
                    toolsetBaselineGet = { settings.sessionToolsetFingerprint(meta.id) },
                    toolsetBaselineSet = { fp -> settings.setSessionToolsetFingerprint(meta.id, fp) },
                    onPersist = { msg -> storage().appendMessage(meta.id, msg) },
                    onReplaceHistory = { msgs ->
                        storage().replaceMessages(meta.id, msgs)
                        // 压缩 / 图片降级 / 编辑撤回 / 删除消息都会重写历史，可能让旧图片失去引用，顺手安排回收。
                        scheduleAttachmentGc(project)
                    },
                ),
            )
            created.loadHistory(storage().loadMessages(meta.id))
            trackRunning(created)
            created
        }
    }

    /**
     * 关闭会话并释放它的全部登记资源（审批规则、子智能体、任务、缓存、诊断、请求记录）。
     *
     * **唯一入口**（ADR 0005 决策 2）。清哪些、按什么顺序全在 [SessionRegistry.Cleanup]，
     * 这里只保留方法名 —— UI 与插件侧按它调用。对从未打开过的会话同样生效。
     */
    internal fun releaseSession(id: String) {
        sessions.release(id)
    }

    /**
     * 删除项目：先关闭该项目下所有已打开会话与子智能体，再删除存储（含工作区）。
     * 若不先关闭，会话会继续在已被递归删除的目录上读写，审批卡片也会挂到不存在的会话。
     */
    internal fun deleteProject(project: Project) {
        val workspace = storage().workspaceDir(project)
        val workspacePath = workspace.absolutePath
        // 按当前会话的项目判定（不看注册表：会话可能已关闭，activeScope() 会是 null）。
        val wasActive = activeSession?.projectId == project.id
        // 一律经注册表的 release（ADR 0005 决策 4）：不再在这里自己拼装「先关哪些会话」。
        sessions.releaseWhere { it.workspace.absolutePath == workspacePath }
        if (wasActive) activeSession = null
        // 段落缓存按工作区分作用域（U19②）：删项目要摘掉那个桶（§10.5 的清除面，不是正确性 —— 谓词里
        // 有 projectId，换了项目必然 miss）。路径必须赶在 `deleteProject` **之前**取：之后调
        // `workspaceDir()` 会走 `ensureDirName` —— `mkdirs()` 重建刚删的目录 + 映射写回 = 占名孤儿。
        SystemPromptBuilder.invalidate(workspace.path)
        // 附件本体就在 `<workspace>/.kair/attachments/v1`，已随工作区一起被删 —— 这里**不能**再调
        // `gcAttachments`：它要经 `workspaceDir()` 解析工作区，而映射刚被摘掉，`ensureDirName` 会
        // `mkdirs()` 把刚删的空目录重建、并把映射写回 `project-dirs.json`（U33 的占名孤儿：
        // 用户再建同名工作区会拿到意外路径）。删项目的这一次回收无事可做且有害。
        storage().deleteProject(project.id)
    }

    /** New session for a project, added to the store and opened immediately. */
    internal fun newSession(project: Project, name: String = "新会话"): SessionMeta {
        val meta = storage().createSession(project.id, name, assembly.activeModel()?.model.orEmpty())
        // 记录创建时的工具集基线：新建会话自此基线起算，工具集变了才提示；
        // 早于本特性创建的旧会话没有记录，首回合按「已变化」提示一次（见 AgentSession）。
        settings.setSessionToolsetFingerprint(meta.id, assembly.toolsetFingerprint(meta.id))
        return meta
    }

    /**
     * 子智能体会话继承**父会话**的模型配置、上下文窗口与工具可见性，但可以被两层覆盖：
     * 设置项「子智能体模型」（长期偏好）与本次运行的 [modelId]（调用方为这次任务选）。
     * 解析优先级见 [SessionAssembly.subagentModel] 与 ModelResolution。
     *
     * [parentSessionId] 可能是嵌套委派中的上级子智能体：沿委派链找到顶层会话再解析，
     * 这样「父会话在输入框切了模型 → 子智能体」仍然一致，不会回退到全局激活模型。
     *
     * 模型在**创建时解析一次**：子智能体是短命会话，中途改设置不应该把它的鉴权与窗口
     * 半路换掉（那会让同一个会话前后两个请求发往不同端点）。
     */
    internal fun createSubAgentSession(
        id: String,
        workspace: File,
        type: SubagentType,
        parentSessionId: String?,
        modelId: String?,
    ): AgentSession {
        // 子智能体只保留失败请求（见 RequestCapture.markSubagent），面板按父会话归组。
        RequestCapture.markSubagent(id)
        val ownerId = parentSessionId?.let { subagents().rootSessionOf(it) ?: it }
        val model = assembly.subagentModel(ownerId, modelId)
        // 子智能体与父会话同工作区：沿用该项目的身份预设，避免同一项目里出现两套身份。
        val projectId = storage().listProjects()
            .firstOrNull { storage().workspaceDir(it).absolutePath == workspace.absolutePath }
            ?.id
        // 浏览器子智能体只保留 browser_* 与 read / read_image（read_image 仍受视觉模型门控）；
        // coding 继承父会话全部工具；search 只保留只读工具（read / ls / grep / find 等）。
        // 过滤器在创建时随模型一起固定，系统提示与请求 schema 共用装配产出的同一份（U42）。
        val toolFilter = subagentToolFilter(type, assembly.toolEnabled(model))
        return assembly.agentSession(
            SessionSpec(
                id = id,
                workspace = workspace,
                modelProvider = { model },
                assemblyToolFilterProvider = { _ -> toolFilter },
                filesProvider = { filesFor(workspace) },
                projectId = projectId,
                assemblySessionId = null,
                // 观测面同理：子智能体不进 SessionRegistry，没有谁调它的 remove，共用宿主那一份就是
                // 「跑过的子智能体数 × 一整份报告（含整段 segments 快照）」永久留在进程里（第一轮 B 审 P2）。
                // 面板要看的是用户当前会话的缓存，子智能体的失败请求另由 RequestCapture 按父会话归组，
                // 所以这里与 diagnostics 同形：临时实例，随 `Subagent` 终态清理摘走会话对象一起回收。
                cacheInspector = CacheInspector(),
                diagnostics = Diagnostics(),
            ),
        )
    }

    /**
     * 当前活跃会话的工作区；没有打开的会话时为 null。
     * 需要「真实项目」的路径解析（插件数据删除 / 设置页文件编辑）必须用这个，
     * **不要**回退到任何「最近活跃工作区」—— 进程初值没有项目，会删错 / 写错项目。
     */
    internal fun activeSessionWorkspace(): File? = activeSessionId?.let(::openWorkspaceOf)

    /** 会话实例的工作区（注册表快照）。给 [activeScope] 按**同一个 id** 取值：两次独立读会拼出混搭三元组。 */
    internal fun openWorkspaceOf(sessionId: String): File? = sessions.get(sessionId)?.workspace

    /** 删除一个会话：关闭 → 删除落盘日志 → 回收因此失去引用的附件。 */
    internal fun deleteSession(project: Project, id: String) {
        // releaseSession 已含 RequestCapture.clear（SessionRegistry.Cleanup.RequestRecords），
        // 不再单独调一次 —— 同一件事只留一个入口。
        releaseSession(id)
        storage().deleteSession(id)
        gcAttachments(project)
    }

    /** 本地存储后端放置会话日志的目录（`sessions.json` 同级下的 `sessions/`）。 */
    private val sessionLogsDir = File(storageRoot, "sessions/sessions")

    /**
     * 从文件管理器删除会话日志时走这条**联动**路径：关闭已打开会话 → 删索引项 → 回收附件。
     *
     * 只删文件会留下一条「点开是空」的会话记录，用户看到的就是「删了但没删」；
     * 反之直接删索引又会留下孤儿文件。两侧必须一起动。
     *
     * @return 是否按联动处理；false 表示该路径不是受管的会话日志（调用方按普通文件删除）。
     */
    internal fun deleteSessionLog(path: String): Boolean {
        val file = File(path)
        if (file.extension != "jsonl" || file.parentFile?.absolutePath != sessionLogsDir.absolutePath) {
            return false
        }
        val id = file.name.removeSuffix(".jsonl")
        // 索引里没有对应会话时（历史残留 / 已从 UI 删除），交给调用方直接删文件。
        val meta = storage().listSessions(null).firstOrNull { it.id == id } ?: return false
        val project = storage().listProjects().firstOrNull { it.id == meta.projectId }
        releaseSession(id)
        storage().deleteSession(id)
        project?.let { gcAttachments(it) }
        return true
    }
}

/**
 * 子智能体工具面：
 * - [SubagentType.CODING] 继承父会话可见的全部工具；
 * - [SubagentType.SEARCH] 只保留只读工具（read / ls / grep / find 等）；
 * - [SubagentType.BROWSER] 只保留 `browser_*` 与 `read` / `read_image`（后者仍受视觉模型
 *   门控，见 `SessionAssembly.toolEnabled`）。
 *
 * 纯函数：装配侧直接使用，JVM 用例也能逐项断言，不必搭一整个容器。
 */
internal fun subagentToolFilter(
    type: SubagentType,
    visible: (Tool) -> Boolean,
): (Tool) -> Boolean = when (type) {
    SubagentType.CODING -> visible
    SubagentType.SEARCH -> { tool -> visible(tool) && tool.readOnly }
    SubagentType.BROWSER -> { tool ->
        tool.name.startsWith(BROWSER_TOOL_PREFIX) ||
            (visible(tool) && (tool.name == "read" || tool.name == ReadImageToolName))
    }
}
