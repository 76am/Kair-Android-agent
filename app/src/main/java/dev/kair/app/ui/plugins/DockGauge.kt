package dev.kair.app.ui.plugins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.theme.MonoTraceMeta
import dev.kair.app.ui.theme.gaugeDisc
import dev.kair.app.ui.theme.gaugeTrack

/**
 * 上下文圆环一档（U115 返工）：**环外径 = 可见圆底外径**，环贴着底的边，不再浮在一枚
 * 大圆钮中间。43dp 留出约 33.4dp 内空（100% 三字放得下），与上一档 34dp 相比环放大 27%。
 */
val DockGaugeSize: Dp = 43.dp

/**
 * 可点时命中区相对环的余量（U105：命中区不得小于 48dp）。
 *
 * U115 返工前是 `size + 14dp`，那层 48dp 底把 34dp 的环围在中间、留 7dp 空隙，
 * 看起来像「小环浮在大钮上」；现在底与环同径，命中区只比环大一圈可点击的边。
 */
private val GAUGE_HIT_MARGIN = 6.dp

/**
 * 统一圆环进度：按 [fraction]（0..1）画一圈 + 中心文字 [centerText]；可点击。
 * [compacting] 时中心改为转圈进度；配色按占用率分级。即底部会话页「上下文占用」圆环外观。
 *
 * [size] 是环的外径，线宽缺省按外径取（约 1/9）——环粗会随尺寸一起缩。
 * 可点时可见圆底与环**同径**（贴紧），命中区 = 环 + [GAUGE_HIT_MARGIN]，仍不低于 48dp。
 * 输入板与插件槽位统一走 [DockGaugeSize]；56dp 那档只留给需要更大环的宿主。
 *
 * [contentDescription] 是读屏用的完整说法：环里那点字在放大字号后可能被裁掉，
 * 百分比从这里补，否则读屏听到的只是「42」。
 */
/**
 * 上下文圆环的测试标记：真机用例靠它量「环有多大、中心那两位数字有没有溢出环内径」。
 * 尺寸问题没法靠截图目测（缩放后 1dp 和 1.4dp 看不出区别，压没压线却完全是两回事）。
 */
const val DOCK_GAUGE_TAG = "dock-gauge"

@Composable
fun DockGauge(
    fraction: Float,
    centerText: String,
    compacting: Boolean = false,
    size: Dp = 56.dp,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val f = fraction.coerceIn(0f, 1f)
    // 轨道与圆底成对：可点的环垫了灰面 / 抬升面，轨道就必须换到能在那层上看见的一档；
    // 不可点的环没有底，仍用原来的 surfaceVariant（与宿主背景一致）。
    val trackColor = if (onClick != null) {
        MaterialTheme.colorScheme.gaugeTrack
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fillColor = when {
        f >= 0.9f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 线宽按外径取（约 1/9）：43dp 是 4.8dp，缩到 26dp 时自动变细，
    // 不会出现「大环细线、小环粗圈」这种同一控件两种质感。
    val stroke = size / 9f
    // U105：命中区不小于 48dp；U115 返工：**可见圆底与环同径**（贴紧），
    // 命中区 = 环 + 6dp（43dp 环 → 49dp 命中），不再是 48dp 底里浮个 34dp 环。
    val hitSize = if (onClick != null) maxOf(size + GAUGE_HIT_MARGIN, 48.dp) else null
    val discSize = if (onClick != null) size else null
    val interaction = remember { MutableInteractionSource() }
    val layer = rememberPressLayer(interaction)
    val layerColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .then(if (hitSize != null) Modifier.size(hitSize) else Modifier)
            .then(
                if (contentDescription != null) {
                    // 合并中心那个数字，否则读屏先念「42」再念「上下文占用 42%」。
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (discSize != null) {
            // 可见底单独一层：按下压层被同一个圆裁住，不会鼓到 48dp 命中区上。
            Box(
                Modifier
                    .size(discSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.gaugeDisc)
                    .pressLayer(layer, layerColor),
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .testTag(DOCK_GAUGE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            // `size` 这个名字在 DrawScope 里同时指外径参数和画布尺寸；画布一侧显式写 `this.size`。
            Canvas(Modifier.fillMaxSize().padding(stroke)) {
                val strokePx = stroke.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx),
                )
                if (f > 0f) {
                    drawArc(
                        color = fillColor,
                        startAngle = -90f,
                        sweepAngle = 360f * f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx),
                    )
                }
            }
            if (compacting) {
                CircularProgressIndicator(
                    Modifier.size(size * 0.42f),
                    // 笔宽跟着外径走：26dp 的环里塞 2dp 的描边会糊成实心点。
                    strokeWidth = (size.value / 18f).dp,
                )
            } else {
                // 百分比：字号按外径派生并固定物理大小（不随系统字号放大）；去字距、行高同字号才放得下 100%。
                val fontSize = with(LocalDensity.current) { (size.value * 0.30f).dp.toSp() }
                Text(
                    centerText,
                    style = MonoTraceMeta.copy(
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        letterSpacing = 0.sp,
                    ),
                    color = labelColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
