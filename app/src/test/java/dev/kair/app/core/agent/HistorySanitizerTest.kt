package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 历史修复的**纯函数**契约（`HistorySanitizer`）。
 *
 * 这些用例的存在理由：修复函数直接决定「下一回合上传的字节」，所以它必须
 * ① 该修的修干净（悬空调用会让之后**每一回合**的请求被 provider 拒绝）、
 * ② 不该动的一个字节都不动（本就干净时**返回同一个列表实例**，会话侧据此决定要不要写盘，
 * 缓存前缀因此逐字节不变）、
 * ③ 幂等（同一历史修两次等于修一次 —— 每次请求装配前都会跑一遍）。
 *
 * ②③ 用 `assertSame`（连列表实例都没换）来断言，不用「内容相等」：内容相等抓不住
 * 「白白重写了一遍历史并落盘」这类回归，而那正是搬错方向时最可能出现的形态。
 * 也不用序列化字节比对，理由见 `repairIsIdempotentAtInstanceLevel`。
 */
class HistorySanitizerTest {

    // ---- 夹具 ---------------------------------------------------------------

    private fun user(vararg parts: Part) = ChatMessage(role = Role.USER, parts = parts.toList())
    private fun userText(text: String) = user(Part.Text(text))

    private fun assistantText(text: String) =
        ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text(text)), stopReason = StopReason.STOP)

    /** 正文 + 若干工具调用的一条 assistant 回合；[text] 为 null 时只有调用。 */
    private fun assistant(text: String?, vararg calls: Part.ToolCall) = ChatMessage(
        role = Role.ASSISTANT,
        parts = buildList {
            if (text != null) add(Part.Text(text))
            addAll(calls.toList())
        },
        stopReason = StopReason.STOP,
    )

    private fun call(id: String, arguments: String = "{}", name: String = "read") = Part.ToolCall(id, name, arguments)

    private fun result(toolCallId: String, content: String = "ok") =
        ChatMessage(role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult(toolCallId, "read", content)))

    private fun toolResult(vararg results: Part.ToolResult) =
        ChatMessage(role = Role.TOOL_RESULT, parts = results.toList())

    // ---- repairToolPairing：悬空调用与无主结果 -------------------------------

    @Test
    fun danglingToolCallIsDroppedAndTextSurvives() {
        // 中断落在「正文已出、参数没吐完」的窗口：正文留下，悬空调用删掉。
        val history = listOf(userText("读一下"), assistant("我这就查", call("c1", "{\"path\":\"a\"")))
        val callPart = history[1].parts[1]

        val repaired = HistorySanitizer.repairToolPairing(history)

        assertEquals(listOf<Part>(Part.Text("我这就查")), repaired[1].parts)
        // 传入的列表不许被就地改写：会话侧「干净就不写盘」的判据依赖输入保持原样。
        assertEquals(listOf(Part.Text("我这就查"), callPart), history[1].parts)
    }

    @Test
    fun emptiedAssistantTurnBecomesInterruptedNotice() {
        // 整条回合只有一个悬空调用：掏空后必须留一条 assistant 消息 ——
        // 换成 user 提示会出现连续两条 user 消息，provider 直接拒。
        val repaired = HistorySanitizer.repairToolPairing(listOf(userText("hi"), assistant(null, call("c1"))))

        assertEquals(2, repaired.size)
        assertEquals(Role.ASSISTANT, repaired[1].role)
        assertEquals(listOf<Part>(Part.Text(AgentSession.INTERRUPTED_NOTICE)), repaired[1].parts)
    }

    @Test
    fun orphanToolResultMessageIsDroppedEntirely() {
        // 无主结果（端点不回 id 时占位 id 与结果对不上）：整条删掉，而不是留下空消息。
        // 按部件比对，不按整条消息：`ChatMessage` 的 id / timestamp 每次构造都不同。
        val history = listOf(userText("hi"), result("no-such-call"))

        val repaired = HistorySanitizer.repairToolPairing(history)

        assertEquals(1, repaired.size)
        assertEquals(Role.USER, repaired[0].role)
    }

    @Test
    fun orphanToolResultPartIsDroppedButMessageSurvives() {
        // 同一条消息里一半有主、一半无主：只删无主的那个部件。
        val history = listOf(
            assistant(null, call("c1"), call("c2")),
            toolResult(Part.ToolResult("c1", "read", "ok"), Part.ToolResult("c9", "read", "ok")),
        )

        val repaired = HistorySanitizer.repairToolPairing(history)

        assertEquals(2, repaired.size)
        assertEquals(listOf<Part>(Part.ToolResult("c1", "read", "ok")), repaired[1].parts)
    }

    @Test
    fun pairedHistoryIsReturnedAsTheSameInstance() {
        // 「本就配对时逐字节不变」的硬形态：**返回同一个列表实例**，不产生拷贝。
        val history = listOf(
            userText("hi"),
            assistant("好", call("c1")),
            result("c1"),
            assistantText("完成"),
        )

        assertSame(history, HistorySanitizer.repairToolPairing(history))
    }

    // ---- repairHistory：中断收尾与请求装配前的统一入口 -----------------------

    @Test
    fun interruptedTurnRepairCoversWholeHistoryNotJustLastTurn() {
        // 悬空调用落在**中间**回合（早期中断留下的残骸）：只看最后一条修不干净，
        // 而任何一处悬空都会让之后每一回合的请求被拒 —— 必须一次修完全历史。
        val history = listOf(
            userText("第一步"),
            assistant(null, call("c1")),
            userText("第二步"),
            assistant("好", call("c2", "{\"path\":\"x\"}")),
            result("c2"),
        )

        val repaired = HistorySanitizer.repairHistory(history)

        assertEquals(listOf<Part>(Part.Text(AgentSession.INTERRUPTED_NOTICE)), repaired[1].parts)
        assertEquals(2, repaired[3].parts.size) // 本就配对的那条原样保留
        assertSame(repaired, HistorySanitizer.repairHistory(repaired))
    }

    @Test
    fun repairIsIdempotentAtInstanceLevel() {
        // 每次请求装配前都会跑一遍（见 streamAssistant），所以修过的历史再修一次必须
        // **一个字节都不动**。这里用 `assertSame`（连列表实例都没换）而不是比序列化字节：
        // `ChatMessage.timestamp` 的默认值是 `System.currentTimeMillis()`，kotlinx 生成的
        // 序列化器对「等于默认值的字段」不写入 —— 同一份内容跨一毫秒就会产出不同字节，
        // 那种比法是假红的来源（本用例最初就是这么写的，实测红过一次）。
        val dirty = listOf(
            userText("hi"),
            assistant(null, call("c1", "{\"path\":\"")), // 悬空 + 参数被输出上限截断
            result("no-such-call"),
        )

        val once = HistorySanitizer.repairHistory(dirty)

        assertEquals(2, once.size) // 悬空调用被掏空 → 中断提示；无主结果整条丢弃
        assertSame(once, HistorySanitizer.repairHistory(once))
    }

    @Test
    fun malformedToolArgumentsBecomeEmptyObjectOnlyInRepairedCopy() {
        // 模型吐出非法 JSON 对象（未转义引号 / 被截断）：OpenAI 兼容端点会原样回传
        // `arguments`，解析失败就吞掉整轮请求。只改请求副本，日志与界面保留原文。
        val broken = call("c1", "{\"cmd\":\"echo \"hi\"\"}")
        val history = listOf(userText("hi"), assistant(null, broken), result("c1"))

        val repaired = HistorySanitizer.repairHistory(history)

        assertEquals("{}", (repaired[1].parts[0] as Part.ToolCall).arguments)
        assertEquals(broken, history[1].parts[0]) // 原文留在传入的历史里，写不写回由会话侧决定
    }

    @Test
    fun lenientOnlyToolArgumentsAreRepairedToo() {
        // 真机空响应的那一发：模型吐了未加引号的占位符 `<longcat_arg_value>`。
        // 宽松解析会把它当成字符串接受，端点严格解析会整轮拒收 —— 判据必须是严格 JSON。
        val broken = call("c1", "{\"command\":\"ls\",\"timeout\": <longcat_arg_value>}")
        val history = listOf(userText("hi"), assistant(null, broken), result("c1"))

        val repaired = HistorySanitizer.repairHistory(history)

        assertEquals("{}", (repaired[1].parts[0] as Part.ToolCall).arguments)
        assertEquals("模型原文仍留在传入的历史里", broken, history[1].parts[0])
    }

    @Test
    fun validAndBlankToolArgumentsAreLeftAlone() {
        // 合法判据是「能解析成 JSON 对象」，`{}` 与**空串**都算合法。
        // 判据一旦改变，正常历史会被无谓重写、前缀缓存全废 —— 这条守住边界。
        val history = listOf(
            userText("hi"),
            assistant(null, call("c1", "{}"), call("c2", ""), call("c3", "{\"path\":\"a/b.md\"}")),
            toolResult(
                Part.ToolResult("c1", "read", "ok"),
                Part.ToolResult("c2", "read", "ok"),
                Part.ToolResult("c3", "read", "ok"),
            ),
        )

        assertSame(history, HistorySanitizer.repairHistory(history))
    }

    @Test
    fun jsonArrayToolArgumentsAreRepaired() {
        // 顶层数组是合法 JSON 但**不是** JSON 对象，端点按对象解析会失败。
        val history = listOf(
            userText("hi"),
            assistant(null, call("c1", "[1,2]")),
            result("c1"),
        )

        val repaired = HistorySanitizer.repairHistory(history)

        assertEquals("{}", (repaired[1].parts[0] as Part.ToolCall).arguments)
    }

    // ---- sealInterruptedTail：中断收尾的封口 --------------------------------

    @Test
    fun interruptedTurnEndingWithToolResultIsSealed() {
        // 弱网下点停止：请求卡在「工具结果已回、下一段输出未到」，这一轮没有 assistant
        // 消息可标中止。封口必须落在尾条工具结果上，否则界面把下一条用户消息当引导。
        val history = listOf(userText("跑一下"), assistant(null, call("c1")), result("c1"))

        val sealed = HistorySanitizer.sealInterruptedTail(history)

        assertEquals(StopReason.ABORTED, sealed[2].stopReason)
        assertEquals("封口只加标记，正文不动", history[2].parts, sealed[2].parts)
        assertSame("幂等：封过的历史不再改写", sealed, HistorySanitizer.sealInterruptedTail(sealed))
    }

    @Test
    fun sealTargetsLastAgentMessageWhenSteeringFollows() {
        // 引导消息是 user 角色，可能落在工具结果之后；封口仍要标在那条工具结果上。
        val history = listOf(
            userText("跑一下"),
            assistant(null, call("c1")),
            result("c1"),
            userText("换个方向"),
        )

        val sealed = HistorySanitizer.sealInterruptedTail(history)

        assertEquals(StopReason.ABORTED, sealed[2].stopReason)
    }

    @Test
    fun turnEndingWithAssistantIsNotSealed() {
        // 正常收口（或流式中断已由 assistant 自己标了 ABORTED）：尾条是 assistant，
        // 不需要封口 —— 封口只针对「没有 assistant 可标」的中断形态。
        val history = listOf(userText("hi"), assistantText("好"))

        assertSame(history, HistorySanitizer.sealInterruptedTail(history))
    }

    // ---- sanitizeUserText：控制字符 -----------------------------------------

    @Test
    fun controlCharsAreStrippedFromUserTurnsOnly() {
        // assistant 正文 / 思考可能带厂商签名，改写会让签名失配 —— 只动 user。
        val assistantPart = Part.Text("原文\u0000带签名", "sig")
        val dirty = listOf(
            userText("查日志\u001B[1;30m尾部\u0000\u007F"),
            ChatMessage(role = Role.ASSISTANT, parts = listOf(assistantPart), stopReason = StopReason.STOP),
        )

        val repaired = HistorySanitizer.sanitizeUserText(dirty)

        assertEquals("查日志[1;30m尾部", (repaired[0].parts[0] as Part.Text).text)
        assertSame(assistantPart, repaired[1].parts[0])
    }

    @Test
    fun printableWhitespaceSurvivesUserTextSanitizing() {
        // 换行 / 回车 / 制表是合法文本，不是要剔的控制字符：误删会把用户贴的代码块压成一行。
        val history = listOf(userText("a\nb\t\rc"), assistantText("x\u0000y"))

        assertSame(history, HistorySanitizer.sanitizeUserText(history))
    }

    // ---- 空历史 -------------------------------------------------------------

    @Test
    fun emptyHistoryIsUntouchedForEveryEntry() {
        val empty = emptyList<ChatMessage>()

        assertSame(empty, HistorySanitizer.repairToolPairing(empty))
        assertSame(empty, HistorySanitizer.repairHistory(empty))
        assertSame(empty, HistorySanitizer.sanitizeUserText(empty))
        assertSame(empty, HistorySanitizer.sealInterruptedTail(empty))
    }
}
