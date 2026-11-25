package dev.kair.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.ui.screens.chat.MessageCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 工具块「就地展开」（U98）的**交互**用例。
 *
 * 为什么不能只有截图：长输出的收起 / 展开是这一批唯一新增的**行为**，而截图只能证明
 * 「收起态长得对」。真机上最常见的失败长这样 —— 注行写着「展开」，点下去什么都不动
 * （点击区只挂在头行上，而人点的是那行字）。所以这里按用户的动作走一遍：
 * 点注行 → 字变成「收起」，再点一次 → 回到「展开」。
 *
 * 用例名不用反引号（`TranscriptVisualTest` 同款）：带空格的 SimpleName 在 DEX < 040 上编不出去。
 */
class ToolBlockExpandTest {

    @get:Rule
    val rule = createComposeRule()

    private val longOutput = (1..12).joinToString("\n") { "第 $it 行输出" }

    /** 只有展开态才看得见的那一行（收起态只画前三行）。 */
    private val LAST_LINE = "第 12 行输出"

    private fun message(stopReason: String = StopReason.STOP) = ChatMessage(
        id = "a1",
        role = Role.ASSISTANT,
        parts = listOf(
            Part.ToolCall(id = "c1", name = "read", arguments = """{"path":"a.kt"}"""),
        ),
        stopReason = stopReason,
        timestamp = System.currentTimeMillis(),
    )

    private fun show(msg: ChatMessage) {
        rule.setKairContent(dark = true) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                MessageCard(
                    msg = msg,
                    editable = false,
                    retryable = true,
                    deletable = true,
                    matched = listOf(
                        Part.ToolResult(toolCallId = "c1", toolName = "read", content = longOutput),
                    ),
                    running = false,
                    groupedCallIds = setOf("c1"),
                    onEdit = {},
                    onRetry = {},
                    onDelete = {},
                    onCopy = {},
                    onSelectText = {},
                )
            }
        }
    }

    @Test
    fun tapping_the_note_line_toggles_the_long_output() {
        show(message())
        // U100 起工具块默认全折叠：收起态一行输出都不画，注行只写「展开」
        // （行数已让位给头行右侧的 token 数）。
        rule.onNodeWithText("展开").assertIsDisplayed()
        rule.onNodeWithText(LAST_LINE, substring = true).assertDoesNotExist()

        rule.onNodeWithText("展开").performClick()
        // 展开之后第 12 行才出现 —— 这比「节点还在不在」更接近用户看到的差别。
        rule.onNodeWithText(LAST_LINE, substring = true).assertExists()
        // 注行文字要整串匹配：`onNodeWithText` 默认是**整串**匹配，写「起」匹配不到「收起」。
        rule.onNodeWithText("收起").performClick()
        rule.onNodeWithText(LAST_LINE, substring = true).assertDoesNotExist()
        rule.onNodeWithText("展开").assertIsDisplayed()
    }

    /**
     * 正常收尾不在底部行盖章（U98）：`stop` 是绝大多数回合的结束方式，标出来等于每回合
     * 都印一行「已结束」。U48 要可见的是那四条静默出口，不是这一条。
     */
    @Test
    fun a_normal_stop_reason_takes_no_line_of_its_own() {
        show(message())
        rule.onNodeWithText("已结束").assertDoesNotExist()
    }

    /**
     * 运行中的调用也必须能展开（U105 返工第二轮）：主线在参数流式写入时就能展开，
     * 看着模型一个字一个字把命令写完；上一版只在结果回来后才给展开，运行中整块点不动。
     *
     * 用「换行后的完整命令」区分两种状态：收起态的摘要把换行压成空格（`line one line two`），
     * 展开态的 IN 保留换行（`line one\nline two`）。
     */
    @Test
    fun a_running_call_can_be_expanded_to_watch_its_arguments() {
        val msg = ChatMessage(
            id = "a2",
            role = Role.ASSISTANT,
            parts = listOf(
                Part.ToolCall(id = "c2", name = "container_shell", arguments = """{"cmd":"line one\nline two"}"""),
            ),
            timestamp = System.currentTimeMillis(),
        )
        rule.setKairContent(dark = true) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                MessageCard(
                    msg = msg,
                    editable = false,
                    retryable = false,
                    deletable = false,
                    matched = emptyList(),
                    running = true,
                    groupedCallIds = setOf("c2"),
                    onEdit = {},
                    onRetry = {},
                    onDelete = {},
                    onCopy = {},
                    onSelectText = {},
                )
            }
        }
        rule.onNodeWithText("运行中 · 展开").assertIsDisplayed().performClick()
        rule.onNodeWithText("line one\nline two", substring = true).assertExists()
        rule.onNodeWithText("运行中 · 收起").assertIsDisplayed()
    }

    /** 但异常收尾必须还在（U48 的四条出口不许被这一批顺手吞掉）。 */
    @Test
    fun an_unusual_stop_reason_still_shows() {
        show(message(StopReason.STEP_LIMIT))
        rule.onNodeWithText("已达步数上限").assertIsDisplayed()
    }

    /** U105：视觉仍是 24dp 的「⋯」，命中盒必须 ≥48dp。 */
    @Test
    fun more_actions_entry_exposes_a_48dp_hit_target() {
        show(message())
        val bounds = rule.onNodeWithContentDescription("更多操作").getUnclippedBoundsInRoot()
        val height = bounds.bottom.value - bounds.top.value
        assertTrue("「⋯」命中区高 ${height}dp，低于 48dp", height >= 48f)
    }
}
