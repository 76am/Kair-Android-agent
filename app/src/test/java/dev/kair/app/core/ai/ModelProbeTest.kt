package dev.kair.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型列表探测：端点地址解析与各家元数据字段解析（不联网络）。 */
class ModelProbeTest {

    @Test
    fun openAiCompatibleListParsesContextAndOutput() {
        val body = """
            {"object":"list","data":[
              {"id":"LongCat-2.5-Preview","object":"model","owned_by":"LongCat",
               "display_name":"LongCat-2.5-Preview","context_window":1048576,"max_output_tokens":262144},
              {"id":"LongCat-2.0","object":"model","display_name":"LongCat-2.0","context_window":1048576}
            ]}
        """.trimIndent()

        val models = parseOpenAiModels(body)

        assertEquals(2, models.size)
        assertEquals("LongCat-2.5-Preview", models[0].id)
        assertEquals(1_048_576, models[0].contextWindow)
        assertEquals(262_144, models[0].maxOutputTokens)
        assertTrue(models[0].reasoningLevels.isEmpty())
        assertNull(models[0].reasoningSupported)
        assertEquals(1_048_576, models[1].contextWindow)
        assertNull(models[1].maxOutputTokens)
    }

    @Test
    fun openRouterFieldsAreRecognized() {
        val body = """
            {"data":[{
              "id":"openai/gpt-4o",
              "name":"GPT-4o",
              "context_length":128000,
              "top_provider":{"context_length":128000,"max_completion_tokens":16384},
              "supported_parameters":["tools","reasoning"]
            }]}
        """.trimIndent()

        val model = parseOpenAiModels(body).single()

        assertEquals("GPT-4o", model.displayName)
        assertEquals(128_000, model.contextWindow)
        assertEquals(16_384, model.maxOutputTokens)
        assertEquals(true, model.reasoningSupported)
        assertTrue(model.reasoningLevels.isEmpty())
    }

    @Test
    fun explicitReasoningEffortsAreNormalizedAndFiltered() {
        val body = """
            {"data":[{
              "id":"some-reasoner",
              "reasoning":{"supported_efforts":["LOW","medium","high","none","bogus"],"default_effort":"medium"}
            }]}
        """.trimIndent()

        val model = parseOpenAiModels(body).single()

        assertEquals(listOf("low", "medium", "high", "off"), model.reasoningLevels)
        assertEquals(true, model.reasoningSupported)
    }

    @Test
    fun deepSeekEffortObjectIsParsed() {
        val body = """
            {"object":"list","data":[{
              "id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek-V4.1-Flash",
              "context_window":1048576,"max_output_tokens":393216,
              "effort":{"supported_levels":["low","high","max"],"default_level":"high"}
            }]}
        """.trimIndent()

        val model = parseOpenAiModels(body).single()

        assertEquals("DeepSeek-V4.1-Flash", model.displayName)
        assertEquals(1_048_576, model.contextWindow)
        assertEquals(393_216, model.maxOutputTokens)
        assertEquals(listOf("low", "high", "max"), model.reasoningLevels)
        assertEquals(true, model.reasoningSupported)
        assertEquals("high", model.defaultReasoningLevel)
    }

    @Test
    fun imageModalitiesAreRecognized() {
        val body = """
            {"data":[
              {"id":"deepseek-flash","input_modalities":["text","image"]},
              {"id":"deepseek-v4-pro","input_modalities":["text"]},
              {"id":"unknown-model"},
              {"id":"capability-model","capabilities":["completion","vision"]}
            ]}
        """.trimIndent()

        val models = parseOpenAiModels(body)

        assertEquals(true, models[0].supportsImage)
        assertEquals(false, models[1].supportsImage)
        assertNull(models[2].supportsImage)
        assertEquals(true, models[3].supportsImage)
    }

    @Test
    fun thinkingCapabilityWithoutLevelsOnlyReportsSupport() {
        val body = """
            {"data":[{"id":"llama3.2","capabilities":["completion","tools","thinking"]}]}
        """.trimIndent()

        val model = parseOpenAiModels(body).single()

        assertTrue(model.reasoningLevels.isEmpty())
        assertEquals(true, model.reasoningSupported)
    }

    @Test
    fun anthropicListParsesIdsAndDisplayNames() {
        val body = """
            {"data":[{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5","type":"model"}],
             "has_more":false}
        """.trimIndent()

        val model = parseAnthropicModels(body).single()

        assertEquals("claude-sonnet-4-5", model.id)
        assertEquals("Claude Sonnet 4.5", model.displayName)
        assertNull(model.contextWindow)
    }

    @Test
    fun geminiListStripsModelPrefixAndReadsTokenLimits() {
        val body = """
            {"models":[{"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro",
                        "inputTokenLimit":1048576,"outputTokenLimit":65536}]}
        """.trimIndent()

        val model = parseGeminiModels(body).single()

        assertEquals("gemini-2.5-pro", model.id)
        assertEquals("Gemini 2.5 Pro", model.displayName)
        assertEquals(1_048_576, model.contextWindow)
        assertEquals(65_536, model.maxOutputTokens)
    }

    @Test
    fun malformedBodyYieldsNoModels() {
        assertTrue(parseOpenAiModels("not json").isEmpty())
        assertTrue(parseAnthropicModels("{}").isEmpty())
        assertTrue(parseGeminiModels("[]").isEmpty())
    }

    @Test
    fun modelListUrlsCoverBaseUrlVariations() {
        assertEquals(
            listOf("https://api.openai.com/v1/models"),
            modelListUrls(TransportIds.OPENAI_CHAT, "https://api.openai.com/v1"),
        )
        assertEquals(
            listOf("https://api.deepseek.com/models", "https://api.deepseek.com/v1/models"),
            modelListUrls(TransportIds.OPENAI_CHAT, "https://api.deepseek.com"),
        )
        assertEquals(
            listOf("https://api.longcat.chat/openai/models", "https://api.longcat.chat/openai/v1/models"),
            modelListUrls(TransportIds.OPENAI_CHAT, "https://api.longcat.chat/openai/"),
        )
        assertEquals(
            listOf("https://api.anthropic.com/v1/models"),
            modelListUrls(TransportIds.ANTHROPIC_MESSAGES, "https://api.anthropic.com"),
        )
        assertEquals(
            listOf("https://api.anthropic.com/v1/models"),
            modelListUrls(TransportIds.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1"),
        )
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/models"),
            modelListUrls(TransportIds.GEMINI_GENERATE, "https://generativelanguage.googleapis.com"),
        )
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/models"),
            modelListUrls(TransportIds.GEMINI_GENERATE, "https://generativelanguage.googleapis.com/v1beta"),
        )
        assertTrue(modelListUrls(TransportIds.OPENAI_CHAT, "   ").isEmpty())
    }
}
