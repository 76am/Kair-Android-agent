package dev.kair.app.core.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** MCP 服务器传输配置。三种形态与 [McpTransport] 的实现一一对应。 */
@Serializable
sealed interface McpTransportConfig {
    /** Streamable HTTP（2025-03-26 起的主流传输）：POST JSON-RPC，响应可为 JSON 或 SSE 流。 */
    @Serializable
    @SerialName("http")
    data class Http(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : McpTransportConfig

    /** 旧版 HTTP+SSE：GET 事件流拿 endpoint，再 POST 到该 endpoint。 */
    @Serializable
    @SerialName("sse")
    data class Sse(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : McpTransportConfig

    /** 本地子进程：在容器（proot）内启动，stdin/stdout 走换行分隔的 JSON-RPC。 */
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        /** 容器内的启动目录；相对路径相对项目工作区（`/projects/<目录名>`）。 */
        val cwd: String? = null,
    ) : McpTransportConfig
}

/**
 * 一台 MCP 服务器。
 *
 * [name] 同时是工具命名空间（`mcp__<name>__<tool>`），因此只允许字母、数字、`_`、`-`、`.`。
 * [disabledTools] 是用户/agent 关掉的远端工具名（按服务器原始名），关掉后不进工具注册表。
 */
@Serializable
data class McpServerConfig(
    val id: String = "",
    val name: String,
    val enabled: Boolean = true,
    val transport: McpTransportConfig,
    val description: String = "",
    /** 单次请求超时（秒）。 */
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val disabledTools: List<String> = emptyList(),
) {
    /** 补齐 id（旧配置 / 导入配置可能没有）。 */
    fun normalized(): McpServerConfig = if (id.isBlank()) copy(id = UUID.randomUUID().toString()) else this

    /** 只影响实际连接的字段；工具开关变化不应触发重连。 */
    fun connectionKey(): String = buildString {
        append(name).append('\u0000')
        when (val t = transport) {
            is McpTransportConfig.Http ->
                append("http\u0000").append(t.url).append('\u0000').append(t.headers.toSortedMap())

            is McpTransportConfig.Sse ->
                append("sse\u0000").append(t.url).append('\u0000').append(t.headers.toSortedMap())

            is McpTransportConfig.Stdio ->
                append("stdio\u0000").append(t.command).append('\u0000').append(t.args)
                    .append('\u0000').append(t.env.toSortedMap()).append('\u0000').append(t.cwd)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60

        /** 服务器名允许的字符：工具名要经各 provider 的 schema，不能出现空格 / 冒号等。 */
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_.-]+$")

        fun validateName(name: String): String? = when {
            name.isBlank() -> "服务器名不能为空"
            !NAME_PATTERN.matches(name) -> "服务器名只能包含字母、数字、下划线、短横线与点"
            else -> null
        }
    }
}

/** 工具名归一化：`mcp__<server>__<tool>`，非 [A-Za-z0-9_] 一律换成 `_`（与 pi 的命名空间规则一致）。 */
fun mcpToolName(server: String, tool: String): String =
    "mcp__${sanitize(server)}__${sanitize(tool)}"

private fun sanitize(value: String): String =
    value.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

/** MCP 客户端 / 服务端共用的 JSON 配置（宽松解析，忽略未知字段）。 */
internal val McpJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * MCP 配置的编解码与 `mcpServers` 兼容导入。
 *
 * 导入格式与 pi / Claude / Cursor 共用：
 * ```json
 * {"mcpServers": {"docs": {"url": "https://example.com/mcp", "headers": {"Authorization": "Bearer x"}},
 *                  "fs": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem"]}}}
 * ```
 * 也接受本应用导出的数组格式（便于备份 / 粘贴恢复）。
 */
object McpConfigCodec {

    fun encode(servers: List<McpServerConfig>): String =
        McpJson.encodeToString(servers)

    fun decode(raw: String?): List<McpServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { McpJson.decodeFromString<List<McpServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    /**
     * 解析一段 `mcpServers` 配置（或本应用的数组导出）。
     *
     * @throws IllegalArgumentException 当顶层结构不对，或某台服务器缺少地址 / 命令时，
     * 消息直接面向用户，可直接展示。
     */
    fun importServers(raw: String): List<McpServerConfig> {
        val root = runCatching { McpJson.parseToJsonElement(raw.trim()) }
            .getOrElse { throw IllegalArgumentException("不是合法的 JSON：${it.message}") }
        return when (root) {
            is JsonArray -> root.map {
                runCatching { McpJson.decodeFromJsonElement(McpServerConfig.serializer(), it) }
                    .getOrElse { e -> throw IllegalArgumentException("配置项格式错误：${e.message}") }
                    .normalized()
            }

            is JsonObject -> {
                val servers = root["mcpServers"] as? JsonObject
                    ?: root.takeIf { entries -> entries.keys.none { it in TOP_LEVEL_KEYS } }
                    ?: throw IllegalArgumentException("缺少 mcpServers 对象")
                servers.entries.map { (name, element) -> parseEntry(name, element).normalized() }
            }

            else -> throw IllegalArgumentException("顶层必须是对象或数组")
        }
    }

    private val TOP_LEVEL_KEYS = setOf("mcpServers", "autoEnableCodemode")

    private fun parseEntry(name: String, element: JsonElement): McpServerConfig {
        McpServerConfig.validateName(name)?.let { throw IllegalArgumentException("$name：$it") }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("$name：配置必须是对象")
        val type = obj.string("type")?.lowercase()
        val url = obj.string("url")
        val command = obj.string("command")
        val transport = when {
            type == "sse" -> McpTransportConfig.Sse(
                url = url ?: throw IllegalArgumentException("$name：缺少 url"),
                headers = obj.stringMap("headers"),
            )

            type == "http" || type == "streamable-http" || type == "streamable_http" ->
                McpTransportConfig.Http(
                    url = url ?: throw IllegalArgumentException("$name：缺少 url"),
                    headers = obj.stringMap("headers"),
                )

            type == "stdio" || command != null -> McpTransportConfig.Stdio(
                command = command ?: throw IllegalArgumentException("$name：缺少 command"),
                args = obj["args"]?.let { arg ->
                    (arg as? JsonArray)?.map { it.jsonPrimitive.content }
                        ?: throw IllegalArgumentException("$name：args 必须是字符串数组")
                }.orEmpty(),
                env = obj.stringMap("env"),
                cwd = obj.string("cwd"),
            )

            url != null -> McpTransportConfig.Http(url = url, headers = obj.stringMap("headers"))
            else -> throw IllegalArgumentException("$name：需要 url 或 command")
        }
        if (transport is McpTransportConfig.Http && !transport.url.startsWith("http")) {
            throw IllegalArgumentException("$name：url 必须是以 http:// 或 https:// 开头")
        }
        if (transport is McpTransportConfig.Sse && !transport.url.startsWith("http")) {
            throw IllegalArgumentException("$name：url 必须是以 http:// 或 https:// 开头")
        }
        if (transport is McpTransportConfig.Stdio && transport.command.isBlank()) {
            throw IllegalArgumentException("$name：command 不能为空")
        }
        return McpServerConfig(
            name = name,
            enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            transport = transport,
            description = obj.string("description").orEmpty(),
            timeoutSeconds = obj["timeout"]?.jsonPrimitive?.intOrNull
                ?: obj["timeoutSeconds"]?.jsonPrimitive?.intOrNull
                ?: McpServerConfig.DEFAULT_TIMEOUT_SECONDS,
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject ?: return emptyMap()
    return obj.entries.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
    }.toMap()
}
