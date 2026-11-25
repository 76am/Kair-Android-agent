package dev.kair.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.browser.BROWSER_PANEL_ID
import dev.kair.app.core.plugin.PanelChrome
import dev.kair.app.ui.browser.BrowserPanelStandalone
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.pluginDisplayText
import dev.kair.app.ui.plugins.PluginSettingsPanel

/**
 * 沉浸面板的「关闭自己」入口：宿主持有这个回调（通常是返回上一页），
 * 通过 CompositionLocal 传给内容 —— 沉浸态没有宿主顶栏，内容得自己给用户一个出口。
 * 默认空实现，非沉浸面板/单测里取到也不会炸。
 */
internal val LocalPanelClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 面板标题解析的唯一入口：面板贡献与插件设置面板都可能是本页数据源。
 * 路由宿主（[PanelHostScreen]）与覆盖层（`PanelOverlay`）共用，避免两处各自猜标题。
 */
@Composable
internal fun panelTitle(container: AppContainer, panelId: String): String {
    val panel = container.plugins.panels().firstOrNull { it.id == panelId }
    val settingsPanel = container.plugins.settingsPanels().firstOrNull { it.second.id == panelId }
    return panel?.let { pluginDisplayText(it.title, it.titleRes) }
        ?: settingsPanel?.second?.let { pluginDisplayText(it.title, it.titleRes) }
        ?: stringResource(R.string.plugins_panel_title)
}

/**
 * 面板正文：插件内容 + 本插件工具开关页脚。**不含顶栏**。
 *
 * 抽出来的原因（U93）：面板有两条呈现路径 —— 独立路由（[PanelHostScreen]，插件页进入）
 * 与会话内覆盖层（`PanelOverlay`，会话里进入）。两条路必须渲染同一个东西，
 * 否则「从哪里进的面板」会决定它长什么样。
 */
@Composable
internal fun PanelContent(container: AppContainer, panelId: String, modifier: Modifier = Modifier) {
    // 插件贡献组合期直读（U46）：注册表是 Compose 状态，remember 住快照会在
    // 装/卸/启停后陈旧；panelId 只是路由入参，不能当新鲜度的 key。
    val panel = container.plugins.panels().firstOrNull { it.id == panelId }
    // 插件声明的设置面板（数值 / 开关 / 文本 / 下拉 / 按钮）也走同一个路由，
    // 用宿主内置控件渲染 —— 插件不接触 Compose。
    val settingsPanel = container.plugins.settingsPanels().firstOrNull { it.second.id == panelId }
    val pluginId = panel?.pluginId ?: settingsPanel?.first
    val pluginTools = pluginId?.let { id -> container.toolCatalog().filter { it.pluginId == id } }.orEmpty()

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                panel != null -> panel.content()
                settingsPanel != null ->
                    PluginSettingsPanel(
                        container,
                        container.pluginSettings(settingsPanel.first),
                        settingsPanel.second,
                    )
                else -> Text(
                    stringResource(R.string.plugins_panel_missing, panelId),
                    Modifier.padding(16.dp),
                )
            }
        }
        // 页脚开关区限高自滚：工具多时也不会把面板正文挤出屏幕。
        if (pluginTools.isNotEmpty()) {
            HorizontalDivider()
            ToolToggleGroup(
                container,
                stringResource(R.string.panel_tools_title),
                pluginTools,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * 面板宿主屏（U86 从 `PluginsScreen.kt` 拆出——原文件卡在 684 行登记值上，
 * 沉浸分支「先拆再改」，守卫条目同步下调）。
 *
 * 两种装饰形态（`PanelChrome`）：
 * - [PanelChrome.Standard]：宿主渲染 TopAppBar（标题 + 返回）与页脚工具区，正文在上；
 * - [PanelChrome.Immersive]：**不渲染** TopAppBar 与页脚工具区，内容自己吃 `WindowInsets`
 *   并负责返回（系统返回键 / 侧滑）—— 图形桌面面板用（任务书 §2「沉浸」行）。
 *
 * U93 起，会话内打开面板改走覆盖层（见 `PanelOverlay`），本屏只承担「从插件页进入」
 * 与沉浸面板两条路 —— 沉浸面板必须占满屏幕，覆盖层装不下它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelHostScreen(container: AppContainer, panelId: String, onBack: () -> Unit) {
    // 浏览器面板是常驻 WebView 的第二个宿主（插件页 / 全屏路由进入）：
    // 与会话内的覆盖层共用同一个控制器与同一份页面，不能走 PanelContent 的普通正文。
    if (panelId == BROWSER_PANEL_ID) {
        BrowserPanelStandalone(onClose = onBack)
        return
    }
    val panel = container.plugins.panels().firstOrNull { it.id == panelId }

    // 沉浸分支：一行分派，正文全屏；返回交给内容自己（系统返回键仍走 onBack）。
    // 通过 Local 把「关闭本面板」交给内容：沉浸面板没有宿主顶栏，需要一个显式的关闭入口
    // （图形桌面胶囊的 ✕）。不改 PanelContribution 的签名，其他面板照旧。
    if (panel != null && panel.chrome == PanelChrome.Immersive) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalPanelClose provides onBack) { panel.content() }
        }
        return
    }

    Scaffold(
        topBar = {
            KairTopBar(
                title = { Text(panelTitle(container, panelId)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        PanelContent(container, panelId, Modifier.padding(padding))
    }
}
