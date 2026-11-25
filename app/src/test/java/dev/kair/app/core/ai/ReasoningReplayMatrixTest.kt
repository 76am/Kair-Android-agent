package dev.kair.app.core.ai

import dev.kair.app.core.agent.RequestAssembler
import dev.kair.app.core.agent.SegmentKind
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考回传的**来源判据**矩阵（U76，任务文档 §3 五条判据的回归形态）。
 *
 * 修的是什么：判据原先只比 `model` 一个字符串，而同一个模型串可以挂在不同中转上
 * （Kair 里这些中转都记为 `providerId = "openai"` 的「自定义」条目）—— 上家的推理链
 * 会被当成自己的原文发出去（任务文档 §1 场景 5，唯一真污染路径）。
 *
 * 怎么验：**同一组 fixtures** 同时喂给六个面 —— 前缀摘要的 canonical / readable 两处，
 * 以及 OpenAI Chat / OpenAI Responses / Anthropic / Gemini 四个传输层的真实请求体，
 * 逐条与独立声明的期望对拍。不用「计数 = N」或名单当守卫：那类守卫拦不住
 * 「某处压根没接新判据」（U53R 实测）。任一接线漏掉，这里就有一条红。
 *
 * 判据 2 / 3 是**防误伤**守卫：新判据是旧判据的真子集（只收紧不放宽），
 * 所以它们在旧代码上必然绿，红读数来自判据 1 / 4 / 5 与空串兜底、落库来源。
 */
class ReasoningReplayMatrixTest {

    // ---- fixtures ----------------------------------------------------------

    /**
     * 一条 assistant 回合的来源与本发请求的目标端点，以及**独立声明**的期望。
     * 期望不是从实现推出来的，而是按「这是不是同一个端点产出」人工判定。
     */
    private data class Case(
        val label: String,
        val msgProvider: String?,
        val msgHost: String?,
        val msgModel: String?,
        val reqProvider: String?,
        val reqHost: String?,
        val reqModel: String = MODEL,
        val replay: Boolean,
    )

    /** 判据 2：同提供商 + 同主机 + 同模型 —— 正常多轮，必须仍带原文。 */
    private val sameOrigin = Case(
        "同源同模型", "openai", "relay-a.test", MODEL, "openai", "relay-a.test", replay = true,
    )

    /** 判据 1：同模型串、不同提供商。 */
    private val otherProvider = Case(
        "同模型串换提供商", "longcat", "api.longcat.chat", MODEL, "openai", "relay-a.test", replay = false,
    )

    /** 判据 1 的真污染路径（任务文档 §1 场景 5）：同模型串、同提供商 id、不同中转。 */
    private val otherHost = Case(
        "同模型串换中转", "openai", "relay-a.test", MODEL, "openai", "relay-b.test", replay = false,
    )

    /** 判据 3：同源换模型 —— 与今天一致，不带原文。 */
    private val otherModel = Case(
        "同源换模型", "openai", "relay-a.test", MODEL, "openai", "relay-a.test",
        reqModel = "another-model", replay = false,
    )

    /** 判据 4：老会话落盘时没有来源字段。 */
    private val legacyNoOrigin = Case(
        "老会话无来源", null, null, MODEL, "openai", "relay-a.test", replay = false,
    )

    /** 判据 4：来源只写了一半（主机名缺失）也不许猜。 */
    private val legacyPartialOrigin = Case(
        "来源只写了一半", "openai", null, MODEL, "openai", "relay-a.test", replay = false,
    )

    /** 判据 4：老会话连模型串都没有。 */
    private val legacyNoModel = Case(
        "老会话无模型", null, null, null, "openai", "relay-a.test", replay = false,
    )

    /** 本发请求未声明来源（如压缩摘要请求）：一律不回传。 */
    private val requestNoOrigin = Case(
        "请求未声明来源", "openai", "relay-a.test", MODEL, null, null, replay = false,
    )

    /** 官方 DeepSeek 同源：与中转条目同 providerId，靠主机名区分，仍应回传。 */
    private val officialDeepSeek = Case(
        "官方 DeepSeek 同源", "openai", "api.deepseek.com", "deepseek-chat",
        "openai", "api.deepseek.com", reqModel = "deepseek-chat", replay = true,
    )

    private val cases = listOf(
        sameOrigin, otherProvider, otherHost, otherModel,
        legacyNoOrigin, legacyPartialOrigin, legacyNoModel, requestNoOrigin, officialDeepSeek,
    )

    private fun assistant(c: Case) = ChatMessage(
        id = "u76-assistant",
        role = Role.ASSISTANT,
        parts = listOf(
            Part.Thinking(THINK_BODY, THINK_SIGNATURE),
            Part.Text("answer", "u76-text-sig"),
            Part.ToolCall("c1", "echo", "{}", "u76-call-sig"),
        ),
        model = c.msgModel,
        timestamp = 0L,
        providerId = c.msgProvider,
        providerHost = c.msgHost,
    )

    private fun request(c: Case) = ChatRequest(
        model = c.reqModel,
        systemPrompt = "",
        messages = listOf(assistant(c)),
        tools = emptyList(),
        providerId = c.reqProvider,
        providerHost = c.reqHost,
    )

    private fun config(c: Case) = ProviderConfig(
        providerId = c.reqProvider ?: "unset",
        transportId = TransportIds.OPENAI_CHAT,
        baseUrl = "https://${c.reqHost ?: "unset.test"}/v1",
        apiKey = "",
    )

    /**
     * 六个面各自「有没有把历史思考发出去」。
     *
     * Responses 回灌的是整个 reasoning item（正文不进请求），所以认加密内容标记；
     * 其余五面认思考正文标记。两个标记都只出现在思考里，不会与正文 / 签名串混淆。
     */
    private fun surfaces(c: Case): Map<String, Boolean> {
        val history = RequestAssembler.assemble(
            model = c.reqModel,
            systemPrompt = "",
            messages = listOf(assistant(c)),
            tools = emptyList(),
            providerId = c.reqProvider,
            providerHost = c.reqHost,
        ).segment(SegmentKind.HISTORY)!!
        val request = request(c)
        return mapOf(
            "canonical" to history.canonical.contains(THINK_BODY),
            "readable" to history.readable.contains(THINK_BODY),
            "openai-chat" to OpenAiProvider().buildBody(request, config(c)).toString().contains(THINK_BODY),
            "openai-responses" to OpenAiResponsesProvider().buildBody(request, config(c)).toString().contains(ENCRYPTED_MARKER),
            "anthropic" to AnthropicProvider().buildBody(request).toString().contains(THINK_BODY),
            "gemini" to GeminiProvider().buildBody(request).toString().contains(THINK_BODY),
        )
    }

    /** 判据 1–4 的主两面：前缀摘要 + OpenAI 兼容请求体（任务文档 §3 的字面要求）。 */
    private fun assertMainSurfaces(c: Case) {
        val s = surfaces(c)
        assertEquals("${c.label}：前缀摘要与期望不符", c.replay, s.getValue("canonical"))
        assertEquals("${c.label}：OpenAI 请求体与期望不符", c.replay, s.getValue("openai-chat"))
    }

    // ---- §3 五条判据 -------------------------------------------------------

    @Test
    fun `判据 1：同模型串换了端点，历史思考不回传`() {
        assertMainSurfaces(otherProvider)
        // 场景 5：providerId 相同、model 相同，只有中转不同 —— 只比 model 或只比 providerId 都拦不住。
        assertMainSurfaces(otherHost)
    }

    @Test
    fun `判据 2：同源同模型仍带原文，不误伤正常多轮`() {
        assertMainSurfaces(sameOrigin)
        assertMainSurfaces(officialDeepSeek)
    }

    @Test
    fun `判据 3：同源换模型与今天一致，不带原文`() {
        assertMainSurfaces(otherModel)
    }

    @Test
    fun `判据 4：来源缺失的老会话与未声明来源的请求都不回传且不崩`() {
        assertMainSurfaces(legacyNoOrigin)
        assertMainSurfaces(legacyPartialOrigin)
        assertMainSurfaces(legacyNoModel)
        assertMainSurfaces(requestNoOrigin)
    }

    @Test
    fun `判据 5：同一组 fixtures 下前缀摘要与四个传输层逐条同口径`() {
        for (c in cases) {
            val s = surfaces(c)
            assertEquals("${c.label}：应覆盖六个面", 6, s.size)
            for ((surface, actual) in s) {
                assertEquals("${c.label} / $surface 与声明的期望不符", c.replay, actual)
            }
            // 摘要与真实请求必须同口径：任一处漏接新判据，这条就红。
            assertEquals(
                "${c.label}：前缀摘要与 OpenAI 请求体口径不一致",
                s.getValue("canonical"),
                s.getValue("openai-chat"),
            )
        }
    }

    // ---- §2.2 空串兜底：显式表，默认不补 -----------------------------------

    /**
     * 缺 `reasoning_content` 会不会 400 是**端点属性**，不是「名字里有 deepseek」。
     * 实测（任务文档 §0）：`deepseek-flash` 与 `LongCat-2.5` 都不强制，所以默认不补。
     */
    @Test
    fun `空串兜底只认显式表里的 host 与 model`() {
        val expectations = listOf(
            Triple("https://api.deepseek.com/v1", "deepseek-chat", true),
            Triple("https://api.deepseek.com/v1", "deepseek-reasoner", true),
            // 同 host 的新模型：实测不强制 → 不补（旧的子串判定会补）。
            Triple("https://api.deepseek.com/v1", "deepseek-flash", false),
            // 中转上的同名模型：端点不是官方，不补。
            Triple("https://relay-a.test/v1", "deepseek-chat", false),
            Triple("https://api.longcat.chat/openai/v1", "LongCat-2.5", false),
            // host 精确匹配：后缀拼接不算官方端点。
            Triple("https://api.deepseek.com.evil.test/v1", "deepseek-chat", false),
            // 端口 / 路径 / 大小写都不改变端点身份（hostOf 归一化）。
            Triple("https://api.deepseek.com:443/v1/chat/completions", "deepseek-chat", true),
            Triple("https://API.DeepSeek.com/v1", "deepseek-chat", true),
        )
        for ((baseUrl, model, expected) in expectations) {
            val body = OpenAiProvider().buildBody(
                ChatRequest(model = model, systemPrompt = "", messages = listOf(noThinkingTurn()), tools = emptyList()),
                ProviderConfig(providerId = "openai", transportId = TransportIds.OPENAI_CHAT, baseUrl = baseUrl, apiKey = ""),
            )
            assertEquals("$baseUrl / $model：空串兜底与显式表不符", expected, body.hasEmptyReasoningContent())
        }
    }

    /** 没有思考的 assistant 回合（思考被关掉 / 老会话），带工具调用 —— 兜底唯一生效的形状。 */
    private fun noThinkingTurn() = ChatMessage(
        id = "u76-no-thinking",
        role = Role.ASSISTANT,
        parts = listOf(Part.Text("answer"), Part.ToolCall("c1", "echo", "{}")),
        model = null,
        timestamp = 0L,
    )

    private fun JsonObject.hasEmptyReasoningContent(): Boolean {
        val assistant = this["messages"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "assistant" }
        if (!assistant.containsKey("reasoning_content")) return false
        return assistant["reasoning_content"]!!.jsonPrimitive.content.isEmpty()
    }

    // ---- 落盘契约：老会话 .jsonl 缺字段 ------------------------------------

    /**
     * 老会话的 `.jsonl` 行里没有来源字段：必须能反序列化（默认值兜住）、思考本体保留，
     * 且按 foreign 处理不回传。这是已拍板的已知副作用，用用例钉住而不是靠注释。
     */
    @Test
    fun `老会话 jsonl 缺来源字段仍能读出且思考不回传`() {
        val legacyLine = """
            {"id":"m1","role":"assistant","parts":[{"type":"thinking","thinking":"$THINK_BODY","signature":"reasoning_content"},{"type":"text","text":"answer"}],"model":"$MODEL","timestamp":0}
        """.trimIndent()

        val decoded = Json.decodeFromString<ChatMessage>(legacyLine)
        assertNull("老会话不应凭空得到 providerId", decoded.providerId)
        assertNull("老会话不应凭空得到 providerHost", decoded.providerHost)
        assertEquals("思考本体必须保留（只是不回传）", THINK_BODY, decoded.thinking)

        val request = ChatRequest(
            model = MODEL,
            systemPrompt = "",
            messages = listOf(decoded),
            tools = emptyList(),
            providerId = "openai",
            providerHost = "relay-a.test",
        )
        assertFalse(
            "老会话的思考不得回传",
            OpenAiProvider().buildBody(request, config(sameOrigin)).toString().contains(THINK_BODY),
        )
        // 往返一致：读出来再写回去，语义不变（encodeDefaults 口径）。
        assertEquals(decoded, Json.decodeFromString<ChatMessage>(Json.encodeToString(decoded)))
    }

    @Test
    fun `新会话落盘写来源，无来源时不写 null 字段`() {
        val withOrigin = assistant(sameOrigin)
        val text = Json.encodeToString(withOrigin)
        assertTrue("落盘应带 providerId：$text", text.contains("\"providerId\":\"openai\""))
        assertTrue("落盘应带归一化主机名：$text", text.contains("\"providerHost\":\"relay-a.test\""))
        assertEquals(withOrigin, Json.decodeFromString<ChatMessage>(text))

        // explicitNulls = false：老形状的消息不会多出两个 null 字段（字节与旧版一致）。
        val legacyText = Json.encodeToString(noThinkingTurn())
        assertFalse("无来源时不应写出 providerId：$legacyText", legacyText.contains("providerId"))
        assertFalse("无来源时不应写出 providerHost：$legacyText", legacyText.contains("providerHost"))
    }

    private companion object {
        const val MODEL = "shared-model"
        const val THINK_BODY = "U76-THINKING-BODY"
        const val ENCRYPTED_MARKER = "U76-ENCRYPTED-REASONING"

        /**
         * 一份签名同时满足三家的回传条件：Anthropic 只要求非空；Responses 要能解析成
         * `type = reasoning` 的 item（含 `encrypted_content`）；OpenAI Chat 不认它
         * （不是字段名）→ 回落 `reasoning_content`。Gemini 原样当 `thoughtSignature`。
         */
        const val THINK_SIGNATURE =
            """{"type":"reasoning","id":"rs_u76","encrypted_content":"U76-ENCRYPTED-REASONING","summary":[]}"""
    }
}
