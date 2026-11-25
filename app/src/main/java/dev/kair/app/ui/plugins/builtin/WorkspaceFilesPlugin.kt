package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.activeScope
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.components.FileEditorBody
import dev.kair.app.ui.icons.PluginGlyph
import java.io.File

/**
 * 工作区文件面板：在应用内直接浏览 / 预览 / 编辑 agent 的工作区文件。
 *
 * 复用内核的文件后端（[AppContainer.fileBackend]，与 `read`/`write` 工具同源），
 * 因此容器在场时同样能读到 container_shell（root）建的、应用 uid 读不动的文件。
 *
 * 为什么不调系统文件管理器：应用私有目录对外部 App 完全不可见，且 Android
 * 没有「打开某目录给用户浏览」的标准 Intent —— 只能自建。好处是不需要任何
 * 存储授权，也不用把工作区挪到共享存储。
 *
 * 列表与对话框这些可复用件在 `WorkspaceFilesUi.kt`：回收站面板（`TrashPlugin`）用同一份
 * [FileList]。回收站原本只是这里的一颗跳转按钮，已删 —— 它有独立面板。
 */
class WorkspaceFilesPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.workspacefiles"

    // 文案的事实源在 strings.xml：接口要求的字面量位留空，宿主渲染时优先取 res（见 PluginDisplayText）。
    override val name = ""
    override val description = ""
    override val nameRes = R.string.files_plugin_name
    override val descriptionRes = R.string.files_plugin_desc

    override fun activate(ctx: PluginContext) {
        ctx.panel(
            PanelContribution(
                id = "panels.workspacefiles",
                title = "",
                titleRes = R.string.files_plugin_name,
                icon = PluginGlyph.Folder,
                order = 30,
                content = { WorkspaceFilesPanel(container) },
            ),
        )
    }
}

@Composable
private fun WorkspaceFilesPanel(container: AppContainer) {
    val projects = remember { container.storage.listProjects() }
    // 根只能来自显式作用域：没有活跃会话就提示，绝不回退 scratch / 第一个项目（静默看错目录）。
    val session = container.activeScope() ?: return NoSessionHint()
    var project by remember {
        mutableStateOf(
            projects.firstOrNull { container.storage.workspaceDir(it) == session.workspace } ?: projects.firstOrNull(),
        )
    }
    // 工作区根（锚点）：项目切换时随之改变，只用于「回到工作区根」入口。
    val home = remember(project) {
        project?.let { container.storage.workspaceDir(it) } ?: session.workspace
    }
    val backend = remember(home) { container.fileBackend(home) }

    // 当前目录用**绝对路径**（不再是相对工作区根的相对路径）：只有绝对路径才能往上翻出
    // 项目根去看同级项目 / 存储根，且它在项目间天然唯一 —— 切项目时 [dir] 立即变化，
    // remember / LaunchedEffect 的 key 随之改变，列表当场刷新（旧实现用相对路径，
    // 切项目后仍是 ""，key 不变，于是继续显示上一个项目的陈旧条目）。
    var dir by rememberPanelBackPath(home.path, home.path)
    var openFile by remember(home) { mutableStateOf<String?>(null) }

    // 路径策略：上翻上限 = Kair 根；可删改的只有各项目工作区、scratch、rootfs，
    // 以及会话日志（只允许删除，且删除必须联动索引与会话列表）。回收站由回收站插件自理。
    val sessionLogs = remember { File(container.storageRoot, "sessions/sessions").path }
    val policy = remember(home, projects, sessionLogs) {
        FsPolicy(
            cap = container.storageRoot.path,
            manageable = buildList {
                projects.forEach { add(container.storage.workspaceDir(it).path) }
                add(File(container.storageRoot, "scratch").path)
                add(File(container.storageRoot, "proot/rootfs").path)
            },
            sessionLogs = sessionLogs,
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (projects.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                projects.forEach { p ->
                    FilterChip(
                        selected = p.id == project?.id,
                        onClick = { project = p },
                        label = { Text(p.name) },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.files_root_hint, home.absolutePath),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        val file = openFile
        if (file != null) {
            FileEditorBody(
                backend = backend,
                absolutePath = file,
                label = previewLabel(home, file),
                modifier = Modifier.fillMaxSize(),
                onBack = { openFile = null },
            )
        } else {
            FileList(
                backend = backend,
                dir = dir,
                homePath = home.path,
                policy = policy,
                onEnter = { dir = it },
                onOpenFile = { openFile = it },
                onDeleteSessionLog = container::deleteSessionLog,
                // 工作区面板的变更全部发生在列表内部（不在面板层批量删/改），本面板没有外部刷新源。
                // 显式传 0 而不是靠默认值：参数去默认值就是为了逼每个调用点表态（界面规范 §9）。
                refreshKey = 0,
            )
        }
    }
}

/** 在绝对目录 [dir] 下拼接一个条目名（处理文件系统根 "/" 的拼接）。 */
internal fun joinPath(dir: String, name: String): String =
    if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

/**
 * 面板的路径策略：把「能上翻到哪」和「哪些路径能改」收敛到一处。
 *
 * - [cap] 上翻上限 = Kair 根（`storageRoot`）。再往上（App 私有目录 → `/data/user/0`
 *   → `/data`）没有意义，也不该让面板去碰。
 * - [manageable] 允许「删除 / 改名 / 移动」的子树根：各项目工作区、`scratch`、容器
 *   `rootfs`、全局回收站。其余路径（Kair 根自身、其下的 `plugins` / `projects` /
 *   `proot` 等结构性目录，以及工作区根**自身**）只读 —— 它们由 App 管理，
 *   手滑删掉会直接影响运行。
 * - [sessionLogs] 会话日志目录：其中的 `<id>.jsonl` **允许删除**，但删除由面板转交
 *   [AppContainer.deleteSessionLog] 联动处理（关会话、删索引、回收附件）；改名 / 移动
 *   仍然禁止，否则索引会指向不存在的文件名。
 */
internal class FsPolicy(
    val cap: String,
    val manageable: List<String>,
    val sessionLogs: String = "",
) {
    /**
     * [path] 是否允许删除 / 改名 / 移动：必须**严格位于**某个可管理子树内部。
     * 前缀比对带上尾斜杠，避免把 `/a/bc` 误判成可管理的 `/a/b` 内部。
     */
    fun canModify(path: String): Boolean = manageable.any { path.startsWith("$it/") }

    /** 会话日志（`<sessionLogs>/<id>.jsonl`）：只允许删除，不允许改名 / 移动。 */
    fun isSessionLog(path: String): Boolean =
        sessionLogs.isNotEmpty() && path.startsWith("$sessionLogs/") && path.endsWith(".jsonl")

    fun canDelete(path: String): Boolean = canModify(path) || isSessionLog(path)

    /** [dir] 的上级目录；已在上限 [cap] 时返回 null（此时不显示「上级目录」）。 */
    fun parentOf(dir: String): String? =
        if (dir == cap) null else File(dir).parentFile?.path
}

/** 预览标题：位于工作区根内显示相对路径，翻到根外则显示绝对路径。 */
private fun previewLabel(home: File, path: String): String {
    val prefix = home.path + "/"
    return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
}
