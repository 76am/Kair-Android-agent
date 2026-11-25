package dev.kair.app.ui.plugins.builtin

import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.browser.BROWSER_PANEL_ID
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.browser.BrowserHost
import dev.kair.app.ui.browser.BrowserPanelFallback
import dev.kair.app.ui.browser.openOverlayPermissionSettings
import dev.kair.app.ui.icons.PluginGlyph

/**
 * Agent Dev Browser（P1）：会话内看 Agent 操作本地 dev server。
 *
 * 面板入口 `panels.browser` 在会话里由 `KairNav` 特判到常驻宿主；插件页从独立路由进入时
 * 走 `PanelHostScreen` 的全屏分支。工具只在执行时取控制器（见 `BrowserTools.kt`），
 * 因此 JVM 装配测试不需要 Android Context。
 */
class BrowserPlugin(private val container: AppContainer) : Plugin {

    override val id = ID
    override val name = "Browser"
    override val nameRes = R.string.browser_plugin_name
    override val description = "Inspect and drive local development pages from the chat."
    override val descriptionRes = R.string.browser_plugin_description

    override fun activate(ctx: PluginContext) {
        val browser = { BrowserHost.controller(container.appContext) }
        ctx.panel(
            PanelContribution(
                id = BROWSER_PANEL_ID,
                title = "Browser",
                titleRes = R.string.browser_plugin_name,
                icon = PluginGlyph.Globe,
                order = 26,
                content = { BrowserPanelFallback() },
            ),
        )
        // 后台悬浮截图需要「显示在其他应用上层」；没有它仍可前台截图，只是 App 退后台后截不了。
        ctx.settingsPanel(
            SettingsPanelSpec(
                id = "settings.browser",
                title = "Browser",
                icon = PluginGlyph.Globe,
                order = 26,
                fields = listOf(
                    SettingField.Action(
                        key = "overlay_permission",
                        labelRes = R.string.browser_overlay_permission,
                        descriptionRes = R.string.browser_overlay_permission_desc,
                        buttonLabel = "",
                        onClick = { openOverlayPermissionSettings(container.appContext) },
                    ).apply { buttonLabelRes = R.string.browser_overlay_grant },
                ),
            ).apply { titleRes = R.string.browser_plugin_name },
        )
        browserTools(browser).forEach(ctx::tool)
        // 插件停用即释放常驻 WebView；重新启用会得到新的控制器。
        ctx.onDeactivate { BrowserHost.release() }
    }

    companion object {
        const val ID = "dev.kair.plugin.browser"
    }
}
