package dev.kair.app.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 流式录制 / 回放的测试适配器（仅测试源集）。
 *
 * 思路与 RikkaHub 的 `StreamTraceReplayTest` 相同：把真实流量的每一帧落成 JSONL，
 * 回放时不连网、不花钱，用毫秒级测试守住「传输层解码 → AgentSession 组装」这条链路。
 *
 * 为什么不需要脱敏请求：录制产物只有 [StreamEvent]，事件里**不含** apiKey / 请求头 /
 * 请求体；凭据永远不会进入 fixture。
 *
 * 手动录制真实流量：构造一次真实请求后调用
 * `writeTrace(file, recordingTransport.stream(request, config).toList())`，
 * 其中 `recordingTransport = RecordingTransport(OpenAiProvider(), sink = { ... })`；
 * 把产物放到 `src/test/resources/stream-traces/<provider>/<scenario>.jsonl` 即可。
 */

internal val TraceJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun encodeTraceEvent(event: StreamEvent): String =
    TraceJson.encodeToString(StreamEvent.serializer(), event)

internal fun decodeTraceEvent(line: String): StreamEvent =
    TraceJson.decodeFromString(StreamEvent.serializer(), line)

/** 把事件序列写成 JSONL（一行一帧）。 */
internal fun writeTrace(file: File, events: List<StreamEvent>) {
    file.parentFile?.mkdirs()
    file.writeText(events.joinToString(separator = "\n", postfix = "\n") { encodeTraceEvent(it) })
}

/** 从文件读回 JSONL；空行忽略。 */
internal fun readTrace(file: File): List<StreamEvent> =
    file.readLines().filter { it.isNotBlank() }.map(::decodeTraceEvent)

/** 从测试资源读回 JSONL。 */
internal fun loadTrace(resource: String): List<StreamEvent> {
    val loader = requireNotNull(Thread.currentThread().contextClassLoader) {
        "测试线程缺少 contextClassLoader，无法读取回放 fixture"
    }
    val stream = loader.getResourceAsStream(resource)
        ?: error("找不到流式回放 fixture：$resource")
    return stream.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.map(::decodeTraceEvent).toList()
    }
}

/**
 * 录制包装：真实传输层的事件原样透出，同时逐帧交给 [sink]。
 * 只在测试 / 手动录制时使用，不进入生产装配路径。
 */
internal class RecordingTransport(
    private val delegate: ProviderTransport,
    private val sink: (StreamEvent) -> Unit,
) : ProviderTransport {
    override val id: String get() = delegate.id

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
        delegate.stream(request, config).onEach { sink(it) }
}

/** 回放传输层：把 fixture 当作一次真实的流，走完整的 AgentSession 路径。 */
internal class ReplayTransport(
    private val trace: List<StreamEvent>,
    override val id: String = "replay",
) : ProviderTransport {
    /** 被调用次数；用于断言重试 / 不重试。 */
    var calls: Int = 0
        private set

    /** 每次收到的请求（按调用顺序）：用于断言「发出去的字节」而不只是解码结果。 */
    val requests = mutableListOf<ChatRequest>()

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> {
        calls++
        requests += request
        return trace.asFlow()
    }
}
