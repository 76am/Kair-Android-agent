package dev.kair.app.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.BufferedSource
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * SSE 解析等传输层实现细节。请求/事件模型等契约见 `:plugin-api` 模块的
 * `dev.kair.app.core.ai.Ai.kt`（本文件与其同包、按模块拆分）。
 */

/** A parsed Server-Sent-Event frame. */
data class SseEvent(val event: String?, val data: String)

/**
 * 空闲看门狗轮询间隔：够小以让超时判定及时，够大以免空转。
 */
private const val SseWatchdogTickMs = 1_000L

/**
 * SSE 空闲超时。部分中转站在工具结果回灌后既不返回内容也不关闭连接，若没有这层
 * 保护，阻塞读会一直挂在 socket 上，会话无限等待（详见改进任务书第 10 项）。
 * 该值同时是「服务端无响应」与「模型长时间思考」的分界：流式推理模型会持续吐
 * token，因此静默超过该时长即判为链路故障。
 */
const val SseIdleTimeoutMs = 120_000L

/**
 * 网络前置阶段（DNS 解析）的硬上界，也是**取消后最迟多久返回**（U57）。
 *
 * 为什么是 5 秒：`call.cancel()` 打不断阻塞解析，用户点停止后得有个明确的止损点。
 * 取 5 秒是因为它明显低于用户「以为卡死」的感知阈值，又足够让正常的慢解析走完；
 * 实现与取舍见 `core/ai/PreConnect.kt`。
 *
 * **不许为了「少报超时」把它调大**：正常解析在百毫秒量级，频繁触顶说明链路真的坏了。
 */
const val PreConnectTimeoutMs = 5_000L

/**
 * 在独立协程里守护一次 SSE 读取：超过 [timeoutMs] 没有任何字节到达就调用
 * [onTimeout] 并关闭 [source]。
 *
 * 之所以必须用独立协程关闭 source，而不是在读循环里查时间戳：`readUtf8Line` 是
 * 阻塞调用，只有真正关掉底层 socket 才能让它立刻返回。keep-alive 注释行也算
 * 活跃（由 [parseSse] 的 onActivity 回调上报），避免误杀只发心跳的流。
 */
internal fun CoroutineScope.startSseWatchdog(
    source: BufferedSource,
    lastActivityMs: AtomicLong,
    timeoutMs: Long = SseIdleTimeoutMs,
    onTimeout: () -> Unit,
): Job = launch(Dispatchers.IO) {
    while (isActive) {
        delay(SseWatchdogTickMs)
        if (System.currentTimeMillis() - lastActivityMs.get() >= timeoutMs) {
            onTimeout()
            source.close()
            break
        }
    }
}

/** 开一条 SSE 空闲计时线，供 [startSseWatchdog] 判定链路是否还活着。 */
internal fun newActivityClock(): AtomicLong = AtomicLong(System.currentTimeMillis())

/**
 * 协程被取消时**立刻**取消 HTTP call。
 *
 * 不能写成 `job.invokeOnCompletion { call.cancel() }`：那个回调只在 job **完成时**触发，
 * 而阻塞的 socket 读会让 job 永远完不成 —— 「等 job 完成才 cancel call、call 被 cancel 读才能
 * 返回」正好首尾相接成死锁。现场就是「请求已发出、模型还没吐字，点停止 → 永久转圈」：
 * 切网络 / 信号差时 socket 半死，读不返回，取消信号进不去。
 *
 * `onCancelling = true` 让回调在**进入 cancelling 状态**时就执行，不等 job 结束。
 * （该重载标了 `@InternalCoroutinesApi`，只在 `:app` 内部使用，不进 `plugin-api` 契约。）
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun cancelHttpCallWhenCancelled(call: okhttp3.Call) {
    currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true) { call.cancel() }
}

/**
 * Reads SSE frames from a blocking source. Yields one event per blank-line
 * terminated block, joining multiple `data:` lines with newlines.
 *
 * [onActivity] 每读到一行（含 keep-alive 注释行）回调一次，供空闲看门狗判定。
 */
fun parseSse(source: BufferedSource, onActivity: (() -> Unit)? = null): Flow<SseEvent> = flow {
    val data = StringBuilder()
    var event: String? = null
    while (true) {
        val line = source.readUtf8Line() ?: break
        onActivity?.invoke()
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    emit(SseEvent(event, data.toString()))
                    data.setLength(0)
                    event = null
                }
            }
            line.startsWith(":") -> Unit // comment / keep-alive
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart())
            }
        }
    }
    if (data.isNotEmpty()) emit(SseEvent(event, data.toString()))
}

class ProviderHttpException(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(600)}")

/**
 * 本地异常（没有拿到 HTTP 响应）的用户侧文案，中文，原文放进括号供排查。
 *
 * `e.message` 是英文实现细节（如 `Software caused connection abort`），对用户没有
 * 信息量；但对排查有用，所以只翻译「发生了什么」，不丢原文。**按异常类型分派**，
 * 不匹配英文关键字 —— `getMessage()` 在不同厂商 ROM 上文案不同，关键字匹配会失效。
 *
 * 非 `IOException` 的意外异常不能叫「网络异常」：那是内部错误（插件传输层抛错、断言
 * 失败），说成网络问题会把排查方向带偏，正是本项目要消灭的「分类猜错」。
 *
 * 只用于本地异常；服务端返回的错误体必须原样展示（见 [describeError]）。
 */
internal fun localFailureMessage(e: Throwable): String {
    // 顺序必须先具体后泛化：SSLHandshakeException < SSLException < IOException。
    val summary = when (e) {
        is SocketTimeoutException -> "请求超时"
        is UnknownHostException -> "域名解析失败"
        is SSLHandshakeException -> "TLS 握手失败"
        is SSLException -> "TLS 连接失败"
        is ConnectException -> "连接被拒绝"
        is IOException -> "连接被中断"
        else -> "内部错误"
    }
    val raw = e.message?.trim().orEmpty()
    return if (raw.isEmpty()) summary else "$summary（$raw）"
}

/**
 * 「服务端卡住」错误文案（面向用户，中文）。只说现象与可能原因，避免用户面对一个
 * 无从下手的「未知错误」；该文本会进入会话转录，帮助用户与后续排查定位。
 */
internal fun stalledUpstreamMessage(): String =
    "服务端在 ${SseIdleTimeoutMs / 1000} 秒内没有任何响应，已判定连接卡住并中止本次请求。\n" +
        "可能原因：\n" +
        "1) 中转站不支持「工具结果回灌」的消息格式（工具调用后就不回内容也不关连接）；\n" +
        "2) 推理模型与当前请求参数（思考强度 / reasoning 字段）不兼容；\n" +
        "3) 中转服务端自身故障或超时。\n" +
        "建议：换模型或改用官方端点重试。"

/**
 * 「流已结束却既无结束原因也无任何内容」错误文案（面向用户，中文）。
 * 这类响应多半是服务端吞掉了错误后直接关流，不能当成正常 STOP。
 */
internal fun emptyStopMessage(): String =
    "服务端返回了空响应：流已结束，但既没有结束原因（finish_reason / stop_reason）也没有任何内容。\n" +
        "可能原因：\n" +
        "1) 中转站吞掉了上游错误后直接关闭连接；\n" +
        "2) 请求被服务端拒绝但未按协议返回错误体；\n" +
        "3) 模型名 / 参数不被该端点接受。\n" +
        "建议：核对模型名与端点，或换模型重试。"
