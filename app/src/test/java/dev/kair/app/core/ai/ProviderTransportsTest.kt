package dev.kair.app.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 传输层注册表：注册 / 解析 / 未注册降级。 */
class ProviderTransportsTest {

    private class FakeTransport(override val id: String) : ProviderTransport {
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            flowOf(StreamEvent.Completed("stop"))
    }

    private fun request() = ChatRequest(
        model = "test-model",
        systemPrompt = "",
        messages = emptyList(),
        tools = emptyList(),
    )

    private fun config(transportId: String) = ProviderConfig(
        providerId = "test-provider",
        transportId = transportId,
        baseUrl = "https://example.test/v1",
        apiKey = "",
    )

    @Test
    fun blankIdIsRejected() {
        assertFalse(ProviderTransports.register(FakeTransport("")))
    }

    @Test
    fun registeredTransportIsResolvedById() {
        try {
            assertTrue(ProviderTransports.register(FakeTransport("test-transport")))
            assertEquals("test-transport", ProviderTransports.byId("test-transport")?.id)
            assertTrue("test-transport" in ProviderTransports.ids)
        } finally {
            ProviderTransports.unregister("test-transport")
        }
    }

    @Test
    fun unregisterRemovesTransport() {
        ProviderTransports.register(FakeTransport("test-removable"))
        assertTrue(ProviderTransports.unregister("test-removable"))
        assertEquals(null, ProviderTransports.byId("test-removable"))
    }

    @Test
    fun unknownTransportDegradesToFailure() = runBlocking {
        val event = ProviderTransports.stream(request(), config("not-registered")).first()
        assertTrue(event is StreamEvent.Failure)
    }

    @Test
    fun registeredTransportReceivesTheRequest() = runBlocking {
        try {
            ProviderTransports.register(FakeTransport("test-dispatch"))
            val event = ProviderTransports.stream(request(), config("test-dispatch")).first()
            assertTrue(event is StreamEvent.Completed)
        } finally {
            ProviderTransports.unregister("test-dispatch")
        }
    }
}
