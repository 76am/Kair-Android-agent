package dev.kair.app.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U41 判据：**终止与释放分离，且「释放」只做一次、不会被「已断」短路**。
 *
 * 现状（改动前）是 `handleClosed` 把 `closed` 置真，`close()` 见到 `closed` 就早退 ——
 * 于是「远端先断、管理器随后 close」这条路径永远不会 `transport.close()`：
 * stdio 子进程 / HTTP 连接都不释放。`start()` 也把自己包装成了一次性关闭，
 * 让「closed = 用户主动关」这个前提从源头失效。
 */
class McpClientTerminateTest {

    @Test
    fun `closing after a remote drop still releases the transport once`() = runBlocking {
        var disconnected = 0
        val transport = FakeTransport()
        val client = McpClient("test", transport, 5_000, onDisconnected = { disconnected++ })
        client.connect(this)

        transport.drop()
        client.close()

        assertEquals("远端断开只通知一次", 1, disconnected)
        assertEquals(
            "远端先断之后 close() 仍必须释放传输（当前实现因 closed=true 早退，一次都没关）",
            1,
            transport.closeCount,
        )
    }

    @Test
    fun `an active close releases the transport once and does not report a drop`() = runBlocking {
        var disconnected = 0
        val transport = FakeTransport()
        val client = McpClient("test", transport, 5_000, onDisconnected = { disconnected++ })
        client.connect(this)

        client.close()
        client.close()

        assertEquals("主动关闭必须幂等", 1, transport.closeCount)
        assertEquals("主动关闭不是「断开」，不得回调 onDisconnected", 0, disconnected)
    }

    private class FakeTransport : McpTransport {
        var closeCount = 0
            private set
        private var onMessage: ((JsonObject) -> Unit)? = null
        private var onClosed: ((Throwable) -> Unit)? = null

        override suspend fun start(
            scope: CoroutineScope,
            onMessage: (JsonObject) -> Unit,
            onClosed: (Throwable) -> Unit,
        ) {
            this.onMessage = onMessage
            this.onClosed = onClosed
        }

        override suspend fun send(message: JsonObject) {
            val method = message["method"]?.toString()?.trim('"') ?: return
            if (method != "initialize") return
            val id = message["id"] ?: return
            onMessage?.invoke(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {
                        put("protocolVersion", "2025-06-18")
                        put("serverInfo", buildJsonObject {
                            put("name", "fake")
                            put("version", "1")
                        })
                    })
                },
            )
        }

        override fun close() {
            closeCount++
        }

        /** 模拟读循环检测到远端断开。 */
        fun drop() {
            onClosed?.invoke(IllegalStateException("连接已断开"))
        }
    }
}
