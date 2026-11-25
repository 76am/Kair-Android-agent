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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.proot.RootAccess
import dev.kair.app.core.proot.RootProbeResult
import kotlinx.coroutines.launch

/**
 * 设备权限段（U116）：root / su 授权探测的状态展示。
 *
 * 冷启动已自动探测一次（`KairApplication.onCreate`）；点按钮重测会再次 exec `su`
 * （首次会弹 Root 管理器）。探测结论同时供两处使用 —— 本段的状态行，以及会话
 * 下一回合追加的 `[root]` system note。不落盘，进程重启后由冷启动探测补上。
 */
@Composable
internal fun RootProbeSection() {
    val result by RootAccess.result.collectAsState()
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_root_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.settings_root_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            rootStatusText(result, probing),
            style = MonoLabel(),
            color = rootStatusColor(result),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp),
        )
        OutlinedButton(
            onClick = {
                if (!probing) {
                    probing = true
                    scope.launch {
                        RootAccess.probe()
                        probing = false
                    }
                }
            },
            enabled = !probing,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                stringResource(
                    if (probing) R.string.settings_root_probing else R.string.settings_root_probe,
                ),
            )
        }
    }
}

@Composable
private fun rootStatusText(result: RootProbeResult?, probing: Boolean): String = when {
    probing -> stringResource(R.string.settings_root_probing)
    result == RootProbeResult.GRANTED -> stringResource(R.string.settings_root_granted)
    result == RootProbeResult.NO_SU -> stringResource(R.string.settings_root_no_su)
    result == RootProbeResult.DENIED -> stringResource(R.string.settings_root_denied)
    result == RootProbeResult.TIMEOUT -> stringResource(R.string.settings_root_timeout)
    else -> stringResource(R.string.settings_root_undetected)
}

@Composable
private fun rootStatusColor(result: RootProbeResult?): Color = when (result) {
    RootProbeResult.GRANTED -> MaterialTheme.colorScheme.primary
    RootProbeResult.DENIED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
