package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.storage.Project
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer

/** 头部高度（U115 返工）：只剩居中的项目名，与项目行同档。 */
private val DrawerHeaderHeight = 52.dp

/**
 * 抽屉头部：只有**居中的当前项目名**，不带 ▾、不带 ＋（U115 返工，负责人真机反馈）。
 *
 * 这里原来是「项目名 + ＋ + ⌄」，⌄ 拉起 M3 `DropdownMenu`：那层浮层会从 65% 的抽屉列
 * 溢出去盖住右边的聊天正文（菜单左缘在屏宽 23%、宽 58%，两边边缘谁也不跟谁），
 * 菜单里每行还挂 ⋮，点开是叠在第一个浮层上的第二层。现在抽屉内不再出现浮层菜单：
 * 切换走 [DrawerTabs] 分段 + [DrawerProjectList] 就地列表，新建动作钉在列底，
 * 重命名 / 删除走对话框。
 *
 * 点项目名仍会切到「项目」段 —— 箭头没了，这只是个备用入口，分段控件才是明面上的开关。
 */
@Composable
internal fun DrawerHeader(project: Project?, onOpenProjects: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val layer = rememberPressLayer(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DrawerHeaderHeight)
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpenProjects)
            .pressLayer(layer, MaterialTheme.colorScheme.onSurface)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            project?.name ?: stringResource(R.string.projects_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分段「项目 → 会话」（U115 返工）：项目在左、会话在右，中间一支向右箭头（14dp）表示流向 ——
 * 选完项目顺箭头落到会话列（点项目后代码里也按这个方向自动跳段）。
 *
 * 选中态用白底 + 粗字：抽屉底本身是 `surfaceContainerLow` 的灰，下划线在那上面读不出来。
 */
@Composable
internal fun DrawerTabs(
    tabProjects: Boolean,
    onProjects: () -> Unit,
    onSessions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrawerTab(
            label = stringResource(R.string.drawer_tab_projects),
            selected = tabProjects,
            onClick = onProjects,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DrawerTab(
            label = stringResource(R.string.drawer_tab_sessions),
            selected = !tabProjects,
            onClick = onSessions,
        )
    }
}

/** 分段里的一格：48dp 量级的命中区（U105），按下走 [pressLayer] 而不是水波纹。 */
@Composable
private fun DrawerTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val layer = rememberPressLayer(interaction)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressLayer(layer, MaterialTheme.colorScheme.onSurface)
            // 当前段不能只靠底色区分，读屏要能听到「已选中」。
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/**
 * 项目列表（就地展开，不是浮层）：当前项白底 + 6dp 实心点（与状态条的待机点同形），
 * 行右侧是会话数与「⋯」—— 二级动作走对话框，不再叠第二层浮层。
 *
 * 点项目 = 切项目：有会话开最近一条，0 会话直接进该项目的新会话页（U116）。
 */
@Composable
internal fun DrawerProjectList(
    projects: List<Project>,
    sessionCounts: Map<String, Int>,
    currentId: String?,
    onPick: (Project) -> Unit,
    onManage: (Project) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        if (projects.isEmpty()) {
            // 项目段不能是白板（U116 无项目页真机反馈）：0 个项目时这里曾是一整块空白，
            // 用户读成「新建项目的页面挂了」。列底的「新建项目」才是动作，这里只给一句引导。
            item {
                Text(
                    stringResource(R.string.projects_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                )
            }
        }
        items(projects, key = { it.id }) { p ->
            val current = p.id == currentId
            val interaction = remember(p.id) { MutableInteractionSource() }
            val layer = rememberPressLayer(interaction)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (current) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null) { onPick(p) }
                    .pressLayer(layer, MaterialTheme.colorScheme.onSurface)
                    .semantics { this.selected = current }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (current) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                sessionCounts[p.id]?.let { count ->
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onManage(p) }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_project_actions))
                }
            }
        }
    }
}
