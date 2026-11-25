package dev.kair.app.core.ai

import dev.kair.app.core.model.cacheHitRate
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** OpenAI 兼容 usage 的解析：命中 token 必须从 input 里拆出来，命中率才不会恒为 0。 */
class OpenAiUsageTest {

    @Test
    fun deepseekCacheHitTokensAreSplitOutOfInput() {
        val usage = parseOpenAiUsage(
            buildJsonObject {
                put("prompt_tokens", 1000)
                put("completion_tokens", 20)
                put("prompt_cache_hit_tokens", 900)
                put("prompt_cache_miss_tokens", 100)
            },
        )
        assertEquals(100, usage.input)
        assertEquals(20, usage.output)
        assertEquals(900, usage.cacheRead)
        assertEquals(0.9, usage.cacheHitRate!!, 1e-9)
    }

    @Test
    fun openAiCachedTokensUnderDetailsAreParsed() {
        val usage = parseOpenAiUsage(
            buildJsonObject {
                put("prompt_tokens", 2006)
                put("completion_tokens", 5)
                putJsonObject("prompt_tokens_details") { put("cached_tokens", 1920) }
            },
        )
        assertEquals(86, usage.input)
        assertEquals(1920, usage.cacheRead)
        assertEquals(1920.0 / 2006.0, usage.cacheHitRate!!, 1e-9)
    }

    @Test
    fun noCacheFieldsLeavesInputIntact() {
        val usage = parseOpenAiUsage(
            buildJsonObject {
                put("prompt_tokens", 500)
                put("completion_tokens", 12)
            },
        )
        assertEquals(500, usage.input)
        assertEquals(0, usage.cacheRead)
    }

    @Test
    fun cachedTokensAreClampedToPromptTokens() {
        val usage = parseOpenAiUsage(
            buildJsonObject {
                put("prompt_tokens", 100)
                put("prompt_cache_hit_tokens", 999)
            },
        )
        assertEquals(100, usage.cacheRead)
        assertEquals(0, usage.input)
    }
}
