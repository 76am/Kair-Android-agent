package dev.kair.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层的纯函数（U98 / U100）：参数摘要、参数原文、token 记法。
 *
 * 为什么值得单测：这些函数**每帧都可能被调用**（流式回合每个 token 都重组一次），
 * 所以要能在没有 Compose 的环境里把「认不出来的输入会不会炸」问清楚 ——
 * 输入来自模型，什么形状都可能有（空串、纯文本、嵌套对象、半截 JSON）。
 * 返回空串是允许的（头行就不显示），抛异常不行。
 */
class ToolTraceTest {

    @Test
    fun `the collapsed summary is the tail of the raw argument`() {
        // U108：折叠行现在是 ticker —— 取尾部，新字符从右进、旧字符向左滑出。
        assertEquals(
            """{"cmd":"rg -n \"onSearch|submitSearch\" app/src"}""",
            recentTailSummary("""{"cmd":"rg -n \"onSearch|submitSearch\" app/src"}"""),
        )
        assertEquals(
            """{"path":"SessionListScreen.kt"}""",
            recentTailSummary("""{"path":"SessionListScreen.kt"}"""),
        )
        // 超长时给的是最后一段，而且头部留省略号表明截断。
        val long = """{"cmd":"echo """ + "x".repeat(500) + """-END"}"""
        val summary = recentTailSummary(long)
        assertTrue("摘要应保留最新的尾部：$summary", summary.endsWith("-END\"}"))
        assertTrue("截断要在头部标记：$summary", summary.startsWith("…"))
        assertTrue("摘要不得超过预算：${summary.length}", summary.length <= 160)
    }

    @Test
    fun `unrecognised shapes degrade instead of throwing`() {
        // 空 / 空对象：没有可显示的东西。
        assertEquals("", recentTailSummary(""))
        assertEquals("", recentTailSummary("   "))
        assertEquals("", recentTailSummary("{}"))
        assertEquals("", recentTailSummary("null"))
        // 不是 JSON：原样压成一行。
        assertEquals("ls -la /tmp", recentTailSummary("ls -la\n/tmp"))
        // 半截 JSON：解析失败也不抛，原样给出来。
        assertEquals("{\"cmd\":", recentTailSummary("{\"cmd\":"))
        // 不认识的键：同样原样，不再拼 `键=值`。
        assertEquals("""{"a":1,"b":2,"c":3}""", recentTailSummary("""{"a":1,"b":2,"c":3}"""))
    }

    @Test
    fun `newlines in an argument never break the single-line header`() {
        val summary = recentTailSummary("{\"cmd\":\"echo a\necho b\"}")
        assertFalse("头行必须是一行：$summary", summary.contains('\n'))
    }

    @Test
    fun `a half-written file argument is summarized within a fixed budget`() {
        // U105 真机：写文件时参数是半截 JSON，闭合前会长到几十万字符。整段交给
        // `maxLines = 1` 的 Text，每帧重新排版会把主线程压住 —— 工具卡因此迟迟不渲染。
        val streaming = "{\"path\":\"/workspace/big.md\",\"content\":\"" + "x".repeat(200_000)
        val streamingSummary = recentTailSummary(streaming)
        assertTrue("半截 JSON 的摘要必须有上限：${streamingSummary.length}", streamingSummary.length <= 160)
        assertTrue(streamingSummary.isNotEmpty())
        assertFalse(streamingSummary.contains('\n'))

        // 参数已经完整、但值本身很长（例如一次长命令）时，同样必须有上限。
        val complete = "{\"cmd\":\"" + "rg -n x ".repeat(20_000) + "\"}"
        assertTrue(
            "完整 JSON 的摘要也必须截断",
            recentTailSummary(complete).length <= 160,
        )
    }

    @Test
    fun `a running call reports the arguments it is still writing`() {
        // U105 第四轮：运行中也要有数字，报的是模型正在写的参数（12 个 ASCII 字符 = 3 tok）。
        assertEquals("3 tok", toolUsageText("""{"cmd":"ls"}""", ""))
        // 出结果后把输出加上去 —— 只增不减，写文件的卡不会从几 k 掉回个位数。
        assertEquals("5 tok", toolUsageText("""{"cmd":"ls"}""", "abcdefgh"))
        // 什么都没有：留白，不摆一个假的 `0 tok`。
        assertEquals(null, toolUsageText("", ""))
    }

    @Test
    fun `the expanded input keeps the argument shape`() {
        // 2026-11-19：展开态保持模型下发的参数形状 —— 不按键名挑值、不逐键铺开、不重排。
        assertEquals(
            """{"cmd":"echo a\necho b"}""",
            toolArgumentText("""{"cmd":"echo a\necho b"}"""),
        )
        assertEquals(
            """{"path":"a.kt","oldText":"x","newText":"y"}""",
            toolArgumentText("""{"path":"a.kt","oldText":"x","newText":"y"}"""),
        )
        // 没有可展示的：空。
        assertEquals("", toolArgumentText(""))
        assertEquals("", toolArgumentText("{}"))
        // 不是 JSON：同样原样。
        assertEquals("ls -la /tmp", toolArgumentText("ls -la /tmp"))
        // 首尾空白也不改写。
        assertEquals("  ls -la  ", toolArgumentText("  ls -la  "))
        // 截断不在这一层做（交给 TailText），不含转义时仍是原文。
        val huge = "x".repeat(10_000)
        assertEquals(huge, toolArgumentText(huge))
    }

    @Test
    fun `escaped unicode in an argument is shown as text`() {
        // 模型把中文写成 JSON 的 \uXXXX 转义（LongCat 等端点如此）：展开态应显示字符本身，
        // 而不是一串 `\u4e0a\u62a4\u6728`——SSE 外层只剥一层，参数自身的转义要在显示层还原。
        val escaped = """{"path":"/tmp/a.md","content":"\u4e0a\u62a4\u6728"}"""
        val shown = toolArgumentText(escaped)
        assertTrue("转义应还原成中文：$shown", shown.contains("上护木"))
        assertFalse("不应残留 \\u 转义：$shown", shown.contains("\\u4e0a"))

        // 流式期间参数还没闭合：尾部窗口内的转义同样要还原。
        val live = toolArgumentText("""{"content":"\u4e0a\u62a4\u6728""")
        assertTrue("流式尾部也应还原：$live", live.contains("上护木"))

        // `\\u4e0a` 是字面量反斜杠 + u，不是转义，不能误还原（正则与 JSON 两条路径都要守）。
        val literal = toolArgumentText("""{"re":"\\u4e0a"}""")
        assertTrue("字面量不能被误还原：$literal", literal.contains("""\\u4e0a"""))
        val literalLive = toolArgumentText("""{"re":"\\u4e0a""")
        assertTrue("流式里的字面量也不能误还原：$literalLive", literalLive.contains("""\\u4e0a"""))
    }

    @Test
    fun `the expanded window keeps only the newest characters`() {
        // U108：展开区只保留最近 5k 字，头部截断并补省略号 —— 流式时用户盯的是尾部。
        val raw = "HEAD-" + "x".repeat(6_000) + "-TAIL"
        val shown = tailText(raw)
        assertTrue("截断标记应在头部：$shown", shown.startsWith("…"))
        assertTrue("最新内容必须在：$shown", shown.endsWith("-TAIL"))
        assertEquals(TextTailWindow + 1, shown.length)

        // 没超过窗口：完全不动。
        val small = """{"cmd":"ls"}"""
        assertEquals(small, tailText(small))
        // 边界：恰好等于窗口也不截断。
        val exact = "y".repeat(TextTailWindow)
        assertEquals(exact, tailText(exact))
    }

    @Test
    fun `token counts use the k notation above one thousand`() {
        assertEquals("42", formatTokenCount(42))
        assertEquals("999", formatTokenCount(999))
        assertEquals("1k", formatTokenCount(1_000))
        assertEquals("2.1k", formatTokenCount(2_100))
        assertEquals("2.1k", formatTokenCount(2_140))
        assertEquals("150k", formatTokenCount(150_000))
    }

    /**
     * 正常收尾不再占一行（U98）：`stop` / `toolUse` 是绝大多数回合的结束方式，
     * 标出来等于每回合盖一个「已结束」的章；而 U48 那四条静默出口必须还在。
     */
    @Test
    fun `only the unusual stop reasons are worth a line`() {
        assertFalse(isNoteworthyStop("stop"))
        assertFalse(isNoteworthyStop("toolUse"))
        listOf("length", "error", "aborted", "stepLimit", "emptyResponse", "sessionClosed").forEach {
            assertTrue("$it 属于 U48 要可见的出口", isNoteworthyStop(it))
        }
    }
}
