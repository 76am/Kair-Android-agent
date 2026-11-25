package dev.kair.app.core.agent

import dev.kair.app.core.ai.OverflowDetector
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试底座**自身**的回归用例（`docs/tasks/archive/P0-1-主循环测试底座.md`「验收底座本身」）。
 *
 * 与其它两个文件的分工：`AgentSessionLoopTest` / `AgentSessionInterruptTest` 断言内核行为，
 * 这里断言底座的五种能力各自真的可用 —— 按请求序号脚本化、半途 Failure、半途挂住、
 * 超窗用量注入、请求字节记录，外加收尾不留全局状态。
 *
 * 为什么这一层不能省：底座坏了，主循环用例不会报错，只会「看着对、其实没测到」——
 * 正是本批要消灭的那类静默失败。
 */
class AgentSessionHarnessTest {

    @Test
    fun scriptedTurnsAreTakenByRequestIndex() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("t1"), ScriptedTurn.text("t2"), ScriptedTurn.text("t3")),
            id = "harness-index",
        )
        val h = harness(transport, sessionId = "harness-index")
        try {
            h.runTurn("one")
            h.runTurn("two")
            h.runTurn("three")

            assertEquals(
                "第 N 次请求必须吐脚本第 N 项，而不是复用最后一项",
                listOf("t1", "t2", "t3"),
                h.session.messages.value.filter { it.role == Role.ASSISTANT }.map { it.text },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun requestWithoutScriptFailsLoudlyInsteadOfReusingLastTurn() = runBlocking {
        // 越界报错是刻意的：静默复用最后一项会让多回合用例在脚本耗尽后继续「成功」。
        // 抛出会被内核当成可重试的传输失败（TransientRetry.kt:136 把异常包成
        // retryable=true），因此这里只断言收尾形态，不断言请求次数。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("t1"), ScriptedTurn.text("t2")),
            id = "harness-script-exhausted",
        )
        val h = harness(transport, sessionId = "harness-script-exhausted")
        try {
            h.runTurn("one")
            h.runTurn("two")
            h.runTurn("three")

            val last = h.session.messages.value.last { it.role == Role.ASSISTANT }
            assertEquals("没有脚本的请求不得当成成功", StopReason.ERROR, last.stopReason)
            assertTrue(
                "报错要指明白是脚本耗尽，而不是含糊的连接问题",
                last.text.contains("脚本只有 2 个回合"),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun nonRetryableFailureDoesNotConsumeTheNextScriptedTurn() = runBlocking {
        // `ScriptedTurn.failure` 固定 retryable=false 的理由就在这儿：可重试的失败会被内核
        // 自动重发，重发多打一次请求、把下一项脚本吃掉，ERROR 分支反而测不到。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.failure("connection reset"), ScriptedTurn.text("ok")),
            id = "harness-failure",
        )
        val h = harness(transport, sessionId = "harness-failure")
        try {
            h.runTurn("first")
            assertEquals("不可重试的失败只应打一次请求", 1, h.requests.size)

            h.runTurn("second")
            assertEquals("失败不得吃掉第二项脚本", 2, h.requests.size)
            assertEquals("ok", h.session.messages.value.last { it.role == Role.ASSISTANT }.text)
        } finally {
            h.close()
        }
    }

    @Test
    fun hangingTurnStaysBusyUntilAbortThenNextScriptDelivers() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.hanging(StreamEvent.TextDelta("half")), ScriptedTurn.text("ok")),
            id = "harness-hang",
        )
        val h = harness(transport, sessionId = "harness-hang")
        val session = h.session
        try {
            session.send("first")
            await("挂住的回合应已把半截正文送到界面") {
                session.messages.value.any { it.role == Role.ASSISTANT && it.text.contains("half") }
            }
            assertTrue("挂住期间会话必须一直表现为忙", session.isBusy)
            assertEquals(SessionStatus.RUNNING, session.status.value)

            // runTurn 的忙闸门：这时另起一轮必须直接抛，不能悄悄与挂住的回合并发改历史。
            val overlapped = runCatching { h.runTurn("overlap") }
            assertTrue(
                "忙时另起回合应被闸门挡住",
                overlapped.exceptionOrNull() is IllegalArgumentException,
            )

            session.abort()
            h.awaitIdle()
            assertEquals("取消收尾跑完后应回到空闲", SessionStatus.IDLE, session.status.value)

            h.runTurn("second")
            assertEquals("挂住的那次请求已占走脚本第 1 项", 2, h.requests.size)
            assertEquals("ok", session.messages.value.last { it.role == Role.ASSISTANT }.text)
        } finally {
            h.close()
        }
    }

    @Test
    fun runTurnRecordsRequestBytesAndPersistsBothEndsOfTheTurn() = runBlocking {
        val persisted = mutableListOf<ChatMessage>()
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("ok")),
            id = "harness-observe",
        )
        val h = harness(transport, sessionId = "harness-observe", onPersist = { persisted += it })
        try {
            h.runTurn("hi")

            val sent = h.requests.single()
            assertEquals("模型名来自注入的 assemblyProvider", "test-model", sent.model)
            assertEquals("请求要带会话 id（prompt_cache_key）", "harness-observe", sent.sessionId)
            assertTrue("默认空工具表不得凭空带上工具", sent.tools.isEmpty())
            // harness 固定时钟：用户消息之后跟着当天日期 system note（F2 的追加通道）。
            // 请求里注入提示带 wire 标记；落盘仍是原文（英文 wire 文案）。
            assertEquals(
                "本回合请求 = 那条用户消息 + 当天日期提示（带 <system-note> 标记）",
                listOf("hi", "<system-note>\n2026-10-29 (Thu)\n</system-note>"),
                sent.messages.map { it.text },
            )

            assertEquals(
                "落盘顺序应在用户回合与模型回合之间追加日期提示",
                listOf(Role.USER to "hi", Role.USER to "2026-10-29 (Thu)", Role.ASSISTANT to "ok"),
                persisted.map { it.role to it.text },
            )
            assertEquals("落盘的模型回合要带收尾原因", StopReason.STOP, persisted.last().stopReason)
        } finally {
            h.close()
        }
    }

    @Test
    fun injectedOverflowUsageReachesTheMessageAndTheDetector() = runBlocking {
        // 超窗用量注入的意义是「让集成用例能驱动溢出分支」，所以先自证注入的形状真的
        // 落到消息的 usage 上、且判定口径与内核同源（OverflowDetector）。
        // 注意：`ScriptedTurn.silentOverflow` 不吐正文，空回合会被内核丢弃（AgentSession
        // 的 hasContent 分支），所以这里补一段正文让回合能落盘、可被观察。
        val contextWindow = 10
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.TextDelta("tail"),
                        StreamEvent.UsageEvent(Usage(input = contextWindow + 1, output = 1)),
                        StreamEvent.Completed(StopReason.STOP),
                    ),
                ),
            ),
            id = "harness-usage",
        )
        val h = harness(transport, sessionId = "harness-usage")
        try {
            h.runTurn("hi")

            val assistant = h.session.messages.value.last { it.role == Role.ASSISTANT }
            assertEquals(
                "注入的用量应原样落到回合的 usage 上",
                Usage(input = contextWindow + 1, output = 1),
                assistant.usage,
            )
            assertTrue(
                "STOP + 越窗用量必须被判定为溢出",
                OverflowDetector.isContextOverflow(assistant, contextWindow),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun closeUnregistersTheTransportFromTheGlobalRegistry() {
        // `ProviderTransports` 是进程级全局注册表：不注销的话下一个用例可能命中上一个
        // 用例的传输层，失败会串味且极难归因（AGENTS.md §10.5 的「清除时机」）。
        val transport = ScriptedTransport(
            listOf(ScriptedTurn.text("ok")),
            id = "harness-close",
        )
        val h = harness(transport, sessionId = "harness-close")
        assertNotNull("harness 必须把传输层注册进全局表", ProviderTransports.byId("harness-close"))

        h.close()

        assertNull("close 要把传输层从全局表摘掉", ProviderTransports.byId("harness-close"))
    }
}
