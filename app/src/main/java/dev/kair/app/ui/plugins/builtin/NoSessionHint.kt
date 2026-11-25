package dev.kair.app.ui.plugins.builtin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.kair.app.R

/**
 * 「没有活跃会话」的统一提示。
 *
 * 需要工作区的面板在拿不到显式作用域时必须显示它并停下，**不得**回退到
 * 「最近活跃工作区」或进程初值目录 —— 静默写错目录，见 `AGENTS.md` §10.4）。
 */
@Composable
internal fun NoSessionHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.active_session_required),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
