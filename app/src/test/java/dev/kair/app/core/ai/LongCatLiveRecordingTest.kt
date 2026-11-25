package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI

/**
 * 真实流量录制（手动运行，默认跳过）。
 *
 * 用真实 LongCat-2.0 端点跑一遍内置 [OpenAiProvider]，把事件写成 JSONL fixture；
 * 产物默认落在 `app/build/longcat-traces/`，确认后复制到
 * `app/src/test/resources/stream-traces/longcat/` 供 CI 回放。
 *
 * 运行方式（密钥只经环境变量传入，不落盘）：
 * ```
 * KAIR_LONGCAT_KEY=... ./gradlew --no-daemon :app:testDebugUnitTest \
 *     --tests 'dev.kair.app.core.ai.LongCatLiveRecordingTest'
 * ```
 */
class LongCatLiveRecordingTest {

    private val key: String = System.getenv("KAIR_LONGCAT_KEY").orEmpty()
    private val baseUrl: String = System.getenv("KAIR_LONGCAT_BASE_URL")
        ?: "https://api.longcat.chat/openai/v1"
    private val model: String = System.getenv("KAIR_LONGCAT_MODEL") ?: "LongCat-2.0"

    @Before
    fun useSandboxProxy() {
        // OkHttp 的默认 ProxySelector 读 JVM 系统属性；测试进程需要显式设置才能出网。
        val proxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return
        val uri = runCatching { URI(proxy) }.getOrNull() ?: return
        listOf("https", "http").forEach { scheme ->
            System.setProperty("$scheme.proxyHost", uri.host)
            System.setProperty("$scheme.proxyPort", uri.port.toString())
        }
    }

    @Test
    fun `records plain text and reasoning trace`() {
        assumeTrue("未设置 KAIR_LONGCAT_KEY，跳过真实录制", key.isNotBlank())
        val events = record("text-reasoning") {
            ChatRequest(
                model = model,
                systemPrompt = "You are a concise assistant.",
                messages = listOf(user("Reply with the single word: OK")),
                tools = emptyList(),
                options = SamplingOptions(maxTokens = 1200),
            )
        }

        assertTrue("应有正文开始边界", events.any { it is StreamEvent.TextStart })
        assertTrue("LongCat 默认输出推理", events.any { it is StreamEvent.ReasoningStart })
        val firstDelta = events.indexOfFirst { it is StreamEvent.TextDelta || it is StreamEvent.ReasoningDelta }
        val firstStart = events.indexOfFirst { it is StreamEvent.TextStart || it is StreamEvent.ReasoningStart }
        assertTrue("Start 必须早于第一个 delta", firstStart in 0 until firstDelta)
        assertTrue("应有用量", events.any { it is StreamEvent.UsageEvent })
        assertTrue("应有完成事件", events.any { it is StreamEvent.Completed })
        assertFalse(events.any { it is StreamEvent.Failure })
        assertTrue("应有正文内容", events.any { it is StreamEvent.TextDelta && it.text.isNotBlank() })
    }

    @Test
    fun `records tool call trace`() {
        assumeTrue("未设置 KAIR_LONGCAT_KEY，跳过真实录制", key.isNotBlank())
        val events = record("tool-call") {
            ChatRequest(
                model = model,
                systemPrompt = "You are a helpful assistant.",
                messages = listOf(user("What is the weather in Beijing? Use the tool.")),
                tools = listOf(weatherTool()),
                options = SamplingOptions(maxTokens = 800),
            )
        }

        val started = events.filterIsInstance<StreamEvent.ToolCallStarted>().single()
        assertEquals("get_weather", started.name)
        assertTrue("工具调用 id 应由端点给出", started.id.isNotBlank())
        assertTrue("应有参数增量", events.any { it is StreamEvent.ToolCallArgsDelta })

        val completed = events.filterIsInstance<StreamEvent.ToolCallsCompleted>().single()
        val call = completed.calls.single()
        assertEquals(started.id, call.id)
        assertEquals("get_weather", call.name)
        assertTrue("参数应包含城市", call.arguments.contains("Beijing"))
        assertTrue(events.any { it is StreamEvent.Completed })
        assertFalse(events.any { it is StreamEvent.Failure })
    }

    @Test
    fun `records invalid api key error trace`() {
        assumeTrue("未设置 KAIR_LONGCAT_KEY，跳过真实录制", key.isNotBlank())
        val events = record("auth-error", apiKey = "ak_invalid_key") {
            ChatRequest(
                model = model,
                systemPrompt = "",
                messages = listOf(user("hi")),
                tools = emptyList(),
            )
        }

        val failure = events.filterIsInstance<StreamEvent.Failure>().single()
        assertTrue("应透出 HTTP 401", failure.message.contains("401"))
        assertFalse("鉴权失败不可重试", failure.isTransient())
    }

    private fun record(
        scenario: String,
        apiKey: String = key,
        request: () -> ChatRequest,
    ): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val transport = RecordingTransport(OpenAiProvider()) { events += it }
        val config = ProviderConfig(
            providerId = "longcat",
            transportId = TransportIds.OPENAI_CHAT,
            baseUrl = baseUrl,
            apiKey = apiKey,
        )
        val streamed = runBlocking { transport.stream(request(), config).toList() }
        assertEquals(streamed, events)

        val dir = File(System.getProperty("kair.trace.dir") ?: "build/longcat-traces")
        val file = File(dir, "longcat/$scenario.jsonl")
        writeTrace(file, events)
        println("录制完成：${file.absolutePath}（${events.size} 帧）")

        assertEquals("录制文件必须可原样读回", events, readTrace(file))
        return events
    }

    private fun user(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    private fun weatherTool() = LlmToolSpec(
        name = "get_weather",
        description = "Get current weather for a city",
        parameters = TraceJson.parseToJsonElement(
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
        ).let { it as kotlinx.serialization.json.JsonObject },
    )
}
