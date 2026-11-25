package dev.kair.app.core.ai

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 错误解析：各厂商错误体形状不同，但最终都要归到一个可判定「是否重试」的分类，
 * 并给用户一句中文说明。
 */
class ErrorParserTest {

    @Test
    fun `extracts the detail from openai anthropic and google shapes`() {
        assertEquals(
            "Incorrect API key provided",
            parseErrorDetail("""{"error":{"message":"Incorrect API key provided","type":"authentication_error"}}"""),
        )
        assertEquals(
            "Overloaded",
            parseErrorDetail("""{"error":{"type":"overloaded_error","message":"Overloaded"}}"""),
        )
        assertEquals(
            "Quota exceeded",
            parseErrorDetail("""{"error":{"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""),
        )
    }

    @Test
    fun `handles relay shapes and plain text`() {
        assertEquals("bad request", parseErrorDetail("""{"message":"bad request"}"""))
        assertEquals("first", parseErrorDetail("""[{"message":"first"},{"message":"second"}]"""))
        assertEquals("upstream timeout", parseErrorDetail("upstream timeout"))
        assertEquals("", parseErrorDetail(null as JsonElement?))
    }

    @Test
    fun `classifies by status and body markers`() {
        assertEquals(ErrorClassification.AUTH, classifyError("invalid api key", 401))
        assertEquals(ErrorClassification.PARAM, classifyError("bad payload", 400))
        assertEquals(ErrorClassification.RATE_LIMIT, classifyError("slow down", 429))
        assertEquals(ErrorClassification.TRANSIENT_HTTP, classifyError("conflict", 409))
        assertEquals(ErrorClassification.SERVER_ERROR, classifyError("boom", 503))
        assertEquals(ErrorClassification.OVERLOADED, classifyError("Overloaded", null))
        assertEquals(ErrorClassification.CAPACITY, classifyError("insufficient capacity", null))
        assertEquals(ErrorClassification.TIMEOUT, classifyError("read timed out", null))
        assertEquals(ErrorClassification.UNKNOWN, classifyError("connection reset by peer", null))
    }

    @Test
    fun `quota exhaustion stays non retryable even behind 429`() {
        val detail = "insufficient_quota"
        assertEquals(ErrorClassification.QUOTA, classifyError(detail, 429))
        assertFalse(classifyError(detail, 429).transient)
        assertFalse(StreamEvent.Failure("HTTP 429: $detail").isTransient())
    }

    @Test
    fun `shows status and raw server text without classification`() {
        assertEquals("HTTP 401\ninvalid api key", describeError("invalid api key", 401))

        // 传输层已拼好 `HTTP {code}: {body}` 时原样展示，不重复加状态码行。
        val longcat =
            """HTTP 402: {"error":{"message":"Insufficient Balance","type":"insufficient_balance_error"}}"""
        assertEquals(longcat, describeError(longcat, 402))

        // 没有状态码的错误：服务端 / 链路卡住的文案本身已完整，原样展示；空文本给中文兜底。
        assertEquals("connection reset by peer", describeError("connection reset by peer", null))
        assertEquals("HTTP 503\n未知错误", describeError("  ", 503))
    }

    @Test
    fun `local transport exceptions are described in chinese by type`() {
        // 真机显示过 `Software caused connection abort`：本地 socket 异常不能原样丢给用户。
        val abort = IOException("Software caused connection abort")
        val text = localFailureMessage(abort)
        assertTrue(text.startsWith("连接被中断"))
        // 原文保留在括号里供排查，但不能是「用户看到的第一句话」。
        assertTrue(text.contains("Software caused connection abort"))

        assertEquals("请求超时（timeout）", localFailureMessage(SocketTimeoutException("timeout")))
        assertEquals("域名解析失败（unable to resolve host）", localFailureMessage(UnknownHostException("unable to resolve host")))
        assertEquals("TLS 握手失败（handshake failed）", localFailureMessage(SSLHandshakeException("handshake failed")))
        assertEquals("连接被拒绝（failed to connect）", localFailureMessage(ConnectException("failed to connect")))
        // 非 IO 的意外异常是内部错误，不许说成「网络异常」把排查方向带偏。
        assertEquals("内部错误", localFailureMessage(RuntimeException()))
        assertEquals("内部错误（assert failed）", localFailureMessage(IllegalStateException("assert failed")))
        assertEquals("连接被中断", localFailureMessage(IOException()))
    }

    @Test
    fun `long server text is capped and points to request export`() {
        val detail = "x".repeat(1_000)
        val text = describeError(detail, 400)
        assertTrue(text.startsWith("HTTP 400\nxxxx"))
        // 500 字符上限 + 截断提示；完整原文在「请求诊断」的导出里。
        assertTrue(text.length < 600)
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("请求诊断"))

        // 正常长度的错误（含 402 余额不足）保持完整。
        val quota =
            """HTTP 429: {"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota"}}"""
        assertEquals(quota, describeError(quota, 429))
    }

    @Test
    fun `http 402 balance errors show the server wording`() {
        // 402 曾被归类成「请求参数有误」，实际是余额不足：展示层不再翻译。
        val raw =
            """HTTP 402: {"error":{"message":"Insufficient Balance (request_id: 9c3eaa48)","type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"""
        val text = describeError(raw, 402)
        assertEquals(raw, text)
        assertFalse(text.contains("请求参数有误"))
        assertTrue(text.contains("Insufficient Balance"))
    }

    @Test
    fun `status code is read from transport formatted messages`() {
        assertEquals(503, errorStatusCode("HTTP 503: upstream busy"))
        assertEquals(null, errorStatusCode("Overloaded"))
        assertEquals(null, errorStatusCode("HTTP error"))
    }

    @Test
    fun `json input is parsed before classification`() {
        val failure = StreamEvent.Failure(
            """HTTP 429: {"error":{"message":"insufficient_quota","type":"insufficient_quota"}}""",
        )
        assertFalse(failure.isTransient())
        val detail = parseErrorDetail(failure.message)
        assertTrue(detail.contains("insufficient_quota"))
    }

    @Test
    fun `real longcat auth error is classified as non retryable auth`() {
        val failure = loadTrace("stream-traces/longcat/auth-error.jsonl").single() as StreamEvent.Failure
        assertFalse(failure.isTransient())
        val detail = parseErrorDetail(failure.message)
        val status = errorStatusCode(failure.message)
        assertEquals(401, status)
        assertEquals(ErrorClassification.AUTH, classifyError(detail, status))
        assertTrue(describeError(detail, status).contains("HTTP 401"))
    }
}
