package dev.kair.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kair.app.core.agent.TurnState
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.plugins.DOCK_GAUGE_TAG
import dev.kair.app.ui.plugins.DockChip
import dev.kair.app.ui.plugins.DockControlSize
import dev.kair.app.ui.plugins.DockGauge
import dev.kair.app.ui.plugins.DockGaugeSize
import dev.kair.app.ui.screens.chat.ComposerInputBar
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 输入板的**目视**用例（U99 ③：模型名 / 圆环与输入框融成一块）。
 *
 * 为什么值得单独一个类：负责人这一条的原话是「那片区域太大了」，而「大不大」截图能回答、
 * 断言只能回答一部分。所以这里两条腿走 —— 断言守住「控件行确实是 30dp 那一档」，
 * 剩下的（圆环里两位数读不读得出、窄屏 1.3× 会不会挤成一团）交给落盘的三张图。
 *
 * **夹具而不是 `ComposerControlRow` 本体**：后者要 `AppContainer`（模型表 / 插件槽 / 会话
 * 存储都在里面），instrumented 里造不出来。这一行真正决定「挤不挤」的是 chip 尺寸、字号与
 * `weight + horizontalScroll` 的排布，这三样在夹具里与生产代码逐字一致，只有数据来源不同。
 */
@RunWith(AndroidJUnit4::class)
class ComposerVisualTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(widthDp: Int, dark: Boolean, fontScale: Float = 1f) {
        rule.setKairContent(widthDp = widthDp, heightDp = 220, dark = dark, fontScale = fontScale) {
            Board()
        }
    }

    @Composable
    private fun Board() {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            // 把输入板推到底部：真机上它贴着屏幕下沿，截图要看的是「底部长什么样」。
            Spacer(Modifier.weight(1f))
            ComposerInputBar(
                input = "",
                onInputChange = {},
                pendingImage = null,
                onPickImage = {},
                onClearImage = {},
                turnState = TurnState.Idle,
                awaitingUser = false,
                focusRequester = remember { FocusRequester() },
                onStop = {},
                onSubmit = {},
                onBottomRequest = {},
                footer = { ControlRow() },
            )
        }
    }

    /** 与 `ComposerControlRow` 同构：左侧控件簇可横滑、右侧圆环钉死；chip 走 30dp，环走 34dp。 */
    @Composable
    private fun ControlRow() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DockChip(value = "Sonnet 4.6", description = "模型 Sonnet 4.6", size = DockControlSize) {}
                DockChip(
                    value = "自动",
                    icon = PluginGlyph.Spark,
                    description = "思考强度 自动",
                    size = DockControlSize,
                ) {}
                DockChip(value = "网页搜索", description = "插件 网页搜索", size = DockControlSize) {}
            }
            DockGauge(
                fraction = 1f,
                centerText = "100%",
                size = DockGaugeSize,
                contentDescription = "上下文 100%",
            ) {}
        }
    }

    @Test
    fun composer_board_dark() {
        show(widthDp = 360, dark = true)
        rule.dumpScreenshot("14-composer-dark")
    }

    @Test
    fun composer_board_light() {
        show(widthDp = 360, dark = false)
        rule.dumpScreenshot("15-composer-light")
    }

    /**
     * U105：chip 的视觉仍是 30dp，但 chip 与圆环的可点区域必须 ≥48dp。
     * 量合并语义后的外层节点；圆环的 43dp 视觉由 `DOCK_GAUGE_TAG` 单独守（见下一条用例）。
     */
    @Test
    fun composer_controls_expose_48dp_hit_targets() {
        show(widthDp = 360, dark = false)
        val chip = rule.onNodeWithText("Sonnet 4.6").getUnclippedBoundsInRoot()
        val chipHeight = chip.bottom.value - chip.top.value
        assertTrue("模型 chip 命中区高 ${chipHeight}dp，低于 48dp", chipHeight >= 48f)

        val gauge = rule.onNodeWithContentDescription("上下文 100%").getUnclippedBoundsInRoot()
        val gaugeHeight = gauge.bottom.value - gauge.top.value
        assertTrue("上下文圆环命中区高 ${gaugeHeight}dp，低于 48dp", gaugeHeight >= 48f)
    }

    /**
     * 窄屏 + 1.3× 字号：这是控件行最容易挤爆的组合。
     *
     * 断言守住两件事：chip 高度必须停在 30dp（涨了就是标题又回来了）；
     * 圆环 43dp **中心仍要有百分比**（U115 返工：环放大到贴紧可见圆底，34dp 那一档
     * 看起来是「小环浮在大钮上」），精确值同时通过 `contentDescription` 给读屏。
     */
    @Test
    fun narrow_board_keeps_a_43dp_ring_with_its_percentage() {
        show(widthDp = 320, dark = true, fontScale = 1.3f)
        val chip = rule.onNodeWithText("Sonnet 4.6", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        // 不用 `DpRect.height`：那是 `androidx.compose.ui.unit` 的扩展属性，这里不值得为一行加 import。
        val chipHeight = chip.bottom.value - chip.top.value
        assertTrue(
            "控件行 chip 高 ${chipHeight}dp，超过 30dp 一档 —— 说明「模型 / 思考」小标题又回来了",
            chipHeight <= 30.5f,
        )
        val ring = rule.onNodeWithTag(DOCK_GAUGE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val ringWidth = ring.right.value - ring.left.value
        assertTrue(
            "圆环外径 ${ringWidth}dp，不再是 43dp 一档 —— 环要贴着可见圆底的边",
            ringWidth in 42f..44f,
        )
        rule.onNodeWithContentDescription("上下文 100%").assertExists()
        rule.onNodeWithText("100%", useUnmergedTree = true).assertExists()
        rule.dumpScreenshot("16-composer-narrow-130")
    }
}
