package dev.kair.app.ui.plugins.builtin

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.activeScope
import dev.kair.app.app.projectMounts
import dev.kair.app.core.desktop.aptFailureExcerpt
import dev.kair.app.core.mcp.McpTransportConfig
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.proot.ContainerMirror
import dev.kair.app.core.proot.ContainerRunner
import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.ProotCommandRunner
import dev.kair.app.core.proot.RootCommandRunner
import dev.kair.app.core.proot.RootfsManager
import dev.kair.app.core.proot.SdcardMounts
import dev.kair.app.core.proot.SessionChannel
import dev.kair.app.core.proot.SessionState
import dev.kair.app.core.proot.aptInstallCommand
import dev.kair.app.core.proot.containerWorkspacePath
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.roundToInt

/**
 * proot 容器插件：贡献命令执行后端（rootfs 就绪后 `container_shell` 才注册，未就绪时宿主只留
 * `android_shell`）与 rootfs 管理面板（安装 / 卸载 / 状态 / 共享存储挂载）。
 *
 * U78 起面板不再显示「执行通道」：会话只有 proot 一档，显示一个恒为 proot 的只读标签
 * 会让用户以为还能切换（ADR 0007 要求「面板无通道选择器」）。
 *
 * 宿主上没有 proot 二进制（如不支持 ABI 的设备）时插件整体不注册，
 * 宿主自然回退本地 shell —— 可降级，不可阻断。
 */
class ProotPlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = "Linux 工作区"
    override val description = "proot 容器命令后端（Ubuntu rootfs）与管理面板。"

    /**
     * rootfs 状态收集任务。插件被禁用再启用会重新 activate：先取消旧任务再起新的，
     * 否则每次开关都泄漏一个永不结束的 collector（collect 挂在宿主 scope 上，
     * 而注册表的 uninstall 没有反激活生命周期可挂）。
     */
    private var stateJob: kotlinx.coroutines.Job? = null

    override fun activate(ctx: PluginContext) {
        val exec = File(container.nativeLibraryDir, "libproot_exec.so")
        val loader = File(container.nativeLibraryDir, "libproot_loader.so")
        val loader32 = File(container.nativeLibraryDir, "libproot_loader32.so").takeIf { it.exists() }
        if (!exec.exists() || !loader.exists()) return

        val prootDir = File(container.storageRoot, "proot")
        val manager = RootfsManager(
            prootDir,
            container.primaryAbi,
            mirror = { ContainerMirror.fromId(container.settings.containerMirrorId) },
            // core 不得 import Android framework：真实日志器由宿主注入（sha 文件拿不到时用）。
            warn = { Log.w("RootfsManager", it) },
        )
        val local = LocalCommandRunner()
        // 共享存储挂载 = 设置开启 + 授权真实生效（能读到共享存储根）
        val mountSdcard = { container.settings.mountSdcard && SdcardMounts.hostAccessible() }
        // 回收站：工作区之外的 app 目录，容器挂到 /trash（见 TrashMounts）
        val trashDir = container.trashDir
        val chroot = RootCommandRunner(
            manager,
            fallback = local,
            mountSdcard = mountSdcard,
            trashDir = trashDir,
        )
        // U78：chroot 不再是会话通道；它只剩「真 su 维护动作」一个用途（ADR 0007）。
        // 工作区权限修复要真 root（proot 的 chown 是假成功），所以把它交给文件访问层。
        container.workspaceFiles.attachRootRunner(chroot)
        val proot = ProotCommandRunner(
            manager = manager,
            prootExec = exec,
            loader = loader,
            loader32 = loader32,
            prootTmpDir = File(prootDir, "tmp"),
            fallback = local,
            mountSdcard = mountSdcard,
            trashDir = trashDir,
        )
        ctx.commandRunner(ContainerRunner(proot))
        // MCP stdio 服务器也跑在这份 rootfs 里：工厂按就绪状态返回 null，装卸无需重注册。
        container.mcpProcessHost.replace { config, workspace ->
            proot.spawnProcess(stdioShellCommand(config, workspace), workspace)
        }
        // 常驻会话：单条长跑容器进程，挂的是工作区父目录（/projects）而非当前项目 ——
        // 端口服务要跨项目常驻（取证见 docs/review/2026-10-28-共享终端取证.md）。
        val session = ContainerSession(
            manager = manager,
            mounts = { container.projectMounts() },
            proot = proot,
            resolveChannel = { resolveSessionChannel(manager) },
        )
        // 用户终端：第二条通道（PTY），启动三元组复用上面这个会话的构造（任务文档 §3 D2-6）。
        val pty = createPtySession(container, manager, session) { resolveSessionChannel(manager) }
        ctx.panel(
            PanelContribution(
                id = "panels.proot",
                title = name,
                icon = PluginGlyph.Layers,
                order = 30,
                content = { ProotPanel(manager, container) },
            ),
        )
        ctx.panel(backgroundTaskPanel("后台任务", container.backgroundTasks))
        ctx.tool(backgroundTaskTool(container.backgroundTasks))
        ctx.panel(processPanel("Linux 进程", manager, container))
        // 会话由本插件生产、终端插件消费（见 ContainerSessionHost）；就绪即拉起。
        startContainerSession(container, manager, session, pty)

        // 向宿主汇报就绪状态：`container_shell` 只在就绪时注册，且必须在 ctx.commandRunner(...) 之后。
        container.setContainerReady(manager.isReady)
        // 后续迁移（安装完成 / 卸载 / 出错）持续汇报；重激活时旧 collector 一并替换。
        stateJob?.cancel()
        stateJob = container.scope.launch {
            manager.state.collect { container.setContainerReady(manager.isReady) }
        }
    }

    /**
     * 会话执行通道路由：U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）。
     * rootfs 未就绪时报 [SessionChannel.UNAVAILABLE]，绝不回退本地 shell
     * （「Android 本机 shell」冒充容器与常驻语义相悖）。
     */
    private fun resolveSessionChannel(manager: RootfsManager): SessionChannel =
        if (manager.isReady) SessionChannel.PROOT else SessionChannel.UNAVAILABLE

    companion object {
        const val ID = "dev.kair.plugin.proot"
    }
}

/**
 * 把 MCP stdio 配置拼成容器内 `/bin/sh -c` 的启动命令。
 *
 * cwd 默认是工作区在容器里的真实路径（`/projects/<目录名>`）；相对 cwd 也按它解析。
 * 所有参数 / 环境变量都经单引号转义，避免命令注入把 agent 给的参数当成 shell 语法。
 */
private fun stdioShellCommand(config: McpTransportConfig.Stdio, workspace: File): String {
    val root = containerWorkspacePath(workspace)
    val cwd = config.cwd?.let { if (it.startsWith("/")) it else "$root/$it" } ?: root
    val env = config.env.entries.joinToString(" ") { (key, value) -> "${shellQuote(key)}=${shellQuote(value)}" }
    val command = (listOf(config.command) + config.args).joinToString(" ") { shellQuote(it) }
    return buildString {
        append("cd ").append(shellQuote(cwd)).append(" 2>/dev/null || cd ")
        append(shellQuote(root)).append("; ")
        if (env.isNotEmpty()) append("export ").append(env).append("; ")
        append("exec ").append(command)
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** rootfs 管理面板：状态展示 + 安装 / 卸载 + 共享存储挂载。 */
@Composable
private fun ProotPanel(manager: RootfsManager, container: AppContainer) {
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        // 宿主（PluginsScreen）只给面板一个 Box(weight(1f))，不提供滚动 ——
        // PanelContribution 的契约是「面板自己负责滚动」。本面板选项最多
        // （rootfs / 通道 / 开发工具 / 挂载），没有 verticalScroll 时超高内容会被裁掉。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val s = state) {
                is RootfsManager.State.NotInstalled -> {
                    Text(stringResource(R.string.proot_state_not_installed), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.proot_hint_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MirrorSection(container, manager)
                    Button(onClick = { scope.launch { manager.install() } }) {
                        Text(stringResource(R.string.proot_action_install))
                    }
                }

                is RootfsManager.State.ReinstallRequired -> {
                    Text(
                        stringResource(R.string.proot_state_reinstall_required, s.installed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.proot_hint_reinstall_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MirrorSection(container, manager)
                    Button(onClick = { scope.launch { manager.install() } }) {
                        Text(stringResource(R.string.proot_action_install))
                    }
                }

                is RootfsManager.State.Downloading -> {
                    Text(
                        s.progress?.let { stringResource(R.string.proot_state_downloading, (it * 100).roundToInt()) }
                            ?: stringResource(R.string.proot_state_downloading_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { (s.progress ?: 0.1f).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is RootfsManager.State.Extracting -> {
                    Text(stringResource(R.string.proot_state_extracting), style = MaterialTheme.typography.bodyMedium)
                    CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
                }

                is RootfsManager.State.Ready -> {
                    Text(
                        stringResource(R.string.proot_state_ready, s.version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.proot_hint_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // devtools 的探测 / 安装落在显式作用域的工作区上：无会话就提示，不退回全局值。
                    container.activeScope()?.let { DevToolsSection(container, it.workspace) }
                        ?: NoSessionHint()
                    SessionCard(container)
                    SdcardMountSection(container)
                    TextButton(onClick = { scope.launch { manager.uninstall() } }) {
                        Text(stringResource(R.string.proot_action_uninstall))
                    }
                }

                is RootfsManager.State.Error -> RootfsErrorSection(container, s, manager) {
                    scope.launch { manager.install() }
                }
            }
        }
    }

    // 进入面板时：安装中断（进程被杀）停在 Downloading 时重做一次，避免「下载中」假死。
    LaunchedEffect(Unit) {
        if (state is RootfsManager.State.Downloading || state is RootfsManager.State.Extracting) {
            manager.install()
        }
    }
}

/**
 * 共享存储挂载开关。开启时若授权未生效（读不到共享存储根）则发起授权：
 * Android 11+ 跳系统「所有文件访问」页；以下走运行时弹窗（targetSdk 28
 * 的 legacy 路径）。授权只影响挂载与否，不影响既有命令。
 */
@Composable
private fun SdcardMountSection(container: AppContainer) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(container.settings.mountSdcard) }
    var accessible by remember { mutableStateOf(SdcardMounts.hostAccessible()) }

    fun refresh() {
        accessible = SdcardMounts.hostAccessible()
    }

    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    fun request() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        } else {
            writeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.container_sdcard_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    container.settings.mountSdcard = on
                    if (on && !SdcardMounts.hostAccessible()) request()
                },
            )
        }
        when {
            !enabled -> Unit
            accessible -> Text(
                stringResource(R.string.container_sdcard_ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Column {
                Text(
                    stringResource(R.string.container_sdcard_no_access),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { request() }) {
                    Text(stringResource(R.string.container_sdcard_grant))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 常驻会话（Linux 工作区）
// ---------------------------------------------------------------------------

/**
 * 常驻会话卡：启动 / 停止、状态、自动启动与绑定工作区。
 *
 * 会话生命周期集中在这里，终端面板只负责交互 —— 避免两个面板都能启停、状态互相打架。
 */
@Composable
private fun SessionCard(container: AppContainer) {
    val session by container.containerSessionHost.session.collectAsState()
    val current = session
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.proot_session_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current == null) {
                Text(
                    stringResource(R.string.proot_session_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            SessionCardBody(current)
        }
    }
}

@Composable
private fun SessionCardBody(session: ContainerSession) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stateLabel(state),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                is SessionState.Error -> MaterialTheme.colorScheme.error
                is SessionState.Running -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        // 常开：Running / Starting / Stopping 都没有按钮；只有失败时给一次手动重试。
        if (state is SessionState.Error) {
            Button(onClick = { scope.launch { session.start() } }) {
                Text(stringResource(R.string.session_action_retry))
            }
        }
    }
    if (state is SessionState.Running) {
        Text(
            stringResource(R.string.session_projects_root),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    (state as? SessionState.Error)?.let {
        Text(
            it.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 会话状态 → 展示文案（终端面板复用）。 */
@Composable
internal fun stateLabel(state: SessionState): String = when (state) {
    is SessionState.Stopped -> stringResource(R.string.session_state_stopped)
    is SessionState.Starting -> stringResource(R.string.session_state_starting)
    is SessionState.Running -> stringResource(R.string.session_state_running)
    is SessionState.Stopping -> stringResource(R.string.session_state_stopping)
    is SessionState.Error -> stringResource(R.string.session_state_error)
}

// ---------------------------------------------------------------------------
// 开发工具一键安装（Linux 工作区）
// ---------------------------------------------------------------------------

/** 可一键安装的开发工具：展示名 + apt 包名 + 用于检测的可执行文件名。 */
private class DevTool(val label: String, val pkg: String, val bin: String)

/** 常用工具清单；Agent 也能自己 `apt-get install`，这里只是给用户一个可见入口。 */
private val DEV_TOOLS = listOf(
    DevTool("Git", "git", "git"),
    DevTool("Python 3", "python3", "python3"),
    DevTool("Node.js", "nodejs", "node"),
    DevTool("OpenSSH 客户端", "openssh-client", "ssh"),
    DevTool("Bash", "bash", "bash"),
    DevTool("curl", "curl", "curl"),
)

/** 检测很快（本地 `command -v`）给 30s；apt 安装要联网下载，给 5 分钟。 */
private const val DEVTOOLS_DETECT_TIMEOUT_SECONDS = 30L
private const val DEVTOOLS_INSTALL_TIMEOUT_SECONDS = 300L

/**
 * 开发工具探测结果缓存：**进程级**（`object`）。面板反复进出都复用同一结果，
 * 只有重启 app（进程重建）后才会重新探测 —— 既避免每次进面板都跑命令，
 * 也顺带消除「检测还没完就点到安装按钮」的窗口。
 */
private object DevToolsProbe {
    /** 已安装的可执行名集合；null = 尚未探测。 */
    val installed = MutableStateFlow<Set<String>?>(null)

    private val lock = Mutex()

    /** 未探测过才执行一次；并发进入只会跑一次。 */
    suspend fun ensure(container: AppContainer, workspace: File) {
        if (installed.value != null) return
        lock.withLock {
            if (installed.value != null) return
            val cmd = DEV_TOOLS.joinToString("; ") { t ->
                "command -v ${t.bin} >/dev/null 2>&1 && echo ${t.bin}=1 || echo ${t.bin}=0"
            }
            val out = runCatching {
                container.commandRunner.run(cmd, workspace, DEVTOOLS_DETECT_TIMEOUT_SECONDS)
            }.getOrNull()
            installed.value = out?.output?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.endsWith("=1") }
                ?.map { it.substringBefore('=') }
                ?.toSet()
                .orEmpty()
        }
    }

    /** 安装成功后直接标记该项已装，无需重跑整轮探测。 */
    fun markInstalled(bin: String) {
        installed.value = (installed.value ?: emptySet()) + bin
    }
}

/**
 * 开发工具一键安装区：逐项检测是否已装（已装显示「已安装」，否则给「安装」按钮），
 * 安装走容器内 `apt-get install`。探测结果进程内缓存（见 [DevToolsProbe]）。
 *
 * 检测与安装都经 [AppContainer.commandRunner]（容器通道），只在 rootfs 就绪的 Ready 分支里
 * 出现（`apt` 可用、`/etc/apt/sources.list` 已配好）；`workspace` 由调用方从显式作用域传入。
 */
@Composable
private fun DevToolsSection(container: AppContainer, workspace: File) {
    val scope = rememberCoroutineScope()
    val installed by DevToolsProbe.installed.collectAsState()
    var busyBin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 进程内只探测一次；完成前 installed 为 null，只显示转圈、不出安装按钮。
    LaunchedEffect(Unit) { DevToolsProbe.ensure(container, workspace) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.proot_devtools_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.proot_devtools_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                stringResource(R.string.proot_tool_install_failed, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installed == null) {
            // 检测中：先转圈，不出按钮（避免检测未完成时被误点安装）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.proot_devtools_checking),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            DEV_TOOLS.forEach { tool ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tool.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        busyBin == tool.bin -> {
                            Text(
                                stringResource(R.string.proot_tool_installing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.padding(start = 8.dp).size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }

                        installed?.contains(tool.bin) == true -> Text(
                            stringResource(R.string.proot_tool_installed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        else -> Button(
                            onClick = {
                                scope.launch {
                                    busyBin = tool.bin
                                    error = null
                                    val result = runCatching {
                                        container.commandRunner.run(
                                            // 索引为空先补 update：重装 rootfs 后 lists 被清，
                                            // 直接 install 只会 `Unable to locate package`（真机 2026-11-16）。
                                            aptInstallCommand(tool.pkg),
                                            workspace,
                                            DEVTOOLS_INSTALL_TIMEOUT_SECONDS,
                                        )
                                    }.getOrNull()
                                    if (result == null || result.exitCode != 0) {
                                        // 只给最后两行时真机看到的是「E: Sub-process ... error code (1)」，
                                        // 真正的 dpkg 报错在更前面（2026-11-16 真机反馈）。
                                        // 复用桌面安装的过滤规则，并保留 `kair:` 自检行。
                                        val excerpt = result?.output?.let { aptFailureExcerpt(it) }.orEmpty()
                                        error = excerpt.takeIf { it.isNotBlank() }
                                            ?: "exit ${result?.exitCode ?: -1}"
                                    } else {
                                        // 装完只更新这一项，不重跑整轮探测。
                                        DevToolsProbe.markInstalled(tool.bin)
                                    }
                                    busyBin = null
                                }
                            },
                            // 同时只允许一个 apt 运行（apt 会抢数据库锁）。
                            enabled = busyBin == null,
                        ) {
                            Text(stringResource(R.string.proot_tool_install))
                        }
                    }
                }
            }
        }
    }
}
