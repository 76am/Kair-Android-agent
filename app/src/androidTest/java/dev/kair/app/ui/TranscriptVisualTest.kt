package dev.kair.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
 * 转录区的**像素**用例（U97）。
 *
 * 图床理由：这一轮把消息从「气泡 + 卡片」换成了「左侧轨道 + 焊点 + 正文」，
 * 而轨道是 `drawBehind` 画出来的 —— 它不产生任何语义节点，`onNodeWithText` 之类
 * 的断言**永远看不见它**。也就是说：线画没画、画到没有、有没有在两条消息之间断掉，
 * 只能靠一张图。
 *
 * 三个场景对着设计稿的三条要求：
 * 1. 深色：朱红焊点 + 竖线连续（跨用户 / agent 两条消息不断）；
 * 2. 浅色：同一套结构在「硫酸纸」上仍然分得清（不是深色反转出来的灰）；
 * 3. 320dp + 1.3× 字号：轨道不能被挤没，正文不许溢出屏幕。
 *
 * **用例名不用反引号**（`DockVisualTest` 同款）：带空格的 Kotlin 反引号名会变成
 * `…$1` 这样的**内部类名**，DEX 版本 < 040（Android 8 的 dex 规范）不许 SimpleName 带空格，
 * `dexBuilderDebugAndroidTest` 直接失败 —— 单测里能跑，instrumented 里编不出去。
 */
class TranscriptVisualTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * 时间戳取「八分钟前」而不是写死一个常数：`formatWhoTime` 的三档是「今天 / 今年 / 跨年」，
     * 写死的常数在设备时钟走到下一年时会自动掉进跨年档，标签行变成
     * `你 · 2025年11月18日 01:20` —— 图看着像回归，其实只是时钟过了。
     * 用相对时间，这张图永远展示设计稿里那一档（`你 · HH:mm`）。
     */
    private val now = System.currentTimeMillis()
    private val userTurn = ChatMessage(
        id = "u1",
        role = Role.USER,
        parts = listOf(
            Part.Text("会话列表的搜索改成输入即过滤，别再按回车。空结果别只说「无结果」，告诉我是不是全在归档里。"),
        ),
        timestamp = now - 9 * 60_000L,
    )

    private val agentTurn = ChatMessage(
        id = "a1",
        role = Role.ASSISTANT,
        parts = listOf(
            Part.Text("先找到现在的搜索是在输入框失焦时才触发的那处。"),
            Part.ToolCall(
                id = "c1",
                name = "container_shell",
                arguments = """{"cmd":"rg -n \"onSearch|submitSearch\" app/src"}""",
            ),
            Part.ToolCall(
                id = "c2",
                name = "read",
                arguments = """{"path":"SessionListScreen.kt"}""",
            ),
            Part.Text("改完了。空结果现在分两种情况：没有匹配，或者匹配到的会话在归档里 —— 后者给一个「去归档看」的按钮。"),
        ),
        // 正常收尾（`stop`）：底部行**不该**出现「已结束」，也不许出现协议原文（U98）。
        stopReason = StopReason.STOP,
        timestamp = now - 60_000L,
    )

    @Test
    fun dark_transcript_keeps_the_rail_continuous() {
        rule.setKairContent(dark = true, widthDp = 360, heightDp = 720) { Transcript() }
        val path = rule.dumpScreenshot("10-transcript-dark")
        assertTrue("截图没落盘：$path", path.endsWith("10-transcript-dark.png"))
    }

    @Test
    fun light_transcript_reads_on_paper() {
        rule.setKairContent(dark = false, widthDp = 360, heightDp = 720) { Transcript() }
        rule.dumpScreenshot("11-transcript-light")
    }

    @Test
    fun narrow_viewport_with_large_font_keeps_the_rail() {
        rule.setKairContent(dark = true, widthDp = 320, heightDp = 640, fontScale = 1.3f) { Transcript() }
        rule.dumpScreenshot("12-transcript-narrow-130")
    }

    /** 与 `ChatScreen` 的列表一致：内边距 10/12，行距由每条消息自带（轨道才不断线）。 */
    @Composable
    private fun Transcript() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 12.dp, top = 10.dp),
        ) {
            MessageCard(
                msg = userTurn,
                editable = true,
                retryable = false,
                deletable = true,
                matched = emptyList(),
                running = false,
                groupedCallIds = emptySet(),
                onEdit = {},
                onRetry = {},
                onDelete = {},
                onCopy = {},
                onSelectText = {},
            )
            MessageCard(
                msg = agentTurn,
                editable = false,
                retryable = true,
                deletable = true,
                matched = listOf(
                    Part.ToolResult(
                        toolCallId = "c1",
                        toolName = "container_shell",
                        content = "SessionListScreen.kt:88: submitSearch(query)\n" +
                            "SessionListScreen.kt:141: onSearch = ::submitSearch",
                    ),
                    // 长输出：给「前三行 + 12 行 · 展开」那条注一个上镜机会。
                    Part.ToolResult(
                        toolCallId = "c2",
                        toolName = "read",
                        content = (1..12).joinToString("\n") { "    $it  val filtered = sessions.filter { s -> s.title.contains(q) }" },
                    ),
                ),
                running = false,
                groupedCallIds = setOf("c1", "c2"),
                onEdit = {},
                onRetry = {},
                onDelete = {},
                onCopy = {},
                onSelectText = {},
            )
        }
    }
}
