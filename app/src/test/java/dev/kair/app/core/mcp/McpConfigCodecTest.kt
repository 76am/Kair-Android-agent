package dev.kair.app.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpConfigCodecTest {

    @Test
    fun `imports the shared mcpServers shape`() {
        val raw = """
            {
              "mcpServers": {
                "docs": {"url": "https://example.com/mcp", "headers": {"Authorization": "Bearer t"}},
                "legacy": {"type": "sse", "url": "https://example.com/sse"},
                "fs": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"]}
              }
            }
        """.trimIndent()

        val servers = McpConfigCodec.importServers(raw)

        assertEquals(listOf("docs", "legacy", "fs"), servers.map { it.name })
        assertEquals(
            McpTransportConfig.Http("https://example.com/mcp", mapOf("Authorization" to "Bearer t")),
            servers[0].transport,
        )
        assertEquals(McpTransportConfig.Sse("https://example.com/sse"), servers[1].transport)
        assertEquals(
            McpTransportConfig.Stdio(
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
            ),
            servers[2].transport,
        )
        assertTrue(servers.all { it.enabled })
        assertTrue(servers.all { it.id.isNotBlank() })
    }

    @Test
    fun `encoded configuration round-trips`() {
        val servers = listOf(
            McpServerConfig(
                id = "a",
                name = "fs",
                transport = McpTransportConfig.Stdio("python", listOf("-m", "server")),
                disabledTools = listOf("write_file"),
            ),
            McpServerConfig(
                id = "b",
                name = "docs",
                transport = McpTransportConfig.Http("https://example.com/mcp"),
            ),
        )

        assertEquals(servers, McpConfigCodec.decode(McpConfigCodec.encode(servers)))
    }

    @Test
    fun `rejects an entry without url or command`() {
        try {
            McpConfigCodec.importServers("""{"mcpServers": {"bad": {"type": "http"}}}""")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bad"))
        }
    }

    @Test
    fun `tool names are namespaced and sanitized`() {
        assertEquals("mcp__my_server__read_file", mcpToolName("my-server", "read:file"))
    }

    @Test
    fun `relative sse endpoints resolve against the stream url`() {
        assertEquals(
            "https://example.com/messages?session=1",
            resolveEndpoint("https://example.com/sse", "/messages?session=1"),
        )
    }

    @Test
    fun `connection key ignores tool switches`() {
        val base = McpServerConfig(
            id = "a",
            name = "fs",
            transport = McpTransportConfig.Http("https://example.com/mcp"),
        )
        assertEquals(base.connectionKey(), base.copy(disabledTools = listOf("x")).connectionKey())
    }
}
