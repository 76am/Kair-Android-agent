package dev.kair.app.ui.plugins.builtin

import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.tools.DeviceScreenTool
import dev.kair.app.core.tools.DeviceShellTool
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * U90 ADB 插件：贡献「ADB」面板（无线调试配对/连接/授权 + 其上的进程保活开关）。
 *
 * 面板以 adb 为主体、进程保活只是其中一个选项——设备级能力（adb + 系统设置）与 proot 容器管理
 * 是不同概念，故独立成插件、可单独禁用；不依赖容器就绪（rootfs 未装时面板照样可用）。
 * 面板 order=32，紧挨 Linux 工作区（30）。
 */
class PhantomKillerPlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = "ADB"
    override val description = "Wireless debugging pairing and device authorization, plus the process keep-alive switch."
    override val nameRes = R.string.phantom_plugin_name
    override val descriptionRes = R.string.phantom_plugin_desc

    override fun activate(ctx: PluginContext) {
        // U91：设备工具常驻注册（插件启用即在列），配对状态在执行期判——配对/清除密钥时
        // 动态增删工具要额外开一个 resync 公开入口，而 AppContainer 在文件级棘轮白名单里只准减。
        val adb = { AdbLink(container.appContext) }
        ctx.tool(DeviceShellTool { command -> adb().shell(command) })
        // 截屏单独成工具，不是「分得细」：二进制过不了 device_shell 的 String 返回，
        // 必须走 ToolResult.images 模型才看得见（见 U91 任务书 §2.3）。
        ctx.tool(DeviceScreenTool { adb().screenshot() })
        // 预热常驻连接：已授权就先把 adb 会话建起来并保持，别等第一次工具调用才握手。
        // 失败静默——无线调试没开是常态而不是错误，工具调用时会返回可操作的提示。
        //
        // **这里必须容忍拿不到 Context**：JVM 单测里的 AppContainer 没有 Android Context，
        // 而上面两条 `ctx.tool { }` 是 lambda、只在工具真被调用时才求值，所以从没碰到这条路。
        // 预热是「立刻执行」的，得自己挡一下（AppContainerWiringTest 会当场炸给你看）。
        val context = runCatching { container.appContext }.getOrNull()
        if (context != null) {
            val link = AdbLink(context)
            if (link.hasPairedKeys()) {
                ctx.scope.launch(Dispatchers.IO) { runCatching { link.prewarm() } }
            }
        }
        ctx.panel(
            PanelContribution(
                id = "panels.phantom",
                title = "ADB",
                titleRes = R.string.phantom_panel_title,
                icon = PluginGlyph.Plug,
                order = 32,
                content = { PhantomKillerPanel(container) },
            ),
        )
    }

    companion object {
        const val ID = "dev.kair.plugin.phantomkiller"
    }
}
