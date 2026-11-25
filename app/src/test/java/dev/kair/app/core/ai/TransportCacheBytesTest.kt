package dev.kair.app.core.ai

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **传输层**的缓存字节回归：同一逻辑状态必须序列化成同一份字节。
 *
 * 项目最重要的不变量是「缓存稳定前缀」，此前只在装配器层（`RequestAssemblerTest` 的
 * `canonical` 摘要）有断言，传输层只测了字段取值 —— 也就是说 provider 里多插一个
 * 随机 id、依赖 `HashMap` 迭代序、或把参数提到前缀里，测试都不会红。
 *
 * 这里锁两件事：
 * 1. **确定性**：同一请求构造两次，字节完全相同（`ChatMessage.id` / `timestamp` 是随机的，
 *    只要有任何 provider 把它们写进请求体，这条就会红）。
 * 2. **只追加**：历史末尾追加一轮后，前 N 条消息的序列化字节逐字节不变 —— 这正是缓存
 *    前缀能命中的前提。
 */
class TransportCacheBytesTest {

    private val config = ProviderConfig(
        providerId = "test",
        transportId = TransportIds.OPENAI_CHAT,
        baseUrl = "https://example.test/v1",
        apiKey = "",
    )

    private fun user(text: String) = ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)))

    private fun assistant(text: String) =
        ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text(text)), model = "test-model")

    private fun history() = listOf(
        user("第一轮问题"),
        assistant("第一轮回答"),
        user("第二轮问题"),
        assistant("第二轮回答"),
    )

    private fun request(
        messages: List<ChatMessage>,
        tools: List<LlmToolSpec> = emptyList(),
    ) = ChatRequest(
        model = "test-model",
        systemPrompt = "SYSTEM PROMPT",
        messages = messages,
        tools = tools,
        options = SamplingOptions(temperature = 0.3, maxTokens = 1024),
        sessionId = "session-1",
    )

    /** 四个传输层各自的请求体；新增 provider 时在这里补一行，否则不变量不被覆盖。 */
    private fun bodies(messages: List<ChatMessage>): Map<String, JsonObject> = mapOf(
        "openai-chat" to OpenAiProvider().buildBody(request(messages), config),
        "openai-responses" to OpenAiResponsesProvider().buildBody(request(messages), config),
        "anthropic" to AnthropicProvider().buildBody(request(messages)),
        "gemini" to GeminiProvider().buildBody(request(messages)),
    )

    @Test
    fun `same logical state serializes to identical bytes`() {
        val first = bodies(history())
        val second = bodies(history())

        for ((name, body) in first) {
            assertEquals("$name 两次构造的请求体不一致", body.toString(), second.getValue(name).toString())
        }
    }

    @Test
    fun `appending a turn leaves the previous messages byte identical`() {
        val before = bodies(history())
        val after = bodies(history() + user("第三轮问题"))

        for ((name, body) in before) {
            val old = body.messagesArray(name)
            val new = after.getValue(name).messagesArray(name)
            assertTrue("$name：追加后消息数不应减少", new.size > old.size)
            for (i in old.indices) {
                // Anthropic 会把 `cache_control` 断点打在**最后一条**消息的最后一个 block 上，
                // 追加一轮就移到新消息 —— 这是协议要求的缓存标记（镜像 pi 的做法），
                // 不属于前缀正文，所以比较前剔除。其它 provider 不受影响。
                assertEquals(
                    "$name：第 $i 条消息在追加后变了（缓存前缀被破坏）",
                    stripCacheControl(old[i]).toString(),
                    stripCacheControl(new[i]).toString(),
                )
            }
        }
    }

    @Test
    fun `anthropic marks cache breakpoints on system, last tool and last message`() {
        // 把断点位置钉死：断点少了缓存命中率降，多了超过 4 个上限会被接口拒绝。
        val tools = listOf(
            LlmToolSpec("read", "read a file", buildJsonObject { put("type", "object") }),
            LlmToolSpec("write", "write a file", buildJsonObject { put("type", "object") }),
        )
        val body = AnthropicProvider().buildBody(request(history(), tools))
        val text = body.toString()

        assertEquals("预期 3 个断点：system + 最后一个 tool + 最后一条消息", 3, text.split("\"cache_control\"").size - 1)
        assertTrue("system 段未打断点：$text", body["system"].toString().contains("cache_control"))
        // 只给最后一个 tool 打断点；两个都打得白白多占一个断点名额。
        val toolText = body["tools"].toString()
        assertEquals("只应有最后一个 tool 带断点：$toolText", 1, toolText.split("\"cache_control\"").size - 1)
        val last = body.messagesArray("anthropic").last().toString()
        assertTrue("最后一条消息未打断点：$last", last.contains("cache_control"))
    }

    @Test
    fun `system prompt is not duplicated into every turn`() {
        // 系统提示进前缀一次即可；每轮重复会让每追加一轮就整体偏移，缓存永远打不中。
        for ((name, body) in bodies(history())) {
            val occurrences = body.toString().split("SYSTEM PROMPT").size - 1
            assertEquals("$name：系统提示在请求体里出现了 $occurrences 次", 1, occurrences)
        }
    }

    /** 递归剔除 `cache_control` 等协议层面的缓存标记，只留下真正进模型的正文。 */
    private fun stripCacheControl(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.filterKeys { it != "cache_control" }
                    .mapValues { stripCacheControl(it.value) },
            )
            is JsonArray -> JsonArray(element.map { stripCacheControl(it) })
            else -> element
        }

    /**
     * 取请求体里的「按轮次的消息数组」。
     *
     * 四个传输层字段名不同（OpenAI `messages` / Anthropic `messages` / Gemini
     * `contents` / Responses `input`），而且有的会把 system 摆在数组首位 —— 断言只看
     * 前 N 条的字节，多一条 system 也不影响。
     */
    private fun JsonObject.messagesArray(provider: String): JsonArray {
        val key = when (provider) {
            "gemini" -> "contents"
            "openai-responses" -> "input"
            else -> "messages"
        }
        val array = this[key]?.jsonArray
            ?: error("$provider 的请求体里没有 `$key` 数组：$this")
        // Responses 的 input 里还会混入工具等条目；这里只取纯消息条目比较前缀。
        return JsonArray(array.filter { it is JsonObject && it.containsKey("role") || it is JsonObject && it.containsKey("content") })
    }
}
