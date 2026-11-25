package dev.kair.plugin.api

import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.ai.ProviderDef
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.EmptyPluginScope
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.plugin.SlotArea
import dev.kair.app.core.plugin.SlotContribution
import dev.kair.app.core.plugin.ToolCallHook
import dev.kair.app.core.plugin.ToolCallOutcome
import dev.kair.app.core.plugin.ToolResultHook
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 热插拔插件 SDK（`.kplug`，manifest `kind: "dex"`）。
 *
 * 这是唯一面向第三方编译的表面：宿主把 `classes.dex` 装入独立的 DexClassLoader
 * 后实例化 [Plugin] 实现类。SDK 本身**不含 Compose**：UI 能力以**声明式**方式
 * 暴露 —— 插件用 [PluginContext.settingsPanel] 声明设置面板（开关 / 数值 / 文本 /
 * 下拉 / 按钮 / 文件查看编辑），
 * 用 [PluginContext.slot] 在顶部 / 底部槽位放置控件（按钮 / 开关 / 文字 / 矢量图标），
 * 宿主用内置控件渲染，插件经 [PluginContext.settings] 读写值。贡献面 = 工具 /
 * 传输层 / 系统提示 / 声明式设置面板 / 声明式 UI 槽位 / 三个单例后端 / 五个钩子。
 *
 * 打包规则（重要）：
 * - 插件编译时依赖本模块，但**不得**把契约类打进自己的 dex —— 宿主按父委派
 *   提供这些类；重复打包会导致同名类不同加载器、触发 ClassCastException。
 * - 清单的 `targetApi` 不得高于 [VERSION]，否则宿主拒绝装载（前向兼容靠它）。
 */
object KairApi {
    /**
     * 插件 API 大版本。
     *
     * - v1：工具 / 传输层 / 系统提示 / 三个单例后端 / 五个钩子。
     * - v2：新增声明式设置面板（[PluginContext.settingsPanel]）与设置存储
     *   （[PluginContext.settings]）。
     * - v3：新增声明式 UI 槽位（[PluginContext.slot]）：顶部 / 底部可放控件与矢量图标。
     * - v4：槽位新增面板导航（[dev.kair.app.core.plugin.SlotElement.Panel]）与选项弹窗
     *   （[dev.kair.app.core.plugin.SlotElement.Picker]）；新增数据路径声明
     *   （[PluginContext.declareData]），卸载时可提示一并清理插件数据。
     * - v5：声明式设置面板新增**文件查看 / 编辑**字段
     *   （[dev.kair.app.core.plugin.SettingField.FileEditor]）：插件声明一个工作区相对
     *   路径，宿主用内置编辑器查看 / 编辑该文件（读写走与 `read`/`write` 同源的文件后端）。
     * - v6：槽位交互回调新增**会话上下文**
     *   （[dev.kair.app.core.plugin.SlotContext]：会话 id + 工作区路径，供插件按会话隔离操作）；
     *   [dev.kair.app.core.plugin.SlotElement.Button] 新增 `confirm` / `confirmMessage`
     *   （点击先弹确认弹窗，确认后再执行）。**注意**：回调签名自此变化，v6 前编译的
     *   槽位插件需按新签名重编译。
     * - v7：[dev.kair.app.core.agent.SystemPromptContext] 新增 `workspace`
     *   （宿主侧真实工作区路径）。容器生效时 `cwd` 是容器内挂载点，插件读工作区文件
     *   必须改用 `workspace`。
     * - v8：新增**观察通道**与**运行时能力**（均带默认实现，旧插件不受影响）：
     *   [PluginContext.onAgentEvent]（回合结束 / 工具执行 / 压缩，只读通知）、
     *   [PluginContext.scope] + [PluginContext.onDeactivate]（后台任务及其卸载时的清理）、
     *   [PluginContext.agents]（给会话发消息 / 跑子智能体 / 读回合摘要）、
     *   [PluginContext.dataDir]（插件私有状态）。
     * - v9：删除 `CommandRunner.environmentNote`（通道事实已由静态系统
     *   提示的 `container_shell` 守则覆盖，动态 note 改为只讲 root 授权）。**破坏性**：旧插件若
     *   覆盖了该成员，需删掉这一处再重编译。
     */
    const val VERSION = 9
}

/**
 * 热插拔插件的入口契约。实现类需无参构造，类名写入清单的 `entryClass`。
 *
 * 生命周期：宿主 `install` 时调用一次 [activate]；`uninstall` 只回收贡献点
 * （类本身驻留内存直到进程重启）。v1 没有 deactivate 回调。
 */
interface Plugin {
    /** 稳定标识，建议反向域名，如 `com.community.myplugin`。 */
    val id: String
    val name: String
    val description: String

    /**
     * 插件版本号，如 `1.8.2`。缺省空串表示未声明，宿主回退到清单 `manifest.version`。
     *
     * 仅用于展示（插件列表的版本徽标）与用户判断是否需要更新，不参与兼容性检查；
     * 宿主版本范围请用清单的 `minKairVersion` / `maxKairVersion`。
     */
    val version: String get() = ""

    /** 注册贡献点；实现应是幂等友好的（重载插件会再次调用）。 */
    fun activate(ctx: PluginContext)
}

/**
 * 热插拔插件拿到的贡献上下文。方法与宿主内置插件一致，但 UI 能力是**声明式**的：
 * 只能声明设置面板（[settingsPanel]）并读写值（[settings]），拿不到 Compose。
 *
 * 单例能力（[storage] / [contextStrategy] / [commandRunner]）：后注册者覆盖
 * 先注册者；插件卸载时宿主按提供者历史栈回滚到上一个提供者（没有则回退内核
 * 默认实现），因此替换内置后端后**卸载即还原**，无需重启。
 */
interface PluginContext {
    /** 贡献一个工具，模型按名调用。同名时后注册者胜。 */
    fun tool(tool: Tool)

    /**
     * 声明一个**设置面板**：宿主把 [SettingsPanelSpec.fields] 用内置控件
     * （开关 / 数值 / 文本 / 下拉 / 按钮 / 文件查看编辑）渲染成一块可点击进入的面板，
     * 用户改动经 [settings] 持久化。插件无需（也不能）接触 Compose —— 面板 UI 由宿主提供。
     */
    fun settingsPanel(spec: SettingsPanelSpec)

    /**
     * 声明一个**UI 槽位** [SlotContribution]：宿主把其中的控件（按钮 / 开关 / 文字 /
     * 键值 chip / 圆环 / 面板入口 / 选项弹窗，均支持一段 SVG 矢量图标）渲染进输入框底部区
     * （[SlotArea.BOTTOM]）。键值 chip 与圆环和会话页控件
     * **共用同一套渲染**。控件交互回调收到本插件的 [settings] 存储面与
     * [dev.kair.app.core.plugin.SlotContext]（触发它的会话 id / 工作区），插件据此把操作
     * 限定在**触发它的那个会话**。同样不接触 Compose —— 槽位 UI 由宿主用内置控件渲染。
     *
     * 顶部区（[SlotArea.TOP]）已停用（U101）：声明了也不会渲染，一律用 BOTTOM。
     */
    fun slot(slot: SlotContribution)

    /** 本插件的设置存取面：读取用户在设置面板里改过的值（按 [SettingsPanelSpec] 的 key）。 */
    val settings: PluginSettingsStore

    /**
     * 声明本插件生成的**数据路径**（工作区相对或绝对），供宿主在卸载时提示并一并
     * 清理（如记忆插件的 `.kair/memory.md`）。声明是可选的——不声明则卸载只回收
     * 贡献点、不动数据。可在 `activate()` 里多次调用；同一路径重复声明按后者覆盖。
     */
    fun declareData(path: String, label: String)

    /** 注册服务商端点定义（出现在服务商选择器里）。 */
    fun provider(def: ProviderDef)

    /** 提供传输层（流式协议解析），按 [ProviderDef.transportId] 解析。 */
    fun transport(transport: ProviderTransport)

    /** 贡献一段系统提示（确定性内容，进缓存稳定前缀）。 */
    fun systemPrompt(contributor: SystemPromptContributor)

    /** 提供上下文 / 压缩策略（单例能力）。 */
    fun contextStrategy(strategy: ContextStrategy)

    /** 提供存储后端（单例能力；工作区必须落在真实文件系统上）。 */
    fun storage(backend: StorageBackend)

    /** 提供命令执行后端（单例能力；`bash` 工具跑命令的引擎）。 */
    fun commandRunner(runner: CommandRunner)

    /**
     * 订阅智能体事件（只读通知，不能改写数据流）。
     *
     * 用途："每 N 回合做一次"、"工具失败后记一笔"、"压缩后重建索引"这类**副作用**型需求。
     * 想改数据请用钩子（`onContext` / `onToolResult` 等）；钩子契约要求纯函数，不要在里面
     * 做 I/O，也不要用它数回合。
     *
     * 处理器在会话线程上同步调用：**必须快速返回**，重活交给 [scope]。抛的异常被宿主吞掉
     * 并记录，不影响会话主循环。
     *
     * 默认空实现：老宿主上调用它什么也不会发生。
     */
    fun onAgentEvent(handler: (AgentEvent) -> Unit) {}

    /**
     * 插件后台任务的作用域：[Plugin.activate] 里起的长任务（定时器、子智能体）都放这里。
     *
     * 宿主在**卸载 / 删除插件时取消它**，因此定时器不会在插件没了之后继续往会话里发消息。
     * 默认值是一个已取消的作用域：老宿主上起不来后台任务，但也不会崩。
     */
    val scope: CoroutineScope get() = EmptyPluginScope

    /** 插件即将被卸载 / 删除（贡献点已被回收）：在这里停定时器、关连接、落盘状态。 */
    fun onDeactivate(handler: () -> Unit) {}

    /**
     * 会话与子智能体桥；宿主不支持时为 null，插件须写 `agents?.` 降级。
     *
     * 注意：`send` 走正常主循环、不绕过审批，并受宿主的速率上限约束（防自激环路）。
     */
    val agents: AgentBridge? get() = null

    /**
     * 插件私有数据目录（如存“上次处理到第几回合”）。不存在时返回 null。
     *
     * 与 [settings] 的区别：settings 是**用户可见配置**（会出现在设置面板），dataDir 是
     * 插件自己的状态，不上界面。卸载时随插件一起清理（见 [declareData]）。
     */
    val dataDir: File? get() = null

    // ---- 中间件钩子（有序，可改写核心数据流） ----

    /** 改写出站的用户输入（成为消息之前）。 */
    fun onInput(handler: (String) -> String?)

    /** 改写单次请求的上下文（不落盘）。 */
    fun onContext(handler: (List<ChatMessage>) -> List<ChatMessage>?)

    /** 检视 / 改写 / 阻断一次工具调用（处理器可挂起，审批闸门在此等待）。 */
    fun onToolCall(handler: suspend (ToolCallHook) -> ToolCallOutcome?)

    /** 改写工具结果（回灌模型之前）。 */
    fun onToolResult(handler: (ToolResultHook) -> ToolResult?)

    /** 增补 / 改写 provider 请求头。 */
    fun onBeforeProviderHeaders(handler: (Map<String, String>) -> Map<String, String>?)
}
