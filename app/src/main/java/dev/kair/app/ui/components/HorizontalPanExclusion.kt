package dev.kair.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 横向滚动子区域对推屏抽屉的「这条手势归我」登记（2026-11-21 立）。
 *
 * 抽屉是**整屏**手势：任意位置右滑都能拉开（U115）。Markdown 表格超过屏宽时，表格自己的
 * 横向滚动同样是右滑 —— 光看方向分不出是谁的意图，真机上表格先被拉出抽屉、表格根本滚不动
 * （负责人 2026-11-21 反馈）。
 *
 * 所以横向可滚的子区域在按下时登记，抽屉读到就整条放弃。寄存器只在指针回调里读写，
 * 不进组合状态 —— 它不驱动任何 UI，抖动它没有意义。指针事件都在主线程分发，无需加锁。
 */
internal class HorizontalPanExclusion {
    private var depth = 0

    val active: Boolean get() = depth > 0

    fun acquire() {
        depth++
    }

    fun release() {
        if (depth > 0) depth--
    }
}

internal val LocalHorizontalPanExclusion = staticCompositionLocalOf { HorizontalPanExclusion() }

/**
 * 让本区域的横向手势优先于抽屉：按下即登记，抬起 / 取消释放。
 *
 * 不能等位移过 slop 再登记：抽屉的裁决看「位移或速度任一更横」，弧形甩动可能在位移还不够
 * 横时就先归抽屉。按下即登记，把选择权交给离手指最近的滚动面 —— 这也正是用户手指按在
 * 表格上的本意。区域是否可横向滚动由调用方判定，短到不需要滚的表格不登记，不抢抽屉。
 */
internal fun Modifier.claimHorizontalPan(exclusion: HorizontalPanExclusion): Modifier =
    pointerInput(exclusion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            exclusion.acquire()
            try {
                do {
                    val event = awaitPointerEvent()
                } while (event.changes.any { it.pressed })
            } finally {
                exclusion.release()
            }
        }
    }
