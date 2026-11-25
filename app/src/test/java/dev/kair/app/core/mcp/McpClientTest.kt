package dev.kair.app.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 用假传输验证协议配对、分页与结果解析；不碰网络与进程。
 */
class McpClientTest {

    private class FakeTransport : McpTransport {
        var onMessage: ((JsonObject) -> Unit)? = null
        val sent = mutableListOf<JsonObject>()

        override suspend fun start(
            scope: CoroutineScope,
            onMessage: (JsonObject) -> Unit,
            onClosed: (Throwable) -> Unit,
        ) {
            this.onMessage = onMessage
        }

        override suspend fun send(message: JsonObject) {
            sent += message
            val id = message["id"] ?: return
            val result = when (message["method"]?.let { (it as JsonPrimitive).content }) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    put("capabilities", buildJsonObject { })
                    put("serverInfo", buildJsonObject {
                        put("name", "fake")
                        put("version", "1.2")
                    })
                }

                "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "echo")
                            put("description", "Echo text")
                            put("inputSchema", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("text", buildJsonObject { put("type", "string") })
                                })
                            })
                        })
                        add(buildJsonObject { put("name", "shot") })
                    })
                }

                "tools/call" -> buildJsonObject {
                    put("isError", false)
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "pong")
                        })
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", "aGk=")
                            put("mimeType", "image/jpeg")
                        })
                    })
                }

                else -> buildJsonObject { }
            }
            onMessage?.invoke(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                },
            )
        }

        override fun close() = Unit
    }

    @Test
    fun `connect performs the handshake and lists tools`() = runBlocking {
        val transport = FakeTransport()
        val client = McpClient("kair", transport, 5_000)
        val info = client.connect(CoroutineScope(Dispatchers.Unconfined))

        assertEquals("fake", info.name)
        assertEquals("2025-06-18", info.protocolVersion)
        val tools = client.listTools()
        assertEquals(listOf("echo", "shot"), tools.map { it.name })
        assertEquals("Echo text", tools[0].description)

        assertEquals("initialize", transport.sent[0]["method"]?.let { (it as JsonPrimitive).content })
        assertEquals("notifications/initialized", transport.sent[1]["method"]?.let { (it as JsonPrimitive).content })
        assertFalse(transport.sent[1].containsKey("id"))
    }

    private class SilentTransport : McpTransport {
        override suspend fun start(
            scope: CoroutineScope,
            onMessage: (JsonObject) -> Unit,
            onClosed: (Throwable) -> Unit,
        ) = Unit

        override suspend fun send(message: JsonObject) = Unit

        override fun close() = Unit
    }

    @Test
    fun `a silent server surfaces a timeout as a protocol error`() = runBlocking {
        val client = McpClient("kair", SilentTransport(), 100)
        try {
            client.connect(CoroutineScope(Dispatchers.Unconfined))
            fail("expected McpProtocolException")
        } catch (e: McpProtocolException) {
            assertTrue(e.message!!.contains("超时"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `callTool maps text and image content`() = runBlocking {
        val transport = FakeTransport()
        val client = McpClient("kair", transport, 5_000)
        client.connect(CoroutineScope(Dispatchers.Unconfined))

        val result = client.callTool("echo", buildJsonObject { put("text", "ping") })

        assertEquals("pong", result.contents[0].text)
        assertEquals("aGk=", result.contents[1].imageBase64)
        assertEquals("image/jpeg", result.contents[1].imageMimeType)
        assertFalse(result.isError)
        assertTrue(transport.sent.last().toString().contains("\"arguments\""))
    }
}
