package dev.kair.app.core.agent

import dev.kair.app.core.ai.CallContextInterceptor
import dev.kair.app.core.ai.CancellableDns
import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.HttpClient
import dev.kair.app.core.ai.PreConnectTimeoutMs
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.ToolCallData
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 停止必须有硬上界（U57）。
 *
 * 缺陷的形状：取消是协作式的 —— `abort()` 只能取消协程，打不断阻塞在网络前置阶段
 * （DNS 解析 / TCP 连接）的调用。弱网下那个调用十几分钟不返回，[TurnState.Stopping]
 * 又没有兜底，用户看到永久转圈、只能杀 App。
 *
 * 这里的 [StuckTransport] 就是在内核眼里那类调用的等价物：它把等待放在
 * [NonCancellable] 里（取消进不去）。真实 OkHttp 的对应修复在 `core/ai/PreConnect.kt`，
 * 由本类里的 DNS 用例单独钉住。
 *
 * 护栏用例（「被放弃的轮次不许再写历史」）把阻塞点放在**工具执行**而不是传输层：
 * 传输层的路走不通 —— 醒来后再 `emit` 会被 Flow 的取消约束挡住（事件根本到不了
 * `TurnExecutor`），用例在没护栏时也是绿的，属于空转；而工具执行路径的写历史发生在
 * `TurnRunner` 的 `withContext(NonCancellable)` 取消收尾里，那里没有任何取消检查，
 * 真会泄漏（见 [StuckTool]）。
 */
class StopUpperBoundTest {

    /**
     * 卡在网络前置阶段的传输层：取消打断不了，事件一个也不吐。
     *
     * @param release 释放闩：为 0 之前一直「解析不出地址」。
     */
    private class StuckTransport(private val release: CountDownLatch) : ScriptedTransport(emptyList(), id = "stuck-transport") {

        /** 传输层已经醒来并返回（用例据此判断「僵尸轮」结束）。 */
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

    /**
     * 取消打不断的工具（U57 ④ 的泄漏点）：醒来后照常给出结果。
     *
     * 之所以要用它造泄漏：`TurnRunner` 在取消收尾里用 `withContext(NonCancellable)`
     * 把工具结果写进历史 —— 那一笔发生在强制收口**之后**，不挡就会追加到已经回到空闲的
     * 会话里，成为下一轮请求的前缀。
     */
    private class StuckTool(private val release: CountDownLatch) : Tool {
        /** 工具已经开始执行（用例据此确定「取消打在工具里」）。 */
        val started = CountDownLatch(1)

        override val name: String = NAME
        override val description: String = "blocks until released"
        override val parameters: kotlinx.serialization.json.JsonObject = schemaOf(props())

        override suspend fun execute(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext): ToolResult {
            started.countDown()
            withContext(NonCancellable) { release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS) }
            return ToolResult(LATE_RESULT)
        }

        companion object {
            const val NAME = "stuck_tool"

            /** 醒来后才写出来的内容：出现在历史里就是泄漏。 */
            const val LATE_RESULT = "迟到的一轮：这次工具调用已经被用户放弃"
        }
    }

    @Test
    fun `stop returns to idle within the hard limit when the transport ignores cancellation`() = runBlocking {
        val release = CountDownLatch(1)
        val transport = StuckTransport(release)
        val h = harness(transport, sessionId = "u57-hard-limit", stopHardLimitMs = HARD_LIMIT_MS)
        try {
            h.session.send("check the deployment")
            await("请求应已发出") { transport.requests.size == 1 }
            h.session.abort()
            assertEquals("取消打断不了的传输层：点停止后先进入收尾", TurnState.Stopping, h.session.turnState.value)
            try {
                withTimeout(AWAIT_MS) {
                    while (h.session.turnState.value != TurnState.Idle) delay(10)
                }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("停止没有硬上界：卡住的网络调用让会话永久停在 Stopping（U57）", e)
            }
            assertFalse("强制收口后必须能再发送", h.session.isBusy)
        } finally {
            release.countDown()
            transport.finished.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
            h.close()
        }
    }

    @Test
    fun `a forced stop leaves a diagnostic instead of silence`() = runBlocking {
        val release = CountDownLatch(1)
        val transport = StuckTransport(release)
        val h = harness(transport, sessionId = "u57-diagnostic", stopHardLimitMs = HARD_LIMIT_MS)
        try {
            h.session.send("check the deployment")
            await("请求应已发出") { transport.requests.size == 1 }
            h.session.abort()
            awaitWithin("强制收口应回到空闲") { h.session.turnState.value == TurnState.Idle }
            val entry = h.diagnostics.stopTimeouts.value[h.session.id]
            assertNotNull("强制收口必须在诊断里留痕，不能变成新的静默失败", entry)
            assertTrue("诊断应说明停止超时：$entry", entry!!.contains("停止"))
        } finally {
            release.countDown()
            transport.finished.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
            h.close()
        }
    }

    @Test
    fun `a forced stop drops everything the abandoned turn writes afterwards`() = runBlocking {
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
            sessionId = "u57-abandoned",
            tools = ToolRegistry().register(tool),
            stopHardLimitMs = HARD_LIMIT_MS,
        )
        try {
            h.session.send("check the deployment")
            assertTrue("工具应已开始执行", tool.started.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS))
            h.session.abort()
            awaitWithin("强制收口应回到空闲") { h.session.turnState.value == TurnState.Idle }
            val before = h.session.messages.value.size

            release.countDown()
            delay(SETTLE_MS)

            val messages = h.session.messages.value
            assertFalse(
                "被放弃的轮次不得把醒来后的工具结果写进历史（防前缀污染）",
                messages.any { it.text.contains(StuckTool.LATE_RESULT) },
            )
            assertTrue("被放弃的轮次不得新增历史消息（之前 $before 条，现在 ${messages.size} 条）", messages.size <= before)
        } finally {
            release.countDown()
            h.close()
        }
    }

    /**
     * 成对判据（U57 B 审）：迟到的定时器不许再减一次计数。
     *
     * 缺陷的形状：`arm` 记的是「点停止时那一轮」，而计数是**每个串行任务各自**用
     * `invokeOnCompletion` 减的。两者之间没有「它还欠着一次减」这个前提，于是存在一个状态 ——
     * 被 arm 的任务已经自己结束（完成回调已减），定时器才响 —— `force` 会再减一次，
     * `inFlight` 从此偏移，之后每一轮 `decrementAndGet() != 0` 都归不了零：
     * 本批要消灭的「永不收口」从另一个方向复现，而且顺带把「被放弃」的归属置错，
     * 让正常轮次的写入也被丢掉。
     *
     * 口径如实写明：这条**直接构造那个状态**（任务已完成 + 另一个串行任务仍在计数），
     * 不是复现真实线程交错 —— 真实交错还需要 `timer` 在锁外被读到旧引用（同批一起改：
     * `arm` 的 `timer` 换表与「被放弃」标注的读写都进了账目锁）。
     */
    @Test
    fun `a late watchdog does not decrement a task that already finished`() = runBlocking {
        // 这里只要会话的 id 与诊断表：账目本身用注入的计数器测，不牵进回合。
        val h = harness(ScriptedTransport(emptyList()), sessionId = "u57-late-watchdog")
        try {
            val counted = AtomicInteger(2) // 被放弃的那一轮 + 另一个仍在计数的串行任务
            val watchdog = StopWatchdog(
                sessionId = h.session.id,
                limitMs = HARD_LIMIT_MS,
                inFlight = counted,
                diagnostics = h.diagnostics,
                scope = this,
                isStopping = { true },
                isClosed = { false },
            )
            // 那一轮自己结束了：由它的完成回调减计数（这里还剩 1，属于另一个任务）。
            val finished = Job()
            finished.complete()
            var finishCalled = false
            watchdog.onFinished(finished) { finishCalled = true }
            assertEquals("任务自己结束只该减一次", 1, counted.get())
            assertFalse("还剩别人在计数，不该收口", finishCalled)

            // 定时器迟到了 —— 对着同一个已结束的任务再响一次。
            var forceClosed = false
            watchdog.arm(listOf(finished)) { forceClosed = true }
            delay(HARD_LIMIT_MS * 4) // 300ms 的表，等到这里它必然已经响过

            assertFalse("迟到的定时器不该再强收口：计数一偏移，之后每一轮都收不了口", forceClosed)
            assertEquals("计数必须仍属于那个还没结束的任务", 1, counted.get())
            assertFalse(
                "既然没有强制收口，就不该留「停止超时」这条诊断",
                h.diagnostics.stopTimeouts.value.containsKey(h.session.id),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `the hard limit stays at the documented twenty seconds`() {
        // 这条守的不是行为，而是「不许把止损线往后挪」：正常收尾经常超过 20 秒，说明主修
        // （网络前置阶段可取消 + 有上界）没做好，该修那段（任务文档 §2.2）。
        assertEquals(20_000L, StopHardLimitMs)
    }

    @Test
    fun `the shared client routes dns through the cancellable lookup`() {
        // 主修的接线点：撤掉 HttpClient 上的 .dns(CancellableDns())，取消又会退回无限等待。
        // 这条守的是「真的装上了」，行为本身由下面两条用例钉住。
        assertTrue(
            "共享客户端必须装上可取消的 DNS",
            HttpClient.client.dns is CancellableDns,
        )
    }

    @Test
    fun `a blocked dns lookup returns as soon as the call is cancelled`() = runBlocking {
        val release = CountDownLatch(1)
        // 5 秒预算用生产默认值：本用例要证明的是「取消先赢」，不是「等满预算才回来」。
        val dns = CancellableDns(BlockingDns(release))
        val client = OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(CallContextInterceptor())
            .build()
        val call = client.newCall(Request.Builder().url("http://kair-dns.test/v1/chat").build())
        val job = launch(Dispatchers.IO) { runCatching { call.execute() } }
        try {
            delay(SETTLE_MS) // 让解析真的卡在阻断点上
            val startedAt = System.nanoTime()
            call.cancel()
            withTimeout(AWAIT_MS) { job.join() }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(
                "取消后应在百毫秒量级返回（实测 ${elapsedMs}ms），而不是等满 ${PreConnectTimeoutMs}ms 预算",
                elapsedMs < PreConnectTimeoutMs / 2,
            )
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a dns lookup that never answers gives up at the pre-connect budget`() {
        val release = CountDownLatch(1)
        val dns = CancellableDns(BlockingDns(release), budgetMs = BUDGET_MS)
        try {
            val startedAt = System.nanoTime()
            val outcome = runCatching { dns.lookup("kair-dns.test") }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue("解析永不返回时必须在预算内放弃（实测 ${elapsedMs}ms）", outcome.isFailure)
            assertTrue("应是「超时」（可重试），不是「解析失败」", outcome.exceptionOrNull() is SocketTimeoutException)
            assertTrue("放弃时机应落在预算附近：实测 ${elapsedMs}ms", elapsedMs in (BUDGET_MS - 100)..AWAIT_MS)
        } finally {
            release.countDown()
        }
    }
}

private fun loopback(): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))

/** 永不（在用例时限内）返回的解析：直到 [release] 被放开。 */
private class BlockingDns(private val release: CountDownLatch) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        release.await(RELEASE_TIMEOUT_S, TimeUnit.SECONDS)
        return loopback()
    }
}

/** DNS 预算用例的取值：生产是 5 秒，用例只等毫秒级。 */
private const val BUDGET_MS = 300L

/** 用例里的硬上界取值：生产是 20 秒，用例只等毫秒级。 */
private const val HARD_LIMIT_MS = 300L

/** 等强制收口的余量：卡住的调用不会自己回来，只能等看门狗。 */
private const val AWAIT_MS = 5_000L

/** 释放闩读等待的上限：真挂死时用例要能失败，而不是无限等。 */
private const val RELEASE_TIMEOUT_S = 10L

/** 僵尸轮吐完后，让收尾回调落地。 */
private const val SETTLE_MS = 300L

/** 用断言口径等一个条件；超时即用例失败（不是让会话无限转圈）。 */
private suspend fun awaitWithin(what: String, predicate: () -> Boolean) {
    try {
        withTimeout(AWAIT_MS) { while (!predicate()) delay(10) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("等待超时：$what", e)
    }
}
