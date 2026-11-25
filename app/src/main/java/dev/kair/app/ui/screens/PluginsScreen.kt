package dev.kair.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.dynamic.PluginIncompatibleException
import dev.kair.app.ui.components.KairTopBar
import dev.kair.app.ui.components.clearFocusOnTap
import dev.kair.app.ui.components.pluginDisplayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 插件页外壳：只持有磁盘态刷新计数、安装 / 删除 / 兼容确认这些「有副作用」的状态，
 * 版式与列表全部下沉到 [PluginsContent]。
 *
 * U106 起把 500 行的单体方法拆开：页面结构（汇总 / 筛选 / 宫格 / 分层列表）在
 * `PluginsContent.kt`，行与菜单在 `PluginRows.kt`，列表分区在 `PluginListSections.kt`。
 * 这里只保留业务流程，避免再撞 `MethodSizeGuardTest` 的登记上限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPanel: (String) -> Unit,
) {
    val context = LocalContext.current
    // 磁盘态刷新计数（U46）：只喂动态插件文件清单 / 版本 / 工具开关落盘结果；
    // 插件贡献列表组合期直读，不经这里。
    var refresh by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 核心插件被关闭前先弹确认，避免误关导致基础能力静默降级。
    var pendingCoreDisable by remember { mutableStateOf<Plugin?>(null) }
    // 兼容检查失败、待用户决定是否「强制安装」的插件包（临时文件 + 不兼容原因）。
    var pendingIncompatible by remember { mutableStateOf<PendingInstall?>(null) }
    // 待确认的「删除插件（含数据）」：声明了数据的插件先弹确认框列出待删项，再一并删除。
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    // 隐藏开关：内置插件的启用开关默认不显示（避免误关导致基础能力降级）；
    // 连点标题「插件」8 次才解锁显示（计数逻辑在 [PluginsTitle]）。
    var showBuiltinSwitches by remember { mutableStateOf(false) }
    var installOpen by remember { mutableStateOf(false) }

    // 安装一个已拷入缓存的插件包；不兼容且未强制时，转入「强制安装」确认对话框。
    // 解包 / dex 装载是成块儿的文件 I/O：放 IO 线程跑，主线程只收结果改状态。
    fun attemptInstall(tmp: File, force: Boolean) {
        scope.launch {
            // installPackage 返回 Result，需 getOrThrow 展开成单层 Result，否则
            // runCatching 会包成 Result<Result<String>>、异常也漏不进 onFailure。
            val result = withContext(Dispatchers.IO) {
                runCatching { container.dynamicPlugins.installPackage(tmp, force).getOrThrow() }
            }
            result.fold(
                onSuccess = { message ->
                    tmp.delete()
                    refresh++
                    snackbar.showSnackbar(message)
                },
                onFailure = { error ->
                    if (error is PluginIncompatibleException && !force) {
                        // 交给对话框决定；临时文件此时不能删，强制安装还要用它。
                        pendingIncompatible = PendingInstall(tmp, error.reasons)
                    } else {
                        tmp.delete()
                        refresh++
                        snackbar.showSnackbar(
                            context.getString(R.string.plugins_install_failed, error.message ?: ""),
                        )
                    }
                },
            )
        }
    }

    // 重试 / 启用：同样下沉到 IO，并把失败原因回显给用户（此前静默吞掉 Result）。
    fun reloadPlugin(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { container.dynamicPlugins.install(id) }
            refresh++
            result.exceptionOrNull()?.let { error ->
                snackbar.showSnackbar(
                    context.getString(R.string.plugins_install_failed, error.message ?: ""),
                )
            }
        }
    }

    fun reloadAll() {
        // 重扫 plugins/ 并重装动态插件；解包 / dex 装载必须在 IO 线程。
        scope.launch {
            withContext(Dispatchers.IO) { container.dynamicPlugins.discover() }
            refresh++
        }
    }

    // 点击「安装」→ 系统文件管理器选插件包 → 拷入缓存 → 宿主校验（含兼容性）→ 安装。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    runCatching { copyPluginToCache(context, uri) }
                }
                copied.fold(
                    onSuccess = { tmp -> attemptInstall(tmp, force = false) },
                    onFailure = { error ->
                        snackbar.showSnackbar(
                            context.getString(R.string.plugins_install_failed, error.message ?: ""),
                        )
                    },
                )
            }
        }
    }
    val pickPlugin = { picker.launch(arrayOf("*/*")) }

    // 删除插件要递归删目录 / 回收 dex 贡献点，是成块儿的文件 IO：下沉到 IO 线程，
    // 否则删除大插件或含数据目录时会把界面卡住。
    fun deletePlugin(id: String, deleteData: Boolean) {
        scope.launch {
            val workspace = container.activeSessionWorkspace()
            val removed = withContext(Dispatchers.IO) {
                container.deleteDynamicPlugin(id, deleteData = deleteData, dataWorkspace = workspace)
            }
            if (!removed && deleteData) {
                snackbar.showSnackbar(context.getString(R.string.active_session_required))
            }
            refresh++
        }
    }

    // 删除一个插件：声明了数据的先弹确认框列出待删项（「确定」后连数据一并删除），
    // 未声明数据的保持原有一步删除流程。数据项来自内存中的声明表，读取本身不涉及 IO。
    fun requestDelete(id: String) {
        val data = container.plugins.pluginData(id)
        // 相对路径数据必须落到某个真实会话的工作区；没有活跃会话时拒绝执行，
        // 避免按进程初值 scratch 解析后「假装删掉了」。
        if (data.any { !File(it.path).isAbsolute } && container.activeSessionWorkspace() == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.active_session_required)) }
            return
        }
        if (data.isEmpty()) {
            deletePlugin(id, deleteData = false)
        } else {
            val owner = container.plugins.installed.firstOrNull { it.id == id }
                ?: container.builtinPluginDefs.firstOrNull { it.id == id }
            pendingDelete = PendingDelete(id, owner?.name ?: id, owner?.nameRes, data)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KairTopBar(
                title = { PluginsTitle { showBuiltinSwitches = !showBuiltinSwitches } },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { installOpen = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.plugins_install))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clearFocusOnTap()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            PluginsContent(
                container = container,
                refresh = refresh,
                showBuiltinSwitches = showBuiltinSwitches,
                onOpenPanel = onOpenPanel,
                onOpenInstall = { installOpen = true },
                onReloadAll = ::reloadAll,
                onReloadPlugin = ::reloadPlugin,
                onRequestDelete = ::requestDelete,
                onRequestCoreDisable = { pendingCoreDisable = it },
                onSetBuiltinEnabled = { plugin, enabled ->
                    container.setPluginEnabled(plugin.id, enabled)
                    refresh++
                },
                onDisableDynamic = { id ->
                    container.dynamicPlugins.uninstall(id)
                    refresh++
                },
                onRemoveShadowed = { id ->
                    scope.launch {
                        withContext(Dispatchers.IO) { container.dynamicPlugins.delete(id) }
                        refresh++
                    }
                },
                onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            )
        }
    }

    if (installOpen) {
        InstallSheet(
            rootPath = container.dynamicPlugins.rootPath,
            onDismiss = { installOpen = false },
            onPick = {
                installOpen = false
                pickPlugin()
            },
            onReload = {
                installOpen = false
                reloadAll()
            },
        )
    }

    // 核心插件关闭前先确认：避免误关导致基础能力（存储 / 命令后端 / 传输层 / 系统提示 / 审批）静默降级。
    pendingCoreDisable?.let { plugin ->
        CoreDisableDialog(
            pluginName = pluginDisplayText(plugin.name, plugin.nameRes),
            onConfirm = {
                container.setPluginEnabled(plugin.id, false)
                pendingCoreDisable = null
                refresh++
            },
            onDismiss = { pendingCoreDisable = null },
        )
    }

    // 兼容检查失败：列出具体原因，并提供「强制安装」（用户自行承担风险）。
    pendingIncompatible?.let { pending ->
        IncompatibleInstallDialog(
            reasons = pending.reasons,
            onForce = {
                val file = pending.file
                pendingIncompatible = null
                attemptInstall(file, force = true)
            },
            onDiscard = {
                pending.file.delete()
                pendingIncompatible = null
            },
        )
    }

    // 删除声明了数据的插件：列出待删数据项，确认后插件与数据一并清理（不进回收站）。
    pendingDelete?.let { pending ->
        DeletePluginDataDialog(
            pluginName = pluginDisplayText(pending.name, pending.nameRes),
            data = pending.data,
            onConfirm = {
                pendingDelete = null
                deletePlugin(pending.id, deleteData = true)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 安装抽屉：说明来源目录 + 选择文件 + 重新扫描。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallSheet(
    rootPath: String,
    onDismiss: () -> Unit,
    onPick: () -> Unit,
    onReload: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.plugins_install),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.plugins_install_hint, rootPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plugins_install_choose))
            }
            TextButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.plugins_reload))
            }
            Text(
                stringResource(R.string.plugins_reload_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 把用户从文件管理器选中的插件包拷到缓存目录（统一命名成 `.kplug`，宿主按扩展名
 * 解包），返回临时文件；调用方负责在安装后 / 取消后删除。读取失败抛异常。
 */
private fun copyPluginToCache(context: Context, uri: Uri): File {
    val tmp = File(context.cacheDir, "kair-install-${System.currentTimeMillis()}.kplug")
    val input = context.contentResolver.openInputStream(uri)
        ?: error(context.getString(R.string.plugins_install_unreadable))
    input.use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
    return tmp
}

/**
 * 插件页标题（带彩蛋入口）：连点 8 次回调一次，主函数据此切内置插件开关的显隐。
 * 独立成组件是守卫棘轮的要求（PluginsScreen 495 行已到登记上限），不是装饰。
 */
@Composable
private fun PluginsTitle(onUnlock: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    Text(
        stringResource(R.string.plugins_title),
        modifier = Modifier.clickable {
            taps++
            if (taps >= 8) {
                onUnlock()
                taps = 0
            }
        },
    )
}
