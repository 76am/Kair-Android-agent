package dev.kair.app.ui.screens.chat

import android.util.Log
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.kair.app.R
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.ui.theme.floatingContainer
import dev.kair.app.ui.theme.onFloatingContainer
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 滚到列表末端的超大偏移：末条消息可能很高，正偏移把它的顶部往上推，超出部分
 * 由 LazyColumn 在边界处钳制，从而露出最新的一行。
 */
private const val ScrollToEndOffset = 1_000_000

/** 自动跟随的日志标签（D4 侦察用）。 */
private const val TAG = "AutoScroll"

/**
 * 立即贴底。末条消息可能很高，用超大正偏移把它的顶部推出视口，超出部分由 LazyColumn
 * 在边界处钳制，从而露出最新的一行。自动跟随、输入框聚焦、↓ 按钮三处共用这一个实现点，
 * 避免「贴底」出现几种偏移口径。
 *
 * **必须是同步的 `requestScrollToItem`，不能改成挂起的 `scrollToItem`**：
 * - 挂起版会参与滚动互斥，用户一拖动就会把等待中的协程取消；如果调用它的是
 *   `snapshotFlow` 的收集协程，取消会直接把整个自动跟随结束掉 —— 退后台 / 拖动 /
 *   重进会话之前都不再跟随（**不是**简单的一次滚动被取消）。
 * - 同步版在下一轮布局里落位，不挂起、不参与互斥；配合 [MessageIdentity] 的
 *   布局前触发，内容增长与补滚落在同一帧。
 */
internal fun LazyListState.requestScrollToLatest(messageCount: Int) {
    if (messageCount <= 0) return
    requestScrollToItem(messageCount - 1, ScrollToEndOffset)
}

/**
 * 自动跟随最新消息到底部。
 *
 * 贴底有两条路径，职责不同：
 * - **同帧贴底**（`SideEffect`）：每次组合应用后、布局前请求滚到末条。流式 token / 工具
 *   输出每帧都会替换末条消息实例，新的正文在同一次 measure 里生效，内容增长与补滚落在
 *   同一帧 —— 否则内容先长出去、下一帧才滚回来，就是可见的跳动（D3 / 2.3 真机复现）。
 * - **布局兜底**（`snapshotFlow`）：视口 / 键盘 / 输入框高度变化，以及折叠块展开这类
 *   不经过消息状态、只在布局期长高的变化。这些没有「消息实例变化」可挂，只能等布局产物
 *   出来再补一帧；大块内容插入时这一帧的位移是预期余量。
 *
 * 触发键取「末个可见项的 index + 底边」（见 [followTargetOf]）与末条消息实例
 * （[MessageIdentity]）：末条在视口外时只取 size 恒为 null，发不出信号（D2）；实例键让
 * 兜底路径在只有正文变化时也能醒一次。
 *
 * 滚动全部走同步的 [requestScrollToLatest]，**不在本协程里挂起等滚动**：挂起版
 * `scrollToItem` 被用户拖动 / 退后台打断时会取消调用协程；而这个协程同时是
 * `snapshotFlow` 的收集者，一旦被取消，自动跟随与输入框跟随会一起永久失效，
 * 只能退出会话再进才恢复（D4 的后台场景就是这么来的）。
 *
 * 除末条高度外，**键盘高度**变化也重新贴底：键盘弹出 / 收起时既无新消息、末条高度
 * 也可能不变，只监听末条高度就没人补滚 —— 视口被压小后最新内容留在下方，表现为
 * 「停止输出后键盘弹出、消息列表不再上推」（见 Issue #8）。
 *
 * 视口高度（`viewportSize.height`）也是触发源，且**不能**只读 `WindowInsets.ime`：键盘
 * 只是让视口变小的原因之一，输入框从一行涨到六行同样会压小列表可用高度。只监听键盘
 * 高度时，多行输入把输入框拉长的那一下没有任何补滚 —— LazyColumn 的滚动偏移停在原处，
 * 最新内容就沉到视口下方，表现为「输入框上移了、聊天内容被挡住不跟着上移」。视口高度
 * 一次性覆盖键盘与输入框增高两种情形。
 *
 * @param itemCount LazyColumn 的**行数**，不是消息条数：回合折叠后一个回合只占一行
 *   （见 `buildFeedItems`），而滚到末行必须用行号。按消息条数算出来的 index 会指向
 *   不存在的行 —— 运行中的回合只剩一行时更是每次都越界，表现就是「正在跑时不再自动下拉」。
 * @param messages 只用来取**末条消息实例**：流式 token 每帧换实例，这是布局产物还没更新时的
 *   兜底触发键（见 [followKey]）。
 * @param atBottom 是否已贴底（`!canScrollForward`）。由宿主计算一次并同时喂给
 *   「↓ 按钮」的可见性，保证按钮与跟随态看到的是同一个判定。
 * @param bottomRequest 显式「回到底部」请求的计数（聚焦输入框 / 点 ↓ 按钮各加一）。
 *   **不要**用「贴底后再等 `atBottom` 变成 true」来恢复跟随态：键盘 inset 动画会把
 *   视口压小，滚动刚完成时推导值可能直接被下一次布局覆盖，`atBottom` 永远等不到
 *   true，表现为「点输入框不触底 / 要点好几次」。
 *   宿主用**两条**入口喂这个计数，不是重复实现：`onFocusChanged` 覆盖「键盘关着 →
 *   点输入框弹键盘」，点击交互覆盖「键盘已弹出、焦点没变 → 再点一次」。只留其一都会
 *   漏掉另一半场景（见 [ChatScreen] 的 composerInteractions）。
 * @return 当前是否处于跟随态。**这是 ↓ 按钮的唯一可见性判据**（见
 *   [rememberJumpToBottomVisible]）：按钮语义上是「给手动上翻的人一个回到底部的入口」，
 *   而 `!canScrollForward` 在流式期间每帧翻转，挂它会得到 15Hz 频闪。
 */
@Composable
internal fun AutoScrollToLatest(
    listState: LazyListState,
    itemCount: Int,
    messages: List<ChatMessage>,
    atBottom: Boolean,
    bottomRequest: Int,
): State<Boolean> {
    // 仅在「跟随态」自动贴底：用户一旦手动拖动列表，就停下；滚回底部后恢复。
    // 否则流式输出每个 token 都会把正在上翻看历史的用户弹回底部。
    val following = remember { mutableStateOf(true) }
    val latestCount by rememberUpdatedState(itemCount)
    val latestMessages by rememberUpdatedState(messages)
    val density = LocalDensity.current
    // 只在这里取一次 inset 对象（它本身不是值，读值在 snapshotFlow 里）。
    val ime = WindowInsets.ime
    // 跟随只在界面 STARTED 时工作：退后台期间 SSE 仍在产出，但没必要每个 token 都
    // 强制重排一次列表；回到前台时 repeatOnLifecycle 会重新起收集，snapshotFlow 的
    // 首帧立即把累计的内容一次贴底。
    val lifecycleOwner = LocalLifecycleOwner.current
    // 同帧贴底：组合应用后、布局前请求滚到末条，新正文在同一次 measure 里生效。
    SideEffect {
        if (following.value && latestCount > 0) {
            listState.requestScrollToItem(latestCount - 1, ScrollToEndOffset)
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following.value = false
                // 松手时若已在底部，立即恢复跟随。之前唯一的恢复路径是下面的
                // `LaunchedEffect(atBottom)`，而它只在 atBottom **值变化**时执行 ——
                // 「本来就贴着底、拉一下又回到底」全程 atBottom 恒 true，effect 不重跑，
                // following 永久停在 false。这正是负责人报告的「触底下拉时，有的时候
                // 会断开不再自动跟踪」的全部触发条件：只有贴着底才中，所以是「有的」。
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (!listState.canScrollForward) following.value = true
            }
        }
    }
    LaunchedEffect(atBottom) {
        if (atBottom) following.value = true
    }
    // 显式请求优先于上面的推导：立刻进入跟随态并贴底，随后由下面的
    // snapshotFlow 继续跟随键盘动画的每一帧。
    LaunchedEffect(bottomRequest) {
        if (bottomRequest == 0) return@LaunchedEffect
        following.value = true
        listState.requestScrollToLatest(latestCount)
    }

    // 布局兜底：消息条数 / 末条实例 / 末个可见项位置 / 视口高度（键盘 + 输入框增高）。
    // 键盘动画不靠组合期读 inset（那会让整屏每帧重组），而是在 snapshotFlow 里读
    // `WindowInsets.ime`；它本身是快照状态，键盘每动一帧都会发出一次通知。
    // 视口高度取 `layoutInfo`：布局产物，键盘与输入框拉长都会让它变，和 IME 高度
    // 是互补而非重复的触发源（后者看不到输入框增高，前者看不到「窗口被 pan、inset 为 0」）。
    LaunchedEffect(listState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // D4 侦察：同一段「跟随却没贴底」只记一条，恢复正常后重新武装（只打日志，不改行为）。
            // 读的是本次请求落位**之前**的布局：大块内容插入时会出现一条，属预期余量；
            // 若又退化成连续刷屏，说明同帧贴底失效。
            var reconLogged = false
            snapshotFlow {
                val info = listState.layoutInfo
                followKey(
                    followTargetOf(
                        count = latestCount,
                        viewportHeight = info.viewportSize.height,
                        imeBottom = ime.getBottom(density),
                        visibleItems = info.visibleItemsInfo.map { VisibleItem(it.index, it.offset, it.size) },
                    ),
                    latestMessages.lastOrNull(),
                )
            }
                .distinctUntilChanged()
                .collect { (target, _) ->
                    if (!following.value || target.count <= 0) return@collect
                    // 同步请求，不挂起：见 requestScrollToLatest 的 KDoc（挂起版被取消会杀死本协程）。
                    listState.requestScrollToLatest(target.count)
                    // D4 侦察（**只埋日志，不做行为修正**）：记触发键五元组 + atBottom，
                    // 同一段「跟随却没贴底」只记一条，恢复后重新武装。
                    val info = listState.layoutInfo
                    val line = d4ReconLine(
                        target = target,
                        atBottom = !listState.canScrollForward,
                        actualLastBottom = info.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size },
                        actualViewportHeight = info.viewportSize.height,
                    )
                    when {
                        line == null -> reconLogged = false
                        !reconLogged -> {
                            reconLogged = true
                            Log.w(TAG, line)
                        }
                    }
                }
        }
    }
    return following
}

/**
 * 「回到底部」的悬浮入口：可见性仍由 [rememberJumpToBottomVisible] 决定，这里只管形态与点击。
 *
 * 用 `SmallFloatingActionButton` 而不是给 `FilledIconButton` 手动加 `Modifier.shadow`：
 * 后者的阴影落在 48dp 点击区上、与本体的 40dp 圆不同心，真机上底部会露出半圈方角
 * （2026-11-05 负责人实测）。FAB 的阴影由 Surface 按同一个圆角形状绘制，与首页「+」一致。
 *
 * 颜色取悬浮语义槽 `floatingContainer`（U102 拍板白底黑字），不要改回 `primary`
 * （那会与 agent 语义抢色）—— 发送键同为白底黑字，靠位置区分而非颜色。
 */
@Composable
internal fun JumpToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.padding(bottom = 8.dp),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.floatingContainer,
        contentColor = MaterialTheme.colorScheme.onFloatingContainer,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 3.dp,
            pressedElevation = 3.dp,
            focusedElevation = 3.dp,
            hoveredElevation = 4.dp,
        ),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            stringResource(R.string.chat_scroll_to_bottom),
        )
    }
}
