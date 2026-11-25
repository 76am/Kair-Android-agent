package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.ToolCallData
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机 `edit` 卡在 `{"edits":` / 3 tok 的**端到端**守卫（U112）。
 *
 * 现场：流式只推出参数开头几个字符，整发全文在 `ToolCallsCompleted` 的收尾帧里。
 * `ToolArgsSettleTest` 只守了合并规则本身；这里守的是**接线** —— 收尾帧到达后、
 * 工具还没回结果时，界面读到的消息（`session.messages`）里必须已经是全文。
 * 若这条红了，界面会一直显示半截参数直到工具输出回来（用户看到的就是「输出后才完整」）。
 */
class ToolArgsVisibilityTest {

    private class BlockingTool : Tool {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override val name: String = NAME
        override val description: String = "blocks until released"
        override val parameters: JsonObject = schemaOf(props())

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            started.countDown()
            withContext(NonCancellable) { release.await(30, TimeUnit.SECONDS) }
            return ToolResult("ok")
        }

        companion object {
            const val NAME = "u112_edit"
        }
    }

    @Test
    fun `the visible message carries the completed args before the tool result lands`() = runBlocking {
        val full = """{"edits":[{"path":".kair/memory.md","oldText":"旧","newText":"新"}]}"""
        val tool = BlockingTool()
        val transport = ScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.ToolCallStarted(0, "call-1", BlockingTool.NAME),
                        StreamEvent.ToolCallArgsDelta(0, """{"edits":"""),
                        StreamEvent.ToolCallsCompleted(
                            listOf(ToolCallData("call-1", BlockingTool.NAME, full)),
                        ),
                        StreamEvent.Completed(StopReason.TOOL_USE),
                    ),
                ),
                ScriptedTurn.text("done"),
            ),
        )
        val h = harness(
            transport,
            sessionId = "u112-args-visibility",
            tools = ToolRegistry().register(tool),
        )
        try {
            h.session.send("go")
            assertTrue("工具应已开始执行", tool.started.await(30, TimeUnit.SECONDS))

            val assistant = h.session.messages.value.last {
                it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty()
            }
            assertEquals(
                "收尾帧全文必须在工具执行期间就已经写进界面消息",
                full,
                assistant.toolCalls.single().arguments,
            )
        } finally {
            tool.release.countDown()
            h.close()
        }
    }

    @Test
    fun `streamed argument deltas update the visible message before the stream ends`() = runBlocking {
        val prefix = """{"edits":"""
        val rest = """[{"path":"a.md","oldText":"x","newText":"y"}]}"""
        val full = prefix + rest
        val releaseRest = CountDownLatch(1)
        val transport = object : ScriptedTransport(emptyList(), id = "u112-args-midstream") {
            override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
                requests += request
                if (requests.size == 1) {
                    emit(StreamEvent.ToolCallStarted(0, "call-1", BlockingTool.NAME))
                    emit(StreamEvent.ToolCallArgsDelta(0, prefix))
                    // 模拟真实端点：前缀先到，其余参数隔一段时间才推（收尾帧再兜全文）。
                    withContext(NonCancellable) { releaseRest.await(30, TimeUnit.SECONDS) }
                    emit(StreamEvent.ToolCallArgsDelta(0, rest))
                    emit(
                        StreamEvent.ToolCallsCompleted(
                            listOf(ToolCallData("call-1", BlockingTool.NAME, full)),
                        ),
                    )
                    emit(StreamEvent.Completed(StopReason.TOOL_USE))
                } else {
                    emit(StreamEvent.TextDelta("done"))
                    emit(StreamEvent.Completed(StopReason.STOP))
                }
            }
        }
        val tool = BlockingTool()
        val h = harness(
            transport,
            sessionId = "u112-args-midstream",
            tools = ToolRegistry().register(tool),
        )
        try {
            h.session.send("go")
            await("流式前缀应已写进界面消息") {
                latestArgs(h) == prefix
            }
            assertTrue("工具此时不应已开始执行", tool.started.count != 0L)

            releaseRest.countDown()
            assertTrue("工具应已开始执行", tool.started.await(30, TimeUnit.SECONDS))
            assertEquals("中途增量必须与收尾全文一致", full, latestArgs(h))
        } finally {
            releaseRest.countDown()
            tool.release.countDown()
            h.close()
        }
    }

    private fun latestArgs(h: SessionHarness): String? =
        h.session.messages.value.lastOrNull { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
            ?.toolCalls?.single()?.arguments
}
