package dev.kair.app.core.ai

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * 录制 / 回放基础设施：JSONL 编解码、录制包装、回放传输层，以及用 fixture 驱动的重试。
 * 这些测试不连网、不花钱。
 */
class StreamTraceTest {

    private fun request() = ChatRequest(
        model = "test-model",
        systemPrompt = "system",
        messages = emptyList(),
        tools = emptyList(),
    )

    private fun config(apiKey: String = "") = ProviderConfig(
        providerId = "test-provider",
        transportId = "replay",
        baseUrl = "https://example.test/v1",
        apiKey = apiKey,
    )

    @Test
    fun `all fixtures survive a JSONL round trip`() {
        val fixtures = listOf(
            "stream-traces/openai/text.jsonl",
            "stream-traces/openai/tool-call.jsonl",
            "stream-traces/openai/reasoning.jsonl",
            "stream-traces/openai/text-then-tool.jsonl",
            "stream-traces/openai/auth-error.jsonl",
            "stream-traces/openai/server-error.jsonl",
            "stream-traces/openai/empty-body.jsonl",
            "stream-traces/anthropic/mixed.jsonl",
            // 真实 LongCat-2.0 流量（由 LongCatLiveRecordingTest 录制）。
            "stream-traces/longcat/text-reasoning.jsonl",
            "stream-traces/longcat/tool-call.jsonl",
            "stream-traces/longcat/auth-error.jsonl",
        )
        val file = File.createTempFile("kair-trace", ".jsonl")
        try {
            fixtures.forEach { fixture ->
                val events = loadTrace(fixture)
                assertTrue("fixture 不应为空：$fixture", events.isNotEmpty())
                writeTrace(file, events)
                assertEquals("JSONL 往返后事件序列必须一致：$fixture", events, readTrace(file))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `recording transport forwards live events and persists them without credentials`() {
        val trace = loadTrace("stream-traces/openai/text.jsonl")
        val recorded = mutableListOf<StreamEvent>()
        val transport = RecordingTransport(ReplayTransport(trace)) { recorded += it }

        val streamed = runBlocking { transport.stream(request(), config(apiKey = "sk-secret")).toList() }

        assertEquals(trace, streamed)
        assertEquals(trace, recorded)

        val file = File.createTempFile("kair-record", ".jsonl")
        try {
            writeTrace(file, recorded)
            assertEquals(trace, readTrace(file))
            // 事件里不含凭据，录制文件天然脱敏。
            assertFalse("录制产物不得包含 apiKey", file.readText().contains("sk-secret"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `replay transport yields the trace in order`() = runBlocking {
        val trace = loadTrace("stream-traces/anthropic/mixed.jsonl")
        val transport = ReplayTransport(trace)
        val events = transport.stream(request(), config()).toList()
        assertEquals(trace, events)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `empty body fixture is retried before any output`() {
        val scripts = ArrayDeque(
            listOf(
                loadTrace("stream-traces/openai/empty-body.jsonl"),
                loadTrace("stream-traces/openai/text.jsonl"),
            ),
        )
        val expected = loadTrace("stream-traces/openai/text.jsonl")
        val delays = mutableListOf<Long>()
        val events = runBlocking {
            retryingStream(
                jitter = { 0 },
                delayMs = { delays += it },
                attempt = { scripts.removeFirst().asFlow() },
            ).toList()
        }
        assertEquals(expected, events)
        assertEquals(listOf(TransientInitialBackoffMs), delays)
    }

    @Test
    fun `server error fixture is retried but auth error is not`() {
        val serverScripts = ArrayDeque(
            listOf(
                loadTrace("stream-traces/openai/server-error.jsonl"),
                loadTrace("stream-traces/openai/text.jsonl"),
            ),
        )
        val serverEvents = runBlocking {
            retryingStream(jitter = { 0 }, delayMs = {}, attempt = { serverScripts.removeFirst().asFlow() }).toList()
        }
        assertEquals(loadTrace("stream-traces/openai/text.jsonl"), serverEvents)

        val authScripts = ArrayDeque(listOf(loadTrace("stream-traces/openai/auth-error.jsonl")))
        val authEvents = runBlocking {
            retryingStream(jitter = { 0 }, delayMs = {}, attempt = { authScripts.removeFirst().asFlow() }).toList()
        }
        assertEquals(1, authEvents.size)
        assertTrue(authEvents.single() is StreamEvent.Failure)
    }
}
