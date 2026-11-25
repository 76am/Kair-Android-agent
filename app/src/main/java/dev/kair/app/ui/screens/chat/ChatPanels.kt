package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.settings.ReasoningAuto
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.ui.components.DockOptionRow
import dev.kair.app.ui.components.DockPanelCard
import dev.kair.app.ui.components.formatSeconds
import dev.kair.app.ui.components.formatSpeed
import dev.kair.app.ui.components.formatTokens
import kotlin.math.roundToInt

@Composable
internal fun reasoningLabel(level: String?): String =
    if (level.isNullOrBlank() || level == ReasoningAuto) stringResource(R.string.chat_reasoning_auto) else level

/** 会话级模型切换：选中即写入该会话的模型覆盖（不动全局默认）。 */
@Composable
internal fun ModelPanel(container: AppContainer, sessionId: String, modifier: Modifier, onDone: () -> Unit) {
    val models = container.settings.savedModels
    val current = container.sessionModel(sessionId)?.id
    DockPanelCard(stringResource(R.string.chat_select_model), modifier) {
        if (models.isEmpty()) {
            Text(stringResource(R.string.chat_model_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            models.forEach { m ->
                DockOptionRow(selected = m.id == current, text = m.name.ifBlank { m.model }) {
                    container.setSessionModel(sessionId, m.id)
                    onDone()
                }
            }
        }
    }
}

/** 思考强度：只列该模型支持的等级 + 「自动」；选择写回该模型配置。 */
@Composable
internal fun ReasoningPanel(
    container: AppContainer,
    model: SavedModel?,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    DockPanelCard(stringResource(R.string.chat_select_reasoning), modifier) {
        if (model == null) {
            Text(stringResource(R.string.chat_model_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            (listOf(ReasoningAuto) + model.reasoningLevels).forEach { level ->
                val selected = (model.reasoningEffort ?: ReasoningAuto) == level
                DockOptionRow(selected = selected, text = reasoningLabel(level)) {
                    container.settings.upsertModel(model.copy(reasoningEffort = level))
                    onDone()
                }
            }
        }
    }
}

/**
 * 上下文详情：用量 / 占用比例 / 压缩阈值 / 缓存命中，并可跳转缓存检查面板、手动压缩。
 *
 * @param compactVisible 控件随插件走：禁用对应插件时其能力贡献点被回收，这里连带隐藏按钮，
 *   避免留下点了没反应的摆设（压缩依赖 ContextPlugin 的压缩策略）。
 * @param compactEnabled 插件在、但当前不满足执行条件（生成中 / 空会话）时只禁用、不隐藏，
 *   与原先顶栏图标的启用规则一致。
 */
@Composable
internal fun ContextPanel(
    container: AppContainer,
    used: Int,
    window: Int,
    cacheHit: Double?,
    compacting: Boolean,
    compactVisible: Boolean,
    compactEnabled: Boolean,
    /** U111：最近一个回合的解码速度；null = 没有可展示的 stats。 */
    turnSpeed: TurnSpeed? = null,
    sessionAverageTps: Double? = null,
    onCompact: () -> Unit,
    onOpenCache: () -> Unit,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    val fraction = if (window > 0) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    DockPanelCard(
        stringResource(R.string.chat_context_title),
        modifier,
        // 动作行进卡片底部固定区：数据再多也不会把「缓存 / 压缩上下文」藏到滚动区底下。
        actions = {
            TextButton(
                onClick = onOpenCache,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.chat_cache_panel), style = MaterialTheme.typography.labelSmall)
            }
            if (compactVisible) {
                TextButton(
                    onClick = onCompact,
                    enabled = compactEnabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.chat_compact_now), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    ) {
        Text(
            stringResource(R.string.chat_context_usage, formatTokens(used), formatTokens(window)) +
                " · " + stringResource(R.string.chat_context_detail_ratio, (fraction * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.chat_context_detail_threshold,
                formatTokens(container.settings.compactionReserveTokens),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(
                R.string.chat_context_detail_keep,
                formatTokens(container.settings.compactionKeepRecentTokens),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.chat_context_detail_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compacting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.chat_compacting), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (cacheHit != null) {
            Text(
                stringResource(R.string.chat_cache_hit, (cacheHit * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // U111：解码速度明细。只展示真实观测到的回合；重启 / 切走后的旧回合没有 stats，不伪造。
        // 2026-11-20：五个指标压成两行（一行三个）——数字一个不删，只改排版，
        // 面板不再需要往下滚就能看全，也不会再靠滚动区去藏动作行。
        val parts = mutableListOf<String>()
        turnSpeed?.let { speed ->
            parts += stringResource(R.string.chat_speed_context_output, speed.outputTokens)
            parts += stringResource(R.string.chat_speed_context_decode, formatSeconds(speed.decodeMs))
            parts += stringResource(R.string.chat_speed_turn_avg_label, formatTurnSpeed(speed))
            speed.firstTokenMs?.let { ttft ->
                parts += stringResource(R.string.chat_speed_context_ttft, formatSeconds(ttft))
            }
        }
        sessionAverageTps?.let { avg ->
            parts += stringResource(R.string.chat_speed_context_session, formatSpeed(avg))
        }
        if (parts.isNotEmpty()) {
            Text(parts.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (parts.size > 3) {
                Text(
                    parts.drop(3).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.chat_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.chat_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
