package dev.kair.app.ui.plugins.builtin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.SectionIssue

/**
 * 失败可见：只读事件。段落诊断与压缩降级按 [effectiveSessionId]（= 选择器选中的会话）；
 * 装载 / 投递是应用级，直接复用宿主已有的失败记录（`DynamicPluginHost` /
 * `SkillRegistry` / `Diagnostics`），不另立第二套采集。
 *
 * @param effectiveSessionId 与面板其余部分同口径，避免「报告是一个会话、诊断是另一个」。
 */
@Composable
internal fun DiagnosticsSection(container: AppContainer, effectiveSessionId: String?) {
    val issuesBySession by container.diagnostics.sectionIssues.collectAsState()
    val compactionBySession by container.diagnostics.compactionFailures.collectAsState()
    val emptyBySession by container.diagnostics.emptyResponses.collectAsState()
    val stopTimeoutBySession by container.diagnostics.stopTimeouts.collectAsState()
    val delivery by container.diagnostics.deliveryFailures.collectAsState()
    // 功能就地记录的提示（如记忆插件「已隐藏 N 条过期」「已截断 N 条」）：只读、不参与正文。
    val notices by container.diagnostics.notices.collectAsState()
    val issues = effectiveSessionId?.let { issuesBySession[it] }.orEmpty()
    val compactionFailure = effectiveSessionId?.let { compactionBySession[it] }
    val emptyCount = effectiveSessionId?.let { emptyBySession[it] } ?: 0
    val stopTimeout = effectiveSessionId?.let { stopTimeoutBySession[it] }
    val pluginFailures = container.dynamicPlugins.failedIds()
    val skillFailures = container.skills.loadFailures

    Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleSmall)
    val clear = issues.isEmpty() &&
        compactionFailure == null &&
        emptyCount == 0 &&
        stopTimeout == null &&
        pluginFailures.isEmpty() &&
        skillFailures.isEmpty() &&
        delivery.permanent == 0 &&
        delivery.temporary == 0 &&
        notices.isEmpty()
    if (clear) {
        Text(
            stringResource(R.string.diag_all_clear),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // 压缩降级排最前：它改变了历史内容，比「某段没进提示」更值得先看见。
    compactionFailure?.let { reason ->
        DiagnosticLine(
            label = stringResource(R.string.diag_compaction_failed),
            text = reason,
            detail = null,
            isError = true,
        )
    }
    // 停止超时（U57 ③）：看门狗把会话强推回空闲，否则「永久转圈」会变成「悄悄回空闲」，
    // 等于新的静默失败。紧跟压缩降级，同属「已经发生、用户必须知道」的一类。
    stopTimeout?.let { detail ->
        DiagnosticLine(
            label = stringResource(R.string.diag_stop_timeout),
            text = detail,
            detail = null,
            isError = true,
        )
    }
    // 空回合（U48 ②）：不落盘、历史无痕，诊断是它唯一的可见痕迹 —— 面板必须能看见。
    if (emptyCount > 0) {
        Text(
            stringResource(R.string.diag_empty_responses, emptyCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    issues.forEach { issue ->
        DiagnosticLine(
            label = stringResource(
                if (issue.kind == SectionIssue.Kind.FAILED) R.string.diag_section_failed
                else R.string.diag_section_absent,
            ),
            text = issue.id,
            detail = issue.detail,
            isError = issue.kind == SectionIssue.Kind.FAILED,
        )
    }
    // 功能就地记录的提示（记忆插件：过期隐藏 / 预算截断 / 文件不可读）。
    notices.values.forEach { text ->
        Text(
            stringResource(R.string.diag_notice, text),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pluginFailures.forEach { id ->
        DiagnosticLine(
            label = stringResource(R.string.diag_plugin_failed),
            text = id,
            detail = container.dynamicPlugins.failureReason(id),
            isError = true,
        )
    }
    skillFailures.forEach { (name, reason) ->
        DiagnosticLine(
            label = stringResource(R.string.diag_skill_skipped),
            text = name,
            detail = reason,
            isError = true,
        )
    }
    if (delivery.permanent > 0 || delivery.temporary > 0) {
        Text(
            stringResource(R.string.diag_delivery, delivery.permanent, delivery.temporary),
            style = MaterialTheme.typography.labelSmall,
            color = if (delivery.permanent > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
internal fun DiagnosticLine(label: String, text: String, detail: String?, isError: Boolean) {
    Text(
        detail?.let { stringResource(R.string.diag_issue_with_detail, label, text, it) }
            ?: stringResource(R.string.diag_issue, label, text),
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
