package dev.kair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R

// 本文件同时提供两种折叠块，视觉职责不同，不要互相替换：
// - CollapsibleSection：卡片式小块（自带底色 / 圆角 / 内边距），用于转录里的思考、
//   工具调用组、压缩检查点等「一段可展开内容」，可带 trailing 状态与 pending 转圈。
// - CollapsibleGroup：分区标题式（无卡片外壳，展开后是普通纵向列表），用于设置 / 插件
//   页把多块内容按分区标题分组收起，避免整页过长。

/**
 * 卡片式折叠块：正文收起后只剩标题行。默认收起，避免长思考与工具输出占满转录；
 * 每个块各自管理展开状态。
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    trailing: String? = null,
    trailingColor: Color = titleColor,
    pending: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = titleColor,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelSmall, color = trailingColor)
                Spacer(Modifier.size(4.dp))
            }
            if (pending) {
                Text(
                    stringResource(R.string.tool_running),
                    style = MaterialTheme.typography.labelSmall,
                    color = titleColor,
                )
                Spacer(Modifier.size(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(2.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse else R.string.action_expand,
                ),
                tint = titleColor,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { content() }
        }
    }
}

/**
 * 可折叠分区：标题行点击展开 / 收起，内容按 [defaultExpanded] 决定初始状态。默认收起，
 * 让插件 / 设置等分区多的页面不必一屏铺满长列表（需要默认展开时显式传 true）。
 */
@Composable
internal fun CollapsibleGroup(
    title: String,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.action_collapse else R.string.action_expand,
                ),
            )
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}
