package dev.kair.app.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

/**
 * 顶栏内容高：U103 从 M3 默认的 64dp 压到 **48dp**（负责人「顶栏都可以压小一下，给窗口」）。
 */
val CompactTopBarContentHeight = 48.dp

/** 顶栏左右留白：与 M3 TopAppBar 的 TopAppBarHorizontalPadding 同值（4dp）。 */
private val TopBarEdgePadding = 4.dp

/**
 * Kair 全部屏面的顶栏：**自绘 48dp 行**（U103 第二版），不再包 M3 TopAppBar。
 *
 * 第一版在外部强压 `Modifier.height(48dp + 状态栏)` 的做法失败：M3 内部按自己的
 * 名义尺寸（64dp 槽位、48dp 最小触达）各自排布，约束压得进容器压不进内部数学 ——
 * 真机实测标题中心落在内容高 48dp 处、☰ 图标却落在 60dp 处，差出 12dp。
 * 自绘行只信一条规则：全体 CenterVertically。
 */
@Composable
fun KairTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(CompactTopBarContentHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = TopBarEdgePadding)) { navigationIcon() }
            Box(Modifier.weight(1f).padding(end = TopBarEdgePadding)) { title() }
            // actions 必须包在 Row 里：调用方传的是多个并排组件（首页 = 插件 + 设置两个
            // 图标按钮），直接进 Box 会叠在一起 —— 表现就是「右上角图标渲染异常」。
            Row(Modifier.padding(end = TopBarEdgePadding)) { actions() }
        }
    }
}

/**
 * 点输入框以外的空白区收焦点（U103）：光标消失、键盘收起。
 *
 * 只 clearFocus 在安卓 8（MIX 2S）上键盘不收，而且**会连 View 层焦点一起清掉**，
 * 招来框架的兜底抢焦（见 [LocalFocusSink] 注释）——所以 U104 起一律走 [parkFocus]，
 * 把焦点停进池子而不是清空。
 *
 * 按钮与输入框会消费 down 事件，父级的 detectTapGestures 收不到 —— 所以不会误清
 * 刚点进输入框拿到的焦点。抽成修饰符是为了让各屏只占一行，别把方法撑过尺寸守卫。
 */
@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val sink = LocalFocusSink.current
    return pointerInput(Unit) {
        detectTapGestures(onTap = {
            keyboard?.hide()
            parkFocus(sink, focusManager)
        })
    }
}
