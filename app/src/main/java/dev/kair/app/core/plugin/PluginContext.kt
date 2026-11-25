package dev.kair.app.core.plugin

import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.ai.ProviderDef
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Handed to [Plugin.activate] so a plugin can contribute without owning the registry. */
class PluginContext internal constructor(
    private val registry: PluginRegistry,
    private val pluginId: String,
) {
    /** Contribute an agent tool, reachable by the model once installed. */
    fun tool(tool: Tool) = registry.addTool(pluginId, tool)

    /** 贡献一个应用面板（Compose 界面分区）。 */
    fun panel(panel: PanelContribution) =
        registry.addPanel(panel.copy(pluginId = pluginId))

    /**
     * 声明一个**设置面板**（数值 / 开关 / 文本）：宿主用内置控件渲染成一块可点击
     * 进入的面板，值按 key 落到本插件的设置存储。第三方（动态）插件经此获得
     * 与内置插件同等的「可配置」能力，但不必接触 Compose。
     */
    fun settingsPanel(spec: SettingsPanelSpec) = registry.addSettingsPanel(pluginId, spec)

    /**
     * 声明一个 **UI 槽位**：宿主把其中的控件（按钮 / 开关 / 文字 / 矢量图标）渲染进
     * 会话页顶部栏 / 输入框底部区。与 [settingsPanel] 同为「插件声明数据、宿主渲染」
     * 的贡献点——插件不接触 Compose，UI 不与应用的 Compose 版本耦合。
     */
    fun slot(slot: SlotContribution) = registry.addSlot(pluginId, slot)

    /**
     * 声明本插件生成的**数据路径**（工作区相对或绝对），供宿主在卸载 / 删除时提示
     * 并一并清理（如记忆插件的 `.kair/memory.md`）。可选——不声明则卸载只回收贡献点。
     */
    fun declareData(path: String, label: String) = registry.addPluginData(pluginId, path, label)

    /** 本插件的设置存取面：读取用户在设置面板里改过的值。 */
    val settings: PluginSettingsStore get() = registry.pluginSettings(pluginId)

    /** Contribute a runnable action surfaced in the Actions list. */
    fun action(action: ActionContribution) =
        registry.addAction(action.copy(pluginId = pluginId))

    /** Contribute a slash command invocable from the chat composer. */
    fun command(command: CommandContribution) =
        registry.addCommand(command.copy(pluginId = pluginId))

    /** Contribute a Compose section appended to the Settings screen. */
    fun settingsSection(section: SettingsSectionContribution) =
        registry.addSettingsSection(section.copy(pluginId = pluginId))

    /** Register a provider/model endpoint so it shows up in the provider picker. */
    fun provider(def: ProviderDef) = registry.addProvider(def)

    /**
     * Provide a Provider 传输层（流式协议解析）。`ProviderDef.transportId` 通过它解析；
     * 未注册的 id 会让请求降级为一个 Failure，而不是中断内核。
     */
    fun transport(transport: ProviderTransport) = registry.addTransport(transport)

    /**
     * 贡献一段系统提示（有序）。同 `order` 按注册顺序拼接；返回 null/空串即不贡献。
     * 段内容进入缓存稳定前缀，必须是确定性的。
     */
    fun systemPrompt(contributor: SystemPromptContributor) =
        registry.addSystemPromptContributor(pluginId, contributor)

    /**
     * Provide the storage backend（项目 / 会话 / 消息持久化）。
     * 单例能力：后注册者覆盖先注册者；无插件提供时内核回退到内置本地文件实现。
     * 卸载时按提供者历史栈回滚到上一个提供者。
     */
    fun storage(backend: StorageBackend) = registry.setStorage(pluginId, backend)

    /**
     * Provide the context/compaction strategy (何时压缩、如何摘要)。
     * 单例能力：后注册者覆盖先注册者；无插件提供时内核降级为不压缩。
     * 卸载时按提供者历史栈回滚到上一个提供者。
     */
    fun contextStrategy(strategy: ContextStrategy) = registry.setContextStrategy(pluginId, strategy)

    /**
     * Provide the command execution backend（`container_shell` 工具跑命令的后端）。
     * 单例能力：后注册者覆盖先注册者；无插件提供时内核回退到内置的本地 shell 实现。
     * 卸载时按提供者历史栈回滚到上一个提供者。
     */
    fun commandRunner(runner: CommandRunner) = registry.setCommandRunner(pluginId, runner)

    /**
     * 订阅智能体事件（只读通知）：回合结束 / 工具执行 / 压缩。
     *
     * 这是插件做「每 N 回合整理一次」这类**副作用**需求的正当入口；数回合不要用
     * `onContext`（它属于装配路径，钩子契约要求纯函数）。
     */
    fun onAgentEvent(handler: (AgentEvent) -> Unit) = registry.addAgentEventHandler(pluginId, handler)

    /** 插件即将被卸载：在这里停定时器 / 落盘状态。 */
    fun onDeactivate(handler: () -> Unit) = registry.addDeactivateHandler(pluginId, handler)

    /** 插件后台任务作用域；卸载时被取消，因此定时器不会在插件消失后继续跑。 */
    val scope: CoroutineScope get() = registry.scopeFor(pluginId)

    /** 会话与子智能体桥；宿主未注入时为 null。 */
    val agents: AgentBridge? get() = registry.agentBridgeOrNull()

    /** 插件私有数据目录（不上界面），用于存“上次处理到第几回合”这类状态。 */
    val dataDir: File? get() = registry.dataDirFor(pluginId)

    // ---- middleware hooks (ordered, may rewrite core data flow) -------------

    /** Rewrite an outbound user turn before it becomes a message. */
    fun onInput(handler: (String) -> String?) = registry.hooks.onInput(pluginId, handler)

    /** Rewrite the transcript for a single request (not persisted). */
    fun onContext(handler: (List<ChatMessage>) -> List<ChatMessage>?) =
        registry.hooks.onContext(pluginId, handler)

    /**
     * Inspect/rewrite a tool call, or block it before execution.
     * 处理器是挂起的（审批闸门需要等待用户决定）。
     */
    fun onToolCall(handler: suspend (ToolCallHook) -> ToolCallOutcome?) =
        registry.hooks.onToolCall(pluginId, handler)

    /** Inspect/rewrite a tool result before it is fed back to the model. */
    fun onToolResult(handler: (ToolResultHook) -> ToolResult?) =
        registry.hooks.onToolResult(pluginId, handler)

    /** Add or rewrite provider request headers. */
    fun onBeforeProviderHeaders(handler: (Map<String, String>) -> Map<String, String>?) =
        registry.hooks.onBeforeProviderHeaders(pluginId, handler)
}
