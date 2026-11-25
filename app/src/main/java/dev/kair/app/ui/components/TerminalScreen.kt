package dev.kair.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import dev.kair.app.core.term.TerminalColor
import dev.kair.app.core.term.TerminalPalette
import dev.kair.app.core.term.TerminalSnapshot
import dev.kair.app.core.term.TerminalViewport

/** 终端默认底色 / 前景：纯黑底 + 浅灰字，与 Termux 默认配色一致（U59 用户要求「跟 termux 差不多」）。 */
private const val DEFAULT_BACKGROUND = 0xFF000000.toInt()
private const val DEFAULT_FOREGROUND = 0xFFE6E6E6.toInt()

/** 单元格度量：等宽字体下按 `M` 的宽度与字体行高算格子。 */
private class CellMetrics(val width: Float, val height: Float, val baseline: Float)

/**
 * 终端网格渲染（U59 步骤 4）：把 [TerminalSnapshot] 画成等宽字符网格。
 *
 * - **纯渲染**：不接收输入、不改解析器状态；光标 / 颜色 / 下划线都按快照画；
 * - 每个非空格子逐字 `drawText`（空格只画背景），宽字符的 continuation 格跳过；
 * - 尺寸变化通过 [onGridSizeChanged] 上报「能放下多少行多少列」，调用方负责去抖与 resize；
 * - [hiddenHeightPx]（键盘遮挡高度）计入逻辑网格：键盘弹出只缩视口，不改 PTY 行列（U85）；
 * - 网格高于视口时整体上移，底边与视口底边对齐，保证光标 / 提示符那条线可见。
 */
@Composable
fun TerminalScreen(
    snapshot: TerminalSnapshot,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
    /**
     * 被软键盘遮挡的高度（px）。**必须与调用方的 `imePadding()` 成对出现**：这里只负责把它
     * 加回逻辑网格，视口本身得先被 `imePadding` 扣掉。反过来用（视口没扣而这里加）会让
     * 行数多算，反而把 PTY 撑大 —— 那才是真正的重排（U85）。
     */
    hiddenHeightPx: Int = 0,
    onGridSizeChanged: (rows: Int, cols: Int) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val textSize = with(density) { fontSizeSp.sp.toPx() }
    val textPaint = remember(textSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            this.textSize = textSize
        }
    }
    val fillPaint = remember { Paint().apply { style = Paint.Style.FILL } }
    val metrics = remember(textPaint) {
        val fm = textPaint.fontMetrics
        CellMetrics(
            width = textPaint.measureText("M").coerceAtLeast(1f),
            height = (fm.bottom - fm.top + fm.leading).coerceAtLeast(1f),
            baseline = -fm.top,
        )
    }

    Canvas(
        modifier = modifier
            .background(Color(DEFAULT_BACKGROUND))
            // 只在**布局尺寸**变化时重算：`hiddenHeightPx` 单独变不会走到这里。
            // 现在靠「`imePadding` 与 IME inset 同源」保证两者同时变，别把它当成响应式的。
            .onSizeChanged { size ->
                val viewport = TerminalViewport.gauge(
                    viewportWidthPx = size.width,
                    viewportHeightPx = size.height,
                    hiddenHeightPx = hiddenHeightPx,
                    cellWidthPx = metrics.width,
                    cellHeightPx = metrics.height,
                )
                onGridSizeChanged(viewport.rows, viewport.cols)
            },
    ) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val cellWidth = metrics.width
            val cellHeight = metrics.height
            // 网格比视口高（键盘弹出）时上移，底边对齐；否则保持顶部对齐。
            // 偏移基准故意用 `snapshot.rows`（**实际绘制的行数**），不是 `onSizeChanged` 刚上报的
            // 行数：`resize` 落地前两者可能差一行，按绘制行数对齐才不会在底部留一条空带。
            val gridHeight = snapshot.rows * cellHeight
            val offsetY = TerminalViewport.offsetYFor(size.height.toInt(), gridHeight)

            if (offsetY != 0f) {
                native.save()
                native.translate(0f, offsetY)
            }

            for (row in 0 until snapshot.rows) {
                val top = row * cellHeight
                for (col in 0 until snapshot.cols) {
                    val cell = snapshot.cell(row, col)
                    if (cell.continuation) continue
                    val left = col * cellWidth
                    val fg = if (cell.fg == TerminalColor.DEFAULT) {
                        DEFAULT_FOREGROUND
                    } else {
                        TerminalPalette.argb(cell.fg, cell.bold)
                    }
                    val bg = if (cell.bg == TerminalColor.DEFAULT) {
                        DEFAULT_BACKGROUND
                    } else {
                        TerminalPalette.argb(cell.bg, false)
                    }
                    // 反显：前景背景互换（DEFAULT 也参与互换，用终端默认色）。
                    val actualFg = if (cell.reverse) bg else fg
                    val actualBg = if (cell.reverse) fg else bg

                    if (actualBg != DEFAULT_BACKGROUND) {
                        fillPaint.color = actualBg
                        native.drawRect(left, top, left + cellWidth, top + cellHeight, fillPaint)
                    }
                    if (cell.char != ' ') {
                        textPaint.color = actualFg
                        native.drawText(cell.char.toString(), left, top + metrics.baseline, textPaint)
                    }
                    if (cell.underline) {
                        fillPaint.color = actualFg
                        val thickness = (cellHeight / 12f).coerceAtLeast(1f)
                        native.drawRect(
                            left,
                            top + cellHeight - thickness,
                            left + cellWidth,
                            top + cellHeight,
                            fillPaint,
                        )
                    }
                }
            }

            if (snapshot.cursorVisible &&
                snapshot.cursorRow in 0 until snapshot.rows &&
                snapshot.cursorCol in 0 until snapshot.cols
            ) {
                val cursorCell = snapshot.cell(snapshot.cursorRow, snapshot.cursorCol)
                val left = snapshot.cursorCol * cellWidth
                val top = snapshot.cursorRow * cellHeight
                fillPaint.color = DEFAULT_FOREGROUND
                native.drawRect(left, top, left + cellWidth, top + cellHeight, fillPaint)
                if (!cursorCell.continuation && cursorCell.char != ' ') {
                    textPaint.color = if (cursorCell.bg == TerminalColor.DEFAULT) {
                        DEFAULT_BACKGROUND
                    } else {
                        TerminalPalette.argb(cursorCell.bg, false)
                    }
                    native.drawText(cursorCell.char.toString(), left, top + metrics.baseline, textPaint)
                }
            }

            if (offsetY != 0f) native.restore()
        }
    }
}
