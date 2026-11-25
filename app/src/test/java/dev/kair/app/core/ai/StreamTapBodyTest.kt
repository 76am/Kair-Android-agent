package dev.kair.app.core.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2xx 流式响应旁路采样：成功响应不能提前读（会打断 SSE），只能在消费端顺手留一份。
 * 没有这份原文，「200 + 空正文」到底是模型没吐还是网关吞了，导出里就分不清。
 */
class StreamTapBodyTest {

    private val session = "stream-tap"

    @After
    fun tearDown() = RequestCapture.clear(session)

    private fun record200() {
        RequestCapture.record(
            CapturedRequest(
                sessionId = session,
                at = 1_700_000_000_000,
                url = "https://example.test/v1/chat/completions",
                body = """{"model":"m","messages":[]}""",
                statusCode = 200,
                responseBody = null,
                imagesKept = false,
                truncated = false,
            ),
        )
    }

    @Test
    fun `streamed body is captured untruncated`() {
        record200()
        val payload = "data: {\"delta\":\"hi\"}\n\n"
        val body = StreamTapBody(payload.toResponseBody("text/event-stream".toMediaType()), session)

        val read = body.source().use { it.readUtf8() }

        assertEquals("采样不得改动下游拿到的字节", payload, read)
        val entry = RequestCapture.latest(session)!!
        assertEquals(payload, entry.streamBody)
        assertFalse(entry.streamBodyTruncated)
    }

    @Test
    fun `oversized stream keeps the head and marks truncation`() {
        record200()
        val payload = "x".repeat(RequestCapture.MAX_RESPONSE_BYTES + 500)
        val body = StreamTapBody(payload.toResponseBody("text/event-stream".toMediaType()), session)

        body.source().use { it.readUtf8() }

        val entry = RequestCapture.latest(session)!!
        assertEquals(RequestCapture.MAX_RESPONSE_BYTES, entry.streamBody!!.length)
        assertTrue("超过上限必须如实标注", entry.streamBodyTruncated)
    }

    @Test
    fun `export falls back to the streamed body`() {
        record200()
        val payload = "data: {\"choices\":[]}\n\n"
        val body = StreamTapBody(payload.toResponseBody("text/event-stream".toMediaType()), session)

        body.source().use { it.readUtf8() }

        val json = RequestCapture.latest(session)!!.toExportJson(includeImages = false)
        assertTrue("导出应带上 2xx 流式原文", json.contains("choices"))
    }
}
