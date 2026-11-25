package dev.kair.app.core.ai

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared JSON configuration for all wire formats. */
val Json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 标记「这条请求来自某个 Agent 会话」。[CaptureTag] 让拦截器只抓 Agent 请求
 * （模型探测、插件自发的请求不抓），并与会话对上号，导出时不会串会话。
 */
internal class CaptureTag(val sessionId: String?)

/**
 * 请求捕获拦截器：抓**真正发出去**的字节。
 *
 * 放在共用的 OkHttp 客户端上，而不是每个传输层里各写一遍 —— 一个概念只有一个实现点，
 * 之后新增传输层自动获得同类诊断能力。成功响应**不许**提前读 body（会打断 SSE）：
 * 非 2xx 才 peek 一段错误原文；2xx 的流式响应则交给 [StreamTapBody] 在消费端旁路采样。
 */
private class CaptureInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tag = request.tag(CaptureTag::class.java) ?: return chain.proceed(request)
        // 大请求体（多图）先看声明长度：序列化那一瞬间会再复制一份整串，
        // 接近上限时正是最容易被顶到 OOM 的时刻。这类请求只留元数据。
        val bodyBytes = request.body?.contentLength() ?: -1L
        val skipped = bodyBytes > RequestCapture.MAX_CAPTURE_BYTES
        val body = if (skipped) {
            ""
        } else {
            runCatching {
                request.body?.let { payload -> Buffer().also { payload.writeTo(it) }.readUtf8() }
            }.getOrNull().orEmpty()
        }
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            // 用户主动停止时传输层调 call.cancel()，OkHttp 抛 IOException("Canceled")。
            // 那是用户意图、不含任何服务端信息，记下来只会把真正的失败挤出缓冲。
            if (e is IOException && e.message == "Canceled") throw e
            // 连不上 / 超时：这次请求根本没拿到响应。它同样是排查对象，
            // 状态记为 null（界面上显示「未收到响应」），异常摘要留给导出文件。
            record(request, tag, body, statusCode = null, responseBody = "${e.javaClass.simpleName}: ${e.message}", bodySkipped = skipped)
            throw e
        }
        var errorBody: String? = null
        var errorTruncated = false
        if (!response.isSuccessful) {
            // 多取 1 字节：能读到就说明响应体超过上限，导出的 meta 里要如实标注。
            runCatching {
                val peeked = response.peekBody((RequestCapture.MAX_RESPONSE_BYTES + 1).toLong()).bytes()
                errorTruncated = peeked.size > RequestCapture.MAX_RESPONSE_BYTES
                errorBody = String(
                    if (errorTruncated) peeked.copyOf(RequestCapture.MAX_RESPONSE_BYTES) else peeked,
                    Charsets.UTF_8,
                )
            }
        }
        record(request, tag, body, response.code, errorBody, errorTruncated, skipped)
        // 成功响应不在这里读：换成旁路采样的 body，由读取方（SSE 解析）顺带留一份原文。
        val okBody = response.body
        if (!response.isSuccessful || okBody == null) return response
        return response.newBuilder().body(StreamTapBody(okBody, tag.sessionId)).build()
    }

    private fun record(
        request: Request,
        tag: CaptureTag,
        body: String,
        statusCode: Int?,
        responseBody: String?,
        responseTruncated: Boolean = false,
        bodySkipped: Boolean = false,
    ) {
        RequestCapture.record(
            CapturedRequest(
                sessionId = tag.sessionId,
                at = System.currentTimeMillis(),
                url = request.url.toString(),
                body = body,
                statusCode = statusCode,
                responseBody = responseBody,
                imagesKept = RequestCapture.hasImageData(body),
                truncated = false,
                responseTruncated = responseTruncated,
                bodySkipped = bodySkipped,
            ),
        )
    }
}

/**
 * 2xx 响应体的旁路采样：读取时顺手留一份前 [RequestCapture.MAX_RESPONSE_BYTES] 字节，
 * 流结束 / 关闭时回填到这一发请求的诊断里。
 *
 * 为什么不能像失败响应那样 `peekBody`：SSE 是一边到一边消费的活流，提前读会卡住生成。
 * 这里只脱水不截流 —— 读多少复制多少，下游拿到的字节与原始响应完全一致。
 */
internal class StreamTapBody(
    private val delegate: ResponseBody,
    private val sessionId: String?,
) : ResponseBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source(): BufferedSource = TapSource(delegate.source(), sessionId).buffer()

    private class TapSource(
        private val upstream: Source,
        private val sessionId: String?,
    ) : Source {
        private val tap = Buffer()
        private var truncated = false
        private var reported = false

        override fun timeout() = upstream.timeout()

        override fun read(sink: Buffer, byteCount: Long): Long {
            val chunk = Buffer()
            val n = upstream.read(chunk, byteCount)
            if (n == -1L) {
                report()
                return -1L
            }
            if (n > 0) {
                val bytes = chunk.readByteArray()
                if (!truncated) {
                    val room = RequestCapture.MAX_RESPONSE_BYTES - tap.size
                    if (room <= 0) {
                        truncated = true
                    } else {
                        val take = minOf(bytes.size.toLong(), room).toInt()
                        tap.write(bytes, 0, take)
                        if (take < bytes.size) truncated = true
                    }
                }
                sink.write(bytes)
            }
            return n
        }

        override fun close() {
            upstream.close()
            report()
        }

        private fun report() {
            if (reported) return
            reported = true
            RequestCapture.attachStreamBody(sessionId, tap.readUtf8(), truncated)
        }
    }
}

/** Shared OkHttp client. Streaming reads have no overall timeout. */
object HttpClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(SseIdleTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 网络前置阶段必须可取消、且有上界（U57）：call.cancel() 打不断阻塞的
            // DNS 解析，而共享客户端上又不能加 callTimeout（会误杀长思考）。
            .dns(CancellableDns())
            // 先于其它拦截器：DNS 解析在 ConnectInterceptor 内部、与调用同线程。
            .addInterceptor(CallContextInterceptor())
            // 停在哪个前置阶段：硬上界触发时，诊断据此说清「卡在哪、卡了多久」。
            .eventListenerFactory { call -> PhaseEventListener(call.request().tag(CaptureTag::class.java)?.sessionId) }
            .addInterceptor(CaptureInterceptor())
            .build()
    }
}
