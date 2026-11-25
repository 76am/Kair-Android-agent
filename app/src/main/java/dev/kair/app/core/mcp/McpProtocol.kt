package dev.kair.app.core.mcp

import dev.kair.app.core.ai.HttpClient
import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** MCP 协议层错误（JSON-RPC error / HTTP 非 2xx / 帧不合法）。 */
internal class McpProtocolException(message: String) : Exception(message)

/** 传输已断开（进程退出 / SSE 流结束 / 会话过期）。 */
internal class McpTransportClosedException(message: String) : Exception(message)

/**
 * `tools/list` 返回的一件工具；[inputSchema] 是远端原始 JSON Schema。
 * [annotations] 按 MCP 规范可带 `readOnlyHint` / `destructiveHint`，用于映射本地审批类别。
 */
data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: JsonObject? = null,
)

/** `tools/call` 返回的内容片段。图片走 base64，交给 [ToolResult.images] 转成多模态部件。 */
data class McpCallContent(
    val text: String? = null,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val resourceUri: String? = null,
)

data class McpCallResult(
    val contents: List<McpCallContent>,
    val isError: Boolean,
    val structured: JsonElement? = null,
)

data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val instructions: String?,
)

/**
 * 一种 MCP 传输。实现只负责「收发 JSON-RPC 消息」，不关心请求 / 响应配对 ——
 * 配对在 [McpClient] 里统一做。
 */
internal interface McpTransport : AutoCloseable {
    /** 启动读循环。[onClosed] 只在**主动 close 之外**的断开时回调一次。 */
    suspend fun start(scope: CoroutineScope, onMessage: (JsonObject) -> Unit, onClosed: (Throwable) -> Unit)

    suspend fun send(message: JsonObject)

    /** 关闭读循环与底层进程 / 连接；实现必须幂等。 */
    override fun close()
}

/**
 * 精简 MCP 客户端：JSON-RPC 2.0 配对 + `initialize` / `tools/list` / `tools/call`。
 *
 * 不含 OAuth 与 resources（首版范围）；服务器发来的 `ping` 会照常应答，
 * 其它服务端请求回 `-32601`，避免服务端一直等回复。
 */
internal class McpClient(
    private val clientName: String,
    private val transport: McpTransport,
    private val timeoutMillis: Long,
    /** 非主动关闭（进程退出 / 流断开）时回调，供连接管理器改状态并重连。 */
    private val onDisconnected: () -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong(1)

    /** 回复服务端请求用的协程作用域；close 时整体取消，避免读循环线程泄漏。 */
    private val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 终止闸门：主动关闭与远端断开共用，保证释放只做一次。 */
    private val terminated = AtomicBoolean(false)

    @Volatile
    var serverInfo: McpServerInfo? = null
        private set

    /**
     * 建立连接并完成初始化握手。
     *
     * @throws McpProtocolException 握手被拒 / 协议版本不支持
     */
    suspend fun connect(scope: CoroutineScope): McpServerInfo {
        transport.start(scope, ::handleMessage, ::handleClosed)
        val response = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", LATEST_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", clientName)
                        put("version", "1.0")
                    },
                )
            },
            timeoutMillis,
        )
        val result = response["result"] as? JsonObject
            ?: throw McpProtocolException("initialize 响应缺少 result")
        val protocolVersion = result.string("protocolVersion")
            ?: throw McpProtocolException("initialize 响应缺少 protocolVersion")
        if (protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
            throw McpProtocolException("服务器选择了不支持的协议版本 $protocolVersion")
        }
        val info = McpServerInfo(
            name = (result["serverInfo"] as? JsonObject)?.string("name").orEmpty(),
            version = (result["serverInfo"] as? JsonObject)?.string("version").orEmpty(),
            protocolVersion = protocolVersion,
            instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull,
        )
        (transport as? StreamableHttpMcpTransport)?.setProtocolVersion(protocolVersion)
        serverInfo = info
        notify("notifications/initialized")
        return info
    }

    /** 拉取全部工具（自动跟随 `nextCursor` 分页）。 */
    suspend fun listTools(): List<McpRemoteTool> {
        val tools = mutableListOf<McpRemoteTool>()
        val cursors = HashSet<String>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val params = cursor?.let { buildJsonObject { put("cursor", it) } }
            val response = request("tools/list", params, timeoutMillis)
            val result = response["result"] as? JsonObject
                ?: throw McpProtocolException("tools/list 响应缺少 result")
            val page = result["tools"] as? JsonArray
                ?: throw McpProtocolException("tools/list 响应缺少 tools 数组")
            page.forEach { element ->
                val tool = element as? JsonObject ?: return@forEach
                val name = tool.string("name") ?: return@forEach
                tools += McpRemoteTool(
                    name = name,
                    description = tool.string("description").orEmpty(),
                    inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    annotations = tool["annotations"] as? JsonObject,
                )
            }
            val next = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return tools
            if (!cursors.add(next)) throw McpProtocolException("tools/list 返回了重复的分页游标")
            cursor = next
        }
        throw McpProtocolException("tools/list 分页超过 $MAX_LIST_PAGES 页")
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val response = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis,
        )
        val result = response["result"] as? JsonObject
            ?: throw McpProtocolException("tools/call 响应缺少 result")
        val contents = (result["content"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            when (item.string("type")) {
                "text" -> McpCallContent(text = item.string("text").orEmpty())
                "image" -> McpCallContent(
                    imageBase64 = item.string("data"),
                    imageMimeType = item.string("mimeType") ?: "image/png",
                )

                "resource" -> {
                    val resource = item["resource"] as? JsonObject
                    val uri = resource?.string("uri")
                    val text = resource?.string("text")
                        ?: resource?.string("blob")?.let { "（二进制资源，已省略）" }
                    McpCallContent(text = text, resourceUri = uri)
                }

                "resource_link" -> McpCallContent(
                    text = item.string("name") ?: item.string("uri"),
                    resourceUri = item.string("uri"),
                )

                else -> McpCallContent(text = item.toString())
            }
        }
        return McpCallResult(
            contents = contents,
            isError = (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            structured = result["structuredContent"],
        )
    }

    /** 主动关闭：不是「断开」，不回调 onDisconnected；释放由 [terminate] 统一做。 */
    fun close() = terminate(McpTransportClosedException("MCP 客户端已关闭"), notify = false)

    /** 唯一终止入口：拒绝新请求 → 完成挂起 → 取消回复作用域 → 关传输。
     *  「远端已断」不能短路「释放」，否则子进程 / 连接泄漏（U41）；[notify] 只决定是否通知管理器。 */
    private fun terminate(reason: Throwable, notify: Boolean) {
        if (!terminated.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        replyScope.cancel()
        runCatching { transport.close() }
        if (notify) runCatching { onDisconnected() }
    }

    private suspend fun request(
        method: String,
        params: JsonObject?,
        timeoutMs: Long,
    ): JsonObject {
        if (terminated.get()) throw McpTransportClosedException("MCP 客户端已关闭")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        return try {
            // 超时必须包住 send 本身：HTTP 传输的 send 会阻塞读到本次响应，
            // 只包 await 会让一个不响应的服务器永久占住调用方。
            withTimeout(timeoutMs) {
                sendRaw(message)
                val response = deferred.await()
                val error = response["error"] as? JsonObject
                if (error != null) {
                    throw McpProtocolException(
                        "JSON-RPC ${error.string("code").orEmpty()}：${error.string("message").orEmpty()}",
                    )
                }
                response
            }
        } catch (e: TimeoutCancellationException) {
            // 超时不是「用户中止」：必须转成普通错误，否则 AgentSession 会把整轮对话
            // 当成用户按停止而截断，模型看不到「服务器超时」这个事实。
            throw McpProtocolException("MCP 请求超时（${timeoutMs}ms）：$method")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun notify(method: String, params: JsonObject? = null) {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        }
        // 通知同样可能被一个不读请求的服务器卡住（HTTP 会等到响应体），必须限时。
        try {
            withTimeout(timeoutMillis) { sendRaw(message) }
        } catch (e: TimeoutCancellationException) {
            throw McpProtocolException("MCP 通知超时（${timeoutMillis}ms）：$method")
        }
    }

    /** 写入串行化由各传输自己负责（stdio 写管道、HTTP 各自独立）。 */
    private suspend fun sendRaw(message: JsonObject) {
        transport.send(message)
    }

    private fun handleMessage(message: JsonObject) {
        val method = message.string("method")
        val id = message["id"]
        if (method != null) {
            // 服务端 → 客户端请求：只支持 ping，其余明确拒绝，避免对端等待。
            if (id == null || id is JsonNull) return
            val response = if (method == "ping") {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject { })
                }
            } else {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        },
                    )
                }
            }
            // 服务端请求的回复走独立协程：这里处在读循环线程上，不能阻塞。
            pendingReply(response)
            return
        }
        val responseId = (id as? JsonPrimitive)?.longOrNull ?: return
        val deferred = pending.remove(responseId) ?: return
        deferred.complete(message)
    }

    private fun pendingReply(response: JsonObject) {
        // transport.send 是挂起的，但读循环不是。用 client 自己的作用域跑一次写入，
        // close() 时整体取消，避免每次回复都留下一个永不回收的作用域。
        replyScope.launch {
            runCatching { transport.send(response) }
        }
    }

    private fun handleClosed(error: Throwable) = terminate(error, notify = true)

    private companion object {
        const val LATEST_PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
        const val MAX_LIST_PAGES = 100
    }
}

// ---------------------------------------------------------------------------
// stdio
// ---------------------------------------------------------------------------

/**
 * stdio 传输：子进程 stdin/stdout 交换换行分隔的 JSON-RPC。
 *
 * 进程由宿主在容器（proot）里拉起，因此服务器命令可以是 `npx ...` / `python ...`；
 * stderr 单独收集，只用于错误提示（绝不能混进 stdout，否则 JSON 流会被污染）。
 */
internal class StdioMcpTransport(private val process: Process) : McpTransport {
    private var reader: Thread? = null
    private var stderrReader: Thread? = null
    private val stderr = StringBuilder()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    override suspend fun start(
        scope: CoroutineScope,
        onMessage: (JsonObject) -> Unit,
        onClosed: (Throwable) -> Unit,
    ) {
        stderrReader = Thread {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.length < STDERR_LIMIT) stderr.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        reader = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) continue
                        val message = runCatching { McpJson.parseToJsonElement(line).jsonObject }.getOrNull()
                            ?: continue
                        onMessage(message)
                    }
                }
                if (!closed) onClosed(McpTransportClosedException(stderrMessage("MCP 进程已退出")))
            } catch (e: Exception) {
                if (!closed) onClosed(McpTransportClosedException(stderrMessage("MCP 进程读取失败：${e.message}")))
            }
        }.apply { isDaemon = true; start() }
    }

    override suspend fun send(message: JsonObject) {
        val line = McpJson.encodeToString(JsonElement.serializer(), message) + "\n"
        synchronized(writeLock) {
            process.outputStream.write(line.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.killTree() }
    }

    private fun stderrMessage(fallback: String): String = synchronized(stderr) {
        stderr.toString().trim().takeIf { it.isNotEmpty() }
            ?.lineSequence()?.toList()?.takeLast(3)?.joinToString("\n")
            ?: fallback
    }

    private companion object {
        const val STDERR_LIMIT = 4_000
    }
}

// ---------------------------------------------------------------------------
// Streamable HTTP
// ---------------------------------------------------------------------------

/**
 * Streamable HTTP 传输（MCP 2025-03-26+）。
 *
 * 每个 JSON-RPC 请求单独 POST；响应可能是普通 JSON，也可能是 SSE 流。
 * 响应流里的消息逐条交给读循环，看到本次请求的响应 id 就提前收流。
 * 首版不订阅服务端主动推送的 GET 流（`tools/list_changed` 靠面板刷新 / 下回合重连感知）。
 */
internal class StreamableHttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient = HttpClient.client,
) : McpTransport {
    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var protocolVersion: String? = null

    @Volatile
    private var onMessage: ((JsonObject) -> Unit)? = null

    @Volatile
    private var onClosed: ((Throwable) -> Unit)? = null

    override suspend fun start(
        scope: CoroutineScope,
        onMessage: (JsonObject) -> Unit,
        onClosed: (Throwable) -> Unit,
    ) {
        this.onMessage = onMessage
        this.onClosed = onClosed
    }

    override suspend fun send(message: JsonObject) = withContext(Dispatchers.IO) {
        val body = McpJson.encodeToString(JsonElement.serializer(), message)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                sessionId?.let { header("Mcp-Session-Id", it) }
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        val call = client.newCall(request)
        try {
            withCancellableCall(call) {
                runInterruptible { call.execute() }.use { response ->
                    response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                    if (!response.isSuccessful) {
                        if (response.code == 404 || response.code == 410) {
                            // 会话过期：让连接管理器改状态并重连，而不是一直卡在 Connected。
                            onClosed?.invoke(McpProtocolException("MCP 会话已过期（HTTP ${response.code}）"))
                        }
                        val detail = runCatching { response.body?.string() }.getOrNull()?.take(300).orEmpty()
                        throw McpProtocolException("HTTP ${response.code}${if (detail.isBlank()) "" else "：$detail"}")
                    }
                    if (response.code == 202 || response.code == 204) return@use
                    val contentType = response.header("Content-Type").orEmpty()
                    val source = response.body?.source() ?: return@use
                    if (contentType.contains("text/event-stream", ignoreCase = true)) {
                        val wanted = (message["id"] as? JsonPrimitive)?.longOrNull
                        readSse(source) { event, data ->
                            if (event == "endpoint") return@readSse true
                            val parsed = runCatching { McpJson.parseToJsonElement(data).jsonObject }.getOrNull()
                                ?: return@readSse true
                            onMessage?.invoke(parsed)
                            wanted == null ||
                                ((parsed["id"] as? JsonPrimitive)?.longOrNull != wanted)
                        }
                    } else {
                        val text = source.readUtf8()
                        if (text.isNotBlank()) {
                            when (val parsed = runCatching { McpJson.parseToJsonElement(text) }.getOrNull()) {
                                is JsonObject -> onMessage?.invoke(parsed)
                                is JsonArray -> parsed.forEach { element ->
                                    (element as? JsonObject)?.let { onMessage?.invoke(it) }
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }
        } catch (e: McpProtocolException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val closed = McpTransportClosedException("HTTP 请求失败：${e.message}")
            // 已连接后的网络错误同样要让管理器感知，否则状态永远停在 Connected。
            onClosed?.invoke(closed)
            throw closed
        }
    }

    override fun close() {
        // 无长连接需要关；会话结束用 DELETE 尽力通知服务端。
        val id = sessionId ?: return
        sessionId = null
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Mcp-Session-Id", id)
            .apply {
                protocolVersion?.let { header("MCP-Protocol-Version", it) }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()
        // 关闭可能发生在 UI 线程：DELETE 只在后台线程尽力发出，绝不阻塞界面。
        Thread {
            runCatching { client.newCall(request).execute().close() }
        }.apply { isDaemon = true }.start()
    }

    /** initialize 之后才能带协议版本头；握手阶段不设。 */
    internal fun setProtocolVersion(version: String) {
        protocolVersion = version
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ---------------------------------------------------------------------------
// 旧版 HTTP+SSE
// ---------------------------------------------------------------------------

/**
 * 旧版 HTTP+SSE 传输：GET 打开事件流，首个 `endpoint` 事件给出 POST 地址；
 * 之后的响应与通知都从这条流回来。
 */
internal class SseMcpTransport(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient = HttpClient.client,
) : McpTransport {
    private val endpoint = CompletableDeferred<String>()
    private var job: Job? = null

    /** GET 事件流的 Call；必须在 launch 之前创建并登记，close 才一定取消得到。 */
    private var call: okhttp3.Call? = null

    /** 在飞的 POST（send）：close 时一并取消，否则关闭后仍会挂起。 */
    private val activePosts = java.util.Collections.synchronizedSet(HashSet<okhttp3.Call>())

    @Volatile
    private var closed = false

    override suspend fun start(
        scope: CoroutineScope,
        onMessage: (JsonObject) -> Unit,
        onClosed: (Throwable) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "text/event-stream")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val eventStreamCall = client.newCall(request)
        call = eventStreamCall
        job = scope.launch(Dispatchers.IO) {
            val activeCall = eventStreamCall
            try {
                activeCall.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw McpProtocolException("HTTP ${response.code}")
                    }
                    val source = response.body?.source()
                        ?: throw McpProtocolException("SSE 响应没有 body")
                    readSse(source) { event, data ->
                        when (event) {
                            "endpoint" -> {
                                endpoint.complete(resolveEndpoint(url, data))
                                true
                            }

                            else -> {
                                val parsed = runCatching { McpJson.parseToJsonElement(data).jsonObject }
                                    .getOrNull()
                                parsed?.let(onMessage)
                                true
                            }
                        }
                    }
                }
                if (!closed) onClosed(McpTransportClosedException("SSE 连接已关闭"))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!closed) onClosed(McpTransportClosedException("SSE 连接失败：${e.message}"))
            }
        }
    }

    override suspend fun send(message: JsonObject) {
        if (closed) throw McpTransportClosedException("SSE 连接已关闭")
        val target = try {
            withTimeout(ENDPOINT_TIMEOUT_MS) { endpoint.await() }
        } catch (e: TimeoutCancellationException) {
            throw McpProtocolException("SSE endpoint 等待超时")
        }
        val body = McpJson.encodeToString(JsonElement.serializer(), message)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(target)
            .post(body)
            .header("Content-Type", "application/json")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val activeCall = client.newCall(request)
        activePosts += activeCall
        try {
            withContext(Dispatchers.IO) {
                withCancellableCall(activeCall) {
                    runInterruptible { activeCall.execute() }.use { response ->
                        if (!response.isSuccessful) {
                            throw McpProtocolException("HTTP ${response.code}")
                        }
                    }
                }
            }
        } finally {
            activePosts -= activeCall
        }
    }

    override fun close() {
        closed = true
        endpoint.completeExceptionally(McpTransportClosedException("SSE 连接已关闭"))
        runCatching { call?.cancel() }
        activePosts.toList().forEach { runCatching { it.cancel() } }
        job?.cancel()
    }

    private companion object {
        const val ENDPOINT_TIMEOUT_MS = 15_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ---------------------------------------------------------------------------
// 共用小工具
// ---------------------------------------------------------------------------

/**
 * 执行一次 OkHttp 调用，并在协程被取消（含 `withTimeout` 超时）时主动 cancel 请求。
 *
 * 只挂 `invokeOnCompletion` 不够：它要等阻塞读返回、Job 终结后才触发；这里的旁路
 * 子协程在父协程取消的瞬间就 cancel socket，`execute()` 才能立刻抛出返回。
 */
private suspend fun <T> withCancellableCall(call: okhttp3.Call, block: suspend () -> T): T = coroutineScope {
    val guard = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            runCatching { call.cancel() }
        }
    }
    try {
        block()
    } finally {
        guard.cancel()
    }
}

/**
 * 逐行读 SSE：空行派发一个事件，返回 false 表示调用方要求停止。
 *
 * 同时兼容 `event:` 有值与只有 `data:` 的两种实现；`data:` 多行按 SSE 规范用 `\n` 拼接。
 */
private fun readSse(source: BufferedSource, onEvent: (event: String, data: String) -> Boolean) {
    var event = ""
    val data = StringBuilder()
    while (true) {
        val line = source.readUtf8Line() ?: return
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    if (!onEvent(event, data.toString())) return
                    data.setLength(0)
                    event = ""
                }
            }

            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }

            // 注释行（`:` 开头）、id/retry 字段忽略
            else -> Unit
        }
    }
}

/** 把 SSE 的 `endpoint`（可能是相对路径）解析成绝对地址。 */
internal fun resolveEndpoint(baseUrl: String, endpoint: String): String {
    val base = URI(baseUrl)
    val target = URI(endpoint)
    return if (target.isAbsolute) endpoint else base.resolve(target).toString()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
