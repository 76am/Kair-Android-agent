package dev.kair.app.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * U41 判据：**单工具开关必须当场生效**，且不靠重连。
 *
 * 现状（改动前）是开关只改设置、不更新运行中的会话配置：`tools()` 读的是
 * `session.config.disabledTools`，所以模型那一侧拿到的还是旧工具集，直到重启或改配置才生效。
 */
class McpToolToggleTest {

    private lateinit var server: ServerSocket
    private lateinit var acceptThread: Thread
    private val initializeCount = AtomicInteger()

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
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

    @Test
    fun `turning a single tool off takes effect without reconnecting`() = runBlocking {
        val config = McpServerConfig(
            id = "srv-toggle",
            name = "toggle",
            transport = McpTransportConfig.Http(url = "http://127.0.0.1:${server.localPort}/mcp"),
        )
        var saved = listOf(config)
        val manager = McpManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            configsProvider = { saved },
            saveConfigs = { saved = it },
            processHost = McpProcessHost(),
            workspace = { null },
            stdioWorkspaceReady = { false },
            onToolsChanged = {},
        )
        try {
            manager.reconcile()
            withTimeout(5_000) {
                manager.status.first { it[config.id] is McpServerStatus.Connected }
            }
            assertEquals(
                "前提：连接后两个工具都在",
                setOf("mcp__toggle__tool_a", "mcp__toggle__tool_b"),
                manager.tools().map { it.name }.toSet(),
            )

            manager.setToolEnabled(config.id, "tool_b", false)

            assertEquals(
                "关掉一个工具必须立刻反映到 tools()（当前实现只改设置、运行态还是旧配置）",
                setOf("mcp__toggle__tool_a"),
                manager.tools().map { it.name }.toSet(),
            )

            manager.setToolEnabled(config.id, "tool_b", true)

            assertEquals(
                "重新打开后必须恢复",
                setOf("mcp__toggle__tool_a", "mcp__toggle__tool_b"),
                manager.tools().map { it.name }.toSet(),
            )
            assertEquals("单工具开关不得触发重连（握手只应发生一次）", 1, initializeCount.get())
        } finally {
            manager.closeAll()
        }
    }

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
            check(requestLine.startsWith("POST "))
            val body = input.readNBytes(headers["content-length"]?.toIntOrNull() ?: 0).decodeToString()
            val request = McpJson.parseToJsonElement(body).jsonObject
            val method = (request["method"] as JsonPrimitive).content
            val id = request["id"]
            if (id == null) {
                writeResponse(client, 202, "application/json", "")
                return
            }
            if (method == "initialize") initializeCount.incrementAndGet()
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
                            put("name", "tool_a")
                            put("description", "A")
                            put("inputSchema", buildJsonObject { put("type", "object") })
                        })
                        add(buildJsonObject {
                            put("name", "tool_b")
                            put("description", "B")
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
            writeResponse(client, 200, "application/json", encoded, extra)
        }
    }

    private fun writeResponse(
        socket: Socket,
        status: Int,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val out = socket.getOutputStream()
        val builder = StringBuilder()
        builder.append("HTTP/1.1 $status OK\r\n")
        builder.append("Content-Type: $contentType\r\n")
        builder.append("Content-Length: ${body.length}\r\n")
        builder.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) -> builder.append("$k: $v\r\n") }
        builder.append("\r\n").append(body)
        out.write(builder.toString().toByteArray())
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            when (b.toChar()) {
                '\r' -> Unit
                '\n' -> return line.toString()
                else -> line.append(b.toChar())
            }
        }
    }
}
