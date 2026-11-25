package dev.kair.app.ui.screens.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kair.app.R
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon
import kotlinx.coroutines.delay

/**
 * 回合的**头部件**（设计稿 v2）：折叠头与运行中回合的 live 块。
 *
 * 回合不再整块渲染：用户消息 / 折叠头 / 每一步 / 最终思考 / 最终回答都是
 * LazyColumn 的独立一行（见 `FeedRows.kt`）。这里只剩与回合统计相关的两个头，
 * 展开状态由列表层的 [FoldExpansion] 持有，本文件不存任何回合状态。
 */

/**
 * 折叠头（设计稿 `.fold`）：56dp 行 + chips。
 *
 * 头行只报四件事：步数、耗时、涉及工具、失败 / 被拒数 —— 别的都不放，
 * 「值不值得点开」由这一行回答。默认收起；展开后每一步由列表单独出卡，
 * 上百步也只组合可见的那几行。
 */
@Composable
internal fun FoldHeader(round: TurnRound, open: Boolean, onToggle: () -> Unit) {
    // 折叠头同样走「矩形 state layer」：M3 的水波纹从触点扩散，在 56dp 高的横条上
    // 会鼓出一颗很圆的亮斑，和整块板的直角边不搭（TraceBlock 已按这个口径改过）。
    val headerInteraction = remember { MutableInteractionSource() }
    val headerLayer = rememberPressLayer(headerInteraction)
    // 进绘制 lambda 前先取色：MaterialTheme 只能在组合期读。
    val headerLayerColor = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(interactionSource = headerInteraction, indication = null) { onToggle() }
            .pressLayer(headerLayer, headerLayerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            SvgPathIcon(
                pathData = PluginGlyph.Layers,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.chat_turn_processed, round.stepCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " · " + formatElapsed(round.elapsedMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // U111：回合平均解码速度，与耗时同一行；没有观测到 stats 的回合不写。
                round.speed?.let { speed ->
                    Text(
                        " · " + stringResource(R.string.chat_speed_turn_avg, formatTurnSpeed(speed)),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            TurnChips(round)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(17.dp)
                .rotate(if (open) 90f else 0f),
        )
    }
}

/** 折叠头第二行：工具 chips + 失败 / 被拒（朱红）chips。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnChips(round: TurnRound) {
    if (round.toolCounts.isEmpty() && round.failCount == 0 && round.denyCount == 0) return
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        round.toolCounts.forEach { (name, n) ->
            StepChip(text = if (n > 1) "$name ×$n" else name)
        }
        if (round.failCount > 0) {
            StepChip(
                text = stringResource(R.string.chat_turn_failed, round.failCount),
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.error,
            )
        }
        if (round.denyCount > 0) {
            StepChip(
                text = stringResource(R.string.chat_turn_denied, round.denyCount),
                background = MaterialTheme.colorScheme.errorContainer,
                foreground = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 一枚 chip（设计稿 `.ctag`）：18dp 高、等宽小字；默认取 slab-high 底。 */
@Composable
private fun StepChip(
    text: String,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .height(18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
            ),
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * 运行中回合的头（设计稿 `.live`）：脉冲点 + 步数 + 走表的耗时。
 *
 * 运行中的回合**不可折叠** —— 正在发生的事不能被折起来；步数与耗时每 500ms 走一次，
 * 耗时同样走方案 A 的 timestamp 口径（起点 = 触发消息，终点 = 当下）。
 */
@Composable
internal fun LiveHead(round: TurnRound) {
    val startTs = round.user?.timestamp ?: round.agent.firstOrNull()?.timestamp
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(round.key, startTs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val transition = rememberInfiniteTransition(label = "live")
    val halo by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "halo",
    )
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = halo)),
            )
            Text(
                stringResource(R.string.chat_turn_running),
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            val elapsed = startTs?.let { formatElapsed(now - it) }
            Text(
                text = "· " + stringResource(R.string.chat_turn_steps, round.stepCount) +
                    (elapsed?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
