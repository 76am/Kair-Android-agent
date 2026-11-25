package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.ToolCallData
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `core/agent` 竞态族（U77）：串行任务的取消范围、看门狗覆盖范围、历史写入的轮次归属。
 *
 * 三条缺陷的形状（任务文档 §0）：
 *  1. `job` 是**单指针**，却有 7 处赋值 —— `abort()` 只取消得掉最后被赋上的那一个；
 *  2. 看门狗 `arm` 只盯那一个任务，而 `inFlight` 计的是**所有**串行任务；
 *  3. `replaceHistory` 没有轮次归属，被 force 放弃的那一轮照样整份改写历史。
 *
 * 用例全部用**确定性栅栏**（[CountDownLatch] / 有限时的 `withTimeout`）构造窗口，
 * 不靠 `sleep` 碰运气：`StuckTransport` 把等待放进 [NonCancellable]，取消进不去 ——
 * 那就是「取消打不断前置阶段」在会话眼里的等价物（与 U57 的 `StopUpperBoundTest` 同款）。
 */
class AgentSessionSerialGateTest {

    /** 卡住的传输层：取消打断不了，醒来后才结束。 */
    private class StuckTransport(private val release: CountDownLatch) : ScriptedTransport(emptyList(), id = "u77-stuck") {

        val finished = CountDownLatch(1)

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            requests += request
            try {
                withContext(NonCancellable) { release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS) }
            } finally {
                finished.countDown()
            }
        }
    }

    /** 第一发请求卡住、之后正常作答：用来验证「被放弃的旧轮不放写完，新一轮照常」的接续。 */
    private class HangOnceTransport(private val release: CountDownLatch) : ScriptedTransport(emptyList(), id = "u77-hang-once") {

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            requests += request
            if (requests.size == 1) {
                withContext(NonCancellable) { release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS) }
            } else {
                emit(StreamEvent.TextDelta(SECOND_ANSWER))
                emit(StreamEvent.Completed(StopReason.STOP))
            }
        }
    }

    /** 取消打不断的工具：醒来后照常给出结果（U57 §4 的泄漏点，这里用来造悬空 `tool_call`）。 */
    private class StuckTool(private val release: CountDownLatch) : Tool {
        val started = CountDownLatch(1)

        override val name: String = NAME
        override val description: String = "blocks until released"
        override val parameters: kotlinx.serialization.json.JsonObject = schemaOf(props())

        override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
            started.countDown()
            withContext(NonCancellable) { release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS) }
            return ToolResult("late result")
        }

        companion object {
            const val NAME = "u77_stuck_tool"
        }
    }

    /**
     * 摘要请求卡住的压缩策略：`summarize` 挂在 [NonCancellable] 里，只有 [release] 能放行。
     * 这样「摘要已成功、正要提交历史」这一步可以按需推迟到强制收口之后。
     */
    private class SlowSummaryStrategy(
        private val release: CountDownLatch,
        private val started: CountDownLatch,
    ) : ContextStrategy {
        override val id: String = "u77.slow-summary"

        override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) = true

        override fun plan(
            messages: List<ChatMessage>,
            settings: CompactionSettings,
            trigger: CompactionTrigger,
        ): CompactionPlan? = CompactionPlan(
            summarized = messages.dropLast(1),
            kept = messages.takeLast(1),
            previousSummary = null,
            tokensBefore = Compaction.estimateContextTokens(messages),
        )

        override suspend fun summarize(
            model: String,
            config: ProviderConfig,
            plan: CompactionPlan,
            customInstructions: String?,
            reserveTokens: Int,
            sessionId: String?,
        ): String {
            started.countDown()
            withContext(NonCancellable) { release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS) }
            return "摘要正文"
        }

        override fun wrapSummary(plan: CompactionPlan, summary: String): String = "SUMMARY: $summary"
    }

    // ---- 判据 1 / 2：取消范围与看门狗覆盖范围 ------------------------------------------

    /**
     * 判据 1（先红）：**卡住的是「job 指针已经被覆盖掉」的那一轮时，第二次点停止仍能收口**。
     *
     * 构造：回合 A 卡在传输层不可取消的等待里 → `abort()` 进入 Stopping → 此时允许改历史，
     * `deleteMessage` 排到闸门后并**覆盖 `job`** → 再 `abort()`。旧实现只取消最后被赋上的
     * 那个（已排队的删除），看门狗也只盯它；它早已结束，`force` 成对判据直接返回 ——
     * 会话停在 `Stopping` 等 A，直到 A 自己醒来。
     */
    @Test
    fun `a second stop still collects the session when the stuck turn is not the recorded one`() = runBlocking {
        val release = CountDownLatch(1)
        val transport = StuckTransport(release)
        val h = harness(transport, sessionId = "u77-abort-all", stopHardLimitMs = HARD_LIMIT_MS)
        try {
            h.session.loadHistory(listOf(ChatMessage(role = Role.USER, parts = listOf(Part.Text("旧消息")))))
            val messageId = h.session.messages.value.single().id

            h.session.send("check the deployment")
            await("请求应已发出") { transport.requests.size == 1 }

            h.session.abort()
            assertEquals("点停止后先进入收尾", TurnState.Stopping, h.session.turnState.value)
            // 停止收尾期间允许改历史（既有语义）：这个任务会排到闸门后，并把 job 指针覆盖掉。
            h.session.deleteMessage(messageId)
            h.session.abort()

            awaitIdleWithin("第二次点停止后仍必须能收口（卡住的那一轮不是指针指向的那个）") { h.session.isBusy }
            assertFalse("强制收口后必须能再发送", h.session.isBusy)
        } finally {
            release.countDown()
            transport.finished.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
            h.close()
        }
    }

    /**
     * 判据 2（先红）：**看门狗按「在跑的全部任务」起表，而不只是指针指向的那一个**。
     *
     * 同一条构造下，旧实现的 `force` 会因为「arm 的那个任务已结束」直接返回，连诊断都不留 ——
     * 用户看到的是转圈，事后什么都没记录。这里断言强制收口**留了痕**。
     */
    @Test
    fun `the watchdog covers every in-flight task and leaves a diagnostic`() = runBlocking {
        val release = CountDownLatch(1)
        val transport = StuckTransport(release)
        val h = harness(transport, sessionId = "u77-watchdog-set", stopHardLimitMs = HARD_LIMIT_MS)
        try {
            h.session.loadHistory(listOf(ChatMessage(role = Role.USER, parts = listOf(Part.Text("旧消息")))))
            val messageId = h.session.messages.value.single().id

            h.session.send("check the deployment")
            await("请求应已发出") { transport.requests.size == 1 }
            h.session.abort()
            h.session.deleteMessage(messageId)
            h.session.abort()

            awaitIdleWithin("强制收口应回到空闲") { h.session.isBusy }
            val entry = h.diagnostics.stopTimeouts.value[h.session.id]
            assertNotNull("强制收口必须在诊断里留痕，不能变成新的静默失败", entry)
            assertTrue("诊断应说明停止超时：$entry", entry!!.contains("停止"))
        } finally {
            release.countDown()
            transport.finished.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
            h.close()
        }
    }

    // ---- 判据 3 / 4：历史写入的轮次归属 ------------------------------------------------

    /**
     * 判据 4 的正例（ADR 0003）：**被放弃的轮次仍要跑「配对修复」**。
     *
     * 取消收尾会把残缺的工具配对补上（这里把悬空 `tool_call` 换成中断提示），否则下一发
     * 请求会被端点整轮拒收。这条与「不许整份改写」是两条规则 —— 挡错了地方就会以
     * 「历史干净了但请求发不出去」的形式复现。
     */
    @Test
    fun `the pairing repair still runs for an abandoned turn`() = runBlocking {
        val release = CountDownLatch(1)
        val tool = StuckTool(release)
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call-1", StuckTool.NAME),
                        StreamEvent.ToolCallArgsDelta(0, "{}"),
                        StreamEvent.ToolCallsCompleted(listOf(ToolCallData("call-1", StuckTool.NAME, "{}"))),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
            ),
        )
        val h = harness(
            transport,
            sessionId = "u77-repair",
            tools = ToolRegistry().register(tool),
            stopHardLimitMs = HARD_LIMIT_MS,
        )
        try {
            h.session.send("check the deployment")
            assertTrue("工具应已开始执行", tool.started.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS))
            h.session.abort()
            awaitIdleWithin("强制收口应回到空闲") { h.session.isBusy }

            release.countDown()
            await("取消收尾应跑完（迟到的工具结果被丢弃）") {
                h.session.messages.value.none { it.toolCalls.isNotEmpty() }
            }

            val messages = h.session.messages.value
            assertEquals(
                "被放弃那一轮的悬空 tool_call 必须被配对修复掉，否则下一发请求会被端点拒收",
                messages,
                HistorySanitizer.repairHistory(messages),
            )
        } finally {
            release.countDown()
            h.close()
        }
    }

    /**
     * 判据 3（先红）：**被放弃那一轮的压缩提交不许整份改写历史**。
     *
     * 构造：摘要请求卡在不可取消的等待里 → `abort()` → 到点上界强制收口（用户已经看到
     * 「回到可发送状态」）→ 摘要这时才回来。旧实现照常 `replaceHistory`：历史被换成
     * 「摘要 + 保留段」，用户没同意、也没看见 —— 直接违反 `AGENTS.md` §3 的「只追加 / 前缀稳定」。
     * 同时断言**没有谎报「已压缩」**（否则界面会弹一次什么都没发生的成功）。
     */
    @Test
    fun `a compaction commit from an abandoned turn neither rewrites history nor reports success`() = runBlocking {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val replaceCalls = AtomicInteger(0)
        val strategy = SlowSummaryStrategy(release, started)
        val h = harness(
            ScriptedTransport(emptyList()),
            sessionId = "u77-abandoned-rewrite",
            contextStrategyProvider = { strategy },
            compactionProvider = { CompactionSettings(enabled = true, reserveTokens = 1_000, keepRecentTokens = 1_000) },
            onReplaceHistory = { replaceCalls.incrementAndGet() },
            stopHardLimitMs = HARD_LIMIT_MS,
        )
        try {
            h.session.loadHistory(
                listOf(
                    ChatMessage(role = Role.USER, parts = listOf(Part.Text("第一问"))),
                    ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text("第一答"))),
                    ChatMessage(role = Role.USER, parts = listOf(Part.Text("第二问"))),
                ),
            )
            val before = h.session.messages.value
            assertEquals("前置：加载历史不该触发落盘", 0, replaceCalls.get())

            h.session.compactNow()
            assertTrue("压缩应已开始摘要", started.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS))
            h.session.abort()
            awaitIdleWithin("强制收口应回到空闲") { h.session.isBusy }

            release.countDown()
            delay(SETTLE_MS)

            assertEquals(
                "被放弃那一轮的压缩提交不许落盘（历史必须原样）",
                before,
                h.session.messages.value,
            )
            assertEquals("被放弃那一轮的压缩提交不许调用整份替换", 0, replaceCalls.get())
            assertNull(
                "既然没改写，就不许报「已压缩 X → Y」",
                h.session.lastCompaction.value,
            )
        } finally {
            release.countDown()
            h.close()
        }
    }

    // ---- 判据 5：新轮不受旧轮影响 ------------------------------------------------------

    /**
     * 判据 5（防回归）：被放弃的旧轮还在闸门里时发的新消息**要能接上**，且旧轮的迟到写入
     * 不会被记到新一轮头上。旧实现里 `abandonedTurn` 是会话级布尔量 —— 新一轮拿到闸门时
     * 才清掉，一旦清理时机错位，被丢的就是**新一轮**的合法写入。
     */
    @Test
    fun `a new turn runs and persists normally after an abandoned one`() = runBlocking {
        val release = CountDownLatch(1)
        val transport = HangOnceTransport(release)
        val h = harness(transport, sessionId = "u77-next-turn", stopHardLimitMs = HARD_LIMIT_MS)
        try {
            h.session.send("first")
            await("请求应已发出") { transport.requests.size == 1 }
            h.session.abort()
            awaitIdleWithin("强制收口应回到空闲") { h.session.isBusy }

            // 被放弃的旧轮还占着闸门：这一轮排到它后面，等它放锁。
            h.session.send("second")
            release.countDown()

            await("新一轮应正常跑完并落盘") {
                h.session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains(SECOND_ANSWER) }
            }
            assertEquals("两轮各发一次请求", 2, transport.requests.size)
            assertTrue(
                "新一轮的用户消息也必须在历史里",
                h.session.messages.value.any { it.role == Role.USER && it.text.contains("second") },
            )
        } finally {
            release.countDown()
            h.close()
        }
    }
}

/** 用例里的硬上界取值：生产是 20 秒，用例只等毫秒级。 */
private const val HARD_LIMIT_MS = 300L

/** 等一次强制收口 / 一个落盘结果的余量。 */
private const val SETTLE_MS = 400L

/** 释放闩读等待的上限：真挂死时用例要能失败，而不是无限等。 */
private const val RELEASE_TIMEOUT_S = 10L

/** 第二轮的固定回答：断言「新一轮真的跑到了端点并落盘」。 */
private const val SECOND_ANSWER = "第二轮的回答"

/** 等「强制收口成功」。取 2 秒：远大于 300ms 的上界，又远小于卡住调用的 10 秒。 */
private suspend fun awaitIdleWithin(what: String, blocked: () -> Boolean) {
    try {
        withTimeout(IDLE_TIMEOUT_MS) { while (blocked()) delay(10) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("等待超时：$what", e)
    }
}

private const val IDLE_TIMEOUT_MS = 2_000L
