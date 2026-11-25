package dev.kair.app.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.kair.app.R
import dev.kair.app.core.browser.BrowserPanelState
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon
import dev.kair.app.ui.plugins.builtin.rememberPanelBackToggle
import dev.kair.app.ui.screens.PANEL_OVERLAY_HEIGHT_FRACTION

internal const val TAB_CONSOLE = 0
internal const val TAB_NETWORK = 1
internal const val TAB_ACTIONS = 2

/**
 * 会话内的浏览器面板宿主。
 *
 * 渲染已经搬到常驻悬浮小窗（[BrowserWindowHost]），面板只留**控制台**：
 * 地址栏、后退刷新、接管 / 交还、控制台 / 网络 / 操作三页日志、关闭页面。
 * 打开 / 收起面板不改变小窗尺寸与位置，也不会触发任何 reparent。
 *
 * **始终在组合里**：`visible = false` 时整块平移到屏外，不销毁控制台状态。
 * 全屏按钮保留（所有面板共用的那套在 `PanelOverlay`，这里只控制本面板铺满屏幕）。
 */
@Composable
internal fun BrowserPanelHost(
    visible: Boolean,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    val controller = BrowserHost.controller(context.applicationContext)
    val state by controller.state.collectAsState()
    val slide by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "browser-panel",
    )
    // 面板开着时系统返回键先收面板，不直接退出会话；截图期间吞掉。
    rememberPanelBackToggle(
        open = visible,
        onClose = { if (!controller.state.value.capturing) onClose() },
    )
    // 首次有页面且没授权时主动问一次（面板不可见时不弹，避免盖住别的界面）。
    BrowserOverlayPermissionPrompt(visible = visible, hasPage = state.url.isNotBlank())
    Box(Modifier.fillMaxSize()) {
        if (slide > 0.01f && !fullscreen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * slide))
                    .clickable(enabled = !state.capturing, onClick = onClose),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (fullscreen) 1f else PANEL_OVERLAY_HEIGHT_FRACTION)
                .align(Alignment.BottomCenter)
                .zIndex(1f)
                .graphicsLayer {
                    translationY = (1f - slide) * size.height
                    alpha = slide
                },
            shape = if (fullscreen) {
                RectangleShape
            } else {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            BrowserPanelBody(
                controller = controller,
                state = state,
                fullscreen = fullscreen,
                onClose = onClose,
                onToggleFullscreen = onToggleFullscreen,
                showHandle = !fullscreen,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .then(if (fullscreen) Modifier.statusBarsPadding() else Modifier),
            )
        }
    }
}

/** 插件页 / 独立路由进入时的全屏控制台；与会话内共用同一个控制器与同一扇小窗。 */
@Composable
internal fun BrowserPanelStandalone(onClose: () -> Unit) {
    val context = LocalContext.current
    val controller = BrowserHost.controller(context.applicationContext)
    val state by controller.state.collectAsState()
    Surface(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        BrowserPanelBody(
            controller = controller,
            state = state,
            fullscreen = true,
            onClose = onClose,
            onToggleFullscreen = null,
            modifier = Modifier.imePadding(),
            standalone = true,
        )
    }
    BrowserOverlayPermissionPrompt(visible = true, hasPage = state.url.isNotBlank())
}

/** 插件面板的兜底正文：正常入口在会话内，这里只给一句指路，避免空白页。 */
@Composable
internal fun BrowserPanelFallback() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.browser_panel_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BrowserPanelBody(
    controller: AndroidBrowserController,
    state: BrowserPanelState,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showHandle: Boolean = false,
    standalone: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_CONSOLE) }
    Column(modifier.fillMaxSize()) {
        if (showHandle) BrowserDragHandle()
        BrowserHeader(fullscreen, state.capturing, onClose, onToggleFullscreen)
        HorizontalDivider()
        if (state.loading) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BrowserToolbar(controller, state)
        BrowserMeta(controller, state)
        BrowserTabRow(selected = tab, state = state, onSelect = { tab = it })
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.url.isBlank()) {
                BrowserNoPage()
            } else {
                when (tab) {
                    TAB_NETWORK -> BrowserNetworkTab(controller, state, Modifier.fillMaxSize())
                    TAB_ACTIONS -> BrowserActionsTab(state, Modifier.fillMaxSize())
                    else -> BrowserConsoleTab(controller, state, Modifier.fillMaxSize())
                }
            }
        }
        if (standalone) Spacer(Modifier.height(4.dp))
    }
}

/** 没有页面时的控制台占位：页面本身在悬浮小窗里，不在这里。 */
@Composable
private fun BrowserNoPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SvgPathIcon(
            PluginGlyph.Globe,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.browser_no_page),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.browser_no_page_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
