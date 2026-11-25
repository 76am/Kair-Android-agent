package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 用**真实端点录下的 SSE**回放 `OpenAiProvider`：守「edit 参数逐帧到达时，
 * 解析层一帧都不丢」。
 *
 * 夹具 `sse/longcat-edit.sse` 是 LongCat-2.5 对一次 `edit` 调用的原始响应
 * （134 个 `arguments` 增量帧、1326 字符，2026-11-20 实抓）。现有
 * `ToolArgsVisibilityTest` 用 `ScriptedTransport` 直接发 `StreamEvent`，绕过了
 * SSE 解码 —— 真机「卡在 `{"edits":` / 3 tok」如果出在解析层，只有这条能抓到。
 *
 * 判据：
 * 1. 所有增量拼起来必须等于收尾帧全文（一字符不差、顺序不变）；
 * 2. 增量在收尾帧之前就到达（不是攒到最后一次给）；
 * 3. 帧数不能塌缩成一帧（端点逐帧推，解析层就得逐帧报）。
 */
class OpenAiSseArgsStreamTest {

    @Test
    fun `streamed edit arguments arrive incrementally and add up`() = runBlocking {
        val fixture = checkNotNull(javaClass.getResource("/sse/longcat-edit.sse")) {
            "缺少夹具 sse/longcat-edit.sse"
        }.readText()

        val events = withSseServer(fixture) { baseUrl ->
            OpenAiProvider().stream(
                ChatRequest(
                    model = "LongCat-2.5",
                    systemPrompt = "",
                    messages = listOf(
                        ChatMessage(
                            id = "u1",
                            role = Role.USER,
                            parts = listOf(Part.Text("go")),
                        ),
                    ),
                    tools = emptyList(),
                    sessionId = "openai-sse-args",
                ),
                ProviderConfig(
                    providerId = "longcat",
                    transportId = TransportIds.OPENAI_CHAT,
                    baseUrl = baseUrl,
                    apiKey = "",
                ),
            ).toList()
        }

        val deltas = events.filterIsInstance<StreamEvent.ToolCallArgsDelta>()
        val completed = events.filterIsInstance<StreamEvent.ToolCallsCompleted>().single()
        val streamed = deltas.joinToString("") { it.delta }

        assertTrue("应逐帧上报（实际 ${deltas.size} 帧）", deltas.size > 20)
        assertEquals("增量拼起来必须等于收尾全文", completed.calls.single().arguments, streamed)
        assertTrue(
            "增量必须先于收尾帧到达",
            events.indexOfFirst { it is StreamEvent.ToolCallArgsDelta } <
                events.indexOfFirst { it is StreamEvent.ToolCallsCompleted },
        )
    }

    /**
     * 起一个只服务一次的最小 HTTP 服务器，把 [sse] 原样写回。
     *
     * 照 `McpHttpTransportTest` 的既有做法用 `java.net.ServerSocket`（Android 单测
     * 编译期没有 `com.sun.net.httpserver`，项目也没有 MockWebServer 依赖）。
     */
    private suspend fun <T> withSseServer(sse: String, block: suspend (String) -> T): T {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread(isDaemon = true, name = "sse-fixture") {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        // 只读到头部结束：请求体不影响响应内容。
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = sse.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/event-stream\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(Charsets.UTF_8),
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
            return try {
                block("http://127.0.0.1:${server.localPort}/v1")
            } finally {
                runCatching { server.close() }
                worker.join(5_000)
            }
        }
    }
}
