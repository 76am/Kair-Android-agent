package dev.kair.app.core.mcp

import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.arrayProp
import dev.kair.app.core.tools.boolProp
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.str
import dev.kair.app.core.tools.strProp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * `mcp_list`：列出已配置服务器、连接状态与可用工具。
 *
 * 只读，不触发连接；状态里 Error 会带上原因，便于模型判断下一步。
 */
class McpListTool(private val mcp: McpManager) : Tool {
    override val name = "mcp_list"
    override val category = ToolCategory.READ
    override val description =
        "List configured MCP servers with their connection status and discovered tools. " +
            "Use this before installing or testing a server."
    override val promptSnippet = "list configured MCP servers and their tools"
    override val readOnly = true
    override val isParallelSafe = true
    override val parameters = schemaOf(props())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val views = mcp.views()
        if (views.isEmpty()) return ToolResult("No MCP servers configured. Install one with mcp_manage.")
        return ToolResult(
            views.joinToString("\n") { view ->
                val config = view.config
                val transport = when (val t = config.transport) {
                    is McpTransportConfig.Http -> "http ${t.url}"
                    is McpTransportConfig.Sse -> "sse ${t.url}"
                    is McpTransportConfig.Stdio -> "stdio ${t.command} ${t.args.joinToString(" ")}".trim()
                }
                val state = when (val s = view.status) {
                    McpServerStatus.Idle -> if (config.enabled) "idle" else "disabled"
                    McpServerStatus.Connecting -> "connecting"
                    is McpServerStatus.Connected -> "connected, ${s.toolCount} tools"
                    is McpServerStatus.Error -> "error: ${s.message}"
                }
                val toolNames = view.tools.take(MAX_TOOLS_IN_LIST).joinToString(", ")
                val more = if (view.tools.size > MAX_TOOLS_IN_LIST) ", …" else ""
                "- ${config.name} (${config.id.take(8)}): $state [$transport]" +
                    if (view.tools.isEmpty()) "" else "\n  tools: $toolNames$more"
            },
        )
    }

    private companion object {
        const val MAX_TOOLS_IN_LIST = 40
    }
}

/**
 * `mcp_manage`：安装 / 删除 / 启停 / 测试 / 导入 MCP 服务器。
 *
 * 安装 stdio 服务器等价于在容器内运行任意命令，必须逐次审批且**不允许永久放行**
 * （与 `install_plugin` 同一信任边界）。
 */
class McpManageTool(private val mcp: McpManager) : Tool {
    override val name = "mcp_manage"
    override val category = ToolCategory.WRITE
    override val description =
        "Install, remove, enable, disable, test, reload or import MCP servers. " +
            "Transports: http (url), sse (url) and stdio (command run inside the Linux container). " +
            "After adding a server, its tools appear as mcp__<server>__<tool>."
    override val promptSnippet = "install or manage MCP servers"
    override val promptGuidelines = listOf(
        "For stdio servers, install the runtime inside the container first (e.g. apt-get install nodejs) and use a container command",
        "After mcp_manage add, call mcp_list to confirm the connection and tool names",
        "mcp_manage requires user approval and cannot be permanently allowed",
    )
    override val requiresApproval = true
    override val approvableForever = false
    override val parameters = schemaOf(
        props(
            "action" to strProp(
                "One of: add, remove, enable, disable, test, reload, import",
            ),
            "name" to strProp("Server name (namespace for its tools); required for add/remove/enable/disable/test"),
            "type" to strProp("Transport: http, sse or stdio (default: http when url is set, stdio when command is set)"),
            "url" to strProp("Server URL for http/sse transports"),
            "headers" to buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("Extra HTTP headers, e.g. {\"Authorization\": \"Bearer …\"}"))
                put("additionalProperties", JsonPrimitive(true))
            },
            "command" to strProp("Container command for stdio, e.g. npx or python"),
            "args" to arrayProp(strProp("Argument"), "Arguments passed to the stdio command"),
            "env" to buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("Extra environment variables for the stdio command"))
                put("additionalProperties", JsonPrimitive(true))
            },
            "cwd" to strProp("Working directory inside the container (default: the project directory under /projects)"),
            "enabled" to boolProp("Whether the server is enabled (default true)"),
            "description" to strProp("One-line description of what the server offers"),
            "timeout" to strProp("Per-request timeout in seconds (default 60)"),
            "json" to strProp("For action=import: an mcpServers JSON document"),
            "path" to strProp("For action=import: workspace-relative path to a JSON file"),
        ),
        "action",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        return when (val action = args.reqStr("action").lowercase()) {
            "add" -> add(args)
            "remove" -> {
                val name = args.reqStr("name")
                val config = mcp.configs().firstOrNull { it.name == name || it.id == name }
                    ?: return err("No MCP server named $name")
                ToolResult(
                    if (mcp.remove(config.id)) "Removed MCP server ${config.name}."
                    else "No MCP server named $name",
                )
            }

            "enable", "disable" -> {
                val name = args.reqStr("name")
                val config = mcp.configs().firstOrNull { it.name == name || it.id == name }
                    ?: return err("No MCP server named $name")
                mcp.setEnabled(config.id, action == "enable")
                ToolResult("${config.name} is now ${if (action == "enable") "enabled" else "disabled"}.")
            }

            "test" -> {
                val name = args.reqStr("name")
                val config = mcp.configs().firstOrNull { it.name == name || it.id == name }
                    ?: return err("No MCP server named $name")
                mcp.testServer(config).fold(
                    onSuccess = { tools ->
                        ToolResult(
                            "Connection OK. ${tools.size} tools: " +
                                tools.map { it.name }.sorted().take(60).joinToString(", "),
                        )
                    },
                    onFailure = { err("Connection failed: ${it.message}") },
                )
            }

            "reload" -> {
                mcp.reconnectAll()
                ToolResult("MCP servers reconnecting; call mcp_list again to see refreshed tools.")
            }

            "import" -> {
                val raw = args.str("json")?.takeIf { it.isNotBlank() } ?: run {
                    val path = args.str("path") ?: return err("Provide json or path for import")
                    val file = File(path).let { if (it.isAbsolute) it else File(ctx.workspace, path) }
                    if (!file.isFile) return err("Config file not found: $path")
                    file.readText()
                }
                mcp.importServers(raw).fold(
                    onSuccess = { ToolResult("Imported $it MCP server(s).") },
                    onFailure = { err("Import failed: ${it.message}") },
                )
            }

            else -> err("Unknown action: $action")
        }
    }

    private fun add(args: JsonObject): ToolResult {
        val name = args.reqStr("name")
        McpServerConfig.validateName(name)?.let { return err(it) }
        val headers = args.headers()
        val transport = when (args.str("type")?.lowercase()) {
            "stdio" -> stdioTransport(args) ?: return err("command is required for a stdio server")
            "sse" -> args.str("url")?.let { McpTransportConfig.Sse(it, headers) }
                ?: return err("url is required for an sse server")

            "http", "streamable-http", "streamable_http" ->
                args.str("url")?.let { McpTransportConfig.Http(it, headers) }
                    ?: return err("url is required for an http server")

            null -> when {
                args.str("command") != null -> stdioTransport(args)
                    ?: return err("command is required for a stdio server")

                args.str("url") != null -> McpTransportConfig.Http(args.reqStr("url"), headers)
                else -> return err("Provide url (http/sse) or command (stdio)")
            }
            else -> return err("Unknown type; use http, sse or stdio")
        }
        val existing = mcp.configs().firstOrNull { it.name == name }
        val config = McpServerConfig(
            id = existing?.id.orEmpty(),
            name = name,
            enabled = args["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            transport = transport,
            description = args.str("description").orEmpty(),
            timeoutSeconds = args.str("timeout")?.toIntOrNull() ?: McpServerConfig.DEFAULT_TIMEOUT_SECONDS,
            disabledTools = existing?.disabledTools.orEmpty(),
        )
        return mcp.upsert(config).fold(
            onSuccess = {
                ToolResult("MCP server $name saved. Use mcp_list to check the connection and tool names.")
            },
            onFailure = { err("Save failed: ${it.message}") },
        )
    }

    private fun stdioTransport(args: JsonObject): McpTransportConfig.Stdio? {
        val command = args.str("command") ?: return null
        val arguments = (args["args"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }.orEmpty()
        val env = (args["env"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }?.toMap().orEmpty()
        return McpTransportConfig.Stdio(command, arguments, env, args.str("cwd"))
    }

    private fun JsonObject.headers(): Map<String, String> =
        (this["headers"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }?.toMap().orEmpty()

    private fun err(message: String) = ToolResult(message, isError = true)
}
