package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.desktop.DesktopResolution
import dev.kair.app.core.desktop.ScaleMode
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon

/**
 * 桌面画面的悬浮件（U88 拆分）：悬浮胶囊 + 软键盘条。
 *
 * 从 `DesktopScreen.kt` 分出来的原因是体量守卫（单文件 500 行）：加指针模式后那边顶破登记值。
 * 边界是「**遮住画面的控件**」vs「画面与手势本身」—— 这两类改动的动因不同（前者是操作手感，
 * 后者是协议与坐标映射），分开以后各改各的，不互相顶行数。
 */

/**
 * 胶囊菜单里需要二次确认的三个危险动作（U89 §F §6 挂账）：重启 / 停止 / 移除环境。
 * 只有「关闭本面板」的 ✕ 不确认 —— 它不停桌面，只是离开画面。
 */
private enum class DesktopDangerAction { Restart, Stop, RemoveEnv }

/** 画面通道状态（x11vnc 删除后只剩帧缓冲读循环；不再有 RFB 连接态）。 */
internal sealed interface DesktopViewState {
    data object Connecting : DesktopViewState
    data object Connected : DesktopViewState
    data class Error(val message: String) : DesktopViewState
}

/**
 * 悬浮胶囊（任务书 §4 F 规格）：右上角距边 12dp、高 40dp、surface 60% + 圆角 20dp + 1dp 边框。
 *
 * U89 起胶囊里**没有指针模式按钮**（§C 定稿：不留模式、不留按钮）—— 剩下的全是「面板动作」：
 * 菜单 / 键盘 / 缩放 / 关闭。
 */
@Composable
internal fun DesktopCapsule(
    modifier: Modifier,
    alpha: Float,
    scaleMode: ScaleMode,
    srcW: Int,
    srcH: Int,
    /** 每秒上屏帧数（U89 F二）：0 = 空闲没变化，不是卡死。 */
    fps: Int,
    viewState: DesktopViewState,
    /** 当前输出分辨率档位（U89 §F）：换档会重启桌面，菜单里先说明再执行。 */
    resolution: DesktopResolution,
    onSelectResolution: (DesktopResolution) -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleScale: () -> Unit,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onRemoveEnv: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 危险动作先收起菜单、记下待确认项；确认后才真正执行（移除环境不可逆）。
    var pendingAction by remember { mutableStateOf<DesktopDangerAction?>(null) }
    // 分辨率档位是单选对话框 —— 与危险动作的确认弹窗分开（它没有「确认」，选中即生效）。
    var resolutionDialogOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .statusBarsPadding()
            .padding(12.dp)
            .graphicsLayer { this.alpha = alpha },
    ) {
        Row(
            modifier = Modifier
                // U105：40 → 48dp，让里面的图标按钮拿到 48dp 命中区（原先 36dp）。
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                // 底色 0.60 → 0.82：胶囊浮在任意桌面画面之上，60% 时亮色内容会透上来，
                // fps 与图标对比度不可控。0.82 仍能看到一点背景，但文字站稳了。
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                // 控件边界用 outline（≥3:1），不要用只有 1.4:1 的 hairline。
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // fps 常驻可见（U89 F二）：只藏在 logcat 里的读数没人看，真机排查“顺不顺”全靠它。
            Text(
                stringResource(R.string.desktop_fps_format, fps),
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                // 不再叠 0.7 alpha：半透明底上的半透明字，亮度一变就糊。
                color = MaterialTheme.colorScheme.onSurface,
            )
            CapsuleIcon(PluginGlyph.Menu, stringResource(R.string.desktop_cd_menu)) { menuOpen = true }
            CapsuleIcon(PluginGlyph.Keyboard, stringResource(R.string.desktop_cd_keyboard)) { onToggleKeyboard() }
            CapsuleIcon(PluginGlyph.Expand, stringResource(R.string.desktop_cd_scale)) { onToggleScale() }
            CapsuleIcon(PluginGlyph.Close, stringResource(R.string.desktop_cd_exit)) { onClose() }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.desktop_menu_restart)) },
                onClick = { menuOpen = false; pendingAction = DesktopDangerAction.Restart },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.desktop_menu_scale) +
                            (if (scaleMode == ScaleMode.Fit) " · fit" else " · 1:1"),
                    )
                },
                onClick = { menuOpen = false; onToggleScale() },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.desktop_menu_resolution_format, resolutionLabel(resolution)))
                },
                onClick = { menuOpen = false; resolutionDialogOpen = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.desktop_menu_stop)) },
                onClick = { menuOpen = false; pendingAction = DesktopDangerAction.Stop },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.desktop_menu_remove_env)) },
                onClick = { menuOpen = false; pendingAction = DesktopDangerAction.RemoveEnv },
            )
            DropdownMenuItem(
                enabled = false,
                text = {
                    Text(
                        stringResource(R.string.desktop_menu_status) + " " +
                            stringResource(R.string.desktop_status_format, srcW, srcH, connectionLabel(viewState)),
                    )
                },
                onClick = {},
            )
        }
    }
    pendingAction?.let { action ->
        val title = when (action) {
            DesktopDangerAction.Restart -> R.string.desktop_confirm_restart_title
            DesktopDangerAction.Stop -> R.string.desktop_confirm_stop_title
            DesktopDangerAction.RemoveEnv -> R.string.desktop_confirm_remove_title
        }
        val message = when (action) {
            DesktopDangerAction.Restart -> R.string.desktop_confirm_restart_message
            DesktopDangerAction.Stop -> R.string.desktop_confirm_stop_message
            DesktopDangerAction.RemoveEnv -> R.string.desktop_confirm_remove_message
        }
        ConfirmDialog(
            title = stringResource(title),
            message = stringResource(message),
            onConfirm = {
                pendingAction = null
                when (action) {
                    DesktopDangerAction.Restart -> onRestart()
                    DesktopDangerAction.Stop -> onStop()
                    DesktopDangerAction.RemoveEnv -> onRemoveEnv()
                }
            },
            onDismiss = { pendingAction = null },
        )
    }
    if (resolutionDialogOpen) {
        DesktopResolutionDialog(
            current = resolution,
            onSelect = {
                resolutionDialogOpen = false
                onSelectResolution(it)
            },
            onDismiss = { resolutionDialogOpen = false },
        )
    }
}

/**
 * 分辨率档位单选（U89 §F）：选中即生效（[onSelect] 里会停 → 起桌面），所以没有「确定」。
 * 底部明说会重启 —— 正在跑的程序会退出，不能让用户以为只是画面变糊。
 */
@Composable
private fun DesktopResolutionDialog(
    current: DesktopResolution,
    onSelect: (DesktopResolution) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.desktop_menu_resolution)) },
        text = {
            Column {
                for (option in DesktopResolution.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(option) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onSelect(option) })
                        Column(Modifier.padding(start = 6.dp)) {
                            Text(
                                stringResource(resolutionLabelRes(option)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(resolutionHintRes(option)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.desktop_resolution_restart_hint),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun resolutionLabel(resolution: DesktopResolution): String =
    stringResource(resolutionLabelRes(resolution))

private fun resolutionLabelRes(resolution: DesktopResolution): Int = when (resolution) {
    DesktopResolution.SHARP -> R.string.desktop_resolution_sharp
    DesktopResolution.BALANCED -> R.string.desktop_resolution_balanced
    DesktopResolution.SMOOTH -> R.string.desktop_resolution_smooth
}

private fun resolutionHintRes(resolution: DesktopResolution): Int = when (resolution) {
    DesktopResolution.SHARP -> R.string.desktop_resolution_sharp_hint
    DesktopResolution.BALANCED -> R.string.desktop_resolution_balanced_hint
    DesktopResolution.SMOOTH -> R.string.desktop_resolution_smooth_hint
}

/** 胶囊按钮：48dp 命中区；线稿仍画 18dp，胶囊不至于被图标撑大。 */
@Composable
private fun CapsuleIcon(
    glyph: String,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            // U105：原先只有 onClickLabel，读屏听到「双击以打开菜单」却不知道这是什么控件；
            // 图标按钮必须有 contentDescription。
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // 线稿图标（[PluginGlyph]）：以前画的是「菜单 / 键盘 / 缩放 / 叉号」这类**文字字符**，
        // 字形由设备上的符号字体决定，深色胶囊上还会带一层彩色。桌面是沉浸面板，
        // 它自己这套 chrome 也不该破例（U97）。
        SvgPathIcon(glyph, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
    }
}

@Composable
private fun connectionLabel(state: DesktopViewState): String = stringResource(
    when (state) {
        DesktopViewState.Connected -> R.string.desktop_state_connected
        DesktopViewState.Connecting -> R.string.desktop_state_connecting
        is DesktopViewState.Error -> R.string.desktop_state_failed
    },
)

/**
 * 本地鼠标光标（2026-11-14）：Xvfb 用 `-nocursor`，指针 sprite 不再画进帧缓冲，
 * 所以屏幕上那个箭头由 App 自己画。
 *
 * 为什么必须本地画：走「触摸 → xdotool → XWarpPointer → Xvfb 重绘 sprite →
 * 我们读帧 → 上屏」这条回路，实测光标一顿一顿（glxgears 齿轮却顺，因为齿轮不经过它）；
 * 用 `kair-cursor-test.sh` 让 X 自己匀速移动光标则完全顺滑 —— 说明瓶颈是回路延迟，不是渲染。
 * 本地画只有一次 Compose 绘制，位置跟 `cursorX/cursorY` 走，零回路延迟。
 *
 * 代价：指针形状固定成箭头，不会随应用变 I 型/resize（先接受；要还原得读 XFixes 的 cursor 图）。
 */
@Composable
internal fun CursorOverlay(position: Offset?, modifier: Modifier = Modifier) {
    if (position == null) return
    // 箭头路径设计在 11×18 的格子里，实际大小 = 单位 × density。
    val arrow = remember {
        Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 16f)
            lineTo(4.2f, 12.2f)
            lineTo(6.8f, 18f)
            lineTo(9.2f, 16.9f)
            lineTo(6.6f, 11.2f)
            lineTo(11f, 11.2f)
            close()
        }
    }
    Canvas(modifier) {
        val unit = 1.1.dp.toPx()
        translate(position.x, position.y) {
            scale(unit, unit, pivot = Offset.Zero) {
                // 白填充 + 黑描边：深浅背景上都看得见。
                drawPath(arrow, Color.White)
                drawPath(arrow, Color.Black, style = Stroke(width = 0.9f))
            }
        }
    }
}
