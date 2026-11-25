package dev.kair.app.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求捕获的边界：按会话隔离、只留最近 [RequestCapture.MAX_ENTRIES] 条、图片默认脱敏、
 * 超限截断要如实标注。这些都是「导出的文件能不能当证据」的直接前提。
 */
class RequestCaptureTest {

    private fun sample(
        sessionId: String?,
        body: String = """{"model":"m","messages":[]}""",
        status: Int? = 400,
        response: String? = "bad request",
    ) = CapturedRequest(
        sessionId = sessionId,
        at = 1_700_000_000_000,
        url = "https://example.test/v1/chat/completions",
        body = body,
        statusCode = status,
        responseBody = response,
        imagesKept = RequestCapture.hasImageData(body),
        truncated = false,
    )

    @After
    fun tearDown() {
        RequestCapture.clear("s")
        RequestCapture.clear("other")
        RequestCapture.clear("")
        RequestCapture.clear("sub")
    }

    @Test
    fun `keeps only the latest requests per session`() {
        repeat(7) { i ->
            RequestCapture.record(sample("s", body = """{"n":$i}"""))
        }
        val kept = RequestCapture.entries("s")
        assertEquals(RequestCapture.MAX_ENTRIES, kept.size)
        // 最近 2 条无条件保留（n5、n6），再加更早的 3 条失败（n2、n3、n4）。
        assertEquals("""{"n":2}""", kept.first().body)
        assertEquals("""{"n":6}""", kept.last().body)
        assertEquals("""{"n":6}""", RequestCapture.latest("s")?.body)
    }

    @Test
    fun `only the two latest successes are kept`() {
        repeat(5) { i ->
            RequestCapture.record(sample("s", body = """{"n":$i}""", status = 200, response = null))
        }
        val kept = RequestCapture.entries("s")
        assertEquals(RequestCapture.LATEST_ALWAYS, kept.size)
        assertEquals(listOf("""{"n":3}""", """{"n":4}"""), kept.map { it.body })
    }

    @Test
    fun `older failures survive the latest window`() {
        RequestCapture.record(sample("s", body = """{"n":0}"""))
        RequestCapture.record(sample("s", body = """{"n":1}""", status = 500))
        RequestCapture.record(sample("s", body = """{"n":2}"""))
        RequestCapture.record(sample("s", body = """{"n":3}""", status = 200, response = null))
        val kept = RequestCapture.entries("s")
        // 最近 2 条（n2、n3）无条件保留，更早的两条失败（n0、n1）也还在。
        assertEquals(
            listOf("""{"n":0}""", """{"n":1}""", """{"n":2}""", """{"n":3}"""),
            kept.map { it.body },
        )
    }

    @Test
    fun `failures beyond the latest window are capped`() {
        repeat(6) { i ->
            RequestCapture.record(sample("s", body = """{"n":$i}"""))
        }
        val kept = RequestCapture.entries("s")
        // n4、n5 是最新窗口，n1、n2、n3 是额外保留的 3 条失败；n0 被挤出。
        assertEquals(RequestCapture.MAX_ENTRIES, kept.size)
        assertEquals("""{"n":1}""", kept.first().body)
    }

    @Test
    fun `request without a response counts as a failure`() {
        RequestCapture.record(
            sample("s", body = """{"n":0}""", status = null, response = "IOException: timeout"),
        )
        RequestCapture.record(sample("s", body = """{"n":1}""", status = 200, response = null))
        RequestCapture.record(sample("s", body = """{"n":2}""", status = 200, response = null))
        val kept = RequestCapture.entries("s")
        assertEquals(listOf("""{"n":0}""", """{"n":1}""", """{"n":2}"""), kept.map { it.body })
        assertTrue("没拿到响应也算失败，必须留作证据", kept.first().failed)
        assertFalse(kept.last().failed)
    }

    @Test
    fun `sessions do not leak into each other`() {
        RequestCapture.record(sample("s", body = """{"owner":"s"}"""))
        assertEquals("""{"owner":"s"}""", RequestCapture.latest("s")?.body)
        // 没有捕获过请求的会话拿到 null，不跨会话兜底。
        assertEquals(null, RequestCapture.latest("other"))
    }

    @Test
    fun `clear drops the session buffer`() {
        RequestCapture.record(sample("s"))
        RequestCapture.clear("s")
        assertEquals(null, RequestCapture.latest("s"))
    }

    @Test
    fun `export redacts image payloads by default`() {
        val base64 = "A".repeat(2048)
        val body = """{"messages":[{"content":"data:image/png;base64,$base64"}]}"""
        val json = sample("s", body = body).toExportJson(includeImages = false)
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertFalse("默认导出不得带原图", json.contains(base64))
        assertTrue("要标出脱敏过", parsed["meta"]?.jsonObject?.get("imagesRedacted")?.jsonPrimitive?.content == "true")
        assertTrue(parsed["request"].toString().contains("base64 2048 chars"))
    }

    /**
     * U32：1M token 上下文量级的请求体现在必须能被捕获到。
     *
     * 改小 [RequestCapture.MAX_CAPTURE_BYTES] 会让这类请求「完全不被捕获」（拦截器直接跳过
     * 序列化，面板里只剩「未捕获：体积过大」），等于查不了。`bodySkipped` 是拦截器
     * （`HttpClient.CaptureInterceptor`）构造的、`record` 不改它，所以这里直接钉常量下界：
     * 门不得低于 1M token 请求体的典型体积。
     */
    @Test
    fun `capture gates admit a 1M token scale request body`() {
        // 1M token 的中文请求体：中文约 1 token = 3 字符，即约 3M 字符 / 9 MB 字节。
        // 英文更省（约 4 字符 1 token = 4M 字节）。取更紧的英文口径做下界。
        val englishBytes = 4_000_000
        assertTrue("捕获门不得低于 1M token 请求体的典型体积", RequestCapture.MAX_CAPTURE_BYTES >= englishBytes)
        assertTrue("单条上限不得低于捕获门，否则捕获了也会被截断", RequestCapture.MAX_BODY_CHARS >= englishBytes)
        assertTrue("全局预算不得低于单条上限，否则满额的一条也装不下", RequestCapture.MAX_TOTAL_CHARS >= RequestCapture.MAX_BODY_CHARS)
    }

    @Test
    fun `oversized body is truncated and marked`() {
        val huge = """{"pad":"${"x".repeat(RequestCapture.MAX_BODY_CHARS + 10)}"}"""
        RequestCapture.record(sample("s", body = huge))
        val latest = RequestCapture.latest("s")!!
        assertTrue("必须标注截断", latest.truncated)
        assertFalse("截断后不得再声称保留原图", latest.imagesKept)
        assertTrue(latest.body.length <= RequestCapture.MAX_BODY_CHARS)
    }

    @Test
    fun `truncated error response is marked in export`() {
        val truncated = sample("s").copy(responseTruncated = true).toExportJson(includeImages = false)
        val meta = Json.parseToJsonElement(truncated).jsonObject["meta"]?.jsonObject
        assertEquals("true", meta?.get("responseTruncated")?.jsonPrimitive?.content)

        // 没截断时也明确写 false，避免阅读者猜「字段缺失是不是代表被截了」。
        val normal = Json.parseToJsonElement(sample("s").toExportJson(false)).jsonObject["meta"]?.jsonObject
        assertEquals("false", normal?.get("responseTruncated")?.jsonPrimitive?.content)
    }

    @Test
    fun `broken request body still exports as readable text`() {
        // 半截请求体（模型输出被掐断 / 截断）不能让导出文件变成无效 JSON。
        val json = sample("s", body = """{"messages":[{"role":"user" """).toExportJson(includeImages = false)
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertTrue(parsed["request"].toString().contains("raw"))
        assertEquals("bad request", parsed["response"]?.jsonObject?.get("body")?.jsonPrimitive?.content)
    }

    @Test
    fun `query credentials are masked before storage`() {
        // 自建中转常把 key 放在 query：界面与导出文件都只能看到参数名。
        RequestCapture.record(
            sample("s").copy(url = "https://relay.test/v1/chat/completions?key=sk-secret&trace=1"),
        )
        val latest = RequestCapture.latest("s")!!
        assertFalse("不得保留密钥", latest.url.contains("sk-secret"))
        val parsedUrl = latest.url.toHttpUrl()
        // 凭据参数被替换成占位，非凭据参数是诊断信息，必须原样保留。
        assertEquals("***", parsedUrl.queryParameter("key"))
        assertEquals("1", parsedUrl.queryParameter("trace"))
        // 导出文件里同样不能出现原文。
        assertFalse(latest.toExportJson(includeImages = false).contains("sk-secret"))
    }

    @Test
    fun `diagnostic query params survive redaction`() {
        // Gemini 的 `alt=sse` 正是排查端点拒收时最该看的一项，不能一起抹掉。
        RequestCapture.record(
            sample("s").copy(url = "https://gen.test/v1beta/models/gemini:streamGenerateContent?alt=sse"),
        )
        assertEquals(
            "https://gen.test/v1beta/models/gemini:streamGenerateContent?alt=sse",
            RequestCapture.latest("s")!!.url,
        )
        // 大小写不敏感的凭据参数名同样要脱敏。
        RequestCapture.record(sample("s").copy(url = "https://gen.test/x?API_KEY=k1&alt=sse"))
        val url = RequestCapture.latest("s")!!.url.toHttpUrl()
        assertEquals("***", url.queryParameter("API_KEY"))
        assertEquals("sse", url.queryParameter("alt"))
    }

    @Test
    fun `skipped body is marked in export`() {
        val skipped = sample("s").copy(body = "", bodySkipped = true).toExportJson(includeImages = false)
        val meta = Json.parseToJsonElement(skipped).jsonObject["meta"]?.jsonObject
        assertEquals("true", meta?.get("bodySkipped")?.jsonPrimitive?.content)
        // 正常捕获时显式写 false，不让「字段缺失」被误读。
        val normal = Json.parseToJsonElement(sample("s").toExportJson(false)).jsonObject["meta"]?.jsonObject
        assertEquals("false", normal?.get("bodySkipped")?.jsonPrimitive?.content)
    }

    @Test
    fun `a stream failure after 200 is a failure and is exported`() {
        // 真机：HTTP 200 之后 SSE 空流结束（空响应），状态码本身看不出问题（U61）。
        RequestCapture.record(sample("s", body = """{"n":0}""", status = 200, response = null))
        RequestCapture.markStreamFailure("s", 1_700_000_000_000, "服务端返回了空响应")

        val entry = RequestCapture.latest("s")!!
        assertTrue("200 + 空流必须算失败", entry.failed)
        assertEquals("状态码仍是事实", 200, entry.statusCode)
        val meta = Json.parseToJsonElement(entry.toExportJson(includeImages = false))
            .jsonObject["meta"]?.jsonObject
        assertEquals("true", meta?.get("failed")?.jsonPrimitive?.content)
        assertEquals("200", meta?.get("status")?.jsonPrimitive?.content)
        assertEquals("服务端返回了空响应", meta?.get("streamFailure")?.jsonPrimitive?.content)
    }

    @Test
    fun `a stream failure does not mark a request from an earlier attempt`() {
        // 本地异常（根本没发请求）时 sinceMillis 晚于最新一条 → 不能把上一发成功请求标成失败。
        RequestCapture.record(sample("s", status = 200, response = null))
        RequestCapture.markStreamFailure("s", 1_700_000_001_000, "本地异常")
        assertFalse(RequestCapture.latest("s")!!.failed)
    }

    @Test
    fun `a stream failure is ignored for non-2xx requests`() {
        // 非 2xx 已经由状态码判失败，不需要也不应该再写流结果。
        RequestCapture.record(sample("s", status = 400))
        RequestCapture.markStreamFailure("s", 1_700_000_000_000, "x")
        assertEquals(null, RequestCapture.latest("s")!!.streamFailure)
    }

    @Test
    fun `subagent keeps only its two latest failures`() {
        RequestCapture.markSubagent("sub")
        RequestCapture.record(sample("sub", body = """{"n":0}""", status = 200, response = null))
        repeat(4) { i ->
            RequestCapture.record(sample("sub", body = """{"n":${i + 1}}"""))
        }
        val kept = RequestCapture.entries("sub")
        // 成功的 n0 一律不留；失败从 n1..n4，只留最后两条。
        assertEquals(RequestCapture.SUBAGENT_MAX_FAILURES, kept.size)
        assertEquals(listOf("""{"n":3}""", """{"n":4}"""), kept.map { it.body })
    }
}
