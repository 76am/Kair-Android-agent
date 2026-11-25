package dev.kair.app.ui.screens.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.app.ProjectNameError
import dev.kair.app.app.renameProject
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.components.DestructiveTextButton
import dev.kair.app.ui.components.ProjectNameDialog

/**
 * 项目行的「⋯」后续两段：动作选择 → 重命名 / 删除确认。
 *
 * 从 `SessionDrawer.kt` 拆出来（文件体量守卫 ≤500 行）；职责与
 * `SessionActionDialogs` 平行：只处理项目的改名 / 删除，不碰会话。
 *
 * 状态自持：动作弹窗关闭后才把重命名 / 删除目标写进自己的状态，父级 [manageFor]
 * 归空不会把本组合拆掉（同 `SessionActionDialogs` 的教训）。
 */
@Composable
internal fun ProjectActionDialogs(
    container: AppContainer,
    manageFor: Project?,
    onDismissManage: () -> Unit,
    onRefresh: () -> Unit,
    onProjectSelected: (String) -> Unit,
    onProjectDeleted: (Project) -> Unit,
) {
    var renameFor by remember { mutableStateOf<Project?>(null) }
    var deleteFor by remember { mutableStateOf<Project?>(null) }

    manageFor?.let { target ->
        MessageActionDialog(
            title = target.name,
            actions = listOf(
                MessageAction(stringResource(R.string.cd_rename)) { renameFor = target },
                MessageAction(stringResource(R.string.cd_delete), destructive = true) {
                    deleteFor = target
                },
            ),
            onDismiss = onDismissManage,
        )
    }

    renameFor?.let { target ->
        ProjectNameDialog(
            title = stringResource(R.string.projects_rename),
            initialName = target.name,
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { renameFor = null },
            onConfirm = { raw ->
                val error: ProjectNameError? = container.renameProject(target, raw)
                if (error == null) onRefresh()
                error
            },
        )
    }

    deleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.projects_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.projects_delete_confirm_message, target.name))
            },
            confirmButton = {
                DestructiveTextButton(stringResource(R.string.action_delete)) {
                    container.deleteProject(target)
                    deleteFor = null
                    // 选中项落到剩余的第一个项目；若删的就是当前项目，宿主会按路由参数跳走。
                    container.storage.listProjects().firstOrNull()?.let { onProjectSelected(it.id) }
                    onRefresh()
                    onProjectDeleted(target)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
