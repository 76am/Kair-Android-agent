package dev.kair.app.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import kotlinx.coroutines.delay

/**
 * agent 正文的 Markdown 渲染（2026-11-21 立）。
 *
 * 只接在 assistant 消息上：用户消息保持纯文本（用户写什么就显示什么，不该替人解释格式）。
 * 每个 [text] 是 `textParagraphs` 的一段 —— 段与段之间的 8dp 间距由调用方的 Column 给，
 * 本组件只管段内块与块之间的间距。
 *
 * [running] 只影响**最后一块表格**：流式期间表格还没写完，先画骨架；
 * 流结束后即使没有收尾空行也按完整表格渲染。
 */
@Composable
internal fun MarkdownBody(
    text: String,
    running: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Paragraph -> MarkdownText(block.text, MaterialTheme.typography.bodyMedium, color)
                is MdBlock.Heading -> MarkdownHeading(block, color)
                is MdBlock.BulletList ->
                    MarkdownList(block.items, ordered = false, start = 1, color = color)
                is MdBlock.OrderedList ->
                    MarkdownList(block.items, ordered = true, start = block.start, color = color)
                is MdBlock.Quote -> MarkdownQuote(block.text)
                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.Code -> MarkdownCodeCard(block)
                is MdBlock.Table -> {
                    val writing = block.open && running && index == blocks.lastIndex
                    if (writing) MarkdownTableSkeleton() else MarkdownTableCard(block)
                }
            }
        }
    }
}

/**
 * 一条 assistant 消息的全部正文段。
 *
 * [streamingLast] 由调用方判：只有「回合在跑、且最后一部分仍是正文」才为 true。
 * 早先说完的段（后面已经跟了工具调用）即使整回合还在跑，表格也不该继续挂骨架。
 */
@Composable
internal fun MarkdownParagraphs(
    paragraphs: List<String>,
    streamingLast: Boolean,
    color: Color,
) {
    paragraphs.forEachIndexed { index, paragraph ->
        MarkdownBody(
            text = paragraph,
            running = streamingLast && index == paragraphs.lastIndex,
            color = color,
        )
    }
}

@Composable
private fun MarkdownHeading(block: MdBlock.Heading, color: Color) {
    val typography = MaterialTheme.typography
    val style = when (block.level) {
        1, 2 -> typography.titleMedium
        3 -> typography.titleSmall
        else -> typography.bodyLarge
    }
    MarkdownText(block.text, style.copy(fontWeight = FontWeight.SemiBold), color)
}

@Composable
private fun MarkdownList(
    items: List<String>,
    ordered: Boolean,
    start: Int,
    color: Color,
) {
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = if (ordered) "${start + index}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = markerColor,
                    modifier = Modifier.widthIn(min = 24.dp).padding(end = 4.dp),
                )
                MarkdownText(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MarkdownQuote(text: String) {
    val bar = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(color = bar, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 10.dp),
    ) {
        MarkdownText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarkdownText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) {
    val spans = remember(text) { parseInline(text) }
    Text(
        text = markdownAnnotated(spans),
        modifier = modifier,
        style = style,
        color = color,
        textAlign = align,
    )
}

/**
 * 行内片段 → AnnotatedString。链接用 `LinkAnnotation.Url`，点开走系统浏览器；
 * 不给链接套 `ClickableText` —— 它会吃掉长按的 down 事件，把气泡菜单弄丢。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
internal fun markdownAnnotated(spans: List<MdSpan>): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeColor = MaterialTheme.colorScheme.onSurface
    return remember(spans, linkColor, codeBackground, codeColor) {
        buildAnnotatedString {
            spans.forEach { span ->
                val spanStyle = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        span.href != null -> TextDecoration.Underline
                        span.strike -> TextDecoration.LineThrough
                        else -> null
                    },
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified,
                    color = when {
                        span.href != null -> linkColor
                        span.code -> codeColor
                        else -> Color.Unspecified
                    },
                )
                val href = span.href
                if (href == null) {
                    withStyle(spanStyle) { append(span.text) }
                } else {
                    withLink(LinkAnnotation.Url(href, TextLinkStyles(style = spanStyle))) {
                        withStyle(spanStyle) { append(span.text) }
                    }
                }
            }
        }
    }
}

/**
 * 围栏代码卡：语言标签 + 复制。
 *
 * 代码折行而不是横向滚动：与气泡里其它文字保持同一条阅读流，流式期间也不会出现
 * 「内容在屏幕外」的错觉；代价是长行会被折成多行，可点复制拿原文。
 */
@Composable
internal fun MarkdownCodeCard(block: MdBlock.Code) {
    val clipboard = LocalClipboardManager.current
    val copyLabel = stringResource(R.string.chat_copy)
    val copiedLabel = stringResource(R.string.markdown_copied)
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1500)
        copied = false
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifBlank { stringResource(R.string.markdown_code) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(block.code))
                    copied = true
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (copied) copiedLabel else copyLabel,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = block.code.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}
