package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.plugin.SlotContribution
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import kotlinx.coroutines.CoroutineScope
import java.io.File
import dev.kair.plugin.api.Plugin as ApiPlugin
import dev.kair.plugin.api.PluginContext as ApiPluginContext

/**
 * SDK 插件到宿主插件的适配器：把 `dev.kair.plugin.api.Plugin` 包成内核
 * `dev.kair.app.core.plugin.Plugin`，使其走与内置插件完全相同的注册 / 卸载路径。
 *
 * [BridgedContext] 逐方法转发到宿主 PluginContext——契约类（Tool / 钩子载荷 /
 * 各后端接口）两侧是同一份类（来自 `:plugin-api`），无任何转换损耗。
 *
 * 适配器同时记录 `activate()` 期间插件交给宿主的**每个对象**，装载完成后交给
 * [PluginBinaryCompat] 做一次运行期兼容巡检：插件按旧版 API 编译、宿主又新增了
 * 无默认实现的接口成员时，这里能提前给出问题，而不是等某次工具调用抛
 * `AbstractMethodError` 闪退。
 */
class ApiPluginAdapter(private val sdk: ApiPlugin) : Plugin {
    override val id: String = sdk.id
    override val name: String = sdk.name
    override val description: String = sdk.description

    /** 插件自行声明的版本；未声明（空串）时由宿主回退到清单 `version`。 */
    override val version: String = sdk.version

    /** activate() 期间插件贡献给宿主的对象（用于二进制兼容巡检）。 */
    private val contributed = mutableListOf<Any>()

    override fun activate(ctx: PluginContext) {
        sdk.activate(BridgedContext(ctx, contributed))
    }

    /**
     * 插件是否缺少当前宿主契约的抽象成员；必须在 [activate] 之后调用。
     * 返回中文可读的成员名（如 `Tool.readOnly`），空 = 兼容。
     */
    fun binaryIssues(): List<String> {
        val missing = LinkedHashSet<String>()
        (listOf<Any>(sdk) + contributed).forEach { missing += PluginBinaryCompat.issues(it) }
        return missing.toList()
    }
}

/** SDK 贡献上下文 → 宿主 PluginContext 的纯转发桥（UI 面 = 声明式设置面板 + 声明式 UI 槽位 + 设置存储）。 */
private class BridgedContext(
    private val ctx: PluginContext,
    /** 仅记录可能被宿主**跨回合调用**的契约对象；纯数据 / lambda 不参与二进制巡检。 */
    private val contributed: MutableList<Any>,
) : ApiPluginContext {
    override fun tool(tool: Tool) {
        contributed += tool
        // 第三方（dex）插件未声明类别的工具默认需要审批；想跟随用户的类别开关必须声明 category。
        val effective = if (tool.category == ToolCategory.UNSPECIFIED && !tool.requiresApproval) {
            UndeclaredCategoryTool(tool)
        } else {
            tool
        }
        ctx.tool(effective)
    }

    override fun settingsPanel(spec: SettingsPanelSpec) = ctx.settingsPanel(spec)
    override fun slot(slot: SlotContribution) = ctx.slot(slot)
    override fun declareData(path: String, label: String) = ctx.declareData(path, label)
    override val settings: PluginSettingsStore get() = ctx.settings
    override fun provider(def: dev.kair.app.core.ai.ProviderDef) = ctx.provider(def)

    override fun transport(transport: ProviderTransport) {
        contributed += transport
        ctx.transport(transport)
    }

    override fun systemPrompt(contributor: SystemPromptContributor) {
        contributed += contributor
        ctx.systemPrompt(contributor)
    }

    override fun contextStrategy(strategy: ContextStrategy) {
        contributed += strategy
        ctx.contextStrategy(strategy)
    }

    override fun storage(backend: StorageBackend) {
        contributed += backend
        ctx.storage(backend)
    }

    override fun commandRunner(runner: CommandRunner) {
        contributed += runner
        ctx.commandRunner(runner)
    }

    // ---- v8 运行时能力：必须逐个转发 ----
    //
    // 这五个成员在本接口里都有默认实现（空函数 / 已取消的作用域 / null），正是这份
    // 「默认」让漏转发变成**静默失效**：dex 插件调用 ctx.onAgentEvent 会走空实现，
    // 永远注册不到宿主，也不报错。所以新增成员时必须同步在这里加一行，
    // 并由 PluginRuntimeTest 的端到端用例守住（走 BridgedContext，而不是直接测宿主 ctx）。

    override fun onAgentEvent(handler: (AgentEvent) -> Unit) = ctx.onAgentEvent(handler)
    override fun onDeactivate(handler: () -> Unit) = ctx.onDeactivate(handler)
    override val scope: CoroutineScope get() = ctx.scope
    override val agents: AgentBridge? get() = ctx.agents
    override val dataDir: File? get() = ctx.dataDir

    override fun onInput(handler: (String) -> String?) = ctx.onInput(handler)
    override fun onContext(handler: (List<dev.kair.app.core.model.ChatMessage>) -> List<dev.kair.app.core.model.ChatMessage>?) =
        ctx.onContext(handler)
    override fun onToolCall(handler: suspend (dev.kair.app.core.plugin.ToolCallHook) -> dev.kair.app.core.plugin.ToolCallOutcome?) =
        ctx.onToolCall(handler)
    override fun onToolResult(handler: (dev.kair.app.core.plugin.ToolResultHook) -> dev.kair.app.core.tools.ToolResult?) =
        ctx.onToolResult(handler)
    override fun onBeforeProviderHeaders(handler: (Map<String, String>) -> Map<String, String>?) =
        ctx.onBeforeProviderHeaders(handler)
}

/**
 * `category` 未声明的第三方工具：强制 `requiresApproval = true`，其余属性原样转发。
 *
 * 只包一层属性，不改变工具的 schema / 执行逻辑；二进制兼容巡检记录的仍是原对象。
 */
private class UndeclaredCategoryTool(private val delegate: Tool) : Tool by delegate {
    /** 只覆写审批；其余成员（含以后新增的）全部委托，避免手写转发漏字段。 */
    override val requiresApproval: Boolean = true
}
