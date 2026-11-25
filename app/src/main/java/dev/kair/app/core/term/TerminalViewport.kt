package dev.kair.app.core.term

/**
 * 终端视口换算（U85）：把「可见高度 + 键盘遮挡高度」还原成逻辑网格尺寸。
 *
 * 键盘弹出只缩小可见区，不改变 PTY 行列——`PtySession.resize` 因此不会写 `TIOCSWINSZ`，
 * 前台全屏程序（sl / vim / top）不会收到 SIGWINCH 重排。
 *
 * 纯函数、不依赖 Compose / Android，判据全部落在 [TerminalViewportTest]。
 */
object TerminalViewport {

    data class Gauge(val rows: Int, val cols: Int, val offsetY: Float)

    /**
     * @param hiddenHeightPx 被键盘遮挡、已从视口扣除的高度；键盘收起时为 0。
     * @param offsetY 网格高于视口时的绘制上移量（≤ 0）；不上移为 0。
     */
    fun gauge(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        hiddenHeightPx: Int,
        cellWidthPx: Float,
        cellHeightPx: Float,
    ): Gauge {
        val cellWidth = cellWidthPx.coerceAtLeast(1f)
        val cellHeight = cellHeightPx.coerceAtLeast(1f)
        val cols = (viewportWidthPx / cellWidth).toInt().coerceAtLeast(1)
        // 键盘遮挡的那部分高度也算进逻辑网格：任一动画帧「可见 + 遮挡」恒定，rows 因此恒定。
        val rows = ((viewportHeightPx + hiddenHeightPx) / cellHeight).toInt().coerceAtLeast(1)
        return Gauge(rows, cols, offsetYFor(viewportHeightPx, rows * cellHeight))
    }

    /** 网格高于视口时整体上移，让底边与视口底边对齐；否则保持顶部对齐（0）。 */
    fun offsetYFor(viewportHeightPx: Int, gridHeightPx: Float): Float {
        if (viewportHeightPx <= 0) return 0f
        return minOf(0f, viewportHeightPx - gridHeightPx)
    }
}
