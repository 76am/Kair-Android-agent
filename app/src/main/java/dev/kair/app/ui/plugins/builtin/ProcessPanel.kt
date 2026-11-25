package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.activeScope
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.proot.ContainerProcess
import dev.kair.app.core.proot.ContainerProcesses
import dev.kair.app.core.proot.RootfsManager
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「Linux 进程」面板的贡献点。标题由 ProotPlugin 传入 —— `activate` 里拿不到
 * `appContext`，面板标题是急切求值的 String（与 TerminalPlugin 同一约束）。
 */
internal fun processPanel(
    title: String,
    manager: RootfsManager,
    container: AppContainer,
): PanelContribution = PanelContribution(
    id = "panels.processes",
    title = title,
    icon = PluginGlyph.List,
    order = 33,
    content = { ProcessPanel(manager, container) },
)

/**
 * 容器进程快照 + 单进程结束。
 *
 * 只列带 [ContainerEnv.MARKER] 的进程（见 [ContainerProcesses]），不扫整机；
 * 刷新是一次性命令，不做自动轮询 —— 每次探测都要起一次 proot。
 * 持久进程的 shell 受保护，结束它请走「Linux 工作区」的停止按钮。
 *
 * 探测 / 结束的工作区只取显式作用域（`activeScope()`）：没有活跃会话就不给操作。
 * 一次性命令会把「工作区父目录」挂到容器 `/projects`，用临时目录当工作区
 * 会把 App 私有目录覆盖到整棵项目树上（2026-11-02 真机取证）。
 */
@Composable
internal fun ProcessPanel(manager: RootfsManager, container: AppContainer) {
    val rootfs by manager.state.collectAsState()
    val ready = rootfs is RootfsManager.State.Ready
    val channelReady = container.commandRunner.containerWorkspacePath != null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 工作区只能来自显式作用域：一次性命令会把「工作区父目录」挂到容器 `/projects`，
    // 传 App 私有目录（曾经的 cacheDir/process-probe）等于用 cacheDir 覆盖整棵项目树。
    val workspace = container.activeScope()?.workspace
    val missingSession = context.getString(R.string.active_session_required)

    val protectedEntries by container.protectedProcesses.entries.collectAsState()
    val protectedCommands = remember(protectedEntries) { protectedEntries.map { it.command }.toSet() }

    var processes by remember { mutableStateOf<List<ContainerProcess>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var killingPid by remember { mutableStateOf<Int?>(null) }
    var pendingKill by remember { mutableStateOf<ContainerProcess?>(null) }
    var killingAll by remember { mutableStateOf(false) }
    var confirmingKillAll by remember { mutableStateOf(false) }

    fun refresh() {
        if (!ready || !channelReady || refreshing) return
        scope.launch {
            val live = liveWorkspace(container) { error = missingSession } ?: return@launch
            refreshing = true
            error = null
            runCatching {
                container.commandRunner.run(
                    ContainerProcesses.PROBE_COMMAND,
                    live,
                    ContainerProcesses.PROBE_TIMEOUT_SECONDS,
                )
            }.onSuccess { result ->
                if (result.exitCode == 0) {
                    processes = ContainerProcesses.parse(result.output)
                } else {
                    error = context.getString(
                        R.string.proot_process_load_failed,
                        result.output.ifBlank { "exit ${result.exitCode}" },
                    )
                }
            }.onFailure {
                error = context.getString(
                    R.string.proot_process_load_failed,
                    it.message ?: it.javaClass.simpleName,
                )
            }
            refreshing = false
        }
    }

    fun kill(process: ContainerProcess) {
        if (killingPid != null) return
        pendingKill = null
        scope.launch {
            val live = liveWorkspace(container) { error = missingSession } ?: return@launch
            killingPid = process.pid
            error = null
            var succeeded = false
            runCatching {
                container.commandRunner.run(
                    ContainerProcesses.killCommand(process.pid),
                    live,
                    ContainerProcesses.KILL_TIMEOUT_SECONDS,
                )
            }.onSuccess { result ->
                if (result.exitCode == 0) {
                    succeeded = true
                } else {
                    error = context.getString(
                        R.string.proot_process_kill_failed,
                        result.output.ifBlank { "exit ${result.exitCode}" },
                    )
                }
            }.onFailure {
                error = context.getString(
                    R.string.proot_process_kill_failed,
                    it.message ?: it.javaClass.simpleName,
                )
            }
            killingPid = null
            // 失败时保留错误，不立刻刷新把它清掉；用户可手动刷新确认进程是否已消失。
            if (succeeded) refresh()
        }
    }

    /**
     * 「全部结束」：运行期归零，不动会话 shell。
     *
     * 只清**本代**进程（[ContainerProcesses.killAllCommand] 的判据）；上一代由会话启动时的
     * 清扫负责 —— 两处分工写在 `docs/tasks/R1-App重启清理上一代容器进程.md` §5.4。
     */
    fun killAll() {
        if (killingAll || !ready || !channelReady) return
        confirmingKillAll = false
        scope.launch {
            val live = liveWorkspace(container) { error = missingSession } ?: return@launch
            killingAll = true
            error = null
            notice = null
            runKillAll(container, live, protectedCommands.toList())
                .onSuccess {
                    notice = it.ifBlank { null }
                    refresh()
                }
                .onFailure {
                    error = context.getString(
                        R.string.proot_process_kill_all_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                }
            killingAll = false
        }
    }

    /**
     * 自启 / 取消自启（面板文案；标识符仍是 `protect`，改名挂 U56 任务文档 §9）：
     * 登记的是**命令行**，不是 PID —— PID 重启后必变，记了等于没记。
     * 语义是「Kair 启动时自动拉起」，不是跨 Kair 重启永续：Kair 死后由收尸人一并收掉。
     *
     * 落盘在主线程之外：这只是一次几 KB 的原子写，但它会随用户点击发生，
     * 没有理由让 UI 线程等文件系统。
     */
    fun protect(process: ContainerProcess) = scope.launch(Dispatchers.IO) {
        container.protectedProcesses.add(process.command)
    }

    fun unprotect(process: ContainerProcess) = scope.launch(Dispatchers.IO) {
        protectedEntries.firstOrNull { it.command == process.command }
            ?.let { container.protectedProcesses.remove(it.id) }
    }

    LaunchedEffect(ready, channelReady, workspace) {
        if (ready && channelReady && workspace != null) refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProcessHeader(
            refreshing = refreshing,
            refreshEnabled = ready && channelReady && workspace != null,
            killingAll = killingAll,
            killAllEnabled = ready &&
                channelReady &&
                workspace != null &&
                !killingAll &&
                processes.any { !it.isSessionShell && it.command !in protectedCommands },
            notice = notice,
            onRefresh = { refresh() },
            onKillAll = { confirmingKillAll = true },
        )
        when {
            !ready -> Text(
                stringResource(R.string.proot_process_no_rootfs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            !channelReady -> Text(
                stringResource(R.string.proot_process_no_channel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

            workspace == null -> NoSessionHint()

            else -> ProcessList(
                processes = processes,
                refreshing = refreshing,
                killingPid = killingPid,
                protectedCommands = protectedCommands,
                error = error,
                onKill = { pendingKill = it },
                onProtect = { protect(it) },
                onUnprotect = { unprotect(it) },
            )
        }
    }

    KillDialogs(
        pendingKill = pendingKill,
        confirmingKillAll = confirmingKillAll,
        killAllCount = processes.count { !it.isSessionShell && it.command !in protectedCommands },
        onClearKill = { pendingKill = null },
        onConfirmKill = { kill(it) },
        onClearKillAll = { confirmingKillAll = false },
        onConfirmKillAll = { killAll() },
    )
}

/**
 * 面板顶部：说明 + 快照标题 + 刷新 / 全部结束 + 上一次操作的回显。
 *
 * 抽出来是因为 `ProcessPanel` 本体已经到方法长度阈值（200 行）：状态机与外设调用留在
 * 本体，这里只做展示与回调。
 */
@Composable
private fun ProcessHeader(
    refreshing: Boolean,
    refreshEnabled: Boolean,
    killingAll: Boolean,
    killAllEnabled: Boolean,
    notice: String?,
    onRefresh: () -> Unit,
    onKillAll: () -> Unit,
) {
    Text(
        stringResource(R.string.proot_process_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.proot_process_snapshot),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh, enabled = refreshEnabled && !refreshing) {
            Text(
                stringResource(
                    if (refreshing) R.string.proot_process_refreshing else R.string.proot_process_refresh,
                ),
            )
        }
        TextButton(onClick = onKillAll, enabled = killAllEnabled) {
            Text(stringResource(R.string.proot_process_kill_all))
        }
    }
    notice?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.ProcessList(
    processes: List<ContainerProcess>,
    refreshing: Boolean,
    killingPid: Int?,
    protectedCommands: Set<String>,
    error: String?,
    onKill: (ContainerProcess) -> Unit,
    onProtect: (ContainerProcess) -> Unit,
    onUnprotect: (ContainerProcess) -> Unit,
) {
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (processes.isEmpty()) {
        Text(
            if (refreshing) stringResource(R.string.proot_process_refreshing) else stringResource(R.string.proot_process_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(processes, key = { it.pid }) { process ->
            ProcessRow(
                process = process,
                killing = killingPid == process.pid,
                isProtected = process.command in protectedCommands,
                onKill = { onKill(process) },
                onProtect = { onProtect(process) },
                onUnprotect = { onUnprotect(process) },
            )
        }
    }
}

@Composable
private fun ProcessRow(
    process: ContainerProcess,
    killing: Boolean,
    isProtected: Boolean,
    onKill: () -> Unit,
    onProtect: () -> Unit,
    onUnprotect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    process.command.ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.proot_process_meta, process.pid, process.ppid, process.rssKb),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (process.isSessionShell) {
                    Text(
                        stringResource(R.string.proot_process_session_shell),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (isProtected) {
                    Text(
                        stringResource(R.string.proot_process_protected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (!process.isSessionShell) {
                // 已登记自启时只留「取消自启」：不给结束入口，避免「杀了又自己回来」的困惑。
                if (isProtected) {
                    TextButton(onClick = onUnprotect) {
                        Text(stringResource(R.string.proot_process_unprotect))
                    }
                } else {
                    TextButton(onClick = onProtect) {
                        Text(stringResource(R.string.proot_process_protect))
                    }
                    TextButton(onClick = onKill, enabled = !killing) {
                        Text(stringResource(R.string.proot_process_kill))
                    }
                }
            }
        }
    }
}

/**
 * 一次性命令的工作区：只取显式作用域（`activeScope()`）。
 *
 * 点下按钮时重取一次 —— 面板可能开着，会话却已被关掉 / 换了项目。
 * 拿不到就回显统一提示并返回 null；**不得**回退到临时目录：一次性命令会把
 * 「工作区父目录」挂到容器 `/projects`，临时目录会覆盖整棵真实项目树。
 */
private fun liveWorkspace(container: AppContainer, onMissing: () -> Unit): File? =
    container.activeScope()?.workspace ?: run {
        onMissing()
        null
    }

/**
 * 跑一次「全部结束」；非 0 退出码转成异常（消息就是脚本输出），由调用方统一显示。
 *
 * 抽成顶层函数只为压 `ProcessPanel` 的方法长度：它已经贴着 200 行阈值，
 * 而这五步（执行 / 判码 / 提回显 / 抛错）与 UI 状态无关。
 */
private suspend fun runKillAll(
    container: AppContainer,
    workspace: File,
    protected: List<String>,
): Result<String> = runCatching {
    val result = container.commandRunner.run(
        // 受保护进程不参与「全部结束」：否则点一次就把刚保护的常驻服务杀了。
        ContainerProcesses.killAllCommand(protected = protected),
        workspace,
        ContainerProcesses.KILL_TIMEOUT_SECONDS,
    )
    if (result.exitCode != 0) error(result.output.ifBlank { "exit ${result.exitCode}" })
    result.output.trim()
}
