package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.desktop.DesktopResolution
import dev.kair.app.core.desktop.DesktopRunner
import dev.kair.app.core.desktop.DesktopState
import dev.kair.app.core.plugin.PanelChrome
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.screens.LocalPanelClose

/**
 * 图形桌面插件（U86）：贡献一个**沉浸式**面板（[PanelChrome.Immersive]，宿主不渲染
 * 顶栏与页脚），显示容器里运行的 X11 桌面。
 *
 * 生命周期（任务书 §3.2）：runner 归 `AppContainer.desktopRunner`（与容器会话同层），
 * 不挂插件实例、也不挂面板 —— 面板销毁只断开 RFB 视图，桌面进程照跑；插件禁用→启用
 * 换实例时句柄仍在，唯一停口（胶囊菜单「停止桌面」）不会失效（审阅 Blocker 3）。
 * 容器会话停止时桌面跟着停（rootfs 与挂载都没了），这条由 [DesktopRunner] 自己盯着。
 */
class DesktopPlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = ""
    override val nameRes = R.string.desktop_plugin_name
    override val description = ""
    override val descriptionRes = R.string.desktop_plugin_description

    override fun activate(ctx: PluginContext) {
        // runner 归 ContainerSessionHost（容器会话层的稳定载体），插件**只持引用**：
        // 插件实例会被 builtinPlugins() 重建，句柄挂这里会丢进程、「停止桌面」失效（审阅 Blocker 3）。
        val runner = container.containerSessionHost.desktop
        ctx.panel(
            PanelContribution(
                id = PANEL_ID,
                title = "",
                titleRes = R.string.desktop_plugin_name,
                icon = PluginGlyph.Monitor,
                order = 33,
                chrome = PanelChrome.Immersive,
                content = { DesktopPanel(container, runner) },
            ),
        )
    }

    companion object {
        const val ID = "dev.kair.plugin.desktop"
        const val PANEL_ID = "panels.desktop"
    }
}

/** 面板根：容器会话缺席给提示；否则按 [DesktopState] 分派（空态 / 进度 / 连接 / 画面 / 错误）。 */
@Composable
internal fun DesktopPanel(container: AppContainer, runner: DesktopRunner) {
    val sessionReady by container.containerSessionHost.sessionReady.collectAsState()
    val desktop by runner.state.collectAsState()
    val metrics = LocalContext.current.resources.displayMetrics
    // 沉浸面板没有宿主顶栏；空态自己给一个显式返回，不能只靠系统返回键（§F §6 挂账）。
    val onClose = LocalPanelClose.current
    // 分辨率档位（U89 §F）：存插件设置，面板重进 / 进程重建都沿用；换档由 runner 停→起。
    var resolution by remember {
        mutableStateOf(
            DesktopResolution.fromId(
                container.settings.pluginSettingString(DesktopPlugin.ID, RESOLUTION_SETTING),
            ),
        )
    }

    // 每次进面板探测一次：装过就显示「启动桌面」，不再假装要安装。
    LaunchedEffect(Unit) { runner.refreshInstallState() }

    if (!sessionReady) {
        NoSessionHint()
        return
    }

    when (val state = desktop) {
        is DesktopState.Idle -> Box(Modifier.fillMaxSize()) {
            DesktopEmptyState(
                installed = state.installed,
                onInstallAndStart = {
                    runner.start(metrics.widthPixels, metrics.heightPixels, resolution)
                },
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.statusBarsPadding().padding(12.dp),
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
        DesktopState.Checking -> Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.desktop_checking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
        }
        is DesktopState.Installing -> Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.desktop_installing, state.fetchedBytes / (1024 * 1024)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
        }
        DesktopState.Starting -> CenteredHint(stringResource(R.string.desktop_connecting))
        // 会话就绪后才起步，中途又被停掉（面板已画完、守卫才失败）：同一句中文引导，
        // 不能把英文 `container session is not running` 泄到界面上。
        DesktopState.NoSession -> NoSessionHint()
        is DesktopState.Error -> CenteredHint(
            stringResource(R.string.desktop_failed, state.message),
            error = true,
            actionLabel = stringResource(R.string.desktop_retry),
            onAction = { runner.start(metrics.widthPixels, metrics.heightPixels, resolution) },
        )
        is DesktopState.SessionLost -> CenteredHint(
            stringResource(R.string.desktop_session_lost, state.sessionState),
            error = true,
            actionLabel = stringResource(R.string.desktop_retry),
            onAction = { runner.start(metrics.widthPixels, metrics.heightPixels, resolution) },
        )
        is DesktopState.Running -> DesktopScreen(
            runner = runner,
            srcW = state.width,
            srcH = state.height,
            resolution = resolution,
            onSelectResolution = { next ->
                if (next != resolution) {
                    resolution = next
                    container.settings.setPluginSettingString(
                        DesktopPlugin.ID,
                        RESOLUTION_SETTING,
                        next.name,
                    )
                    runner.setResolution(next)
                }
            },
        )
    }
}

/** 容器会话未就绪时的中文引导；顶栏判据与 [DesktopState.NoSession] 共用这一份表现。 */
@Composable
private fun NoSessionHint() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.desktop_no_session),
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

/** 分辨率档位在插件设置里的键（与桌面插件 id 组合，见 `SettingsStore.pluginSettingString`）。 */
private const val RESOLUTION_SETTING = "resolution"

/** 空态（任务书 §4 F）：未装给体积说明 +「安装并启动」；已装只给「启动桌面」。 */
@Composable
private fun DesktopEmptyState(installed: Boolean, onInstallAndStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (installed) {
                stringResource(R.string.desktop_installed_hint)
            } else {
                stringResource(
                    R.string.desktop_empty_hint,
                    DesktopRunner.DOWNLOAD_HINT_MB,
                    DesktopRunner.INSTALLED_HINT_MB,
                    DesktopRunner.INSTALL_SPACE_REQUIRED_MB,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onInstallAndStart) {
            Text(
                stringResource(
                    if (installed) R.string.desktop_start else R.string.desktop_install_start,
                ),
            )
        }
    }
}

@Composable
private fun CenteredHint(
    text: String,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
