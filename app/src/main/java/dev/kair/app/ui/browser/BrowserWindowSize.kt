package dev.kair.app.ui.browser

/** 小窗宽度占屏幕宽度的比例：上一版 0.60 的一半，观感接近系统画中画。 */
private const val WINDOW_WIDTH_FRACTION = 0.30f

/** 小窗高度上限占屏幕高度的比例：竖屏下也不至于变成「整屏黑块」。 */
private const val WINDOW_MAX_HEIGHT_FRACTION = 0.62f

/** 尺寸异常（分屏为 0 / 外接屏）时的兜底：9:19.5 竖屏。 */
private const val WINDOW_ASPECT_FALLBACK = 2.1667f

/**
 * 悬浮小窗页面区尺寸：宽度取屏宽 30%（旧实现 60% 的一半），高度按**当前屏幕的宽高比**
 * 缩放（竖屏 ≈ 9:19.5，横屏 ≈ 19.5:9），网页拿到的是真实手机视口，而不是平板式的矮胖块。
 *
 * 高度超过屏高 62% 时**缩窄宽度**而不是压扁比例；宽 / 高跌破下限时**按比例一起放大**
 * （下限只兜底，不允许把比例拉变形）。屏幕比例本身异常时退回 9:19.5。
 * 纯函数（无 Android 依赖），边界由 `BrowserWindowSizeTest` 钉住。
 */
internal fun browserWindowSizePx(
    screenWidth: Int,
    screenHeight: Int,
    minWidthPx: Int,
    minHeightPx: Int,
): Pair<Int, Int> {
    val safeWidth = screenWidth.coerceAtLeast(1)
    val ratio = (screenHeight.toFloat() / safeWidth)
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(0.42f, 2.4f)
        ?: WINDOW_ASPECT_FALLBACK
    val maxHeight = (screenHeight * WINDOW_MAX_HEIGHT_FRACTION).toInt()
    var width = (screenWidth * WINDOW_WIDTH_FRACTION).toInt().coerceAtLeast(minWidthPx)
    if (width * ratio > maxHeight) width = (maxHeight / ratio).toInt().coerceAtLeast(minWidthPx)
    var height = (width * ratio).toInt()
    if (height < minHeightPx) {
        // 保比例放大：只抬高度会把横屏小窗压成方块，宁可让宽度跟着涨。
        height = minHeightPx
        width = (height / ratio).toInt()
    }
    return width to height
}
