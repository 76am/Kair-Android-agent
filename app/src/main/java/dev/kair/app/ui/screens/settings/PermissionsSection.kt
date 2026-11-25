package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import kotlinx.coroutines.launch

/**
 * 工作区权限修复段：默认只修插件声明路径 + 各项目 .kair（精准、快）；
 * 第二个按钮递归修当前项目整棵工作区（大仓库会慢）。
 *
 * U106 起不再用「标题 + 横线」自带分区：分节标题由设置页统一排出，
 * 这里只负责说明、两个动作与就地回显的状态行。
 */
@Composable
internal fun PermissionsSection(container: AppContainer) {
    var repairBusy by remember { mutableStateOf(false) }
    var repairMessage by remember { mutableStateOf<String?>(null) }
    var repairTone by remember { mutableStateOf(RepairTone.IDLE) }
    val repairScope = rememberCoroutineScope()
    val runRepair: (Boolean) -> Unit = { full ->
        if (full && container.activeSessionWorkspace() == null) {
            // 没有活跃会话就没有「当前项目」；明确提示，不跑一次空修复还报「权限正常」。
            repairMessage = container.appContext.getString(R.string.active_session_required)
            repairTone = RepairTone.FAILED
        } else if (!repairBusy) {
            repairBusy = true
            repairMessage = null
            repairTone = RepairTone.RUNNING
            repairScope.launch {
                val report = container.repairWorkspacePermissions(
                    scope = container.activeSessionWorkspace(),
                    fullWorkspace = full,
                ) {
                    repairMessage = container.appContext.getString(R.string.settings_permissions_running)
                }
                repairMessage = when {
                    report.failed > 0 -> container.appContext.getString(
                        R.string.settings_permissions_failed,
                        report.repaired,
                        report.failed,
                    )
                    report.repaired > 0 -> container.appContext.getString(
                        R.string.settings_permissions_repaired,
                        report.repaired,
                    )
                    else -> container.appContext.getString(R.string.settings_permissions_normal)
                }
                repairTone = if (report.failed > 0) RepairTone.FAILED else RepairTone.OK
                repairBusy = false
            }
        }
    }
    // 设置页是全局的，而「递归整棵树」只能落到**某一棵**工作区上；目标是「活跃会话所属项目」，
    // 所以按钮文字里直接写项目名（U81）——「当前项目」四个字在全局页面上必然被读成「文件面板里正选的那个」。
    // 可点性必须跟 `runRepair` 真正的守卫**同一个源**：`activeSessionWorkspace()` 非空，而不是
    // 「项目名查得到」。可达的不一致：删掉当前活跃会话时 `deleteSession` 只 release 注册表实例、
    // 不重新指派 `activeSession` —— 实例没了而 meta 还在，就会出现「按钮亮着、点下去弹『需要活跃会话』」。
    // 所以名字也由工作区派生：工作区不在，名字就当作没有（按钮置灰 + 通用文案）。
    // activeSession* 不是可观测状态：设置页每次进入都重新组合，切换/删除会话后重进即刷新。
    val activeWorkspace = container.activeSessionWorkspace()?.absolutePath
    val activeProjectName = remember(activeWorkspace) {
        activeWorkspace?.let {
            runCatching {
                val id = container.activeSessionMeta?.projectId
                container.storage.listProjects().firstOrNull { it.id == id }?.name
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_permissions_hint_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { runRepair(false) },
            enabled = !repairBusy,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(stringResource(R.string.settings_permissions_check))
        }
        OutlinedButton(
            onClick = { runRepair(true) },
            enabled = !repairBusy && activeProjectName != null,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                activeProjectName?.let {
                    stringResource(R.string.settings_permissions_full_named, it)
                } ?: stringResource(R.string.settings_permissions_full),
            )
        }
        if (activeProjectName == null) {
            Text(
                stringResource(R.string.settings_permissions_full_needs_session),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            repairMessage.orEmpty(),
            style = MonoLabel(),
            color = when (repairTone) {
                RepairTone.OK -> MaterialTheme.colorScheme.primary
                RepairTone.FAILED -> MaterialTheme.colorScheme.error
                RepairTone.RUNNING -> MaterialTheme.colorScheme.primary
                RepairTone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp),
        )
    }
}

/** 权限修复的结果语气：只影响回显颜色，不参与业务判定。 */
private enum class RepairTone { IDLE, RUNNING, OK, FAILED }
