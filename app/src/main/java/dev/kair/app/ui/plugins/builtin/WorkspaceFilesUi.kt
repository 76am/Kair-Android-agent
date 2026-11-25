package dev.kair.app.ui.plugins.builtin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.storage.Delivery
import dev.kair.app.core.tools.DirEntry
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.ui.components.DestructiveTextButton
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 文件列表里待执行的变更操作：决定弹出哪个对话框。字段均为绝对路径。 */
internal sealed interface FileOp {
    data class NewFile(val dir: String) : FileOp
    data class NewFolder(val dir: String) : FileOp
    data class Rename(val path: String) : FileOp
    data class Copy(val path: String) : FileOp
    data class Move(val path: String) : FileOp
    data class Delete(val path: String) : FileOp
}

/**
 * 目录浏览器：列目录 + 逐条操作 + 变更对话框，文案一律走 `strings.xml`。
 *
 * 「工作区文件」面板与「回收站」面板共用这一份（两个入口、一套实现，§10.4）；
 * 差异只在传进来的 [backend]、[dir]、[policy] 与工具条按钮 —— 回收站没有
 * 「工作区根」可回，故 [homePath] 为 null 时整颗按钮不渲染。
 * [refreshKey]：面板层变更计数，**故意不给默认值** —— 调用点必须表态（没有就写 `0`），见 `界面规范` §9。
 */
@Composable
internal fun FileList(
    backend: FileBackend,
    dir: String,
    homePath: String?,
    policy: FsPolicy,
    onEnter: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    /** 会话日志的联动删除：返回 false 表示索引里已无该会话，按普通文件删除即可。 */
    onDeleteSessionLog: (String) -> Boolean,
    refreshKey: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // key = 后端 + 绝对路径 + 外部刷新键：项目切换 / 目录切换 / 面板层变更任一发生都重拉。
    var entries by remember(backend, dir, refreshKey) { mutableStateOf<List<DirEntry>?>(null) }
    var error by remember(backend, dir, refreshKey) { mutableStateOf<String?>(null) }
    // 变更操作计数：每次成功后自增，触发下面的 LaunchedEffect 重新拉取目录。
    var refresh by remember(backend, dir, refreshKey) { mutableStateOf(0) }
    var status by remember(backend, dir, refreshKey) { mutableStateOf<String?>(null) }
    var pending by remember(backend, dir, refreshKey) { mutableStateOf<FileOp?>(null) }

    // 执行一次变更：成功提示 + 刷新列表；失败给出原因，列表保持原状。
    fun run(op: suspend () -> Unit, okMessage: String) {
        scope.launch {
            runCatching { op() }
                .onSuccess { status = okMessage; refresh++ }
                .onFailure { status = context.getString(R.string.files_op_failed, it.message ?: "") }
        }
    }

    // 导入：系统文件选择器（可多选）→ 逐个拷到缓存（限 100MB）→ FileBackend 落到当前目录。
    // 目标可能位于容器 rootfs（root 属主），所以落盘统一走 backend，而不是直接 java.io.File。
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            var imported = 0
            val skipped = ArrayList<String>()
            for (uri in uris) {
                runCatching { withContext(Dispatchers.IO) { stageImport(context, uri, MAX_IMPORT_BYTES) } }
                    .fold(
                        onSuccess = { staged ->
                            val target = uniqueImportTarget(dir, staged.name)
                            val landed = runCatching { backend.copy(staged.path, target) }
                            File(staged.path).delete()
                            landed.fold(
                                onSuccess = { imported++ },
                                onFailure = { skipped.add(context.getString(R.string.files_import_failed_item, staged.name, it.message ?: "")) },
                            )
                        },
                        onFailure = { error ->
                            skipped.add(
                                if (error is ImportTooLarge) {
                                    context.getString(R.string.files_import_too_large, error.fileName, MAX_IMPORT_BYTES / 1024 / 1024)
                                } else {
                                    context.getString(R.string.files_import_failed_item, uri.lastPathSegment ?: "", error.message ?: "")
                                },
                            )
                        },
                    )
            }
            status = buildString {
                append(context.getString(R.string.files_imported, imported))
                if (skipped.isNotEmpty()) {
                    append("；").append(context.getString(R.string.files_import_skipped, skipped.joinToString("；")))
                }
            }
            refresh++
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importFiles(uris)
    }

    LaunchedEffect(backend, dir, refresh, refreshKey) {
        entries = null
        error = null
        runCatching { backend.list(dir) }
            .onSuccess { list ->
                entries = list.sortedWith(
                    compareBy<DirEntry> { !it.isDirectory }.thenBy { it.name.lowercase() },
                )
            }
            .onFailure { error = it.message }
    }

    val loaded = entries
    val failure = error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                dir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 回到当前项目的工作区根。回收站面板没有这个锚点（它不属于任何工作区），
            // 所以 homePath 为 null 时这里什么都不放，而不是放一个跳到别处的按钮。
            homePath?.let { root ->
                TextButton(onClick = { onEnter(root) }) {
                    Text(stringResource(R.string.files_home))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { pending = FileOp.NewFile(dir) }) {
                Text(stringResource(R.string.files_new_file))
            }
            TextButton(onClick = { pending = FileOp.NewFolder(dir) }) {
                Text(stringResource(R.string.files_new_folder))
            }
            TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.files_import))
            }
        }
        status?.let { Hint(it) }

        policy.parentOf(dir)?.let { parent ->
            FileRow(
                icon = PluginGlyph.ArrowUp,
                name = stringResource(R.string.files_parent),
                onClick = { onEnter(parent) },
            )
        }
        when {
            failure != null -> Hint(context.getString(R.string.files_list_failed, failure))
            loaded == null -> Hint(stringResource(R.string.files_loading))
            loaded.isEmpty() -> Hint(stringResource(R.string.files_empty))
            else -> loaded.forEach { entry ->
                val abs = joinPath(dir, entry.name)
                FileRow(
                    icon = if (entry.isDirectory) PluginGlyph.Folder else PluginGlyph.File,
                    name = entry.name,
                    onClick = {
                        if (entry.isDirectory) onEnter(abs) else onOpenFile(abs)
                    },
                    actions = {
                        FileActionsMenu(
                            showModify = policy.canModify(abs),
                            showDelete = policy.canDelete(abs),
                            onRename = { pending = FileOp.Rename(abs) },
                            onCopy = { pending = FileOp.Copy(abs) },
                            onMove = { pending = FileOp.Move(abs) },
                            onExport = {
                                val target = "${Delivery.PATH}/${entry.name}"
                                run(
                                    { backend.copy(abs, target) },
                                    context.getString(R.string.files_exported, target),
                                )
                            },
                            onDelete = { pending = FileOp.Delete(abs) },
                        )
                    },
                )
            }
        }
    }

    // 对话框与待执行操作解耦：先关掉对话框，再在后台跑变更、完成后刷新。
    when (val op = pending) {
        is FileOp.NewFile -> NameInputDialog(
            title = stringResource(R.string.files_new_file),
            initial = "",
            onDismiss = { pending = null },
            onConfirm = { name ->
                val target = joinPath(op.dir, name)
                pending = null
                run({ backend.writeText(target, "") }, context.getString(R.string.files_created, name))
            },
        )
        is FileOp.NewFolder -> NameInputDialog(
            title = stringResource(R.string.files_new_folder),
            initial = "",
            onDismiss = { pending = null },
            onConfirm = { name ->
                val target = joinPath(op.dir, name)
                pending = null
                run({ backend.mkdir(target) }, context.getString(R.string.files_created, name))
            },
        )
        is FileOp.Rename -> NameInputDialog(
            title = stringResource(R.string.files_rename),
            initial = File(op.path).name,
            onDismiss = { pending = null },
            onConfirm = { name ->
                val parent = File(op.path).parent ?: "/"
                val target = joinPath(parent, name)
                pending = null
                run(
                    { backend.move(op.path, target) },
                    context.getString(R.string.files_renamed),
                )
            },
        )
        is FileOp.Copy -> PathInputDialog(
            title = stringResource(R.string.files_copy),
            initial = op.path,
            onDismiss = { pending = null },
            onConfirm = { target ->
                pending = null
                run(
                    { backend.copy(op.path, target) },
                    context.getString(R.string.files_copied),
                )
            },
        )
        is FileOp.Move -> PathInputDialog(
            title = stringResource(R.string.files_move),
            initial = op.path,
            onDismiss = { pending = null },
            onConfirm = { target ->
                pending = null
                run(
                    { backend.move(op.path, target) },
                    context.getString(R.string.files_moved),
                )
            },
        )
        is FileOp.Delete -> ConfirmDialog(
            title = stringResource(R.string.files_delete),
            message = if (policy.isSessionLog(op.path)) {
                stringResource(R.string.files_delete_session_log_confirm, File(op.path).name)
            } else {
                stringResource(R.string.files_delete_confirm, op.path)
            },
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                run(
                    {
                        // 会话日志必须联动：只删文件会在会话列表里留下一条点开是空的记录。
                        // 索引里已无该会话（历史残留）时退回直接删文件。
                        if (!policy.isSessionLog(op.path) || !onDeleteSessionLog(op.path)) {
                            backend.delete(op.path)
                        }
                    },
                    context.getString(R.string.files_deleted, op.path),
                )
            },
        )
        null -> Unit
    }
}

/**
 * 单个条目的操作菜单。[showModify] 为假时只保留「复制 / 导出」这两项**无损**操作 ——
 * 结构性目录（工作区根自身、`plugins` / `projects` / `proot` 等）不提供改名 / 移动。
 * [showDelete] 单独控制删除：会话日志允许删除（走联动收尾），但不允许改名 / 移动。
 */
@Composable
private fun FileActionsMenu(
    showModify: Boolean,
    showDelete: Boolean,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.files_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showModify) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_rename)) },
                    onClick = { open = false; onRename() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_copy)) },
                onClick = { open = false; onCopy() },
            )
            if (showModify) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_move)) },
                    onClick = { open = false; onMove() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_export)) },
                onClick = { open = false; onExport() },
            )
            if (showDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_delete)) },
                    onClick = { open = false; onDelete() },
                )
            }
        }
    }
}

/** 名称输入对话框（新建 / 重命名）。 */
@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.files_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.files_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 目标路径对话框（复制 / 移动）。 */
@Composable
private fun PathInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.files_path_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path.trim()) }, enabled = path.isNotBlank()) {
                Text(stringResource(R.string.files_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** 危险操作确认对话框（文件删除、回收站一键清空共用这一份）。 */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            DestructiveTextButton(stringResource(R.string.files_confirm), onClick = onConfirm)
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
