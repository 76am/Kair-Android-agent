package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.LocalFileBackend
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * A5 第二步的定点用例：`TurnRunner` 用假 host 驱动，**不出现任何 `AgentSession` 实例**。
 *
 * 覆盖的是「只能靠整会话 + 假传输层才能断言」的几条语义：中止 / 截断 / 溢出 / 步数上限 /
 * 退出点的队列种类 / ABORTED 早退。判据都是可数的事实（请求次数、配对结果条数、历史顺序），
 * 不是「看起来对」。
 */
class TurnRunnerTest {

    /** 一次运行的现场：历史、队列、调用计数。 */
    private class Stage {
        val messages = mutableListOf<ChatMessage>()
        val queue = TurnQueue()
        val registry = ToolRegistry()
        val events = mutableListOf<AgentEvent>()
        var streamCalls = 0
        var clearQueueCalls = 0

        /** 每发请求前看到的（历史快照）—— 用来钉住「压缩后第二发里没有孤儿结果」。 */
        var onStream: ((List<ChatMessage>) -> Unit)? = null
    }

    /** 只用来让 `contextStrategy != null`；压缩本体走注入的假 `CompactionControl`。 */
    private class NullStrategy : ContextStrategy {
        override val id: String = "test.null"

        override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) = false

        override fun plan(
            messages: List<ChatMessage>,
            settings: CompactionSettings,
            trigger: CompactionTrigger,
        ): CompactionPlan? = null

        override suspend fun summarize(
            model: String,
            config: ProviderConfig,
            plan: CompactionPlan,
            customInstructions: String?,
            reserveTokens: Int,
            sessionId: String?,
        ): String = ""

        override fun wrapSummary(plan: CompactionPlan, summary: String): String = summary
    }

    private fun assistant(
        vararg parts: Part,
        stopReason: String = StopReason.STOP,
        usage: Usage? = null,
    ) = ChatMessage(role = Role.ASSISTANT, parts = parts.toList(), stopReason = stopReason, usage = usage)

    private fun toolCall(id: String, name: String) = Part.ToolCall(id, name, "{}")

    private fun tool(
        name: String,
        body: suspend () -> ToolResult,
    ): Tool = object : Tool {
        override val name: String = name
        override val description: String = name
        override val parameters: JsonObject = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = body()
    }

    /** 需要读 [ToolContext]（`requestCompact`）的工具。 */
    private fun toolWithContext(
        name: String,
        body: suspend (ToolContext) -> ToolResult,
    ): Tool = object : Tool {
        override val name: String = name
        override val description: String = name
        override val parameters: JsonObject = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = body(ctx)
    }

    /** 假压缩控制面：按注入的 lambda 作答，并记下「模型主动请求」次数（U58）。 */
    private class FakeCompaction(
        val ifNeeded: suspend () -> Boolean = { false },
        val overflow: suspend (ContextStrategy) -> Boolean = { false },
    ) : CompactionControl {
        var requests = 0
        var ifNeededCalls = 0
        override suspend fun compactIfNeeded(): Boolean {
            ifNeededCalls++
            return ifNeeded()
        }

        override suspend fun compactOnOverflow(strategy: ContextStrategy): Boolean = overflow(strategy)
        override fun requestCompaction() { requests++ }
    }

    private fun stage(
        vararg stream: ChatMessage,
        maxSteps: Int = 24,
        contextWindow: Int = 128_000,
        isActive: suspend () -> Boolean = { true },
        isSessionClosed: () -> Boolean = { false },
        contextStrategy: ContextStrategy? = null,
        /** 压缩控制面可能要看历史，所以在 [Stage] 建好之后再注入。 */
        compactionFactory: (Stage) -> CompactionControl = { FakeCompaction() },
    ): Pair<Stage, TurnRunner> {
        val st = Stage()
        val compaction = compactionFactory(st)
        val snapshot = {
            inputsOf(
                assembly = AgentAssembly(AgentSettings(model = "m", systemPrompt = "sp", maxSteps = maxSteps)),
                config = ProviderConfig("test-provider", "no-such-transport", "https://example.test/v1", ""),
                tools = st.registry.all(),
                contextWindow = contextWindow,
            )
        }
        val host = TurnRunner.Host(
            sessionId = "s1",
            workspace = File("."),
            inputs = snapshot,
            messages = { st.messages },
            tools = st.registry,
            hooks = HookBus(),
            turnQueue = st.queue,
            streamAssistant = {
                st.onStream?.invoke(st.messages.toList())
                val next = stream.getOrNull(st.streamCalls)
                    ?: error("stream 调用超出脚本：第 ${st.streamCalls + 1} 次（脚本 ${stream.size} 次）")
                st.streamCalls++
                // 真实装配里 TurnExecutor 会把这一条写进历史；假 host 必须照做，
                // 否则「溢出时摘下再放回」这条路径根本走不到。
                st.messages += next
                RequestOutcome(next, snapshot())
            },
            refreshPrompt = { false },
            contextStrategy = { contextStrategy },
            commandRunner = { LocalCommandRunner() },
            files = { LocalFileBackend(".") },
            // 让 append 真的挂起一次：否则「取消收尾不加 NonCancellable」这类变异抓不住
            // （假实现不挂起，取消与否都能写完）。
            append = { delay(2); st.messages += it },
            toolResultMessage = { callId, name, content, isError ->
                ChatMessage(
                    role = Role.TOOL_RESULT,
                    parts = listOf(Part.ToolResult(callId, name, content, isError)),
                    isError = isError,
                )
            },
            parseArgs = { JsonObject(emptyMap()) },
            removeMessage = { id ->
                val found = st.messages.firstOrNull { it.id == id }
                if (found != null) st.messages.remove(found)
                found
            },
            restoreMessage = { st.messages += it },
            clearQueue = {
                st.clearQueueCalls++
                st.queue.clear()
            },
            onEvent = { st.events += it },
            turnCount = { 1 },
            isActive = isActive,
            isSessionClosed = isSessionClosed,
            sanitizeInterrupted = { },
            // 固定日期，避免跨零点 flaky（U14 同类坑）；日期提示在断言里按 isSystemNote 过滤。
            today = { LocalDate.of(2026, 1, 1) },
            compaction = compaction,
        )
        return st to TurnRunner(host)
    }

    /** 断言用的历史视图：去掉日期提示这类系统注记。 */
    private fun Stage.real(): List<ChatMessage> = messages.filterNot { it.isSystemNote }

    @Test
    fun `步数上限到点就退出，但最后一步的工具结果仍在历史里`() = runBlocking {
        val (st, runner) = stage(
            assistant(toolCall("c1", "t")),
            assistant(toolCall("c2", "t")),
            assistant(toolCall("c3", "t")),
            maxSteps = 2,
        )
        st.registry.register(tool("t") { ToolResult("ok") })

        runner.runUser(null)

        assertEquals("maxSteps=2 只该发 2 次请求", 2, st.streamCalls)
        val results = st.real().filter { it.role == Role.TOOL_RESULT }
        assertEquals("第 2 次的工具结果仍要写进历史", 2, results.size)
        assertEquals("c2", results.last().toolResults.single().toolCallId)
    }

    /** U48 ③：`isActive()` 早退不许静默 —— 关闭报 SESSION_CLOSED，用户取消报 ABORTED。 */
    @Test
    fun `会话已关闭的退出报 SESSION_CLOSED，未关闭的取消报 ABORTED`() = runBlocking {
        val (stClosed, rClosed) = stage(isActive = { false }, isSessionClosed = { true })
        rClosed.runUser(null)
        assertEquals(
            "关闭 / 淘汰的终止原因必须是 sessionClosed",
            listOf(StopReason.SESSION_CLOSED),
            stClosed.events.filterIsInstance<AgentEvent.TurnEnd>().map { it.stopReason },
        )

        val (stAborted, rAborted) = stage(isActive = { false }, isSessionClosed = { false })
        rAborted.runUser(null)
        assertEquals(
            "用户中止的终止原因必须是 aborted",
            listOf(StopReason.ABORTED),
            stAborted.events.filterIsInstance<AgentEvent.TurnEnd>().map { it.stopReason },
        )
    }

    /** U48 B 审：队列竞态出口（空队列）没跑成回合 —— 不许广播幻影 TurnEnd，插件计数会多算。 */
    @Test
    fun `queue-empty exit emits no TurnEnd`() = runBlocking {
        val (st, runner) = stage()
        runner.runUserFromQueue(QueueKind.FOLLOW_UP)
        assertTrue("没跑成回合就不该有回合结束事件", st.events.none { it is AgentEvent.TurnEnd })
    }

    @Test
    fun `执行中被取消：每个调用都有配对结果，已完成的保留真实输出`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val (st, runner) = stage(
            assistant(toolCall("c1", "a"), toolCall("c2", "b")),
            assistant(Part.Text("不会跑到这里")),
        )
        st.registry.register(tool("a") { ToolResult("真实输出") })
        st.registry.register(
            tool("b") {
                secondStarted.complete(Unit)
                // 真的把协程挂住，由测试从外部取消 —— 只有外部取消才会让上下文进入
                // 「已取消」状态，`NonCancellable` 的缺失才会被看见。
                awaitCancellation()
            },
        )

        // 不能用「工具自己抛 CancellationException」模拟：那样当前协程并未被取消，
        // 收尾里的 suspend 照样能跑完，NonCancellable 有没有都一样绿。
        val job = launch { runner.runUser(null) }
        secondStarted.await()
        job.cancel()
        job.join()

        assertTrue("用例前提：runUser 是被取消结束的", job.isCancelled)
        val results = st.real().filter { it.role == Role.TOOL_RESULT }
        assertEquals("两个调用都必须有配对结果", 2, results.size)
        assertEquals("真实输出", results[0].toolResults.single().content)
        assertFalse("第 1 条不是错误", results[0].isError)
        assertEquals("c2", results[1].toolResults.single().toolCallId)
        assertEquals(AgentSession.TOOL_ABORTED, results[1].toolResults.single().content)
        assertTrue("第 2 条标记为错误", results[1].isError)
    }

    @Test
    fun `LENGTH 截断：每个调用补一条截断错误，且不再发请求`() = runBlocking {
        // 脚本里多备几轮：变异（break 改成 continue）会真的再发一次请求，必须能被数出来。
        val (st, runner) = stage(
            assistant(toolCall("c1", "t"), toolCall("c2", "t"), stopReason = StopReason.LENGTH),
            assistant(Part.Text("第三轮")),
            assistant(Part.Text("第四轮")),
        )
        st.registry.register(tool("t") { ToolResult("不该被执行") })

        runner.runUser(null)

        val results = st.real().filter { it.role == Role.TOOL_RESULT }
        assertEquals(2, results.size)
        assertTrue(results.all { it.isError })
        assertTrue(
            "内容是截断提示而不是工具输出",
            results.all { it.toolResults.single().content.startsWith("Tool call arguments were truncated") },
        )
        assertEquals("截断分支必须 break，不能再发第 3 次请求", 1, st.streamCalls)
    }

    @Test
    fun `溢出压缩成功会重新请求，失败则把消息放回原位`() = runBlocking {
        val overflow = Usage(input = 128_001, output = 1)

        // 成功：压缩后重新请求，第二次回合没有工具调用即结束。
        val (ok, okRunner) = stage(
            assistant(Part.Text("溢出这一条"), usage = overflow),
            assistant(Part.Text("压缩后")),
            contextStrategy = NullStrategy(),
            compactionFactory = { FakeCompaction(overflow = { true }) },
        )
        okRunner.runUser(null)
        assertEquals("压缩成功必须重新请求", 2, ok.streamCalls)
        assertEquals("被摘下的溢出消息不应留在历史里", 0, ok.real().count { it.text == "溢出这一条" })

        // 失败：摘下的消息必须放回，历史顺序不变。
        val (bad, badRunner) = stage(
            assistant(Part.Text("溢出这一条"), usage = overflow),
            contextStrategy = NullStrategy(),
            compactionFactory = { FakeCompaction(overflow = { false }) },
        )
        badRunner.runUser(null)
        assertEquals("压缩失败后消息必须回到历史", 1, bad.real().count { it.text == "溢出这一条" })
        assertEquals("溢出这一条", bad.real().first().text)
    }

    @Test
    fun `inner 退出时只接 FOLLOW_UP，STEER 留给下一次请求`() = runBlocking {
        // maxSteps=1：工具执行完就 break，**恰好在取 STEER 之前**退出 —— 否则下一次循环开头
        // 就会把 STEER 取走，「退出点只接 FOLLOW_UP」与「两种都接」在结果上分不开。
        // 判据取历史顺序：跟进消息必须排在插话之前。
        val (st, runner) = stage(
            assistant(toolCall("c1", "t")),
            assistant(Part.Text("第二轮")),
            maxSteps = 1,
        )
        st.registry.register(
            tool("t") {
                st.queue.enqueue(ChatMessage(role = Role.USER, parts = listOf(Part.Text("排队跟进"))), QueueKind.FOLLOW_UP)
                st.queue.enqueue(ChatMessage(role = Role.USER, parts = listOf(Part.Text("插话"))), QueueKind.STEER)
                ToolResult("ok")
            },
        )

        runner.runUser(null)

        val texts = st.real().map { it.text }
        val followIdx = texts.indexOf("排队跟进")
        assertTrue("FOLLOW_UP 必须在本轮退出时接上", followIdx >= 0)
        assertTrue("STEER 必须在跟进之后（它属于下一次请求的前置）", texts.indexOf("插话") > followIdx)
        assertEquals("两轮各一次请求", 2, st.streamCalls)
    }

    @Test
    fun `stream 返回 ABORTED：清队列后立即返回，不写任何工具结果`() = runBlocking {
        val (st, runner) = stage(assistant(toolCall("c1", "t"), stopReason = StopReason.ABORTED))
        st.registry.register(tool("t") { ToolResult("不该被执行") })

        runner.runUser(null)

        assertEquals("ABORTED 必须清队列", 1, st.clearQueueCalls)
        assertEquals("不许执行工具", 0, st.real().count { it.role == Role.TOOL_RESULT })
        assertEquals(1, st.streamCalls)
    }

    @Test
    fun `模型调用 compact_context：结果先落盘，下一步才压缩，第二发没有孤儿 tool_result`() = runBlocking {
        lateinit var compaction: FakeCompaction
        val (st, runner) = stage(
            assistant(toolCall("c1", "compact_context")),
            assistant(Part.Text("压缩后继续")),
            contextStrategy = NullStrategy(),
            compactionFactory = { stage ->
                FakeCompaction(ifNeeded = {
                    // 只在模型已经请求过（工具已执行）时压缩；压缩把旧历史收成检查点，
                    // 保留最近两条原文 —— 即本轮 tool_call 与 tool_result 的配对。
                    if (compaction.requests > 0) {
                        val kept = stage.messages.takeLast(2)
                        stage.messages.clear()
                        stage.messages += ChatMessage(
                            role = Role.USER,
                            isCompaction = true,
                            parts = listOf(Part.Text("SUMMARY")),
                        )
                        stage.messages += kept
                        true
                    } else {
                        false
                    }
                }).also { compaction = it }
            },
        )
        st.registry.register(
            toolWithContext("compact_context") { ctx ->
                ctx.requestCompact()
                ToolResult("Compaction requested.")
            },
        )
        val seen = mutableListOf<List<ChatMessage>>()
        st.onStream = { seen += it }

        runner.runUser(null)

        assertEquals("工具只置位一次", 1, compaction.requests)
        assertEquals("两次请求各判一次（第一次未请求、第二次请求后强制压）", 2, compaction.ifNeededCalls)
        val second = seen[1]
        assertTrue("压缩后首条必须是检查点", second.first().isCompaction)
        val callId = second.flatMap { it.toolCalls }.single().id
        assertEquals(
            "调用与结果必须成对（孤儿 tool_result 会让端点 400）",
            1,
            second.flatMap { it.parts.filterIsInstance<Part.ToolResult>() }
                .count { it.toolCallId == callId },
        )
    }
}
