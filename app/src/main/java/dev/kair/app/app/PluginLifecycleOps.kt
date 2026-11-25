package dev.kair.app.app

import dev.kair.app.core.mcp.McpManager
import dev.kair.app.core.mcp.McpProcessHost
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.dynamic.DynamicPluginHost
import dev.kair.app.core.proot.ContainerSessionHost
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.ui.plugins.builtin.McpPlugin
import dev.kair.app.ui.plugins.builtin.ProotPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 内置插件的启用 / 禁用、动态插件删除（U31 第一刀 B 段）。
 *
 * 为什么在这儿：这段原本长在 [AppContainer] 里，但它只是「注册表 + 设置 + 宿主」的编排，
 * 不碰装配主体的会话/工具状态。搬出来后容器只剩一行委托，体量棘轮（`SourceSizeGuardTest`）
 * 继续只许容器变瘦。
 *
 * 依赖显式注入：`setContainerReady` / `resyncTools` 是容器的 public / private 方法，
 * 用函数引用传入，不为搬家抬高可见性；`builtinPlugins` 传的是容器里对顶层函数的调用
 * （**不能**绕回容器的 `builtinPluginDefs` 属性 —— 那已经是本类的委托，会递归）。
 */
internal class PluginLifecycleOps(
    private val plugins: PluginRegistry,
    private val dynamicPlugins: DynamicPluginHost,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val containerSessionHost: ContainerSessionHost,
    private val mcpProcessHost: McpProcessHost,
    private val mcp: McpManager,
    private val setContainerReady: (Boolean) -> Unit,
    private val resyncTools: () -> Unit,
    private val builtinPlugins: () -> List<Plugin>,
) {

    /** 内置插件完整清单（含被禁用的），供插件页展示与重新启用。 */
    fun builtinPluginDefs(): List<Plugin> = builtinPlugins()

    /** 某插件当前是否已装载（在注册表内）。 */
    fun isPluginEnabled(id: String): Boolean = plugins.installed.any { it.id == id }

    /** 某插件的设置存储，供 UI 渲染声明式设置面板（开关 / 数值 / 文本 / 下拉）。 */
    fun pluginSettings(id: String): PluginSettingsStore =
        plugins.pluginSettings(id)

    /**
     * 删除一个动态插件：卸载贡献点 + 删除磁盘文件 + 清除其设置键与数据声明。
     * [deleteData] 为 true 时，一并删除插件声明的数据（不进回收站）。
     * 相对路径的数据必须以 [dataWorkspace]（活跃会话的工作区）为基准；
     * 没有活跃会话且存在相对路径声明时返回 false，绝不回退到 scratch。
     * 与「停用」（只回收贡献点、保留文件与数据、可再启用）不同，删除不可恢复。
     */
    fun deleteDynamicPlugin(
        id: String,
        deleteData: Boolean = false,
        dataWorkspace: File? = null,
    ): Boolean {
        // 先取数据声明：卸载 / 清除后注册表里就没有了。
        val declared = plugins.pluginData(id)
        if (deleteData && dataWorkspace == null && declared.any { !File(it.path).isAbsolute }) {
            return false
        }
        val removed = dynamicPlugins.delete(id)
        if (removed) {
            if (deleteData) declared.forEach { deletePluginDataPath(it.path, dataWorkspace) }
            plugins.clearPluginData(id)
            settings.clearPluginSettings(id)
        }
        return removed
    }

    /**
     * 删除一条插件数据：绝对路径原样、相对路径相对显式传入的 [workspace] 解析
     * （不读「最近活跃工作区」这种隐式全局状态）；目录递归删除、文件直接删除，
     * 均不进回收站。失败静默（尽力而为）。
     */
    private fun deletePluginDataPath(path: String, workspace: File?) {
        val file = File(path).let { if (it.isAbsolute) it else File(requireNotNull(workspace), path) }
        runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
    }

    /**
     * 启用 / 禁用一个**内置**插件：改动即时生效（重建工具终序）并落盘，
     * 下次启动按落盘状态装载。第三方动态插件请走 `dynamicPlugins.uninstall`。
     */
    fun setPluginEnabled(id: String, enabled: Boolean) {
        // 只作用于内置插件；未知名直接忽略。
        if (builtinPlugins().none { it.id == id }) return
        if (enabled) {
            if (isPluginEnabled(id)) return
            settings.disabledBuiltinPlugins = settings.disabledBuiltinPlugins - id
            builtinPlugins().firstOrNull { it.id == id }?.let { plugins.install(it) }
        } else {
            if (!isPluginEnabled(id)) return
            plugins.uninstall(id)
            settings.disabledBuiltinPlugins = settings.disabledBuiltinPlugins + id
            // 会话归 proot 能力所有：禁用后不能留下一条无人管理的长跑 su/proot。
            if (id == ProotPlugin.ID) {
                scope.launch { containerSessionHost.clearAndStop() }
                // stdio MCP 也跑在这份 rootfs 里：工厂清空 + 断开 stdio 会话。
                // 同时把 containerReady 归零，重新启用时 becameReady 才成立、stdio 才会重连。
                mcpProcessHost.clear()
                setContainerReady(false)
            }
            // MCP 插件禁用：断开全部服务器，工具随之从注册表消失（见 resyncTools）。
            if (id == McpPlugin.ID) mcp.closeAll()
        }
        resyncTools()
    }
}
