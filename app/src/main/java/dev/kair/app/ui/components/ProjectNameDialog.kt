package dev.kair.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.ProjectNameError

/**
 * 新建 / 重命名共用的项目名对话框：输入、保存、失败原因都留在这里。
 *
 * [onConfirm] 返回 null 表示成功（由调用方关闭对话框并刷新）；否则把原因显示在输入框下方，
 * 对话框不关 —— 用户改个名字就能继续，不必重新填一遍。
 *
 * 从 `HomeScreen.kt` 搬来（U116 删旧首页）：抽屉与无项目页都还用它。
 */
@Composable
internal fun ProjectNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> ProjectNameError?,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var error by remember(initialName) { mutableStateOf<ProjectNameError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.projects_name_label)) },
                    singleLine = true,
                )
                error?.let { ProjectNameErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val failure = onConfirm(name)
                if (failure == null) onDismiss() else error = failure
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ProjectNameErrorText(error: ProjectNameError) {
    val text = when (error) {
        ProjectNameError.BLANK -> stringResource(R.string.project_name_blank)
        ProjectNameError.DUPLICATE -> stringResource(R.string.project_name_duplicate)
        ProjectNameError.BUSY -> stringResource(R.string.project_rename_busy)
        ProjectNameError.MOVE_FAILED -> stringResource(R.string.project_rename_failed)
        ProjectNameError.CREATE_FAILED -> stringResource(R.string.project_create_failed)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
