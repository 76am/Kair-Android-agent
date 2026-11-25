package dev.kair.app.core.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 一次**已经发出去**的请求的观测副本。内容是传输层序列化后的真实字节，
 * 不是装配器的中间产物 —— 排查「端点为什么不收」必须看线上那一份。
 */
internal data class CapturedRequest(
    val sessionId: String?,
    val at: Long,
    val url: String,
    /** 实际发出的请求体（超长时先脱敏图片再截断，见 [RequestCapture.record]）。 */
    val body: String,
    /** HTTP 状态码；null 表示根本没拿到响应（连接失败 / 超时 / 用户中止）。 */
    val statusCode: Int?,
    /** 仅失败时记录：服务端原文或异常摘要。成功流式响应不能整体读走，否则会破坏 SSE。 */
    val responseBody: String?,
    /**
     * 成功流式响应的旁路采样（读取时顺手留前 [MAX_RESPONSE_BYTES] 字节）。
     *
     * 为什么不能像失败响应那样 peek：SSE 是一边到一边消费的活流，提前读走会卡住
     * 生成；只能在消费端旁路采样，见 [attachStreamBody]。
     */
    val streamBody: String? = null,
    /** 旁路采样超过上限，只保留了头部。 */
    val streamBodyTruncated: Boolean = false,
    /**
     * HTTP 2xx 之后**流式阶段**的失败原因（空响应 / 链路卡住 / 流内错误）。
     *
     * 为什么单独一个字段：状态码本身看不出问题（200 + 空流），而成功流又不能读 body；
     * 这个字段由传输层消费者在 `StreamEvent.Failure` 时回填，是「这一发其实失败了」的唯一判据。
     */
    val streamFailure: String? = null,
    /** 请求体里的图片原始数据是否还在；超过内存上限时为 false，导出时无法补回。 */
    val imagesKept: Boolean,
    /** 请求体被截断过，导出的 JSON 可能不完整。 */
    val truncated: Boolean,
    /** 失败响应原文超过上限，导出里只保留了头部。 */
    val responseTruncated: Boolean = false,
    /** 请求体太大，**没有**捕获（连序列化都跳过了，避免瞬时再拷一份），导出里只有元数据。 */
    val bodySkipped: Boolean = false,
) {
    /** 是否属于需要排查的请求：非 2xx、没拿到响应，或 2xx 之后流式阶段失败。 */
    val failed: Boolean get() = statusCode == null || statusCode !in 200..299 || streamFailure != null
}

/**
 * 会话级请求捕获缓冲：每个会话保留**最近 [LATEST_ALWAYS] 条请求 + 更早的至多 [MAX_FAILURES] 条失败请求**，
 * **只在内存**。
 *
 * 归属：会话。生命周期：随进程存活，不落盘（避免把带历史正文的请求长期留在磁盘上）。
 * 清除时机：[clear] 由宿主在关闭 / 删除会话时调用；子智能体跑完即清（面板没有导出入口）。
 * 并发：读写在 [lock] 内串行，捕获发生在网络线程、导出发生在界面线程，互不重叠。
 *
 * 为什么不是「最近 N 条」：请求是累积快照，第 N 条包含前面全部回合，
 * 总量随回合数平方增长。成功请求用完即弃（下一次会覆盖它），只有失败才有复用价值；
 * 但最新几条必须无条件保留 —— HTTP 200 之后流才失败 / 空响应，状态码本身看不出问题，
 * 却正是最需要导出的那一份。
 *
 * 为什么不在会话日志里存请求体：日志是模型输入的映射，塞进请求体既会污染缓存前缀，
 * 也会让文件体积随图片暴涨；排查用的一次性证据按需导出即可。
 */
internal object RequestCapture {
    /** 无条件保留的最近请求条数（不论成败）。 */
    const val LATEST_ALWAYS = 2

    /** 最近 [LATEST_ALWAYS] 条之外，额外保留的失败请求条数。 */
    const val MAX_FAILURES = 3

    /** 子智能体只留最近 N 条**失败**请求（成功副本没有排查价值，数量又多）。 */
    const val SUBAGENT_MAX_FAILURES = 2

    /** 缓冲上限 = [LATEST_ALWAYS] 条最新 + [MAX_FAILURES] 条更早的失败。 */
    const val MAX_ENTRIES = LATEST_ALWAYS + MAX_FAILURES

    /**
     * 全局内存预算（字符数）。跨会话累计超过后，丢弃最久没再记录的会话。
     *
     * U32 由 6M 调到 40M：请求体按会话累积快照，现代模型的上下文普遍 ≥1M token，
     * 而 [MAX_CAPTURE_BYTES] 与 [MAX_BODY_CHARS] 同步放开到 8M —— 全局预算要装得下
     * 「每条 8M × [MAX_ENTRIES] 条」的上界，否则单个会话就把预算吃光、其他会话被误丢。
     *
     * 风险如实登记：中文 UTF-8 一字 3 字节，40M 字符最坏约 120 MB 堆。Android 应用堆
     * 通常 256–512 MB，可承受但不宽裕；真超了有上面那句「丢弃最久没再记录的会话」兜底。
     * 条数上限（[MAX_ENTRIES]）不变 —— 那是控制「留几份」的，与体积无关。
     */
    const val MAX_TOTAL_CHARS = 40_000_000

    /**
     * 单条请求体的内存上限（字符数，8M）。超过就先脱敏图片再截断。
     *
     * U32 由 2M 放开到 8M：与 [MAX_CAPTURE_BYTES] 配套 —— 只放开前者会让请求「捕获了
     * 但被截断」，面板里仍然看不到全文，等于没放开。
     */
    const val MAX_BODY_CHARS = 8_000_000

    /**
     * 请求体**字节数**超过它就整体跳过捕获（连序列化都不做）。
     *
     * U32 由 1M 放开到 8M：这是**唯一会让请求完全不被捕获**的门 —— 1M 上下文的请求体
     * 就是这个量级，被它挡掉后面板里只剩「未捕获：体积过大」，等于查不了。
     *
     * [MAX_BODY_CHARS] 只限制「留下多少」，序列化那一瞬间的整份 String 照样会多占一份
     * 内存；多图请求的 body 本来就接近 [MAX_BODY_CHARS]，再复制一份就可能把低内存机型
     * 顶到 OOM。这类请求留元数据（端点 / 状态 / 体积）即可，面板会标注「未捕获：体积过大」。
     */
    const val MAX_CAPTURE_BYTES = 8_000_000L

    /** 流失败原因只留一段：它来自厂商原文，导出里不需要整段错误体。 */
    private const val MAX_STREAM_FAILURE_CHARS = 500

    /** 2xx 流式响应旁路采样的字节上限（与失败响应的 peekBody 上限一致）。 */
    const val MAX_RESPONSE_BYTES = 8_000

    /** base64 连续串（图片 / PDF 本体）；短于此长度的正常文本不会被误伤。 */
    private val BASE64_RUN = Regex("[A-Za-z0-9+/]{512,}={0,2}")

    private val lock = Any()
    private val bySession = LinkedHashMap<String, ArrayDeque<CapturedRequest>>()

    /** 子智能体会话 id：保留规则不同（只留失败），由宿主在创建会话时登记。 */
    private val subagentSessions = mutableSetOf<String>()
    private val _version = MutableStateFlow(0)

    /** 每次缓冲变化自增；插件面板据此重组，不必轮询。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** 登记「这个会话是子智能体」：成功请求不占位，只留最近 [SUBAGENT_MAX_FAILURES] 条失败。 */
    fun markSubagent(sessionId: String) {
        synchronized(lock) { subagentSessions.add(sessionId) }
    }

    /**
     * 回填「HTTP 2xx 之后流式阶段失败」。
     *
     * 只标该会话**最新一条**、且 `at >= sinceMillis` 的请求 —— [sinceMillis] 是本次流式
     * 尝试的起点，否则「本地异常（根本没发请求）」会把上一发的成功请求误标成失败。
     * 失败条目会被 [retain] 当失败保留，不会因为状态码 200 被当成成功副本挤掉。
     */
    fun markStreamFailure(sessionId: String?, sinceMillis: Long, reason: String) {
        synchronized(lock) {
            val queue = bySession[sessionId ?: ""] ?: return
            val latest = queue.lastOrNull() ?: return
            if (latest.streamFailure != null) return // 幂等：同一发只标一次
            if (latest.at < sinceMillis) return // 最新一条是上一发：本次尝试没发出请求
            if (latest.statusCode == null || latest.statusCode !in 200..299) return
            val capped = reason.replace('\n', ' ').take(MAX_STREAM_FAILURE_CHARS)
            queue.removeLast()
            queue.addLast(latest.copy(streamFailure = capped))
            _version.value++
        }
    }

    fun record(request: CapturedRequest) {
        // 端点可能把 key 写在 query（`?key=sk-...`）：界面和导出文件都不能出现它。
        // 在入库这一个点脱敏，面板与导出共用同一份，不会漏掉某条路径。
        val redacted = request.copy(url = redactUrlQuery(request.url))
        val body = redacted.body
        val capped = when {
            body.length <= MAX_BODY_CHARS -> redacted
            else -> redacted.copy(
                body = redactImages(body).take(MAX_BODY_CHARS),
                imagesKept = false,
                truncated = true,
            )
        }
        synchronized(lock) {
            val queue = bySession.getOrPut(capped.sessionId ?: "") { ArrayDeque() }
            queue.addLast(capped)
            retain(queue, subagent = capped.sessionId in subagentSessions)
            evictOverBudget()
            _version.value++
        }
    }

    /**
     * 回填成功流式响应体的旁路采样。
     *
     * 只认该会话**最新一条** 2xx 请求：采样就发生在同一次请求的读取过程中，此刻它必然
     * 是最新一条；不同会话（含子智能体）各有自己的队列，不会串。已有失败响应原文的
     * （非 2xx）不覆盖。导出时 `response.body` 优先用失败原文，没有才用这份采样。
     */
    fun attachStreamBody(sessionId: String?, body: String, truncated: Boolean) {
        if (sessionId == null || body.isEmpty()) return
        synchronized(lock) {
            val queue = bySession[sessionId] ?: return
            val latest = queue.peekLast() ?: return
            if (latest.statusCode?.let { it in 200..299 } != true) return
            if (latest.responseBody != null || latest.streamBody != null) return
            queue.removeLast()
            queue.addLast(
                latest.copy(
                    streamBody = body.take(MAX_RESPONSE_BYTES),
                    streamBodyTruncated = truncated || body.length > MAX_RESPONSE_BYTES,
                ),
            )
            _version.value++
        }
    }

    /**
     * 只留「最近 [LATEST_ALWAYS] 条 + 更早的至多 [MAX_FAILURES] 条失败」。
     * 旧的成功请求没有任何复用价值：下一次请求必然包含它们，而且体积更大。
     *
     * 子智能体更紧：成功副本一律不留，只留最近 [SUBAGENT_MAX_FAILURES] 条失败。
     */
    private fun retain(queue: ArrayDeque<CapturedRequest>, subagent: Boolean) {
        val items = queue.toList()
        if (subagent) {
            val kept = items.filter { it.failed }.takeLast(SUBAGENT_MAX_FAILURES)
            queue.clear()
            kept.forEach(queue::addLast)
            return
        }
        if (items.size <= LATEST_ALWAYS) return
        val keep = BooleanArray(items.size)
        for (i in items.lastIndex downTo items.size - LATEST_ALWAYS) keep[i] = true
        var failures = 0
        for (i in items.size - LATEST_ALWAYS - 1 downTo 0) {
            if (failures >= MAX_FAILURES) break
            if (items[i].failed) {
                keep[i] = true
                failures++
            }
        }
        queue.clear()
        items.forEachIndexed { index, item -> if (keep[index]) queue.addLast(item) }
    }

    /**
     * 跨会话总预算：请求是累积快照，开着多个会话时若各自无限留就会累积。
     * 超预算时丢掉**最久没再记录**的会话（子智能体通常最先被丢）。
     */
    private fun evictOverBudget() {
        var total = bySession.values.sumOf { queue -> queue.sumOf { it.body.length } }
        while (total > MAX_TOTAL_CHARS && bySession.size > 1) {
            val oldest = bySession.entries.minByOrNull { it.value.lastOrNull()?.at ?: 0L } ?: break
            total -= oldest.value.sumOf { it.body.length }
            bySession.remove(oldest.key)
        }
    }

    /** 该会话最近一条请求；没有就返回 null（不跨会话兜底，避免导出别人的历史）。 */
    fun latest(sessionId: String?): CapturedRequest? = synchronized(lock) {
        bySession[sessionId ?: ""]?.lastOrNull()
    }

    /** 该会话当前保留的请求（由旧到新），供诊断与回归测试观察缓冲上限。 */
    fun entries(sessionId: String?): List<CapturedRequest> = synchronized(lock) {
        bySession[sessionId ?: ""]?.toList().orEmpty()
    }

    /** 当前所有会话的缓冲快照（会话 id → 由旧到新），供插件面板汇总展示。 */
    fun snapshot(): Map<String, List<CapturedRequest>> = synchronized(lock) {
        bySession.mapValues { (_, queue) -> queue.toList() }
    }

    fun clear(sessionId: String) {
        synchronized(lock) {
            bySession.remove(sessionId)
            subagentSessions.remove(sessionId)
            _version.value++
        }
    }

    /** 清空全部会话的缓冲（面板的「清空」入口）。保留子智能体标记，运行中的委派后续仍按紧规则保留。 */
    fun clearAll() {
        synchronized(lock) {
            bySession.clear()
            _version.value++
        }
    }

    /** 把图片原始数据换成占位符，保留长度信息：排查「是不是图片太大」时仍然有用。 */
    internal fun redactImages(body: String): String = BASE64_RUN.replace(body) {
        "<base64 ${it.value.length} chars>"
    }

    /** 请求体里是否带图片 / PDF 之类的大块 base64 数据。 */
    internal fun hasImageData(body: String): Boolean = BASE64_RUN.containsMatchIn(body)
}

/**
 * 只抹掉「名字像凭据」的 query 值，其余原样保留。
 *
 * 用户自建中转常把 key 放在 query（`?key=sk-...`），原样进界面和导出 JSON 等于把凭据
 * 写进 Download 目录。但**不能整条抹**：`?alt=sse` 这类参数恰恰是排查端点拒收时最该
 * 看的一项，全脱敏会让导出失去诊断价值。所以按参数名判断，值统一换 [MASKED_QUERY_VALUE]。
 * 解析失败（非常规 URL）时整体不展示 query，宁可少信息也不泄露。
 */
internal fun redactUrlQuery(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?')
    val sensitive = parsed.queryParameterNames.filter { it.lowercase() in SENSITIVE_QUERY_NAMES }
    if (sensitive.isEmpty()) return parsed.toString()
    return parsed.newBuilder()
        .apply { sensitive.forEach { setQueryParameter(it, MASKED_QUERY_VALUE) } }
        .build()
        .toString()
}

/** query 值脱敏后的占位。 */
internal const val MASKED_QUERY_VALUE = "***"

/** 名字命中这些（大小写不敏感）才当作凭据抹掉；其余参数是诊断信息。 */
internal val SENSITIVE_QUERY_NAMES = setOf(
    "key",
    "api_key",
    "apikey",
    "api-key",
    "token",
    "access_token",
    "accesstoken",
    "sig",
    "signature",
    "secret",
    "password",
    "auth",
    "authorization",
)

/** 导出文件名：`kair-request-20261025-183800.json`。 */
internal fun requestExportFileName(at: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
    return "kair-request-$stamp.json"
}

/**
 * 把捕获内容拼成可直接发出去的诊断文件。
 *
 * [includeImages] 为 false（默认）时图片本体一律脱敏；请求体本身已经不带图片数据
 * （超限被清掉）时，勾选也补不回来，`meta.imagesKept` 会写明这一点。
 */
internal fun CapturedRequest.toExportJson(includeImages: Boolean): String {
    val bodyText = if (includeImages) body else RequestCapture.redactImages(body)
    val request = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull()
    return buildJsonObject {
        put("meta", buildJsonObject {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at)))
            put("sessionId", JsonPrimitive(sessionId))
            put("endpoint", url)
            put("status", JsonPrimitive(statusCode))
            // 状态码是事实，失败结论单独给出：200 + 空流也算 failed（见 CapturedRequest.failed）。
            put("failed", JsonPrimitive(failed))
            streamFailure?.let { put("streamFailure", JsonPrimitive(it)) }
            put("imagesKept", JsonPrimitive(imagesKept))
            put("truncated", JsonPrimitive(truncated))
            put("responseTruncated", JsonPrimitive(responseTruncated || streamBodyTruncated))
            put("bodySkipped", JsonPrimitive(bodySkipped))
            if (!includeImages && imagesKept) put("imagesRedacted", JsonPrimitive(true))
        })
        // 请求体能解析就嵌成 JSON（便于格式化对比），不能解析就按原文放入字符串，
        // 免得半截请求体让整个导出文件变得不可读。
        if (request != null && !truncated) put("request", request)
        else put("request", buildJsonObject { put("raw", bodyText) })
        put("response", buildJsonObject {
            // 非 2xx 的失败原文优先；2xx 只有旁路采样（见 attachStreamBody）。
            put("body", JsonPrimitive(responseBody ?: streamBody))
        })
    }.toString()
}

/** 写进交付目录。返回文件；写失败（无权限 / 目录不可建）返回 null，由界面提示。 */
internal fun CapturedRequest.exportTo(dir: File, includeImages: Boolean): File? = runCatching {
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, requestExportFileName(at))
    file.writeText(toExportJson(includeImages))
    file
}.getOrNull()
