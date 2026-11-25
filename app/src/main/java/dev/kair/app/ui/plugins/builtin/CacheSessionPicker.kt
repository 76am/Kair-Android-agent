package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.storage.SessionMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 缓存面板的**作用域抬头**：面板列在**全局**区域，但它读的是某个会话的缓存报告，
 * 所以「在看哪个会话」必须比任何一行数字更显眼 —— 它是抬头，不是面板里的一行控件。
 *
 * 为什么需要选：`cacheInspector.latest` 存着**所有会话**的报告（`CacheInspector()` 是进程级
 * 单例），「只显示当前会话」原本只是 UI 过滤。只跟随当前会话会让「查昨天那个会话的命中率
 * 为什么掉」无路可走；而隐藏面板入口又会让跨会话对比彻底没有落点。所以留入口、给选择。
 *
 * 形态（第二轮改版，负责人两轮反馈各打回一次）：
 * - **标题块**而不是散行：`titleSmall` 标题「会话」+ 可点的会话名行 + 一行状态，
 *   与同面板的「诊断」「分段」两个块同级 —— 上一版把那行塞在标题序列之外，
 *   就成了「一屏 `labelSmall` 里凭空多出的一个控件」，突兀。
 * - **可点暗示靠 ▾ 图标，不靠边框**：沿用仓里已有的原语
 *   （`clip(MaterialTheme.shapes.small)` + `clickable` + `Icons.Filled.ArrowDropDown`；
 *   圆角按局部层级取主题 Shapes），不新造自绘控件。
 *   第一版用 `OutlinedButton` 全宽边框 —— 在一屏 `labelSmall` 文本里格格不入，打回；
 *   第二版换成裸 `TextButton` 无边框 —— 看不出可点，又打回。
 * - **状态显式**：跟随态 = 行不上色、状态行写「跟随当前会话」；手动选过 = 行带
 *   `surfaceVariant` 底色、状态行写「已固定」；菜单里当前项打勾。
 * - 默认显示**当前会话的名字**（[selectedId] 为 null 时取 [currentSessionId]），不是
 *   「查看会话」这类标签：抬头写的是「当前状态」，不是动作名。
 *
 * [currentSessionId] 由调用方传进来而不是自己读 `container.activeSessionId` ——
 * `GlobalStateGuardTest` 的登记表要求 `ui/plugins/` 下每处「读当前会话」都登记并写理由
 * （规则 3），而这里只是一个纯展示组件，读全局状态不是它的职责。调用方（`CachePanel`）
 * 本来就读了，多传一个参数比新开一个读点干净。
 *
 * 抽成独立文件而不是塞进 `CachePanel`：后者加完选择器一度到 518 行，撞
 * `SourceSizeGuardTest` 的 500 硬上限（白名单只准减不准增），诊断区已一并抽去
 * `CacheDiagnosticsSection.kt`。
 */
@Composable
internal fun CacheSessionPicker(
    container: AppContainer,
    selectedId: String?,
    currentSessionId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reports by container.cacheInspector.latest.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    // 名字与「最近活跃」都从持久化元数据取，与请求诊断面板同口径
    // （见 RequestLogPanel.buildRequestGroups）。runCatching：存储读失败时退化成短 id
    // 与插入序，不让整个面板崩掉。
    val meta = remember(reports.keys) { sessionMetaOf(container) }
    val projects = remember(meta.keys) { projectNamesOf(container) }
    // 菜单里同名会话分不出谁是谁，条目尾部带最近活跃时间；与抽屉会话列表同一格式（SessionDrawer）。
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 实际在查的会话：选了就用选的，没选就跟随当前。
    val effectiveId = selectedId ?: currentSessionId
    val label = effectiveId?.let { cacheSessionLabel(it, meta, projects) }
        ?: stringResource(R.string.cache_session_none)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.cache_session_label), style = MaterialTheme.typography.titleSmall)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        // 固定态与跟随态要一眼分得出：底色是唯一的「这不是当前会话」提示。
                        if (selectedId == null) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { expanded = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.cache_session_switch),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // 第一项 = 回到「跟随当前会话」。它在 reports 里没有条目时也要可选 ——
                // 否则当前会话还没发过消息就把用户锁死在别的历史会话上。
                DropdownMenuItem(
                    text = {
                        Text(
                            currentSessionId?.let {
                                stringResource(
                                    R.string.cache_session_follow,
                                    cacheSessionLabel(it, meta, projects),
                                )
                            } ?: stringResource(R.string.cache_session_follow_none),
                        )
                    },
                    leadingIcon = { if (selectedId == null) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                val others = reports.keys
                    .filter { it != currentSessionId }
                    .sortedByDescending { id -> meta[id]?.updatedAt ?: 0L }
                if (others.isNotEmpty()) HorizontalDivider()
                others.forEach { sessionId ->
                    DropdownMenuItem(
                        text = {
                            Text(cacheSessionLabel(sessionId, meta, projects), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = { if (sessionId == selectedId) Icon(Icons.Filled.Check, null) },
                        trailingIcon = {
                            meta[sessionId]?.let {
                                Text(
                                    fmt.format(Date(it.updatedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelect(sessionId)
                            expanded = false
                        },
                    )
                }
            }
        }
        // 状态行：什么时候算的 + 现在是跟随还是固定。缺了这两句，用户分不清
        // 「这是当前会话的数字」还是「我还停在昨天那个会话上」。
        val updatedAt = effectiveId?.let { meta[it]?.updatedAt }
        Text(
            stringResource(
                R.string.cache_session_status,
                updatedAt?.let { stringResource(R.string.cache_session_updated, fmt.format(Date(it))) }
                    ?: stringResource(R.string.cache_session_no_time),
                stringResource(
                    if (selectedId == null) R.string.cache_session_following else R.string.cache_session_pinned,
                ),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 会话元数据一次读入：拼名与排序都用它，不逐个 id 重读存储。
 *
 * `internal` 是因为分段详情页（`CachePanel.CacheSegmentDetail`）也要标会话名，
 * 两处共用一个装载点，不各写一遍。
 */
internal fun sessionMetaOf(container: AppContainer): Map<String, SessionMeta> = runCatching {
    container.storage.listSessions().associateBy { it.id }
}.getOrDefault(emptyMap())

/**
 * 项目 id → 名字。与 [sessionMetaOf] 一起用，给 `cacheSessionLabel` 拼「项目 · 会话」。
 * runCatching：存储读失败时退化成只有会话名。
 */
internal fun projectNamesOf(container: AppContainer): Map<String, String> = runCatching {
    container.storage.listProjects().associate { it.id to it.name }
}.getOrDefault(emptyMap())

/** 会话显示名；缺元数据时短 id（与请求诊断面板同口径）。 */
@Composable
internal fun sessionLabelOf(container: AppContainer, sessionId: String): String {
    val meta = remember(sessionId) { sessionMetaOf(container) }
    val projects = remember(meta.keys) { projectNamesOf(container) }
    return cacheSessionLabel(sessionId, meta, projects)
}

/**
 * 显示名。取「项目 · 会话」；缺项目元数据时只显会话名；缺会话元数据时短 id。
 *
 * 文案走 `stringResource` 而不是 `" · "` 字面拼接 —— 这是 `DocsConsistencyTest` 的硬要求
 * （A6-3：`strings.xml` 里定义了却无人引用的条目算红）。
 */
@Composable
private fun cacheSessionLabel(
    sessionId: String,
    meta: Map<String, SessionMeta>,
    projects: Map<String, String>,
): String {
    val session = meta[sessionId]
    val project = session?.let { projects[it.projectId] }
    return when {
        session == null -> stringResource(R.string.cache_session_unknown, sessionId.take(8))
        project == null -> session.name
        else -> stringResource(R.string.cache_session_group, project, session.name)
    }
}
