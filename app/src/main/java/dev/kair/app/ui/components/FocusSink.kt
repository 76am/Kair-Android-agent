package dev.kair.app.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/**
 * 焦点池（U104）：全窗口唯一的「停车位」。
 *
 * 背景：`focusManager.clearFocus()` 会把 **Compose 焦点和 View 层焦点一起清掉**。
 * 安卓 8（MIX 2S 实测）上，输入法收起后系统窗口重新获焦，框架发现「没有任何 View
 * 有焦点」就对 Compose 根发 view 级 requestFocus —— Compose 只能用 focusSearch(Enter)
 * 兜底挑**第一个可聚焦节点**；触摸模式下按钮不可聚焦（focusableInNonTouchMode），
 * 恰好落在第一个输入框（设置页 = 最大步数），光标 + 输入法一起回来。
 *
 * 修法：收焦点时不清空，而是把焦点停到这个 1dp、无样式、不可见的节点上 ——
 * View 层焦点始终在，框架的兜底请求走 `requestFocusCurrent` 的 `isFocused` 早退，
 * 抢焦从源头不发生；输入法因为焦点不在可编辑节点上而正常收起。
 */
val LocalFocusSink = staticCompositionLocalOf<FocusRequester?> { null }

/** 焦点池节点本体：铺在导航层最外层，跨屏存活（导航不销毁它，焦点才停得住）。 */
@Composable
fun FocusSink(requester: FocusRequester) {
    Box(Modifier.size(1.dp).focusRequester(requester).focusable())
}

/**
 * 收焦点的统一入口：优先把焦点停进池子（View 层焦点不丢）；池子不可用时才退回硬清。
 * 这样「收焦点」在各屏只表现为一个调用，不再散落 clearFocus + hide 的组合。
 */
internal fun parkFocus(sink: FocusRequester?, focusManager: FocusManager) {
    if (sink != null && runCatching { sink.requestFocus() }.isSuccess) return
    focusManager.clearFocus()
}
