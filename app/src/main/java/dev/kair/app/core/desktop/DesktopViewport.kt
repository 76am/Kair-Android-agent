package dev.kair.app.core.desktop

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视口映射纯函数（U86 §4 C）：framebuffer → 屏幕矩形、屏幕点 → framebuffer 坐标、
 * 胶囊淡出 alpha。全部 JVM 可测（判据见 `DesktopViewportTest` / `DesktopOverlayFadeTest`）。
 */

/** 缩放模式：等比装入（允许黑边）或 1:1（超出裁剪）。 */
internal enum class ScaleMode { Fit, Actual }

/** 源画面在视口里的目标矩形；x/y 可为负（1:1 裁剪时源比视口宽/高）。 */
internal data class DstRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** 等比装入 / 1:1 居中；任一尺寸 ≤ 0 时退化为零矩形（不崩，点击一律落空）。 */
internal fun fitRect(srcW: Int, srcH: Int, viewW: Int, viewH: Int, mode: ScaleMode): DstRect {
    if (srcW <= 0 || srcH <= 0 || viewW <= 0 || viewH <= 0) return DstRect(0, 0, 0, 0)
    val (w, h) = if (mode == ScaleMode.Fit) {
        val scale = min(viewW.toDouble() / srcW, viewH.toDouble() / srcH)
        (srcW * scale).roundToInt() to (srcH * scale).roundToInt()
    } else {
        srcW to srcH
    }
    return DstRect(x = (viewW - w) / 2, y = (viewH - h) / 2, w = w, h = h)
}

/**
 * 屏幕坐标 → framebuffer 坐标；点落在黑边 / 画布外返回 null（**不发越界事件**，任务书 §4 C）。
 * 结果按向下取整并夹在 [0, src) 内（dst 边缘的舍入误差不越界）。
 */
internal fun toFramebuffer(px: Float, py: Float, dst: DstRect, srcW: Int, srcH: Int): Pair<Int, Int>? {
    if (dst.w <= 0 || dst.h <= 0 || srcW <= 0 || srcH <= 0) return null
    if (px < dst.x || py < dst.y || px >= dst.x + dst.w || py >= dst.y + dst.h) return null
    val fx = ((px - dst.x) / dst.w * srcW).toInt().coerceIn(0, srcW - 1)
    val fy = ((py - dst.y) / dst.h * srcH).toInt().coerceIn(0, srcH - 1)
    return fx to fy
}

/**
 * 胶囊 alpha（任务书 §4 F）：进入 3s 后淡到 0.3，任何触摸把 idle 归零即恢复 1.0。
 * 纯函数，UI 只负责喂「距上次触摸的毫秒数」。
 */
internal fun overlayAlpha(idleMs: Long): Float = if (idleMs <= FADE_AFTER_MS) 1f else FADED_ALPHA

private const val FADE_AFTER_MS = 3_000L
private const val FADED_ALPHA = 0.3f
