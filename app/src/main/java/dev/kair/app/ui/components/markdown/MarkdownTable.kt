package dev.kair.app.ui.components.markdown

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.kair.app.R
import dev.kair.app.ui.components.LocalHorizontalPanExclusion
import dev.kair.app.ui.components.claimHorizontalPan
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon
import kotlinx.coroutines.delay

/**
 * 表格卡（2026-11-21 立）：表头行给「复制源码 / 放大」，表格本体横向滚动。
 *
 * 不做下载：负责人 2026-11-21 明确去掉 CSV 下载。
 * 复制的永远是 [MdBlock.Table.source] —— 原始 Markdown，不是界面上重新拼的文本。
 */
@Composable
internal fun MarkdownTableCard(table: MdBlock.Table) {
    var expanded by remember(table.source) { mutableStateOf(false) }
    var copied by remember(table.source) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1500)
        copied = false
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标按钮没有可见文字，复制后把标题换成「已复制」作为反馈（1.5s 后还原）。
            Text(
                text = stringResource(if (copied) R.string.markdown_copied else R.string.markdown_table),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TableAction(
                glyph = PluginGlyph.Copy,
                label = stringResource(if (copied) R.string.markdown_copied else R.string.chat_copy),
                onClick = {
                    clipboard.setText(AnnotatedString(table.source))
                    copied = true
                },
            )
            TableAction(
                glyph = PluginGlyph.Expand,
                label = stringResource(R.string.action_fullscreen),
                onClick = { expanded = true },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MarkdownTableGrid(table, Modifier.fillMaxWidth())
    }
    if (expanded) {
        MarkdownTableDialog(table, onDismiss = { expanded = false })
    }
}

/**
 * 表头动作：48dp 命中盒、28dp 圆形压层（与「⋯」同一套 [pressLayer] 口径）。
 * 表格卡本身不参与长按菜单的判定，按钮单击不会和气泡长按抢手势。
 */
@Composable
private fun TableAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val alpha = rememberPressLayer(interaction)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).pressLayer(alpha, color),
            contentAlignment = Alignment.Center,
        ) {
            SvgPathIcon(glyph, color, Modifier.size(16.dp))
        }
    }
}

/**
 * 表格网格：先按内容量一遍列宽，再决定铺满还是横向滚动。
 *
 * 量宽用 [rememberTextMeasurer] 而不是固有尺寸：Row 的固有宽度拿不到「跨行最大值」，
 * 会变成每行各窄各的，列就对不齐。
 */
@Composable
internal fun MarkdownTableGrid(table: MdBlock.Table, modifier: Modifier = Modifier) {
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val bodyStyle = MaterialTheme.typography.bodySmall
    val firstColumnStyle = bodyStyle.copy(fontFamily = FontFamily.Monospace)
    val textColor = MaterialTheme.colorScheme.onSurface
    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val exclusion = LocalHorizontalPanExclusion.current
    val horizontalPadding = 10.dp
    val verticalPadding = 8.dp
    val minCell = 56.dp
    val maxCell = 220.dp

    BoxWithConstraints(modifier) {
        val available = constraints.maxWidth
        val horizontalPaddingPx = with(density) { horizontalPadding.roundToPx() }
        val minCellPx = with(density) { minCell.roundToPx() }
        val maxCellPx = with(density) { maxCell.roundToPx() }
        val layout = remember(table.header, table.rows, available, minCellPx, maxCellPx, headerStyle, bodyStyle) {
            val result = IntArray(table.header.size) { minCellPx }
            fun measureInto(column: Int, text: String, style: TextStyle) {
                if (column >= result.size) return
                val width = measurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    constraints = Constraints(maxWidth = maxCellPx),
                ).size.width
                result[column] = maxOf(result[column], width + horizontalPaddingPx * 2)
            }
            table.header.forEachIndexed { column, text -> measureInto(column, text, headerStyle) }
            table.rows.forEach { row ->
                row.forEachIndexed { column, text ->
                    measureInto(column, text, if (column == 0) firstColumnStyle else bodyStyle)
                }
            }
            // 内容比卡片窄时按比例铺满，避免整张表挤在左边；宽了就让外层横向滚动。
            val contentWidth = result.sum()
            if (available != Constraints.Infinity && available > contentWidth && contentWidth > 0) {
                val extra = available - contentWidth
                result.indices.forEach { column -> result[column] += extra * result[column] / contentWidth }
            }
            // 只有真的需要横向滚动时才抢抽屉手势：短表不改变既有右滑开抽屉的手感。
            result to (available != Constraints.Infinity && contentWidth > available)
        }
        val columnWidths = layout.first
        val scrollable = layout.second
        val totalWidth = with(density) { columnWidths.sum().toDp() }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.claimHorizontalPan(exclusion) else Modifier)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(Modifier.width(totalWidth).background(headerBackground)) {
                table.header.forEachIndexed { column, text ->
                    TableCell(
                        text = text,
                        width = with(density) { columnWidths[column].toDp() },
                        style = headerStyle,
                        color = textColor,
                        align = table.aligns.getOrElse(column) { MdAlign.Start },
                        horizontalPadding = horizontalPadding,
                        verticalPadding = verticalPadding,
                    )
                }
            }
            HorizontalDivider(color = lineColor, modifier = Modifier.width(totalWidth))
            table.rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.width(totalWidth)) {
                    row.forEachIndexed { column, text ->
                        TableCell(
                            text = text,
                            width = with(density) { columnWidths[column].toDp() },
                            style = if (column == 0) firstColumnStyle else bodyStyle,
                            color = if (column == 0) dimColor else textColor,
                            align = table.aligns.getOrElse(column) { MdAlign.Start },
                            horizontalPadding = horizontalPadding,
                            verticalPadding = verticalPadding,
                        )
                    }
                }
                if (rowIndex != table.rows.lastIndex) {
                    HorizontalDivider(color = lineColor, modifier = Modifier.width(totalWidth))
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    style: TextStyle,
    color: Color,
    align: MdAlign,
    horizontalPadding: Dp,
    verticalPadding: Dp,
) {
    val spans = remember(text) { parseInline(text) }
    Box(Modifier.width(width).padding(horizontal = horizontalPadding, vertical = verticalPadding)) {
        Text(
            text = markdownAnnotated(spans),
            modifier = Modifier.fillMaxWidth(),
            style = style,
            color = color,
            textAlign = when (align) {
                MdAlign.Start -> TextAlign.Start
                MdAlign.Center -> TextAlign.Center
                MdAlign.End -> TextAlign.End
            },
        )
    }
}

/**
 * 流式骨架：最后一块表格还在写时先占住位置，闭合（遇到空行或流结束）后再换成真表格。
 * 高度固定为三行，不随收到的半行抖动。
 */
@Composable
internal fun MarkdownTableSkeleton() {
    val label = stringResource(R.string.markdown_table_writing)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "table-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "table-skeleton-alpha",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .fillMaxWidth(1f - index * 0.18f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(base.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun MarkdownTableDialog(table: MdBlock.Table, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 560.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.markdown_table),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TableAction(
                        glyph = PluginGlyph.Close,
                        label = stringResource(R.string.action_close),
                        onClick = onDismiss,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownTableGrid(table, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
