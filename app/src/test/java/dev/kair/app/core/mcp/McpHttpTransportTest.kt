package dev.kair.app.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * 用一个最小 HTTP 假服务器验证 Streamable HTTP 传输：初始化握手、会话头回传、
 * JSON 与 SSE 两种响应形态。不连外网。
 *
 * 不用 MockWebServer（项目没有该测试依赖），也不用 com.sun.net.httpserver
 * （Android 单测编译期不可用），只用 java.net。
 */
class McpHttpTransportTest {

    private lateinit var server: ServerSocket
    private lateinit var acceptThread: Thread
    private val sessionSeen = AtomicReference<String?>(null)
    private val receivedMethods = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        acceptThread = Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { handle(socket) }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        acceptThread.interrupt()
    }

    private fun url() = "http://127.0.0.1:${server.localPort}/mcp"

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = input.readNBytes(length).decodeToString()
            assertTrue(requestLine.startsWith("POST "))
            val request = McpJson.parseToJsonElement(body).jsonObject
            sessionSeen.set(headers["mcp-session-id"])
            val method = (request["method"] as JsonPrimitive).content
            receivedMethods += method
            val id = request["id"]
            if (id == null) {
                writeResponse(client, 202, "application/json", "")
                return
            }
            val result = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    put("capabilities", buildJsonObject { })
                    put("serverInfo", buildJsonObject {
                        put("name", "local")
                        put("version", "1")
                    })
                }

                "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "echo")
                            put("description", "Echo")
                            put("inputSchema", buildJsonObject { put("type", "object") })
                        })
                    })
                }

                else -> buildJsonObject { }
            }
            val message = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
            val encoded = McpJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), message)
            val extra = if (method == "initialize") mapOf("Mcp-Session-Id" to "sess-1") else emptyMap()
            when (sse) {
                true -> writeResponse(
                    client,
                    200,
                    "text/event-stream",
                    "event: message\ndata: $encoded\n\n",
                    extra,
                )

                false -> writeResponse(client, 200, "application/json", encoded, extra)
            }
        }
    }

    /** 每个测试选择响应形态。 */
    private var sse = false

    private fun writeResponse(
        socket: Socket,
        code: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(" OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            // 连接随后关闭：客户端每次请求重新建连，测试服务器无需处理 keep-alive。
            append("Connection: close\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return buffer.toString().takeIf { it.isNotEmpty() }
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(b.toChar())
        }
    }

    private fun handshakeAndList() = runBlocking {
        val client = McpClient(
            clientName = "kair-test",
            transport = StreamableHttpMcpTransport(url(), emptyMap()),
            timeoutMillis = 10_000,
        )
        try {
            val info = client.connect(CoroutineScope(Dispatchers.Unconfined))
            val tools = client.listTools()
            assertEquals("2025-06-18", info.protocolVersion)
            assertEquals(listOf("echo"), tools.map { it.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun `handshakes over plain JSON responses`() {
        handshakeAndList()
        // tools/list 必须带上 initialize 返回的会话头
        assertEquals("sess-1", sessionSeen.get())
        assertTrue(receivedMethods.containsAll(listOf("initialize", "notifications/initialized", "tools/list")))
    }

    @Test
    fun `handshakes over an SSE response body`() {
        sse = true
        handshakeAndList()
        assertTrue(receivedMethods.contains("tools/list"))
    }
}
