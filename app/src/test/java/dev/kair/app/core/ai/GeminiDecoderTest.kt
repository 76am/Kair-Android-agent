package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini 原生 `streamGenerateContent` 帧解码：每帧是一份完整响应，推理用
 * `part.thought = true` 标记，工具参数一次性完整返回。用真实帧形状离线验证。
 */
class GeminiDecoderTest {

    @Test
    fun `text chunks accumulate into one segment and usage is split`() {
        val d = GeminiDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}""",
        )
        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":12,"cachedContentTokenCount":5,"candidatesTokenCount":2,"thoughtsTokenCount":0}}""",
        )
        events += d.finish()

        assertEquals(
            listOf(
                StreamEvent.TextStart("gemini-t0"),
                StreamEvent.TextDelta("Hel"),
                StreamEvent.TextDelta("lo"),
                StreamEvent.TextEnd("gemini-t0"),
                StreamEvent.UsageEvent(Usage(input = 7, output = 2, cacheRead = 5, reasoning = 0)),
                StreamEvent.Completed(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `thought tokens count as output like dsh`() {
        val usage = parseGeminiUsage(
            Json.parseToJsonElement(
                """{"promptTokenCount":10,"cachedContentTokenCount":4,"candidatesTokenCount":5,"thoughtsTokenCount":7}""",
            ).jsonObject,
        )

        assertEquals(6, usage.input)
        assertEquals(4, usage.cacheRead)
        assertEquals(12, usage.output)
        assertEquals(7, usage.reasoning)
    }

    @Test
    fun `thought parts become reasoning and close before text`() {
        val d = GeminiDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"think","thought":true}],"role":"model"}}]}""",
        )
        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"Hi"}],"role":"model"},"finishReason":"STOP"}]}""",
        )
        events += d.finish()

        assertEquals(
            listOf(
                StreamEvent.ReasoningStart("gemini-r0", "REASONING_TEXT"),
                StreamEvent.ReasoningDelta("think"),
                StreamEvent.ReasoningEnd("gemini-r0"),
                StreamEvent.TextStart("gemini-t0"),
                StreamEvent.TextDelta("Hi"),
                StreamEvent.TextEnd("gemini-t0"),
                StreamEvent.Completed(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `function call carries id and complete args`() {
        val d = GeminiDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Beijing"},"id":"fc_1"}}],"role":"model"},"finishReason":"STOP"}]}""",
        )
        events += d.finish()

        assertEquals(StreamEvent.ToolCallStarted(0, "fc_1", "get_weather"), events[0])
        assertEquals(StreamEvent.ToolCallArgsDelta(0, """{"city":"Beijing"}"""), events[1])
        assertEquals(
            StreamEvent.ToolCallsCompleted(listOf(ToolCallData("fc_1", "get_weather", """{"city":"Beijing"}"""))),
            events[2],
        )
        assertEquals(StreamEvent.Completed(StopReason.TOOL_USE), events[3])
    }

    @Test
    fun `stream error keeps http status for classification`() {
        val d = GeminiDecoder()
        val events = d.accept("""{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")

        assertEquals(listOf(StreamEvent.Failure("HTTP 429: Quota exceeded")), events)
        assertTrue(d.finish().isEmpty())
    }

    @Test
    fun `blocked prompt is terminal`() {
        val d = GeminiDecoder()
        val events = d.accept("""{"promptFeedback":{"blockReason":"SAFETY"}}""")

        assertEquals(listOf(StreamEvent.Failure("Gemini blocked the prompt (SAFETY)", retryable = false)), events)
        assertTrue(d.finish().isEmpty())
    }

    @Test
    fun `empty stream is retryable`() {
        val events = GeminiDecoder().finish()

        assertTrue((events.single() as StreamEvent.Failure).retryable == true)
    }

    @Test
    fun `thought signatures ride on text end and tool call start`() {
        val d = GeminiDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"ok","thoughtSignature":"sig-text"}],"role":"model"}}]}""",
        )
        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"echo","args":{}},"thoughtSignature":"sig-call"}],"role":"model"},"finishReason":"STOP"}]}""",
        )
        events += d.finish()

        assertEquals("sig-text", events.filterIsInstance<StreamEvent.TextEnd>().single().signature)
        assertEquals("sig-call", events.filterIsInstance<StreamEvent.ToolCallStarted>().single().signature)
    }

    @Test
    fun `signature on an empty text part keeps an empty segment`() {
        val d = GeminiDecoder()
        val events = mutableListOf<StreamEvent>()

        events += d.accept(
            """{"candidates":[{"content":{"parts":[{"text":"","thoughtSignature":"sig-empty"}],"role":"model"},"finishReason":"STOP"}]}""",
        )
        events += d.finish()

        assertEquals(
            listOf(
                StreamEvent.TextStart("gemini-t0"),
                StreamEvent.TextEnd("gemini-t0", "sig-empty"),
                StreamEvent.Completed(StopReason.STOP),
            ),
            events,
        )
    }

    @Test
    fun `full json schema is sent as parametersJsonSchema with dsh budgets`() {
        val schema = Json.parseToJsonElement(
            """
            {
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "additionalProperties": false,
              "required": ["city"],
              "properties": {
                "city": {"type": "string"},
                "mode": {"oneOf": [{"type": "string"}, {"type": "null"}]}
              }
            }
            """.trimIndent(),
        ).jsonObject

        val body = GeminiProvider().buildBody(
            ChatRequest(
                model = "gemini-2.5-pro",
                systemPrompt = "",
                messages = emptyList(),
                tools = listOf(LlmToolSpec("echo", "Echo", schema)),
                options = SamplingOptions(reasoningEffort = "low"),
            ),
        )

        val decl = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals(schema, decl["parametersJsonSchema"]!!.jsonObject)
        assertFalse(decl.containsKey("parameters"))
        assertEquals(
            2048,
            body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `gemini 3 uses thinking level`() {
        val body = GeminiProvider().buildBody(
            ChatRequest(
                model = "gemini-3-pro-preview",
                systemPrompt = "",
                messages = emptyList(),
                tools = emptyList(),
                options = SamplingOptions(reasoningEffort = "medium"),
            ),
        )

        val thinking = body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("MEDIUM", thinking["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals(true, thinking["includeThoughts"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `signatures are replayed only for the same origin endpoint`() {
        // 来源键是 (providerId, providerHost, model) 三元组（U76）：同模型串换中转也要丢签名。
        val message = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(
                Part.Text("ok", "sig-text"),
                Part.ToolCall("c1", "echo", "{}", "sig-call"),
            ),
            model = "gemini-3-pro-preview",
            providerId = "gemini",
            providerHost = "generativelanguage.googleapis.com",
        )

        fun partsFor(model: String, host: String = "generativelanguage.googleapis.com"): List<kotlinx.serialization.json.JsonObject> =
            GeminiProvider().buildBody(
                ChatRequest(
                    model = model,
                    systemPrompt = "",
                    messages = listOf(message),
                    tools = emptyList(),
                    providerId = "gemini",
                    providerHost = host,
                ),
            )["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject }

        val same = partsFor("gemini-3-pro-preview")
        assertEquals("sig-text", same[0]["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("sig-call", same[1]["thoughtSignature"]!!.jsonPrimitive.content)

        val other = partsFor("gemini-2.5-pro")
        assertFalse(other[0].containsKey("thoughtSignature"))
        assertFalse(other[1].containsKey("thoughtSignature"))

        val otherHost = partsFor("gemini-3-pro-preview", host = "relay.test")
        assertFalse(otherHost[0].containsKey("thoughtSignature"))
        assertFalse(otherHost[1].containsKey("thoughtSignature"))
    }
}
