package dev.kair.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 粘贴完整端点地址时的协议识别与根地址归一化。 */
class EndpointUrlTest {

    private fun assertInferred(raw: String, transportId: String, baseUrl: String) {
        val actual = inferEndpoint(raw)
        assertEquals(transportId, actual?.transportId)
        assertEquals(baseUrl, actual?.baseUrl)
    }

    @Test
    fun chatCompletionsEndpointIsRecognizedAndTrimmed() {
        assertInferred(
            "https://api.openai.com/v1/chat/completions",
            TransportIds.OPENAI_CHAT,
            "https://api.openai.com/v1",
        )
    }

    @Test
    fun responsesEndpointIsRecognizedAndTrimmed() {
        assertInferred(
            "https://api.deepseek.com/responses/",
            TransportIds.OPENAI_RESPONSES,
            "https://api.deepseek.com",
        )
    }

    @Test
    fun anthropicMessagesEndpointDropsVersionPrefix() {
        assertInferred(
            "https://api.deepseek.com/anthropic/v1/messages",
            TransportIds.ANTHROPIC_MESSAGES,
            "https://api.deepseek.com/anthropic",
        )
    }

    @Test
    fun anthropicHostIsRecognizedWithoutEndpointPath() {
        assertInferred(
            "https://api.anthropic.com/v1",
            TransportIds.ANTHROPIC_MESSAGES,
            "https://api.anthropic.com",
        )
    }

    @Test
    fun genericGatewayRootIsLeftToTheUser() {
        // 通用网关根地址看不出协议：保持用户当前选择。
        assertNull(inferEndpoint("https://api.longcat.chat/openai/v1"))
        assertNull(inferEndpoint("https://proxy.example.com/v1"))
        assertNull(inferEndpoint("https://api.openai.com/v1"))
    }

    @Test
    fun blankInputIsIgnored() {
        assertNull(inferEndpoint(""))
        assertNull(inferEndpoint("   "))
    }
}
