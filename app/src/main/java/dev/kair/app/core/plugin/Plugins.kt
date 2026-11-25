package dev.kair.app.core.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.ai.ProviderDef
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件契约：声明元数据，并在 [Plugin.activate] 里把贡献点（工具 / 面板 / 动作 / 设置分区 /
 * 单例能力）注册进共享的 [PluginRegistry]。
 */
interface Plugin {
    /** Stable reverse-DNS style id, e.g. `dev.kair.plugin.subagents`. */
    val id: String
    val name: String
    val description: String

    /** 显示文案的资源 id：非 null 时宿主优先取 `strings.xml`，否则逐字回退上面的字面量。
     *  内置插件据此满足 §4；第三方插件没有资源上下文，不声明即可。解析只在 `PluginDisplayText.kt` 一处。 */
    val nameRes: Int? get() = null
    val descriptionRes: Int? get() = null

    /**
     * 「核心」标记：关闭会静默降级应用的基础能力（存储 / 命令后端 / 传输层 /
     * 系统提示 / 审批等）。宿主据此在禁用前弹确认框，避免用户误关。
     * 默认 false，第三方插件无需声明。
     */
    val core: Boolean get() = false

    /** 插件版本号（如 `1.8.2`）：仅用于列表展示；回退规则与宿主版本范围判据见 `docs/插件接口契约.md`。 */
    val version: String get() = ""

    /** 注册本插件的贡献点；宿主可能重复调用（重载插件），实现须幂等友好。 */
    fun activate(ctx: PluginContext)
}

/**
 * 面板的宿主装饰形态（U86 §4 F）。
 * [Standard] = 现状：宿主渲染 TopAppBar + 页脚工具区；
 * [Immersive] = 沉浸式：宿主**不渲染** TopAppBar 与页脚工具区，内容自己吃满窗口Insets
 * （图形桌面面板用；「退出沉浸」是面板内部行为，不改这里的声明）。
 */
enum class PanelChrome { Standard, Immersive }

/** 插件贡献的 UI 面板：[content] 渲染正文，标题与返回导航由宿主提供（[chrome] 为 Immersive 时不提供）。 */
data class PanelContribution(
    val pluginId: String = "",
    val id: String,
    val title: String,
    /** [title] 的字符串资源 id，null = 逐字回退。内置插件据此走 `strings.xml`（§4）。 */
    val titleRes: Int? = null,
    /** Short glyph/emoji shown next to the title. */
    val icon: String? = null,
    val order: Int = 100,
    /** 宿主装饰形态；默认 [PanelChrome.Standard]（与旧行为逐字一致，第三方 dex 不受影响）。 */
    val chrome: PanelChrome = PanelChrome.Standard,
    val content: @Composable () -> Unit,
)
// titleRes 放得进本类的构造器，是因为**没有插件 dex 会构造它**：第三方 SDK 的 PluginContext
// 没有 panel() 通道。反例见 SettingsPanelSpec.titleRes（dex 自己构造 → 只能进类体）。

/** A runnable command contributed by a plugin. */
data class ActionContribution(
    val pluginId: String = "",
    val id: String,
    val title: String,
    val description: String = "",
    val group: String = "Actions",
    val run: () -> Unit,
)

/** 传给斜杠命令的运行时上下文：命令在装配期全局注册，执行时作用于当前打开的会话。 */
class CommandContext(
    /** The open session, or null when no chat is on screen. */
    val session: AgentSession?,
    /** Text typed after the command name (may be empty). */
    val args: String,
)

/** A slash command contributed by a plugin, e.g. `/compact`. */
data class CommandContribution(
    val pluginId: String = "",
    /** Name without the leading slash. */
    val name: String,
    val title: String,
    val description: String = "",
    val run: (CommandContext) -> Unit,
)

/** A Compose section appended to the Settings screen by a plugin. */
data class SettingsSectionContribution(
    val pluginId: String = "",
    val id: String,
    val title: String,
    val order: Int = 100,
    val content: @Composable () -> Unit,
)

/** 工具条目：记录贡献者，供卸载时回收。 */
private data class ToolEntry(val pluginId: String, val tool: Tool)

/** 插件声明的数据项：[path] 为工作区相对或绝对路径，[label] 供卸载确认框展示。 */
data class PluginData(val path: String, val label: String)

/**
 * 单例能力的提供者历史：按插件 id 记录注册顺序，**后注册者胜**。
 *
 * 卸载时移除记录、「当前生效者」回滚到上一个提供者；全部移除后返回 null —— 内核回退默认实现
 * （本地 shell / 本地存储 / 不压缩），所以「社区插件替换原装后端 → 卸载还原」无需重启。
 */
private class ProviderHistory<T : Any> {
    // 并发：注册在插件装载线程（`install_plugin` 工具跑在会话协程里），读在装配线程。
    private val entries = CopyOnWriteArrayList<Pair<String, T>>()

    /** 注册（同一插件重复注册时替换自己的记录，仍排在末位）。 */
    fun set(pluginId: String, provider: T) {
        entries.removeAll { it.first == pluginId }
        entries.add(pluginId to provider)
    }

    /** 卸载：移除该插件的全部记录。 */
    fun remove(pluginId: String) {
        entries.removeAll { it.first == pluginId }
    }

    /** 当前生效的提供者；null 表示无插件提供。 */
    fun current(): T? = entries.lastOrNull()?.second
}

/**
 * 收集已安装插件的贡献点（工具 / 面板 / 动作 / 设置分区 / 单例能力），宿主与内置插件共用它。
 *
 * 覆盖语义（统一为**后注册者胜**）：动态插件注册在内置插件之后，因此可以用
 * 同名工具覆盖内置工具；内置插件之间的同名冲突由注册顺序决定（确定性）。
 */
class PluginRegistry(
    /**
     * 每个插件 id → 其设置存储。宿主注入落盘实现（SharedPreferences）；缺省为
     * 进程内兜底（单测 / 未接存储的宿主）。
     */
    private val pluginSettingsFactory: (String) -> PluginSettingsStore = { InMemoryPluginSettingsStore() },
) {
    private val plugins = CopyOnWriteArrayList<Plugin>()
    private val tools = CopyOnWriteArrayList<ToolEntry>()
    private val panels = mutableStateListOf<PanelContribution>()
    private val actions = mutableStateListOf<ActionContribution>()
    private val commands = mutableStateListOf<CommandContribution>()
    private val settingsSections = mutableStateListOf<SettingsSectionContribution>()

    /** 声明式设置面板：插件 id → 规格。UI 侧用内置控件渲染。 */
    private val settingsPanels = mutableStateListOf<Pair<String, SettingsPanelSpec>>()

    /** 声明式 UI 槽位：插件 id → 槽位。UI 侧按 area 取用并用内置控件渲染。 */
    private val slotContributions = mutableStateListOf<Pair<String, SlotContribution>>()

    /**
     * 插件声明的数据项：插件 id → 数据列表。卸载确认框据此列出待删数据。
     * 卸载（停用）时**保留**——用户可能稍后再彻底删除并清理数据；删除流程显式调用
     * [clearPluginData] 回收。
     */
    private val pluginData = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** 当前生效的上下文/压缩策略；单例能力，按提供者历史管理（卸载回滚）。 */
    private val contextStrategies = ProviderHistory<ContextStrategy>()

    /** 系统提示段落贡献者（有序），记录贡献者以便卸载回收。 */
    private val systemPromptContributors = CopyOnWriteArrayList<Pair<String, SystemPromptContributor>>()

    /** 当前生效的存储后端；单例能力，按提供者历史管理（卸载回滚）。 */
    private val storageBackends = ProviderHistory<StorageBackend>()

    /** 当前生效的命令执行后端；单例能力，按提供者历史管理（卸载回滚）。 */
    private val commandRunners = ProviderHistory<CommandRunner>()

    /** Middleware hooks contributed by plugins; read by [dev.kair.app.core.agent.AgentSession]. */
    val hooks = HookBus()

    /**
     * 事件订阅者（插件 id → 处理器）。**只读通知**，不参与装配。
     *
     * 与 [hooks] 的区别：钩子要被返回値改写数据流、契约要求纯函数；事件只告知
     * “发生了什么”，允许副作用（写文件 / 计数 / 起后台任务）。
     */
    private val eventHandlers = CopyOnWriteArrayList<Pair<String, (AgentEvent) -> Unit>>()

    /** 插件卸载时的回调（停定时器 / 落盘状态）。 */
    private val deactivateHandlers = CopyOnWriteArrayList<Pair<String, () -> Unit>>()

    /**
     * 插件后台任务的作用域（按插件隔离）。
     *
     * 生命周期：首次 [scopeFor] 时创建，**卸载 / 删除时取消**。归属是插件而非会话，
     * 因为定时器这类任务与具体会话无关（进程重建后由插件重新 activate 建立）。
     */
    private val pluginScopes = ConcurrentHashMap<String, CoroutineScope>()

    /** 会话/子智能体桥；由宿主注入（AppContainer）。未注入时为 null，插件自行降级。 */
    private var agentBridge: AgentBridge? = null

    /** 插件私有数据根目录；由宿主注入（应用私有目录下的 plugin-data）。 */
    private var pluginDataRoot: File? = null

    fun setAgentBridge(bridge: AgentBridge?) {
        agentBridge = bridge
    }

    internal fun agentBridgeOrNull(): AgentBridge? = agentBridge

    fun setPluginDataRoot(dir: File?) {
        pluginDataRoot = dir
    }

    internal fun addAgentEventHandler(pluginId: String, handler: (AgentEvent) -> Unit) {
        eventHandlers.add(pluginId to handler)
    }

    internal fun addDeactivateHandler(pluginId: String, handler: () -> Unit) {
        deactivateHandlers.add(pluginId to handler)
    }

    internal fun scopeFor(pluginId: String): CoroutineScope =
        pluginScopes.computeIfAbsent(pluginId) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    internal fun dataDirFor(pluginId: String): File? {
        val root = pluginDataRoot ?: return null
        val dir = File(root, pluginId)
        return runCatching { dir.mkdirs(); dir.takeIf { it.isDirectory } }.getOrNull()
    }

    /**
     * 广播一个事件。**永不抛出**：插件抛异常不能拖垮会话主循环（与提示段贡献点的
     * 异常隔离同一原则）；处理器是同步调用，必须自己快速返回。
     */
    fun emitEvent(event: AgentEvent) {
        // 拷一份快照：处理器里若卸载插件（改 list）不应影响本次遍历。
        eventHandlers.toList().forEach { (_, handler) -> runCatching { handler(event) } }
    }

    val installed: List<Plugin> get() = plugins

    fun install(plugin: Plugin): PluginRegistry {
        if (plugins.any { it.id == plugin.id }) return this
        plugins.add(plugin)
        try {
            plugin.activate(PluginContext(this, plugin.id))
        } catch (t: Throwable) {
            // 半途失败的插件必须整份回滚：否则它已登记的钩子 / 工具 / 订阅会一直生效，
            // 而列表里看不到它，用户无法通过「禁用」清掉（U40）。
            uninstall(plugin.id)
            throw t
        }
        return this
    }

    fun installAll(list: Iterable<Plugin>): PluginRegistry {
        list.forEach { install(it) }
        return this
    }

    /**
     * 卸载一个插件：移除其全部贡献（工具 / 面板 / 动作 / 命令 / 设置分区 / 提示段），
     * 单例能力（命令后端 / 存储 / 压缩策略）按提供者历史栈回滚到上一个提供者；
     * 没有更早的提供者时内核回退默认实现。
     */
    fun uninstall(pluginId: String): Boolean {
        val plugin = plugins.firstOrNull { it.id == pluginId } ?: return false
        plugins.remove(plugin)
        tools.removeAll { it.pluginId == pluginId }
        systemPromptContributors.removeAll { it.first == pluginId }
        panels.removeAll { it.pluginId == pluginId }
        actions.removeAll { it.pluginId == pluginId }
        commands.removeAll { it.pluginId == pluginId }
        settingsSections.removeAll { it.pluginId == pluginId }
        settingsPanels.removeAll { it.first == pluginId }
        slotContributions.removeAll { it.first == pluginId }
        contextStrategies.remove(pluginId)
        storageBackends.remove(pluginId)
        commandRunners.remove(pluginId)
        // 先回收事件订阅与钩子，再通知插件停手，最后取消作用域：
        // 顺序反了会在插件已停手后仍收到事件，或在作用域还活着时又被派发一轮。
        eventHandlers.removeAll { it.first == pluginId }
        hooks.removeOwner(pluginId)
        deactivateHandlers.filter { it.first == pluginId }.forEach { runCatching { it.second() } }
        deactivateHandlers.removeAll { it.first == pluginId }
        pluginScopes.remove(pluginId)?.cancel()
        return true
    }

    internal fun addTool(pluginId: String, tool: Tool) {
        // 后注册者胜：动态插件可覆盖内置工具（如脚本版 container_shell）。
        tools.removeAll { it.tool.name == tool.name }
        tools.add(ToolEntry(pluginId, tool))
    }

    internal fun addPanel(panel: PanelContribution) {
        panels.add(panel)
        panels.sortBy { it.order }
    }

    internal fun addAction(action: ActionContribution) {
        if (actions.none { it.id == action.id }) actions.add(action)
    }

    internal fun addCommand(command: CommandContribution) {
        if (commands.none { it.name == command.name }) commands.add(command)
    }

    internal fun addSettingsSection(section: SettingsSectionContribution) {
        settingsSections.add(section)
        settingsSections.sortBy { it.order }
    }

    internal fun addSettingsPanel(pluginId: String, spec: SettingsPanelSpec) {
        // 同一插件重复声明同一面板 id 时替换旧规格（重载插件会再次 activate）。
        settingsPanels.removeAll { it.first == pluginId && it.second.id == spec.id }
        settingsPanels.add(pluginId to spec)
        settingsPanels.sortBy { it.second.order }
    }

    internal fun addSlot(pluginId: String, slot: SlotContribution) {
        // 同一插件重复声明同一槽位 id 时替换旧声明（重载插件会再次 activate）。
        slotContributions.removeAll { it.first == pluginId && it.second.id == slot.id }
        slotContributions.add(pluginId to slot)
        slotContributions.sortBy { it.second.order }
    }

    /** 记录一个插件数据声明；同一 (插件, 路径) 重复声明时替换旧标签。 */
    internal fun addPluginData(pluginId: String, path: String, label: String) {
        if (path.isBlank()) return
        pluginData.removeAll { it.first == pluginId && it.second == path }
        pluginData.add(Triple(pluginId, path, label))
    }

    /** 某插件声明的数据项（按声明顺序）。 */
    fun pluginData(pluginId: String): List<PluginData> =
        pluginData.filter { it.first == pluginId }.map { PluginData(it.second, it.third) }

    /** 清除某插件的数据声明（删除插件、清理数据后调用）。 */
    fun clearPluginData(pluginId: String) {
        pluginData.removeAll { it.first == pluginId }
    }

    /** 某插件的设置存储；宿主据此注入落盘实现。 */
    fun pluginSettings(pluginId: String): PluginSettingsStore = pluginSettingsFactory(pluginId)

    internal fun addProvider(def: ProviderDef) {
        ModelCatalog.register(def)
    }

    internal fun addTransport(transport: ProviderTransport) {
        ProviderTransports.register(transport)
    }

    internal fun addSystemPromptContributor(pluginId: String, contributor: SystemPromptContributor) {
        systemPromptContributors.add(pluginId to contributor)
    }

    internal fun setContextStrategy(pluginId: String, strategy: ContextStrategy) {
        contextStrategies.set(pluginId, strategy)
    }

    internal fun setStorage(pluginId: String, backend: StorageBackend) {
        storageBackends.set(pluginId, backend)
    }

    internal fun setCommandRunner(pluginId: String, runner: CommandRunner) {
        commandRunners.set(pluginId, runner)
    }

    fun tools(): List<Tool> = tools.map { it.tool }

    /**
     * 工具及其**贡献者插件 id**（注册顺序即终序）。宿主据此把「工具开关」渲染到
     * 各自插件的面板里（见 `AppContainer.toolCatalog`）。关闭某工具只影响宿主是否
     * 注册它，不改这里的贡献记录 —— 重新开启时无需重新装载插件。
     */
    fun toolEntries(): List<Pair<String, Tool>> = tools.map { it.pluginId to it.tool }
    fun panels(): List<PanelContribution> = panels.toList()
    fun actions(): List<ActionContribution> = actions.toList()
    fun commands(): List<CommandContribution> = commands.toList()
    fun settingsSections(): List<SettingsSectionContribution> = settingsSections.toList()

    /** 声明式设置面板（插件 id → 规格），按 order 升序；UI 用内置控件渲染。 */
    fun settingsPanels(): List<Pair<String, SettingsPanelSpec>> = settingsPanels.toList()

    /**
     * 声明式 UI 槽位（插件 id → 槽位），按 order 升序。传 [area] 只取该区域的槽位；
     * 传 null 取全部。UI 用内置控件按 [SlotElement] 类型渲染。
     */
    fun slots(area: SlotArea? = null): List<Pair<String, SlotContribution>> =
        if (area == null) slotContributions.toList()
        else slotContributions.filter { it.second.area == area }

    fun contextStrategy(): ContextStrategy? = contextStrategies.current()

    /** 按 order 升序（同序按注册顺序）返回系统提示段落贡献者。 */
    fun systemPromptContributors(): List<SystemPromptContributor> =
        systemPromptContributors.sortedBy { it.second.order }.map { it.second }

    /** 当前存储后端；无插件提供时为 null，由内核回退到内置本地实现。 */
    fun storage(): StorageBackend? = storageBackends.current()

    /** 当前命令执行后端；无插件提供时为 null，由内核回退到内置本地 shell 实现。 */
    fun commandRunner(): CommandRunner? = commandRunners.current()
}
