package dev.kair.app.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 端点像素上限是 [ProviderTransport] 的**能力位**（U23）：
 * 默认实现必须独立工作（旧插件不实现 = 未声明上限），具体上限只由端点实现声明。
 */
class ProviderTransportPixelLimitTest {

    @Test
    fun `a legacy transport without the capability declares no cap`() {
        val legacy = object : ProviderTransport {
            override val id = "legacy-transport"
            override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = emptyFlow()
        }
        assertNull("默认实现必须返回 null（不缩放）", legacy.maxImagePixels(config("openai")))
    }

    @Test
    fun `longcat declares its endpoint pixel cap`() {
        val transport = OpenAiProvider()
        assertEquals(
            "5242880 是 LongCat 端点属性",
            5_242_880L,
            transport.maxImagePixels(config("longcat")),
        )
        assertNull("其它 OpenAI 兼容端点不背 LongCat 的上限", transport.maxImagePixels(config("openai")))
    }

    /**
     * 上限是**端点**属性，判定必须看真正在调的那个端点。`providerId` 只是目录条目：
     * 「自定义」里填 LongCat 的 URL、或把预设条目的 baseUrl 改成别的地址，都是同一个端点
     * 却拿到不同答案 —— 后果是「内核以为没有上限」，大图逐字节原样发出去，真机继续 400。
     */
    @Test
    fun `the cap follows the endpoint actually being called`() {
        val transport = OpenAiProvider()
        assertEquals(
            "「自定义」条目指向 longcat 地址，同样是 longcat 端点",
            5_242_880L,
            transport.maxImagePixels(config("openai", baseUrl = "https://api.longcat.chat/openai/v1")),
        )
        assertEquals(
            "预设条目改了代理地址仍按 longcat 声明上限",
            5_242_880L,
            transport.maxImagePixels(config("longcat", baseUrl = "https://proxy.internal/v1")),
        )
    }

    /** 只看主机名，且必须是 longcat.chat 本身或其子域：路径里带字样、域名后缀拼接都不算。 */
    @Test
    fun `the endpoint match is anchored to the host name`() {
        val transport = OpenAiProvider()
        listOf(
            "https://api.longcat.chat.attacker.test/openai/v1",
            "https://notlongcat.chat/openai/v1",
            "https://example.test/longcat.chat/v1",
            "https://example.test/v1?key=api.longcat.chat",
        ).forEach { url ->
            assertNull("$url 不是 longcat 端点", transport.maxImagePixels(config("openai", baseUrl = url)))
        }
        assertEquals(
            "大小写与尾随斜杠不影响判定",
            5_242_880L,
            transport.maxImagePixels(config("openai", baseUrl = "https://API.LongCat.chat:443/openai/v1/")),
        )
    }

    private fun config(
        providerId: String,
        baseUrl: String = "https://example.test/v1",
    ) = ProviderConfig(
        providerId = providerId,
        transportId = TransportIds.OPENAI_CHAT,
        baseUrl = baseUrl,
        apiKey = "",
    )
}
