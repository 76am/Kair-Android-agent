package dev.kair.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.ui.screens.chat.MessageCard
import dev.kair.app.ui.screens.chat.TRACE_RUNNING_TAG
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 「哪一次调用在跑」的**可视性**用例（U99 补）。
 *
 * 起因是负责人的追问：「工具正在运行时，还有转圈提示吗？」—— 改之前只有注行两个字
 * 「运行中」，一个回合平铺五六次调用时，那句灰字挑不出是哪一块在跑。修法是在
 * [dev.kair.app.ui.screens.chat.TraceBlock] 头行右侧挂一个 12dp 转圈。
 *
 * 为什么必须用真机用例而不是快照：转圈是**只在某一时刻存在**的节点，截图只能证明「此刻有」，
 * 证明不了「结果回来后它会消失」。两条用例合起来才是完整判据：
 * 没有结果 → 有转圈；结果回来了 → 转圈消失、注行换回行数。
 *
 * 定位用 `TRACE_RUNNING_TAG` 而不是 `hasProgressBarRangeInfo(Indeterminate)`：
 * 后者在 MIX 2S 上实测找不到节点（见 `TRACE_RUNNING_TAG` 的 KDoc）。
 *
 * 用例名不用反引号（`ToolBlockExpandTest` 同款）：带空格的 SimpleName 在 DEX < 040 上编不出去。
 */
class ToolRunningTest {

    @get:Rule
    val rule = createComposeRule()

    private fun message() = ChatMessage(
        id = "a1",
        role = Role.ASSISTANT,
        parts = listOf(
            Part.ToolCall(id = "c1", name = "bash", arguments = """{"command":"make"}"""),
        ),
        timestamp = System.currentTimeMillis(),
    )

    private fun show(matched: List<Part.ToolResult?>, running: Boolean) {
        rule.setKairContent(dark = true) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                MessageCard(
                    msg = message(),
                    editable = false,
                    retryable = true,
                    deletable = true,
                    matched = matched,
                    running = running,
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

    /** 结果未回：板上要有一个转圈，注行同时写着「运行中」（文字给不吃动效的人）。 */
    @Test
    fun a_call_without_result_spins_in_its_own_block() {
        show(matched = listOf(null), running = true)
        rule.onNodeWithText("运行中").assertIsDisplayed()
        rule.onNodeWithTag(TRACE_RUNNING_TAG, useUnmergedTree = true).assertExists()
        // 只断言「节点在」证明不了它落在对的格子里、也没把参数摘要挤掉 —— 留一张图给人看。
        val path = rule.dumpScreenshot("13-tool-running-dark")
        assertTrue("截图没落盘：$path", path.endsWith("13-tool-running-dark.png"))
    }

    /**
     * 结果回了：转圈必须消失。留着不动的转圈比没有更糟 —— 用户会以为它还在跑，
     * 于是等一个已经结束的东西。
     */
    @Test
    fun a_call_with_result_stops_spinning() {
        show(
            matched = listOf(
                Part.ToolResult(toolCallId = "c1", toolName = "bash", content = "ok\n"),
            ),
            running = true,
        )
        rule.onNodeWithText("运行中").assertDoesNotExist()
        rule.onNodeWithTag(TRACE_RUNNING_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
