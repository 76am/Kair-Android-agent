package dev.kair.app.core.plugin

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the middleware hook chain. */
class HookBusTest {

    private fun user(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    @Test
    fun inputHooksChainInRegistrationOrder() {
        val bus = HookBus()
        bus.onInput { it.uppercase() }
        bus.onInput { "$it!" }
        assertEquals("HELLO!", bus.runInput("hello"))
    }

    @Test
    fun nullReturnKeepsCurrentValue() {
        val bus = HookBus()
        bus.onInput { null }
        bus.onInput { "replaced" }
        assertEquals("replaced", bus.runInput("original"))
    }

    @Test
    fun emptyBusIsIdentity() {
        val bus = HookBus()
        assertTrue(bus.isEmpty)
        assertEquals("text", bus.runInput("text"))
        val messages = listOf(user("a"))
        assertEquals(messages, bus.runContext(messages))
    }

    @Test
    fun contextHooksSeeEachOthersOutput() {
        val bus = HookBus()
        bus.onContext { msgs -> msgs + user("tail") }
        bus.onContext { msgs -> msgs + user("last-${msgs.size}") }
        val out = bus.runContext(listOf(user("head")))
        assertEquals(listOf("head", "tail", "last-2"), out.map { it.text })
    }

    @Test
    fun toolCallHookCanRewriteArguments() = kotlinx.coroutines.runBlocking {
        val bus = HookBus()
        bus.onToolCall { hook ->
            ToolCallOutcome(buildJsonObject { put("path", "/rewritten") })
        }
        val outcome = bus.runToolCall("read", buildJsonObject { put("path", "/original") })
        assertNull(outcome.blockReason)
        assertEquals("/rewritten", outcome.arguments["path"].toString().trim('"'))
    }

    @Test
    fun toolCallHookCanBlock() = kotlinx.coroutines.runBlocking {
        val bus = HookBus()
        bus.onToolCall { ToolCallOutcome(it.arguments, blockReason = "denied") }
        val outcome = bus.runToolCall("rm", buildJsonObject { put("path", "/etc") })
        assertEquals("denied", outcome.blockReason)
    }

    @Test
    fun toolResultHookRewritesContent() {
        val bus = HookBus()
        bus.onToolResult { ToolResult("[${it.result.content}]", isError = true) }
        val out = bus.runToolResult("read", ToolResult("body"))
        assertEquals("[body]", out.content)
        assertTrue(out.isError)
    }

    @Test
    fun headerHooksMergeInsteadOfReplace() {
        val bus = HookBus()
        bus.onBeforeProviderHeaders { it + ("X-Kair-Client" to "kair") }
        val headers = bus.runHeaders(mapOf("Authorization" to "Bearer k"))
        assertEquals("Bearer k", headers["Authorization"])
        assertEquals("kair", headers["X-Kair-Client"])
    }

    @Test
    fun sizeCountsEveryHandler() {
        val bus = HookBus()
        bus.onInput { it }
        bus.onContext { it }
        bus.onToolCall { ToolCallOutcome(it.arguments) }
        bus.onToolResult { it.result }
        bus.onBeforeProviderHeaders { it }
        assertEquals(5, bus.size)
        assertFalse(bus.isEmpty)
    }

    @Test
    fun toolCallHookCarriesSessionId() = kotlinx.coroutines.runBlocking {
        val bus = HookBus()
        var seen: String? = null
        bus.onToolCall { hook -> seen = hook.sessionId; null }
        bus.runToolCall("read", buildJsonObject { }, "session-42")
        assertEquals("session-42", seen)
    }
}
