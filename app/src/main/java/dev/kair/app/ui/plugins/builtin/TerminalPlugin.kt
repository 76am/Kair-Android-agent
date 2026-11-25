package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.ContainerSessionHost
import dev.kair.app.core.proot.PtySession
import dev.kair.app.core.proot.PtyState
import dev.kair.app.core.proot.SessionState
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.launch

/**
 * 终端插件：把「Linux 会话」里原来的终端卡拆成独立能力。
 *
 * - 会话本身由 [ProotPlugin] 生产并发布到 [AppContainer.containerSessionHost]，
 *   本插件只消费，不重复创建长跑进程；proot 插件被禁用/重启用后面板自动恢复。
 * - 入口：插件面板（`panels.terminal`）。
 * - **只有一条渲染路径**：PTY 终端（Termux 形态，[PtyTerminalBody]），终端占满面板、
 *   点一下即输入，支持 vim / top 等全屏程序与运行中 resize。
 * - PTY 不可用（起不来 / shell 退出 / 未运行）时只显示原因 + 「重启会话」，**没有**第二套
 *   可输入的终端界面。U83 删掉了行式终端降级路径：它跑的是同一个 proot 会话进程
 *   （同一个 rootfs、同一份 `/projects`），容器不在它一样跑不了；容器在而 PTY 起不来
 *   则是 PTY 自身的缺陷，该修缺陷，不该长期养一套功能残缺的第二终端（负责人 2026-11-13 拍板）。
 */
class TerminalPlugin(private val container: AppContainer) : Plugin {

    override val id = ID
    override val name = "终端"
    override val description = "在 proot 容器里运行的常驻终端（PTY）。"

    override fun activate(ctx: PluginContext) {
        // 标题直接用插件名（与 R.string.terminal_* 同值「终端」）：
        // activate **不许碰 appContext** —— 装配要在 JVM 上可测（P4-1 / A2），
        // 而 PanelContribution.title 是急切求值的 String，懒不了。
        // 面板正文里的文案仍走 stringResource（组合层，真机上才求值）。
        ctx.panel(
            PanelContribution(
                id = PANEL_ID,
                title = name,
                icon = PluginGlyph.Keyboard,
                order = 32,
                content = { TerminalPanel(container) },
            ),
        )
    }

    companion object {
        const val ID = "dev.kair.plugin.terminal"
        const val PANEL_ID = "panels.terminal"
    }
}

/** 终端面板：会话不存在时提示启用 Linux 工作区；存在时渲染完整终端。 */
@Composable
private fun TerminalPanel(container: AppContainer) {
    val session by container.containerSessionHost.session.collectAsState()
    val pty by container.containerSessionHost.pty.collectAsState()
    val current = session
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (current == null) {
            Text(
                stringResource(R.string.terminal_no_session),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.terminal_no_session_hint),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        TerminalBody(current, pty, container.containerSessionHost)
    }
}

@Composable
private fun ColumnScope.TerminalBody(
    session: ContainerSession,
    pty: PtySession?,
    host: ContainerSessionHost,
) {
    val state by session.state.collectAsState()
    val stalled by session.suspectStalled.collectAsState()
    val scope = rememberCoroutineScope()
    val ptyState = if (pty != null) pty.state.collectAsState().value else null
    val restarting by host.restarting.collectAsState()

    // ---- 会话状态（启停与自动启动在「Linux 工作区」面板）--------------------
    // 2026-11-08 用户要求「压细一点」：状态行收成一行 12sp，重启入口退化成文字按钮，
    // 不再用有 40dp 最小高度的 TextButton（否则这一行永远占掉终端两行字的空间）。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stateLabel(state),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = when (state) {
                is SessionState.Error -> MaterialTheme.colorScheme.error
                is SessionState.Running -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // 三态（Running / Error / Stopped）都可点：它存在的理由就是「卡死时唯一的出路」。
        // 过渡态（Starting / Stopping）禁用避免与自动启停打架；重启窗口内也禁用 ——
        // 否则连点会把第二次 restart 排进 lock 队列，起完又被停一遍。
        // U59：改调 host.restart()，一次把帧协议会话与用户终端 PTY 都换掉（任务文档 D3）。
        val canRestart = !restarting && state !is SessionState.Starting && state !is SessionState.Stopping
        Text(
            stringResource(R.string.terminal_restart_session),
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    enabled = canRestart,
                    role = Role.Button,
                    onClick = { scope.launch { host.restart() } },
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (canRestart) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
    }
    (state as? SessionState.Error)?.let {
        Text(
            it.message,
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (stalled) {
        Text(
            stringResource(R.string.terminal_stalled_hint),
            modifier = Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    val ptySession = pty
    // 渲染哪一屏交给纯函数（U82）：重启窗口里两条通道都停在过渡态，按状态猜会露出旧画面。
    when (terminalScreen(restarting, ptyState, state)) {
        TerminalScreen.Pty -> ptySession?.let {
            // Termux 形态：终端占满剩余空间，点一下弹键盘直接打字，底部是扩展按键行。
            PtyTerminalBody(it, Modifier.fillMaxWidth().weight(1f))
        }
        // 启动 / 停止 / 重启的过渡态：只放这一行，避免任何「上一代」的画面闪一下。
        TerminalScreen.Starting -> Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.terminal_pty_starting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 没有可用的 PTY：只说明原因（U83 起不再回落第二套终端）。
        TerminalScreen.Failed -> Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when (ptyState) {
                    is PtyState.Error -> ptyState.message
                    is PtyState.Exited -> stringResource(R.string.terminal_pty_exited)
                    else -> stringResource(R.string.terminal_not_running)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (ptyState is PtyState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
