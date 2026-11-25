package dev.kair.app.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OpenAI 兼容协议请求体：思考参数的两种写法。 */
class OpenAiRequestBodyTest {

    private fun body(effort: String?): JsonObject = OpenAiProvider().buildBody(
        ChatRequest(
            model = "test-model",
            systemPrompt = "",
            messages = emptyList(),
            tools = emptyList(),
            options = SamplingOptions(reasoningEffort = effort),
        ),
        ProviderConfig(
            providerId = "longcat",
            transportId = TransportIds.OPENAI_CHAT,
            baseUrl = "https://example.test/v1",
            apiKey = "",
        ),
    )

    @Test
    fun offLevelDisablesThinkingInsteadOfSendingEffort() {
        val body = body("off")

        val thinking = body["thinking"] as JsonObject
        assertEquals("disabled", (thinking["type"] as JsonPrimitive).content)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun regularLevelIsSentAsReasoningEffort() {
        val body = body("high")

        assertEquals("high", (body["reasoning_effort"] as JsonPrimitive).content)
        assertNull(body["thinking"])
    }
}
