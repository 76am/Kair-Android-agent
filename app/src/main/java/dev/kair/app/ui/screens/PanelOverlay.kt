package dev.kair.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PanelChrome

/**
 * 工具型面板覆盖层的高度比例：上方留出约四分之一让会话可见 —— agent 继续产出时
 * 能瞟到进度，这是「面板不离开会话」的一半价值，另一半是收起即回原位。
 */
internal const val PANEL_OVERLAY_HEIGHT_FRACTION = 0.74f

/**
 * 覆盖层外壳的测试锚点。
 *
 * 加它是因为「74% 高」这条契约只能靠量尺寸验证，而外壳里没有任何节点带尺寸语义
 * （标题与按钮都按内容撑开）。用 testTag 而不是靠「关掉按钮上方的那个父节点」——
 * 后者在标题换成两行、按钮换位置时会静默失效。
 */
internal const val PANEL_OVERLAY_TEST_TAG = "panel-overlay-chrome"

/** 面板以哪种宿主打开：会话内覆盖层，还是独立路由的全屏宿主。 */
internal enum class PanelHostKind { Overlay, FullScreen }

/**
 * 面板宿主选择规则（U93）。
 *
 * 沉浸面板（图形桌面）要整块屏幕，压进 74% 只会缩水，仍走 `Routes.panel`；
 * 其余工具面板走覆盖层。查不到注册信息时（插件刚被禁用、id 已失效）按覆盖层处理，
 * 保持 U93 之前的行为不变 —— 那条路径本就不会渲染出正文。
 */
internal fun panelHostKind(chrome: PanelChrome?): PanelHostKind =
    if (chrome == PanelChrome.Immersive) PanelHostKind.FullScreen else PanelHostKind.Overlay

/**
 * 覆盖层的**外壳**：标题 + 全屏 + 关闭 + 正文槽。
 *
 * 与 [PanelOverlay] 分开是为了可测：[PanelOverlay] 要 `AppContainer` 才能拿到面板标题与
 * 正文，而「高度比例、显式关闭出口、正文是否被压掉」这些契约跟容器毫无关系。
 * 拆开之后 instrumented 测试能把外壳单独渲染出来量高度、点关闭。
 *
 * 「全屏」是 U100 补的：有些面板（日志、桌面）值得给一整屏。它是**换宿主**——
 * 覆盖层收起、走独立路由，面板会被重建，插件自己 remember 的展开状态回到初始；
 * 所以做成一个明确按钮，不做滑动手势隐式触发。
 *
 * @param onFullscreen 传 null 表示该面板不支持全屏（不渲染按钮）。
 */
@Composable
internal fun PanelOverlayChrome(
    title: String,
    onClose: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(PANEL_OVERLAY_HEIGHT_FRACTION)
            .testTag(PANEL_OVERLAY_TEST_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 全屏在关闭左边：两个出口都在右手，动作由轻到重排列。
            if (onFullscreen != null) {
                TextButton(onClick = onFullscreen) { Text(stringResource(R.string.action_fullscreen)) }
            }
            // 显式出口：下滑与返回键都能关，但两个手势都不写在自己脸上。
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
        HorizontalDivider()
        content()
    }
}

/**
 * 会话内的面板覆盖层（U93）。
 *
 * 为什么不是导航到 `Routes.panel`：那条路是**换页**——面板盖住会话、返回再回来，
 * 用户每次都要确认「我刚说到哪了」。覆盖层把面板放在会话之上，会话留在下面不卸载，
 * 收起即回原位，返回键 / 下滑 / 点遮罩三个出口都只是收起这一层。
 *
 * 沉浸面板（图形桌面）不走这里——它要整块屏幕，仍由 `PanelHostScreen` 承担，
 * 判定见 [panelHostKind]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanelOverlay(
    container: AppContainer,
    panelId: String,
    onClose: () -> Unit,
    onFullscreen: (() -> Unit)? = null,
) {
    // skipPartiallyExpanded：直接展开到目标高度。半展态在这里没有意义——
    // 半展时正文只剩一条缝，用户还得再拖一次，多一次手势。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        PanelOverlayChrome(title = panelTitle(container, panelId), onClose = onClose, onFullscreen = onFullscreen) {
            PanelContent(container, panelId, Modifier.weight(1f))
        }
    }
}
