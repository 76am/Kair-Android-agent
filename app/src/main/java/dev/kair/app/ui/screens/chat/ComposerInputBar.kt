package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.TurnState
import dev.kair.app.core.model.Part
import dev.kair.app.ui.components.ImagePart
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon

/**
 * 输入区：待发送图片预览 + 一块**输入板**（附件 / 文字框 / 停止键 / 发送键 + 下沿控件行）。
 *
 * 形态是「一块抬起的板」：surfaceContainerHigh 底 + 1dp 分界线 + 14dp 圆角。
 * 模型 / 思考强度 / 插件 slot / 上下文圆环那一行由 [footer] 画在板内（负责人 2026-11-17：
 * 「模型名那片区域太大，应该跟输入框融一起」）—— 它们在语义上属于「这次要发什么」，
 * 不是输入框上方的另一层。
 *
 * 发送（纸飞机）走 `send` —— 忙时入 `FOLLOW_UP`、**本回合结束**才作为新回合发出，空闲时直接起回合。
 * 插话不在这排：它是排队区每条的改道动作（[QueueSection] 的 △ → [promoteToSteer]）——
 * 两个都长得像发送的按钮，让人分不清按下去是等回合还是插话（负责人 2026-11-01 纠正 U22）。
 *
 * 停止收尾期间仍可发：消息入 `FOLLOW_UP`，收尾结束后由 `drainQueueAfterStop` 接上。
 * 禁发的问题是收尾一旦异常挂住（工具不响应取消），用户就被永久锁在输入框外。
 *
 * 「回到底部」的两条入口（轻点输入框的焦点变化 / 已聚焦时的交互源）从这里发出：
 * 两条都只是把同一个计数加一，重复触发只会多滚一次。
 */
@Composable
internal fun ComposerInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    pendingImage: Part.Image?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    /** 回合状态：停止键的显示 / 文案全部由它派生（不再额外传 busy / stopping 两个布尔）。 */
    turnState: TurnState,
    /** 有待审批 / 待回答卡片时，状态条让位给卡片（卡片本身就是「在等你」）。 */
    awaitingUser: Boolean,
    /** U111：解码速度相位与实时值；默认 Idle，草稿页 / 旧调用点不用关心。 */
    speedPhase: SpeedPhase = SpeedPhase.IDLE,
    liveSpeedText: String? = null,
    /** 状态条后缀：本会话均速（常驻）。 */
    speedSuffix: String? = null,
    focusRequester: FocusRequester,
    onStop: () -> Unit,
    onSubmit: () -> Unit,
    onBottomRequest: () -> Unit,
    /** 板内下沿的控件行（模型 / 思考强度 / 插件 slot / 上下文圆环），由 [ComposerControlRow] 提供。 */
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val stopping = turnState == TurnState.Stopping
    val attachLabel = stringResource(R.string.chat_attach_image)
    val removeImageLabel = stringResource(R.string.chat_image_remove)
    val stopLabel = stringResource(R.string.chat_stop)
    val sendLabel = stringResource(R.string.chat_send)
    val interactions = remember { MutableInteractionSource() }
    LaunchedEffect(interactions) {
        var pressedAt = 0L
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressedAt = System.currentTimeMillis()
                is PressInteraction.Release ->
                    if (System.currentTimeMillis() - pressedAt in 0..400) onBottomRequest()
                else -> Unit
            }
        }
    }

    // 运行态状态条：贴在输入胶囊上方，覆盖「工具结果已回、下一段输出未到」的空档（U58 ②）。
    // U111 起同一行显示解码速度（实时值 / 本会话均速后缀），常驻不消失，见 [AgentActivityStrip]。
    AgentActivityStrip(turnState, awaitingUser, speedPhase, liveSpeedText, speedSuffix)

    // 待发送图片预览：挂在输入框上方，可单独移除。发送时随文字一并带给模型。
    pendingImage?.let { img ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImagePart(img, Modifier.size(72.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.chat_image_attached),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClearImage,
                modifier = Modifier.semantics { contentDescription = removeImageLabel },
            ) {
                SvgPathIcon(
                    PluginGlyph.Close,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(18.dp),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            // 设计稿的 `.composer`：一整块抬起的板（13dp 圆角 + 1dp 分界线）。
            // 输入行与下沿控件行共用这一块底 —— 分两块画就会回到「三层挤在底部」的老样子。
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = onPickImage,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = attachLabel },
            ) {
                SvgPathIcon(
                    PluginGlyph.Attach,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(20.dp),
                )
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 160.dp)
                    // 首次轻点（键盘未弹出）走焦点变化；已聚焦时的重复轻点走交互源。
                    // 焦点请求器供「撤回编辑」把光标送回输入框（U22）。
                    .onFocusChanged { focus -> if (focus.isFocused) onBottomRequest() }
                    .focusRequester(focusRequester)
                    // U105：外接键盘的发送快捷键。只认 Ctrl / Cmd + Enter；
                    // 纯 Enter 与 Shift+Enter 保持换行，也不抢中文输入法的确认键。
                    .onPreviewKeyEvent { e ->
                        val send = e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isCtrlPressed || e.isMetaPressed)
                        if (send) onSubmit()
                        send
                    }
                    .padding(vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                interactionSource = interactions,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (input.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_input_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (stopping) {
                // 已点停止、进程 / 收尾尚未结束：转圈表示「正在停止」。
                val stoppingLabel = stringResource(R.string.chat_stopping)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clearAndSetSemantics { contentDescription = stoppingLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else if (turnState is TurnState.Running) {
                // 运行中：实心停止键。U105 返工：回到主线那套 FilledIconButton 默认配色
                // （primary / onPrimary）与 Material 图标 —— 自绘白圆在浅色板上与亮起的
                // 发送键几乎同色，按下与禁用分不出来。
                // 不叠转圈 —— 上方状态条已有一个，两个转动元素看着花（负责人 2026-11-07）。
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Stop, stopLabel, Modifier.size(18.dp))
                    }
                }
            }
            // 48dp 的布局盒与输入框最小高度对齐，40dp 的实心圆居中；
            // 单行时与附件键同轴，多行时随输入框长高、贴着底边。
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 停用态由 M3 默认的 onSurface 12% 灰承担，与亮起的 primary 对比明确。
                FilledIconButton(
                    onClick = { onSubmit() },
                    enabled = input.isNotBlank() || pendingImage != null,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, sendLabel, Modifier.size(18.dp))
                }
            }
        }
        // 下沿控件行：与输入行同一块板，只隔 2dp 呼吸 —— 设计稿 `.row2{margin-top:9px}`。
        if (footer != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            ) {
                footer()
            }
        }
    }
}
