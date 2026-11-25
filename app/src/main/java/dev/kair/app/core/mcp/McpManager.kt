package dev.kair.app.core.mcp

import dev.kair.app.core.model.Part
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolException
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 一台服务器对 UI / 工具的可见状态。 */
sealed interface McpServerStatus {
    data object Idle : McpServerStatus
    data object Connecting : McpServerStatus
    data class Connected(val toolCount: Int) : McpServerStatus
    data class Error(val message: String) : McpServerStatus
}

/**
 * MCP 服务器连接管理器。
 *
 * 每台服务器一份独立会话（状态 + 锁 + 重试任务），互不阻塞 —— 一台连不上不影响
 * 其它服务器，也不会因为 UI 线程读状态而卡住。工具集只在**连接成功**后出现在
 * [tools]，`tools/list` 的顺序按服务器名 + 工具名排序，保证进入系统提示前缀的
 * 内容顺序确定（缓存稳定前缀契约）。
 *
 * 归属：整个应用（配置是全局设置）。生命周期：随进程；配置来自 SettingsStore，
 * 进程重建后由插件 activate 触发 [reconcile] 重建连接。并发：同一服务器的连接 /
 * 关闭串行（每会话一把锁），不同服务器并行。
 */
class McpManager(
    private val scope: CoroutineScope,
    private val configsProvider: () -> List<McpServerConfig>,
    private val saveConfigs: (List<McpServerConfig>) -> Unit,
    private val processHost: McpProcessHost,
    /**
     * stdio 子进程的工作目录。**没有打开的会话时为 null**：本类不做任何回退，
     * 拿不到就 fail-fast（把进程 cwd 绑到临时目录等于让模型在空目录里干活）。
     */
    private val workspace: () -> File?,
    /**
     * stdio 服务器是否已有可绑定的真实工作区：还没有打开的会话时不要拉起 stdio，
     * 必须等首次打开真实会话再加（由宿主调 [reconnectStdio]）。
     */
    private val stdioWorkspaceReady: () -> Boolean = { true },
    /** 连接 / 断开 / 工具开关变化后通知宿主重建工具终序。 */
    private val onToolsChanged: () -> Unit,
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    private val _status = MutableStateFlow<Map<String, McpServerStatus>>(emptyMap())
    val status: StateFlow<Map<String, McpServerStatus>> = _status.asStateFlow()

    /** 当前配置（从设置真源读取，供 UI 使用）。 */
    fun configs(): List<McpServerConfig> = configsProvider()

    /** 面板 / agent 工具用的一致快照：配置 + 状态 + 已发现工具名。 */
    data class ServerView(
        val config: McpServerConfig,
        val status: McpServerStatus,
        val tools: List<String>,
    )

    fun views(): List<ServerView> = configs().map { config ->
        val session = sessions[config.id]
        ServerView(
            config = config,
            status = if (!config.enabled) {
                McpServerStatus.Idle
            } else {
                session?.status ?: McpServerStatus.Idle
            },
            tools = session?.tools?.map { it.name }?.sorted().orEmpty(),
        )
    }

    /**
     * 让运行态对齐配置：新增连接、名称 / 地址变化重连、删除或禁用关闭。
     * 幂等，可在面板刷新、导入、开关变更后随时调用。
     */
    fun reconcile() {
        val desired = configsProvider().filter { it.enabled }.associateBy { it.id }
        (sessions.keys - desired.keys).forEach { id ->
            sessions.remove(id)?.let { closeSession(it) }
        }
        desired.values.forEach { config ->
            val existing = sessions[config.id]
            if (existing == null) {
                // 还没有真实工作区时不要拉起 stdio（见 stdioWorkspaceReady）：等首次打开
                // 会话后由 AppContainer 调 reconnectStdio()。
                if (config.transport is McpTransportConfig.Stdio && !stdioWorkspaceReady()) {
                    return@forEach
                }
                val session = McpSession(config)
                if (sessions.putIfAbsent(config.id, session) == null) {
                    publish(session, McpServerStatus.Connecting)
                    launchConnect(session)
                }
                return@forEach
            }
            val previousKey = existing.config.connectionKey()
            existing.config = config
            // 配置没变但停在 Error（网络 / rootfs 之前不可用）：reconcile 是显式刷新入口，
            // 借此重试一次，避免用户必须改配置才能恢复。
            if (previousKey != config.connectionKey() || existing.status is McpServerStatus.Error) {
                launchConnect(existing)
            }
        }
        onToolsChanged()
    }

    /** 已连接服务器暴露给模型的工具，顺序确定。 */
    fun tools(): List<Tool> = sessions.values
        .sortedBy { it.config.name }
        .flatMap { session ->
            if (session.status !is McpServerStatus.Connected) return@flatMap emptyList()
            session.tools
                .filter { it.name !in session.config.disabledTools }
                .sortedBy { it.name }
                .map { McpToolAdapter(this, session.config, it) }
        }

    /** 调用远端工具；未连接时抛出可读错误，由工具适配器转成错误结果。 */
    suspend fun callTool(serverId: String, toolName: String, arguments: JsonObject): McpCallResult {
        val session = sessions[serverId]
            ?: throw ToolException("MCP 服务器未连接：$serverId")
        val client = session.client
            ?: throw ToolException("MCP 服务器未连接：${session.config.name}")
        return client.callTool(toolName, arguments)
    }

    /**
     * 临时连接一台服务器并列出工具（面板「测试连接」/ agent 安装前自检用）。
     * 不影响已有会话。
     */
    suspend fun testServer(config: McpServerConfig): Result<List<McpRemoteTool>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val client = openClient(config)
                try {
                    client.connect(scope)
                    client.listTools()
                } finally {
                    client.close()
                }
            }
        }

    // ---- 配置变更 ----------------------------------------------------------

    fun upsert(config: McpServerConfig): Result<Unit> {
        val normalized = config.normalized()
        McpServerConfig.validateName(normalized.name)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val configs = configsProvider()
        if (configs.any { it.name == normalized.name && it.id != normalized.id }) {
            return Result.failure(IllegalArgumentException("服务器名已存在：${normalized.name}"))
        }
        // 编辑已有服务器时，调用方通常不知道单个工具的开关状态：保留原开关。
        val existing = configs.firstOrNull { it.id == normalized.id }
        val merged = if (normalized.disabledTools.isEmpty() && existing != null) {
            normalized.copy(disabledTools = existing.disabledTools)
        } else {
            normalized
        }
        val next = if (configs.any { it.id == merged.id }) {
            configs.map { if (it.id == merged.id) merged else it }
        } else {
            configs + merged
        }
        saveConfigs(next)
        reconcile()
        return Result.success(Unit)
    }

    fun remove(id: String): Boolean {
        val configs = configsProvider()
        if (configs.none { it.id == id }) return false
        saveConfigs(configs.filterNot { it.id == id })
        reconcile()
        return true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val configs = configsProvider()
        saveConfigs(configs.map { if (it.id == id) it.copy(enabled = enabled) else it })
        reconcile()
    }

    /** 单工具开关：只影响注册表，不重连。 */
    fun setToolEnabled(id: String, toolName: String, enabled: Boolean) {
        val configs = configsProvider()
        val current = configs.firstOrNull { it.id == id } ?: return
        val disabled = if (enabled) current.disabledTools - toolName else current.disabledTools + toolName
        if (disabled == current.disabledTools) return
        val updated = current.copy(disabledTools = disabled.sorted())
        saveConfigs(configs.map { if (it.id == id) updated else it })
        // 运行态同步：`tools()` 读的是会话自己的 config，只改设置不更新它就是「开关不生效」，
        // 模型那一侧要到重连 / 重启才看到变化（U41）。重连不是必须的：工具列表已经缓存。
        sessions[id]?.config = updated
        onToolsChanged()
    }

    /** 导入 `mcpServers` 配置；同名服务器整条替换。返回导入条数。 */
    fun importServers(raw: String): Result<Int> = runCatching {
        val parsed = McpConfigCodec.importServers(raw)
        if (parsed.isEmpty()) throw IllegalArgumentException("没有可导入的服务器")
        val current = configsProvider()
        val currentByName = current.associateBy { it.name }
        val currentById = current.associateBy { it.id }
        // 同名整条替换，但保留用户原有的单工具开关；id 若与另一台服务器撞车则重新生成，
        // 否则 setEnabled/remove 会按 id 同时命中两条。
        val imported = parsed.map { config ->
            val normalized = config.normalized()
            val sameName = currentByName[normalized.name]
            val collidedId = currentById[normalized.id]
            normalized.copy(
                id = if (collidedId != null && collidedId.name != normalized.name) "" else normalized.id,
                disabledTools = sameName?.disabledTools.orEmpty(),
            ).normalized()
        }
        val byName = imported.associateBy { it.name }
        val merged = current.filterNot { it.name in byName.keys } + byName.values
        saveConfigs(merged)
        reconcile()
        imported.size
    }

    /** 强制重连全部已启用服务器：重新握手并刷新 `tools/list`（面板「刷新」/ agent reload）。 */
    fun reconnectAll() {
        sessions.values.forEach { session ->
            if (session.config.enabled) launchConnect(session)
        }
    }

    /**
     * 只重连 stdio 服务器。它们的工作目录绑定在当时的容器工作区上：
     * 首次打开真实会话、或切换项目后必须重连，否则会一直读到旧项目的文件。
     */
    fun reconnectStdio() {
        if (!stdioWorkspaceReady()) return
        configsProvider()
            .filter { it.enabled && it.transport is McpTransportConfig.Stdio }
            .forEach { config ->
                val existing = sessions[config.id]
                if (existing == null) {
                    // rootfs 卸载时 closeStdioSessions 会移除会话；就绪后要能重建。
                    val session = McpSession(config)
                    if (sessions.putIfAbsent(config.id, session) == null) {
                        publish(session, McpServerStatus.Connecting)
                        launchConnect(session)
                    }
                } else {
                    existing.config = config
                    launchConnect(existing)
                }
            }
    }

    /**
     * 只关掉 stdio 会话（容器插件被禁用 / rootfs 被卸载时调用）。
     * 远程 HTTP / SSE 服务器与容器无关，不能一起断。
     */
    fun closeStdioSessions() {
        sessions.values
            .filter { it.config.transport is McpTransportConfig.Stdio }
            .forEach { session ->
                sessions.remove(session.config.id)
                closeSession(session)
            }
    }

    fun closeAll() {
        sessions.values.forEach { session ->
            session.retryJob?.cancel()
            session.client?.close()
        }
        sessions.clear()
        _status.value = emptyMap()
        onToolsChanged()
    }

    // ---- 连接 --------------------------------------------------------------

    private fun launchConnect(session: McpSession) {
        session.retryJob?.cancel()
        // 显式重连（面板 / 配置变化 / rootfs 就绪）重新给满重试额度。
        session.attempts = 0
        session.retryJob = scope.launch(Dispatchers.IO) {
            connect(session)
        }
    }

    private suspend fun connect(session: McpSession) {
        session.lock.withLock {
            if (sessions[session.config.id] !== session) return
            publish(session, McpServerStatus.Connecting)
            session.client?.close()
            session.client = null
            session.connectedKey = null
            val config = session.config
            var pending: McpClient? = null
            // 只有「当前已提交的 client」断开才改状态；握手阶段的断开由 catch 统一收口，
            // 否则会 publish 两次 Error、调度两条重试链。
            val holder = java.util.concurrent.atomic.AtomicReference<McpClient?>()
            try {
                pending = openClient(config) {
                    // 读循环线程回调：切回 IO 调度器再动状态机。
                    scope.launch(Dispatchers.IO) {
                        if (sessions[config.id] === session &&
                            holder.get() != null &&
                            session.client === holder.get() &&
                            session.config.connectionKey() == config.connectionKey()
                        ) {
                            publish(session, McpServerStatus.Error("连接已断开"))
                            scheduleRetry(session)
                        }
                    }
                }
                holder.set(pending)
                pending.connect(scope)
                val tools = pending.listTools()
                if (sessions[session.config.id] !== session ||
                    session.config.connectionKey() != config.connectionKey()
                ) {
                    pending.close()
                    return
                }
                session.client = pending
                pending = null
                session.connectedKey = config.connectionKey()
                session.tools = tools
                session.attempts = 0
                publish(session, McpServerStatus.Connected(tools.size))
                onToolsChanged()
            } catch (e: kotlinx.coroutines.CancellationException) {
                pending?.close()
                throw e
            } catch (e: Exception) {
                pending?.close()
                // 会话可能已在等待期间被删除 / 替换：不要给已移除的 id 写回状态。
                if (sessions[session.config.id] === session) {
                    publish(session, McpServerStatus.Error(e.message ?: e.javaClass.simpleName))
                    scheduleRetry(session)
                }
            }
        }
    }

    /**
     * 指数退避重连：1s → 2s → 4s … 上限 30s，最多 [MAX_RETRY_ATTEMPTS] 次。
     * 达到上限后停在 Error，等用户点「重连」或配置变化再试。
     */
    private fun scheduleRetry(session: McpSession) {
        if (session.attempts >= MAX_RETRY_ATTEMPTS) return
        session.attempts += 1
        val delayMs = (BASE_RETRY_DELAY_MS shl (session.attempts - 1).coerceAtMost(4))
            .coerceAtMost(MAX_RETRY_DELAY_MS)
        session.retryJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (sessions[session.config.id] === session && session.config.enabled) {
                connect(session)
            }
        }
    }

    private suspend fun openClient(
        config: McpServerConfig,
        onDisconnected: () -> Unit = {},
    ): McpClient {
        val transport: McpTransport = when (val t = config.transport) {
            is McpTransportConfig.Http -> StreamableHttpMcpTransport(t.url, t.headers)
            is McpTransportConfig.Sse -> SseMcpTransport(t.url, t.headers)
            is McpTransportConfig.Stdio -> {
                // 没有打开的会话就没有可绑定的工作区：报错而不是退回临时目录。
                val cwd = workspace()
                    ?: throw McpProtocolException("stdio 服务器需要已打开的会话：请先打开一个会话再连接")
                val process = processHost.spawn(t, cwd)
                    ?: throw McpProtocolException("stdio 服务器需要 Linux 容器：请先安装 rootfs")
                StdioMcpTransport(process)
            }
        }
        return McpClient(
            clientName = config.name,
            transport = transport,
            timeoutMillis = config.timeoutSeconds.coerceIn(5, 600) * 1000L,
            onDisconnected = onDisconnected,
        )
    }

    private fun closeSession(session: McpSession) {
        session.retryJob?.cancel()
        session.retryJob = null
        session.client?.close()
        session.client = null
        _status.update { it - session.config.id }
        onToolsChanged()
    }

    private fun publish(session: McpSession, value: McpServerStatus) {
        session.status = value
        _status.update { it + (session.config.id to value) }
    }

    // config 与下面五个字段一样跨线程读写：setToolEnabled / reconcile 在调用方线程写，
    // tools() 在装配线程读 —— 没有 @Volatile 时「开关立刻生效」缺可见性保障（U41，B 评审补）。
    private class McpSession(@Volatile var config: McpServerConfig) {
        val lock = Mutex()

        @Volatile
        var client: McpClient? = null

        @Volatile
        var connectedKey: String? = null

        @Volatile
        var tools: List<McpRemoteTool> = emptyList()

        @Volatile
        var status: McpServerStatus = McpServerStatus.Idle

        @Volatile
        var attempts: Int = 0

        var retryJob: Job? = null
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 5
        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }
}

/**
 * 一件远端工具在本地的 [Tool] 形态。
 *
 * 工具名带服务器命名空间（`mcp__<server>__<tool>`），避免与内置工具 / 其它服务器
 * 撞名；参数 Schema 先内联 `$ref` 再交给 provider。
 */
private class McpToolAdapter(
    private val manager: McpManager,
    private val server: McpServerConfig,
    private val remote: McpRemoteTool,
) : Tool {
    override val name: String = mcpToolName(server.name, remote.name)

    override val description: String = remote.description.ifBlank {
        "MCP tool '${remote.name}' from server '${server.name}'"
    }

    override val parameters: JsonObject = inlineToolSchema(remote.inputSchema)

    /**
     * 远端工具的审批类别：MCP 的 `annotations.readOnlyHint` / `destructiveHint`
     * 映射为本地 READ / DELETE；没有声明时按 EXECUTE 处理（默认不弹窗，用户可在
     * 审批面板打开「执行类工具需审批」统一拦截）。
     */
    override val category: ToolCategory = when {
        remote.annotations?.bool("readOnlyHint") == true -> ToolCategory.READ
        remote.annotations?.bool("destructiveHint") == true -> ToolCategory.DELETE
        else -> ToolCategory.EXECUTE
    }

    /** 远端工具的副作用未知：保守地不与其它工具并发。 */
    override val isParallelSafe: Boolean = false

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val result = manager.callTool(server.id, remote.name, args)
        val text = result.contents.mapNotNull { it.text }.joinToString("\n")
        val images = result.contents.mapNotNull { content ->
            content.imageBase64?.let {
                Part.Image(base64 = it, mediaType = content.imageMimeType ?: "image/png")
            }
        }
        val structured = result.structured?.takeIf { it != JsonObject(emptyMap()) }?.toString()
        val body = text.ifBlank {
            structured ?: if (images.isEmpty()) "MCP tool returned no content" else ""
        }
        return ToolResult(content = body, isError = result.isError, images = images)
    }
}

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
