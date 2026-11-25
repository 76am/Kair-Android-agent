package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.mcp.McpListTool
import dev.kair.app.core.mcp.McpManageTool
import dev.kair.app.core.mcp.McpServerConfig
import dev.kair.app.core.mcp.McpServerStatus
import dev.kair.app.core.mcp.McpTransportConfig
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.launch

/**
 * MCP 插件：把 MCP 服务器接入 agent 工具集，并提供安装 / 管理面板。
 *
 * 工具入口：`mcp_list`（只读）与 `mcp_manage`（需审批，不允许永久放行）。
 * 服务器连接由 [dev.kair.app.core.mcp.McpManager] 持有；本插件只做贡献点与 UI。
 */
class McpPlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = "MCP"
    override val description = "接入 MCP 服务器：远程 HTTP/SSE 与容器内 stdio，工具自动进入 agent 工具集。"

    override fun activate(ctx: PluginContext) {
        ctx.tool(McpListTool(container.mcp))
        ctx.tool(McpManageTool(container.mcp))
        ctx.panel(
            PanelContribution(
                id = "panels.mcp",
                title = "MCP 服务器",
                icon = PluginGlyph.Plug,
                order = 32,
                content = { McpPanel(container) },
            ),
        )
        // 启动时对齐一次配置：已启用的服务器在后台连接，连上即出现在工具列表。
        container.mcp.reconcile()
    }

    companion object {
        const val ID = "dev.kair.plugin.mcp"
    }
}

@Composable
private fun McpPanel(container: AppContainer) {
    // status 是唯一可观察的真态；配置变化后手动 refresh（低频操作）。
    val statuses by container.mcp.status.collectAsState()
    var configs by remember { mutableStateOf(container.mcp.configs()) }
    var editor by remember { mutableStateOf<McpEditor?>(null) }
    var importing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val context = androidx.compose.ui.platform.LocalContext.current

    fun refresh() {
        configs = container.mcp.configs()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.mcp_panel_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editor = McpEditor() }) {
                Text(stringResource(R.string.mcp_add))
            }
            TextButton(onClick = { importing = true }) {
                Text(stringResource(R.string.mcp_import))
            }
        }
        feedback?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (configs.isEmpty()) {
            Text(stringResource(R.string.mcp_empty), style = MaterialTheme.typography.bodyMedium)
        }
        configs.forEach { config ->
            McpServerCard(
                config = config,
                status = statuses[config.id] ?: McpServerStatus.Idle,
                tools = container.mcp.views().firstOrNull { it.config.id == config.id }?.tools.orEmpty(),
                onToggle = { enabled ->
                    container.mcp.setEnabled(config.id, enabled)
                    refresh()
                },
                onEdit = { editor = McpEditor.from(config) },
                onDelete = {
                    container.mcp.remove(config.id)
                    refresh()
                },
                onTest = {
                    scope.launch {
                        feedback = container.mcp.testServer(config).fold(
                            onSuccess = { tools -> context.getString(R.string.mcp_test_ok, tools.size) },
                            onFailure = { context.getString(R.string.mcp_test_failed, it.message.orEmpty()) },
                        )
                    }
                },
                onToolToggle = { tool, enabled ->
                    container.mcp.setToolEnabled(config.id, tool, enabled)
                    refresh()
                },
            )
        }
    }

    editor?.let { draft ->
        McpEditorDialog(
            initial = draft,
            onDismiss = { editor = null },
            onSave = { config ->
                container.mcp.upsert(config).also { result ->
                    if (result.isSuccess) {
                        editor = null
                        refresh()
                    }
                }
            },
        )
    }

    if (importing) {
        McpImportDialog(
            onDismiss = { importing = false },
            onImport = { raw ->
                val result = container.mcp.importServers(raw)
                importing = false
                result.fold(
                    onSuccess = {
                        feedback = null
                        refresh()
                    },
                    onFailure = { feedback = it.message },
                )
            },
        )
    }

    // 面板每次进入都对齐一次：rootfs / 网络恢复后不必重启 app。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.mcp.reconcile()
        refresh()
    }
}

@Composable
private fun McpServerCard(
    config: McpServerConfig,
    status: McpServerStatus,
    tools: List<String>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onToolToggle: (String, Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(config.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(
                        transportSummary(config),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }
            Text(
                statusText(status, config.enabled),
                style = MaterialTheme.typography.bodySmall,
                color = when (status) {
                    is McpServerStatus.Error -> MaterialTheme.colorScheme.error
                    is McpServerStatus.Connected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            config.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (tools.isNotEmpty()) {
                Text(
                    stringResource(R.string.mcp_tools_title, tools.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tools.forEach { tool ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tool,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Switch(
                            checked = tool !in config.disabledTools,
                            onCheckedChange = { onToolToggle(tool, it) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onTest) { Text(stringResource(R.string.mcp_test)) }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.mcp_edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.mcp_delete)) }
            }
        }
    }
}

@Composable
private fun statusText(status: McpServerStatus, enabled: Boolean): String = when (status) {
    McpServerStatus.Idle -> stringResource(if (enabled) R.string.mcp_status_idle else R.string.mcp_status_disabled)
    McpServerStatus.Connecting -> stringResource(R.string.mcp_status_connecting)
    is McpServerStatus.Connected -> stringResource(R.string.mcp_status_connected, status.toolCount)
    is McpServerStatus.Error -> stringResource(R.string.mcp_status_error, status.message)
}

private fun transportSummary(config: McpServerConfig): String = when (val t = config.transport) {
    is McpTransportConfig.Http -> "HTTP · ${t.url}"
    is McpTransportConfig.Sse -> "SSE · ${t.url}"
    is McpTransportConfig.Stdio -> "stdio · " + (listOf(t.command) + t.args).joinToString(" ")
}

/** 编辑器草稿；[id] 为空表示新建。 */
private data class McpEditor(
    val id: String = "",
    val name: String = "",
    val transport: String = "http",
    val url: String = "",
    val command: String = "",
    val args: String = "",
    val env: String = "",
    val cwd: String = "",
    val headers: String = "",
    val description: String = "",
    val timeout: String = "60",
    val enabled: Boolean = true,
) {
    companion object {
        fun from(config: McpServerConfig): McpEditor = when (val t = config.transport) {
            is McpTransportConfig.Http -> McpEditor(
                id = config.id,
                name = config.name,
                transport = "http",
                url = t.url,
                headers = t.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" },
                description = config.description,
                timeout = config.timeoutSeconds.toString(),
                enabled = config.enabled,
            )

            is McpTransportConfig.Sse -> McpEditor(
                id = config.id,
                name = config.name,
                transport = "sse",
                url = t.url,
                headers = t.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" },
                description = config.description,
                timeout = config.timeoutSeconds.toString(),
                enabled = config.enabled,
            )

            is McpTransportConfig.Stdio -> McpEditor(
                id = config.id,
                name = config.name,
                transport = "stdio",
                command = t.command,
                args = t.args.joinToString("\n"),
                env = t.env.entries.joinToString("\n") { (k, v) -> "$k=$v" },
                cwd = t.cwd.orEmpty(),
                description = config.description,
                timeout = config.timeoutSeconds.toString(),
                enabled = config.enabled,
            )
        }
    }
}

@Composable
private fun McpEditorDialog(
    initial: McpEditor,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Result<Unit>,
) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildConfig(): Result<McpServerConfig> {
        val transport = when (draft.transport) {
            "stdio" -> McpTransportConfig.Stdio(
                command = draft.command.trim(),
                args = draft.args.lines().map { it.trim() }.filter { it.isNotEmpty() },
                env = parseEnv(draft.env),
                cwd = draft.cwd.trim().takeIf { it.isNotEmpty() },
            )

            "sse" -> McpTransportConfig.Sse(draft.url.trim(), parseHeaders(draft.headers))
            else -> McpTransportConfig.Http(draft.url.trim(), parseHeaders(draft.headers))
        }
        if (draft.name.isBlank()) return Result.failure(IllegalArgumentException("服务器名不能为空"))
        if (transport is McpTransportConfig.Http && transport.url.isBlank()) {
            return Result.failure(IllegalArgumentException("URL 不能为空"))
        }
        if (transport is McpTransportConfig.Sse && transport.url.isBlank()) {
            return Result.failure(IllegalArgumentException("URL 不能为空"))
        }
        if (transport is McpTransportConfig.Stdio && transport.command.isBlank()) {
            return Result.failure(IllegalArgumentException("命令不能为空"))
        }
        return Result.success(
            McpServerConfig(
                id = draft.id,
                name = draft.name.trim(),
                enabled = draft.enabled,
                transport = transport,
                description = draft.description.trim(),
                timeoutSeconds = draft.timeout.toIntOrNull()
                    ?.coerceIn(5, 600)
                    ?: McpServerConfig.DEFAULT_TIMEOUT_SECONDS,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (draft.id.isBlank()) R.string.mcp_editor_add_title else R.string.mcp_editor_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.mcp_field_name)) },
                    singleLine = true,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("http", "sse", "stdio").forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = draft.transport == type,
                            onClick = { draft = draft.copy(transport = type) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                        ) { Text(type.uppercase()) }
                    }
                }
                if (draft.transport == "stdio") {
                    OutlinedTextField(
                        value = draft.command,
                        onValueChange = { draft = draft.copy(command = it) },
                        label = { Text(stringResource(R.string.mcp_field_command)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.args,
                        onValueChange = { draft = draft.copy(args = it) },
                        label = { Text(stringResource(R.string.mcp_field_args)) },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = draft.env,
                        onValueChange = { draft = draft.copy(env = it) },
                        label = { Text(stringResource(R.string.mcp_field_env)) },
                        minLines = 2,
                    )
                    OutlinedTextField(
                        value = draft.cwd,
                        onValueChange = { draft = draft.copy(cwd = it) },
                        label = { Text(stringResource(R.string.mcp_field_cwd)) },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = draft.url,
                        onValueChange = { draft = draft.copy(url = it) },
                        label = { Text(stringResource(R.string.mcp_field_url)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.headers,
                        onValueChange = { draft = draft.copy(headers = it) },
                        label = { Text(stringResource(R.string.mcp_field_headers)) },
                        minLines = 2,
                    )
                }
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it) },
                    label = { Text(stringResource(R.string.mcp_field_description)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.timeout,
                    onValueChange = { draft = draft.copy(timeout = it.filter { c -> c.isDigit() }) },
                    label = { Text(stringResource(R.string.mcp_field_timeout)) },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mcp_field_enabled), modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val config = buildConfig().getOrElse {
                    error = it.message
                    return@TextButton
                }
                onSave(config).onFailure { error = it.message }
            }) { Text(stringResource(R.string.mcp_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) }
        },
    )
}

@Composable
private fun McpImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.mcp_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }) { Text(stringResource(R.string.mcp_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) }
        },
    )
}

private fun parseHeaders(raw: String): Map<String, String> = raw.lines().mapNotNull { line ->
    val idx = line.indexOf(':')
    if (idx <= 0) return@mapNotNull null
    val name = line.substring(0, idx).trim()
    val value = line.substring(idx + 1).trim()
    if (name.isEmpty()) null else name to value
}.toMap()

private fun parseEnv(raw: String): Map<String, String> = raw.lines().mapNotNull { line ->
    val idx = line.indexOf('=')
    if (idx <= 0) return@mapNotNull null
    val name = line.substring(0, idx).trim()
    val value = line.substring(idx + 1).trim()
    if (name.isEmpty()) null else name to value
}.toMap()
