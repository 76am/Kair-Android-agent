package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.CacheReport
import dev.kair.app.core.agent.RequestSegment
import dev.kair.app.core.agent.SegmentChange
import dev.kair.app.core.agent.SegmentKind
import dev.kair.app.ui.components.formatSize

/**
 * 缓存与诊断面板：最近一次请求的前缀分段、稳定性判定、命中率，
 * 以及**失败可见**的四类事件（段落缺席 / 贡献点异常、插件装载失败、
 * 技能文件跳过、插件投递失败计数）。
 *
 * 会话口径：报告与诊断同用选择器选定的会话（未选定 = 跟随当前）；插件装载 / 技能跳过 /
 * 投递失败是**应用级**，不随会话变。
 *
 * 拆分：诊断区在 `CacheDiagnosticsSection.kt`，会话选择器在 `CacheSessionPicker.kt` ——
 * 本文件加完选择器一度 518 行，撞 SourceSizeGuardTest 的 500 硬上限（白名单只准减不准增），
 * 现在 427 行。再长要先拆。
 *
 * 诊断只读：不参与请求装配，也不改变任何一位请求字节。
 */
@Composable
internal fun CachePanel(container: AppContainer) {
    val reports by container.cacheInspector.latest.collectAsState()
    // 选中某分段后切换到详情视图（同一面板内），可查看完整内容与相对上回合的新增。
    var selected by remember { mutableStateOf<SegmentKind?>(null) }
    // null = 跟随当前会话（老行为）；非 null = 用户从下拉里选定的某个会话。
    var picked by remember { mutableStateOf<String?>(null) }

    // 实际在查的会话：选了就用选的，没选就跟随当前。**只读一次** —— 读多次会让
    // `GlobalStateGuardTest` 规则 3 的额度（本文件 3 次）超支，而且四次读若中途
    // 当前会话变了，面板上下会来自不同会话。
    val effectiveSessionId = picked ?: container.activeSessionId

    // 取**选中会话**的数据：多会话并发时，若按「最后一次装配」取就会出现面板串会话。
    val r = effectiveSessionId?.let { reports[it] }
    val detail = r?.let { report -> selected?.let { kind -> report.segments.firstOrNull { it.kind == kind } } }
    if (r != null && detail != null) {
        CacheSegmentDetail(
            report = r,
            seg = detail,
            // 详情页是独立视图，选过历史会话时必须标明在看哪个会话。
            sessionLabel = effectiveSessionId?.let { sessionLabelOf(container, it) }.orEmpty(),
            onBack = { selected = null },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 作用域抬头：面板列在全局区域，读的却是某个会话的报告，所以「在看哪个会话」
        // 放最前 —— 它决定下面所有数字的归属。做成「标题 + 会话名 + 状态」三行一个块，
        // 与下面的「诊断」「分段」同级；上一版是一行凭空挂着的控件，两轮都被打回。
        CacheSessionPicker(
            container = container,
            selectedId = picked,
            currentSessionId = effectiveSessionId,
            onSelect = { picked = it },
        )
        HorizontalDivider()
        if (r == null) {
            Text(
                stringResource(R.string.cache_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            CacheReportBody(r)

            Text(stringResource(R.string.cache_segments), style = MaterialTheme.typography.titleSmall)
            r.segments.forEach { seg ->
                val change = r.changes[seg.kind]
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = seg.kind },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(seg.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                changeLabel(change),
                                style = MaterialTheme.typography.labelSmall,
                                color = changeColor(change),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(formatSize(seg.bytes), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            seg.digest,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (seg.preview.isNotBlank()) {
                            Text(
                                seg.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        // 诊断挪到报告之后（原来挤在命中率前面）：主数字先给、异常后给，无异常时只剩
        // 一行「全部正常」，不该把报告推到屏幕外。
        // 诊断与上面的报告同会话：各取各的当前会话就会出现「报告是昨天那个、诊断是这个」，
        // 等于另一种串会话。
        DiagnosticsSection(container, effectiveSessionId = effectiveSessionId)
    }
}

/**
 * 单个分段的详情：展示相对上一回合的**新增内容**与本次**完整内容**，
 * 让用户看清到底是哪段文本被塞进了缓存前缀（进而解释命中率变化）。
 *
 * @param sessionLabel 详情页是独立视图，选过历史会话时必须标明在看哪个会话。
 */
@Composable
private fun CacheSegmentDetail(
    report: CacheReport,
    seg: RequestSegment,
    sessionLabel: String,
    onBack: () -> Unit,
) {
    val change = report.changes[seg.kind]
    // 「本次新增」在装配时就已算好并截断到展示预算：此处不再对整段历史做
    // startsWith / removePrefix 复制（那一步在长会话下正是主线程 OOM 的来源）。
    val appended = report.appended[seg.kind]
    // 可读视图会加「思考」这类给人看的标签；核对「到底发了什么」要切到原始前缀。
    var rawMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.cache_detail_back)) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(seg.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(changeLabel(change), style = MaterialTheme.typography.labelSmall, color = changeColor(change))
        }
        // 详情页是独立视图：选过历史会话时必须标明在看哪个会话，否则数字读完不知道是谁的。
        if (sessionLabel.isNotBlank()) {
            Text(
                stringResource(R.string.cache_detail_session, sessionLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.cache_detail_meta, formatSize(seg.bytes), seg.digest),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { rawMode = false }) {
                Text(
                    stringResource(R.string.cache_detail_view_readable),
                    color = if (rawMode) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = { rawMode = true }) {
                Text(
                    stringResource(R.string.cache_detail_view_raw),
                    color = if (rawMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rawMode) {
            Text(
                stringResource(R.string.cache_detail_raw_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (change) {
            SegmentChange.NOT_APPLICABLE -> Text(
                stringResource(R.string.cache_detail_not_prefixed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentChange.UNCHANGED -> Text(
                stringResource(R.string.cache_detail_unchanged),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SegmentChange.REWRITTEN -> Text(
                stringResource(R.string.cache_detail_rewritten),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        if (appended != null && appended.text.isNotBlank()) {
            Text(
                stringResource(R.string.cache_detail_added, appended.bytes),
                style = MaterialTheme.typography.titleSmall,
            )
            SelectionContainer {
                Text(
                    if (rawMode) appended.raw else appended.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Text(stringResource(R.string.cache_detail_full), style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(
                if (rawMode) seg.raw else seg.readable,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun changeLabel(change: SegmentChange?): String = stringResource(
    when (change) {
        SegmentChange.NEW -> R.string.cache_change_new
        SegmentChange.UNCHANGED -> R.string.cache_change_unchanged
        SegmentChange.APPENDED -> R.string.cache_change_appended
        SegmentChange.REWRITTEN -> R.string.cache_change_rewritten
        SegmentChange.NOT_APPLICABLE, null -> R.string.cache_change_na
    },
)

@Composable
private fun changeColor(change: SegmentChange?): Color = when (change) {
    SegmentChange.REWRITTEN -> MaterialTheme.colorScheme.error
    SegmentChange.APPENDED, SegmentChange.UNCHANGED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
