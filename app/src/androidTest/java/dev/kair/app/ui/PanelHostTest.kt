package dev.kair.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kair.app.R
import dev.kair.app.core.plugin.PanelChrome
import dev.kair.app.ui.screens.PANEL_OVERLAY_HEIGHT_FRACTION
import dev.kair.app.ui.screens.PANEL_OVERLAY_TEST_TAG
import dev.kair.app.ui.screens.PanelHostKind
import dev.kair.app.ui.screens.PanelOverlayChrome
import dev.kair.app.ui.screens.panelHostKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 面板宿主的两条硬契约（U93 / U94）。
 *
 * 一是**选哪条宿主**：沉浸面板全屏、工具面板覆盖层 —— 这条决定了「点一下会不会离开会话」，
 * 是 U93 的整个前提；它此前只写在注释里。
 * 二是**覆盖层真的只占 74%**：剩下那一截是「会话还在视线里」的唯一凭据。比例改错
 * （比如为了多给终端几行改成 0.9）不会有任何编译错误，只会让「瞟一眼」这件事消失。
 */
@RunWith(AndroidJUnit4::class)
class PanelHostTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun immersive_panel_goes_full_screen_everything_else_overlays() {
        assertEquals(PanelHostKind.FullScreen, panelHostKind(PanelChrome.Immersive))
        assertEquals(PanelHostKind.Overlay, panelHostKind(PanelChrome.Standard))
        // 插件刚被禁用、id 已失效：保持 U93 之前的行为（走覆盖层分支），不额外造一条路径。
        assertEquals(PanelHostKind.Overlay, panelHostKind(null))
    }

    @Test
    fun overlay_chrome_takes_three_quarters_and_leaves_the_session_visible() {
        val heightDp = 500
        rule.setKairContent(widthDp = 400, heightDp = heightDp) {
            PanelOverlayChrome(title = "终端", onClose = {}) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            }
        }
        val chrome = rule.onNodeWithTag(PANEL_OVERLAY_TEST_TAG).getUnclippedBoundsInRoot()
        val chromeHeight = (chrome.bottom - chrome.top).value
        val expected = heightDp * PANEL_OVERLAY_HEIGHT_FRACTION
        assertTrue(
            "覆盖层高度 ${chromeHeight}dp 与约定 ${expected}dp 不符",
            abs(chromeHeight - expected) < 1.5f,
        )
        // 顶部对齐：留白必须在下方（会话在下面），不能在标题上方多出一截空白。
        assertTrue("覆盖层应从顶部开始，实际 top=${chrome.top.value}dp", abs(chrome.top.value) < 1f)
        assertTrue(
            "留下的可见高度只有 ${heightDp - chromeHeight}dp，会话等于看不见",
            heightDp - chromeHeight >= 100f,
        )
    }

    @Test
    fun close_button_is_an_explicit_exit() {
        var closed = 0
        rule.setKairContent(widthDp = 400, heightDp = 500) {
            PanelOverlayChrome(title = "终端", onClose = { closed++ }) {
                Box(Modifier.fillMaxSize())
            }
        }
        val label = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.action_close)
        rule.onNodeWithText(label).performClick()
        assertEquals("关闭按钮必须回调 onClose（下滑与返回键之外的可发现出口）", 1, closed)
    }

    @Test
    fun title_survives_long_panel_names() {
        val long = "容器里的 Linux 会话（显示器 + 地球仪，含端口转发与粘贴板桥接）"
        rule.setKairContent(widthDp = 320, heightDp = 500) {
            PanelOverlayChrome(title = long, onClose = {}) {
                Box(Modifier.fillMaxSize())
            }
        }
        // 单行省略：不因为长标题把标题栏撑成两行、把正文顶下去。
        rule.onNodeWithText(long, substring = false).assertExists()
        rule.dumpScreenshot("02-overlay-long-title-320dp")
    }
}
