package dev.kair.app.ui.plugins.builtin

import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import dev.kair.app.core.proot.ContainerMirror
import dev.kair.app.core.proot.RootfsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * rootfs 安装相关 UI（镜像预设 / 失败信息），从 `ProotPlugin.kt` 拆出：
 * 那边只留插件装配与命令注入，面板不再继续变胖（B8 体量棘轮）。
 */

/**
 * 容器镜像预设（apt / pip / rootfs 下载同源）。选中即写设置并立刻应用到已安装的 rootfs
 * （未安装时 `reapplyMirror` 返回 false，什么都不做；下次安装会写入）。
 *
 * 站点名映射到 `strings.xml`，**不把 URL 拼到 UI 层**（core/proot 是唯一事实源）。
 */
@Composable
internal fun MirrorSection(container: AppContainer, manager: RootfsManager) {
    val scope = rememberCoroutineScope()
    var selectedId by remember {
        mutableStateOf(ContainerMirror.fromId(container.settings.containerMirrorId).id)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.proot_mirror_label), style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ContainerMirror.entries.forEach { mirror ->
                FilterChip(
                    selected = mirror.id == selectedId,
                    onClick = {
                        selectedId = mirror.id
                        container.settings.containerMirrorId = mirror.id
                        // 已安装的 rootfs 立刻生效；未安装时返回 false，不建目录不报错。
                        // 写盘可能失败（权限 / 磁盘满）：不许抛进 composition 的 scope 崩 UI。
                        scope.launch {
                            try {
                                manager.reapplyMirror(mirror)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("ProotRootfsUi", "reapplyMirror(${mirror.id}) failed", e)
                            }
                        }
                    },
                    label = { Text(mirrorName(mirror), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            stringResource(R.string.proot_mirror_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun mirrorName(mirror: ContainerMirror): String = when (mirror) {
    ContainerMirror.ALIYUN -> stringResource(R.string.proot_mirror_aliyun)
    ContainerMirror.TUNA -> stringResource(R.string.proot_mirror_tuna)
    ContainerMirror.USTC -> stringResource(R.string.proot_mirror_ustc)
    ContainerMirror.HUAWEI -> stringResource(R.string.proot_mirror_huawei)
    ContainerMirror.OFFICIAL -> stringResource(R.string.proot_mirror_official)
}

/** 安装失败：校验失败走专用文案；ABI 硬限制时不提供重试 / 换源。 */
@Composable
internal fun RootfsErrorSection(
    container: AppContainer,
    state: RootfsManager.State.Error,
    manager: RootfsManager,
    onRetry: () -> Unit,
) {
    Text(
        // core 只携带英文详情，校验失败的用户文案在这里翻译（AGENTS.md §4）。
        if (state.checksumMismatch) {
            stringResource(R.string.proot_error_checksum)
        } else {
            stringResource(R.string.proot_state_error, state.message)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    // ABI 不支持属于硬限制，重试无意义；校验失败常因镜像未同步，允许当场换源再试。
    if (manager.unsupportedReason == null) {
        MirrorSection(container, manager)
        Button(onClick = onRetry) { Text(stringResource(R.string.proot_action_retry)) }
    }
}
