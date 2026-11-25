package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kair.app.R

/**
 * 宿主注入的系统提示（后台任务完成 / 工具集变化）：**分隔条**，不是卡片。
 *
 * 它夹在两个回合折叠卡之间出现，如果也做成同宽同圆角的卡片，就会和「已处理 N 步」
 * 贴成一摞、分不清谁是谁。改成一根细线 + 中间灰字之后，它退成「回合之间的一件事」，
 * 与折叠体内的步分隔线共用同一套语言；点开才显示原文。
 */
@Composable
internal fun SystemNoteBar(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    val line = MaterialTheme.colorScheme.outlineVariant
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    // 行高即 48dp 命中盒（U105 口径），原先 14dp 上下内边距改由行高承担，
    // 文字 / 分隔线仍垂直居中，位置与总高度（≈47dp）不变。
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(line))
            Row(
                modifier = Modifier
                    // 命中盒必须写在 size 之后、clickable 之前：先定 48dp 行高，
                    // 触点才跟着 48dp（同 TurnStep / MoreActionsEntry）。
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { open = !open }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = stringResource(R.string.chat_system_note),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        letterSpacing = 0.09.em,
                    ),
                    color = tint,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp).rotate(if (open) 180f else 0f),
                )
            }
            Box(Modifier.weight(1f).height(1.dp).background(line))
        }
        if (open) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, line, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = tint,
                    )
                }
            }
        }
    }
}
