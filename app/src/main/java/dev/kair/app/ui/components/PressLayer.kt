package dev.kair.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color

/**
 * 按下反馈的唯一口径：在本体形状内压一层半透明，**不用 M3 水波纹**。
 *
 * 为什么不用波纹：波纹画在**命中框**上，跟着触点扩散，而命中框按 U105 一律补到 48dp，
 * 本体往往只有 24–34dp（圆环、「⋯」、30dp 高的 chip）。于是按下是一块盖住邻位的方形亮斑
 * ——用户既看不清按到了什么，也看不清原本那里能点（2026-11-20 真机反馈：上下文圆环与「⋯」都是这个形态）。
 * 折叠头与工具卡早就改成 state layer（`TurnFold` / `TraceBlock`），本类把那份口径收成一处，
 * 数值沿用它们：10% `onSurface`，按下 90ms、松开 150ms。
 *
 * 用法固定三步：`clip(本体形状)` → `background(...)` → `pressLayer(...)`，且 clickable 传
 * `indication = null`。`pressLayer` 排在 `clip` 之后，压层才会被同一个形状裁住。
 */
@Composable
internal fun rememberPressLayer(interaction: InteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) PRESS_ALPHA else 0f,
        animationSpec = tween(durationMillis = if (pressed) PRESS_IN_MS else PRESS_OUT_MS),
        label = "press-layer",
    ).value
}

/** 把 [rememberPressLayer] 的值画出来。alpha 为 0 时一个像素都不画，不留空图层。 */
internal fun Modifier.pressLayer(alpha: Float, color: Color): Modifier = drawWithContent {
    drawContent()
    if (alpha > 0f) drawRect(color.copy(alpha = alpha))
}

private const val PRESS_ALPHA = 0.10f
private const val PRESS_IN_MS = 90
private const val PRESS_OUT_MS = 150
