package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 「回到底部」按钮的迟滞：离底不足这个距离不显示（贴底差 1px 不算上翻）。 */
internal const val JumpToBottomHysteresisDp = 24

/**
 * ↓ 按钮的可见性判据：**用户手动上翻**才显示，自动跟随中的一帧间隙不算。
 *
 * 不能用 `!canScrollForward`：流式输出每个 token 都会让它翻一次（内容长高 → 立刻可滚 →
 * 补滚落位 → 又到底），按钮因此以推送频率（约 15Hz）频闪 —— 负责人报的「下拉按键一起闪」
 * 就是这一条。`follow` 是人主动拖出来的状态，一个回合内不会自己翻。
 */
internal fun jumpToBottomVisible(following: Boolean, messageCount: Int): Boolean =
    !following && messageCount > 0

/** 迟滞判据：离底 [distancePx] 至少 [thresholdPx] 才算「翻上去了」。 */
internal fun isFarEnoughToShow(distancePx: Int, thresholdPx: Int): Boolean =
    distancePx >= thresholdPx

/**
 * 离底的像素距离。
 *
 * - 末条已不在可见项里（用户翻得很上）→ [Int.MAX_VALUE]，不必算精确值；
 * - 否则 = 末个可见项底边 - 视口高度（贴底时约 0，超出说明内容比视口高）。
 *
 * 不用 `canScrollForward` 代替：它只回答「还能不能滚」，答不出「滚上去多少」，
 * 而迟滞需要后者。
 */
internal fun distanceToBottomOf(
    totalItems: Int,
    visibleItems: List<VisibleItem>,
    viewportHeight: Int,
): Int {
    if (totalItems <= 0 || visibleItems.isEmpty()) return 0
    val last = visibleItems.last()
    if (last.index < totalItems - 1) return Int.MAX_VALUE
    return last.offset + last.size - viewportHeight
}

/**
 * 「回到底部」按钮的可见性（组合里读一次）。
 *
 * 读 `layoutInfo` 只发生在**不跟随**时：跟随中直接返回 false，流式每帧的布局变化
 * 不会让这个 `derivedStateOf` 失效，也就不会把整屏拖进每帧重组。
 */
@Composable
internal fun rememberJumpToBottomVisible(
    listState: LazyListState,
    following: State<Boolean>,
    messageCount: Int,
): Boolean {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { JumpToBottomHysteresisDp.dp.roundToPx() } }
    val count = rememberUpdatedState(messageCount)
    val visible = remember(listState, thresholdPx) {
        derivedStateOf {
            if (!jumpToBottomVisible(following.value, count.value)) return@derivedStateOf false
            val info = listState.layoutInfo
            isFarEnoughToShow(
                distanceToBottomOf(
                    totalItems = info.totalItemsCount,
                    visibleItems = info.visibleItemsInfo.map { VisibleItem(it.index, it.offset, it.size) },
                    viewportHeight = info.viewportSize.height,
                ),
                thresholdPx,
            )
        }
    }
    return visible.value
}
