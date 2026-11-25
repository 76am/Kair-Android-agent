package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.Compaction
import dev.kair.app.core.model.Part
import dev.kair.app.core.tools.parseToolArguments
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.theme.MonoTrace
import dev.kair.app.ui.theme.MonoTraceMeta

/** 工具输出长的展开上限（字符）；超出即截断并加提示（见 [toolPreview]）。 */
private const val ToolPreviewMaxChars = 4000

/** 一屏里最多平铺几个调用；超出的部分收在「展开其余 N 次」后面，避免一回合廿次读取铺满几屏。 */
private const val MaxInlineTools = 4

/**
 * 展示用截断：超过 [ToolPreviewMaxChars] 时截断并追加一行「已截断」提示，避免用户
 * 误以为工具只产出了这么点内容。上限与提示集中在此，不再散落魔法数字。
 */
@Composable
private fun toolPreview(text: String): String =
    if (text.length <= ToolPreviewMaxChars) {
        text
    } else {
        text.take(ToolPreviewMaxChars) + "\n" +
            stringResource(R.string.chat_tool_truncated, ToolPreviewMaxChars)
    }

/**
 * 工具类别 → 线稿图标（[toolGroupCategory] 已有分类，不再另建一套）。
 *
 * 一屏廿次调用里，形状比工具名更快认出「这是在读文件、还是在跑命令」；
 * 认不出的工具落 [PluginGlyph.Box]（方框 + 十字），不画成问号 —— 问号像报错。
 */
internal fun toolGlyph(name: String): String = when (toolGroupCategory(name)) {
    "command" -> PluginGlyph.Terminal
    "read" -> PluginGlyph.File
    "edit" -> PluginGlyph.Edit
    "browse" -> PluginGlyph.Folder
    "search" -> PluginGlyph.Search
    "subagent" -> PluginGlyph.People
    "skill" -> PluginGlyph.Spark
    "plugin" -> PluginGlyph.Plug
    "browser" -> PluginGlyph.Globe
    "ask" -> PluginGlyph.Info
    else -> PluginGlyph.Box
}

/**
 * 一个 assistant 回合内的工具调用（U98 改版）。
 *
 * 旧版是「外层折叠块 + 每个调用再各自一个折叠块」，一层套一层 —— 展开一个回合要点两次，
 * 而收起时整回合只剩一句「执行 1 次命令」，看不出它到底执行了什么。新版把每次调用
 * **直接平铺**成 [TraceBlock]（工具名 + 命令 + 输出前三行 + 行数注），与设计稿一致：
 * 工具调用是转录的一部分，不是需要先承认再打开的东西。
 *
 * 平铺的代价是「读取 13 个文件」这类回合会很长，所以超过 [MaxInlineTools] 个之后
 * 收起剩余部分，收尾那行同时给出分项计数（复用 [toolGroupTitle]，它本来就是给这个用的）。
 */
@Composable
internal fun ToolCallGroup(
    calls: List<Part.ToolCall>,
    matched: List<Part.ToolResult?>,
    running: Boolean,
    /** 稳定归属键（消息 id）：调用 id 在流式期间可能被收尾帧修正，不能拿它当状态键。 */
    groupId: String,
) {
    val overflow = calls.size > MaxInlineTools
    // 展开状态挂在**消息**上（不是第一个调用 id）：流式追加时 `calls` 会变长、
    // 调用 id 还可能被收尾帧修正，只有消息 id 从占位气泡诞生起就不变。
    var openAll by rememberSaveable(groupId) { mutableStateOf(false) }
    val shown = if (overflow && !openAll) calls.take(MaxInlineTools) else calls
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // `take` 取的是前缀，下标与 `matched` 仍然对齐 —— 不能改成 filter，那样会错位。
        // key 用「消息 id + 下标」而不是调用 id：端点不回 id 时 `ToolCallStarted` 先落
        // 占位 id、收尾帧再改成权威 id —— 若状态树跟着 id 换 key，用户展开的卡片会在
        // 参数刚收齐的那一瞬间被重组回折叠态（真机表现：输出回来后才看得见完整参数）。
        shown.forEachIndexed { i, tc ->
            key("$groupId:$i") {
                ToolCallBlock(
                    tc = tc,
                    result = matched.getOrNull(i),
                    pending = running && matched.getOrNull(i) == null,
                    anchor = "$groupId:$i",
                )
            }
        }
        if (overflow) {
            val rest = calls.size - MaxInlineTools
            val failed = matched.count { it?.isError == true }
            // 失败数挂在这行上：被收起的那几次里若有失败，收起态也得看得见（旧版由折叠头承担）。
            Text(
                text = buildList {
                    add(toolGroupTitle(calls))
                    if (failed > 0) add(stringResource(R.string.tool_group_failed, failed))
                    add(
                        if (openAll) {
                            stringResource(R.string.action_collapse)
                        } else {
                            stringResource(R.string.tool_group_expand_rest, rest)
                        },
                    )
                }.joinToString(" · "),
                style = MonoTrace,
                color = if (failed > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openAll = !openAll },
            )
        }
    }
}

/**
 * 一次调用（U100 改版，U105 返工）：头行（工具名 + 用量 + 展开提示）、可选参数摘要、
 * 展开后的 IN / OUT。
 *
 * - **默认全折叠**，短输出也折。折叠态只有头行（有参数时再加一行摘要），
 *   一屏能放下五六个调用；右上角的 token 数是「值不值得点开」的判断依据。
 * - **展开态先给原始输入**（[toolArgumentText]，模型下发的原文，不解析），再给输出。
 * - **用量在头行右侧**：不再单独占注行（见 [formatTokenCount]）。
 *
 * 失败时工具名转朱红、输出转朱红 —— 与「颜色即角色」同口径（朱红 = 危险 / 失败）。
 * 运行中不给输出区（还没有内容），头行右侧按阶段写「参数写入中」或「运行中」，
 * 比空着一块让人以为「没输出」清楚。
 */
@Composable
internal fun ToolCallBlock(
    tc: Part.ToolCall,
    result: Part.ToolResult?,
    pending: Boolean,
    onLongClick: (() -> Unit)? = null,
    /**
     * 展开状态与重组身份用的稳定键（默认调用 id）。
     *
     * 流式期间调用 id 是**可变的**：端点不回 id 时先落占位 id，收尾帧到达才改成权威 id
     * （见 `TurnExecutor.ToolCallStarted` 的注释）。拿它当 `rememberSaveable` 的 key，
     * 参数刚收齐的那一刻整棵子树会被丢弃重建 —— 用户已经展开的 IN 区被收回折叠态，
     * 看起来就像「参数要到输出回来后才完整」。调用方应传「消息 id + 下标」。
     */
    anchor: String = tc.id,
) {
    var open by rememberSaveable(anchor) { mutableStateOf(false) }
    // 展开态就是参数原文（不按键名挑值、不重排）；闭合后还原 `\uXXXX` 转义，
    // 流式期间只还原尾部窗口。截断交给 TailText。
    val inputText = remember(tc.arguments) { toolArgumentText(tc.arguments) }
    // 折叠态 ticker 与展开区共用同一份还原结果：模型把中文写成转义时，收起态也得可读。
    val detail = remember(inputText) { recentTailSummary(inputText) }
    val isError = result?.isError == true
    val body = result?.content.orEmpty()
    // 运行中也报用量：参数流式写入时数字跟着涨（见 [toolUsageText]）。
    val usage = remember(tc.arguments, body) { toolUsageText(tc.arguments, body) }
    // 参数还没收齐（不是合法 JSON 对象）：这是「模型还在写参数」，不是「工具在跑」。
    // 两者都表现为「没有结果 + 数字不动」，但一个该等、一个该看转圈 —— 文案必须分开，
    // 否则端点把参数攒到收尾帧时，卡片会像卡死一样挂着「运行中 · 3 tok」。
    val writingArgs = remember(tc.arguments, pending) {
        pending && parseToolArguments(tc.arguments) == null
    }
    // 只要有参数就允许展开：流式期间点开就能看到尾部跟随的原文（U108）。
    // 但**不再自动展开** —— 折叠态的 ticker 已经能看出它在写，屏幕留给后续内容。
    val expandable = inputText.isNotBlank() || body.isNotBlank()
    val browserOpen = LocalBrowserPanelOpen.current
    val browserAction: (@Composable () -> Unit)? = if (tc.name.startsWith("browser_") && browserOpen != null) {
        {
            Text(
                stringResource(R.string.browser_open_in_panel),
                style = MonoTraceMeta,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable { browserOpen() }
                    .padding(start = 8.dp),
            )
        }
    } else {
        null
    }
    TraceBlock(
        title = tc.name,
        icon = toolGlyph(tc.name),
        // 展开后正文区已有 IN，摘要不再重复；收起时才用一行 ticker 兜住。
        detail = if (open && inputText.isNotBlank()) null else detail,
        // 运行中报参数用量，出结果后切到输出用量（见 [toolUsageText]）。
        usage = usage,
        accent = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        // 「哪一次调用在跑」落在那一块板上（U99 补）：注行的「运行中」是文字，转圈是位置。
        busy = pending,
        onToggle = if (expandable) ({ open = !open }) else null,
        onLongClick = onLongClick,
        meta = toolMeta(
            isError = isError,
            expandable = expandable,
            expanded = open,
            pending = pending,
            writingArgs = writingArgs,
        ).ifBlank { null },
        metaColor = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        action = browserAction,
        input = if (open && inputText.isNotBlank()) {
            {
                // 与输出同色（正文色）。一屏里同时出现正文 / 命令 / 输出 / diff 四套颜色
                // 就是五颜六色；输入与输出本来就靠 IN / OUT 两行小标分开，不必再叠一层颜色。
                TailText(
                    raw = inputText,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            null
        },
        content = if (open && body.isNotBlank()) {
            { ToolOutputBody(body, isError = isError, diff = tc.name == "edit") }
        } else {
            null
        },
    )
}

/**
 * 输出正文（展开态）。
 *
 * 取正文色（`onSurface`），**不给工具输出单独上色**。曾经试过青碧，理由是「刻痕不该与
 * 正文混为一谈」；但真机上正文 / 命令 / 输出 / diff 四套颜色同屏，读起来像着了色，
 * 而 diff 本身已经有增红删绿 —— 输出再单独染色只会跟 diff 抢注意力。
 * 分层的活交给底板、IN/OUT 两行小标、等宽字体，不交给颜色。
 *
 * `edit` 的输出是统一 diff，只有它逐行上色 —— 逐行建 `Text` 在长日志上是成倍的排版开销，
 * 所以其余工具整体一个 `Text`（换行由文本引擎处理）。
 */
@Composable
private fun ToolOutputBody(body: String, isError: Boolean, diff: Boolean) {
    val color = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val text = toolPreview(body)
    if (!diff) {
        Text(text = text, style = MonoTrace, color = color)
        return
    }
    Column {
        text.split('\n').forEach { line ->
            Text(line, style = MonoTrace, color = if (isError) color else diffLineColor(line))
        }
    }
}

/**
 * 注：`参数写入中 · 失败 · 展开`。
 *
 * 行数在 U100 移到了头行右侧（换成 token）—— 注行只承担「现在能不能点、点了会怎样」，
 * 不再兼任「有多长」。没有可写的就不渲染（返回空串，由调用方转成 null）。
 *
 * [writingArgs] 与 [pending] 分开：前者是「模型还在写参数」，后者是「工具在跑 / 等结果」。
 * 端点把参数攒到收尾帧时增量是 0，卡片会长时间停在同一个 token 数 —— 若仍写「运行中」，
 * 读起来就是「工具卡死」；写「参数写入中」，用户才知道该等的是模型而不是工具。
 */
@Composable
private fun toolMeta(
    isError: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    pending: Boolean,
    writingArgs: Boolean,
): String {
    val parts = buildList {
        if (pending) {
            add(stringResource(if (writingArgs) R.string.tool_writing_args else R.string.tool_running))
        }
        if (isError) add(stringResource(R.string.chat_tool_failed))
        if (expandable) {
            add(stringResource(if (expanded) R.string.action_collapse else R.string.action_expand))
        }
    }
    return parts.joinToString(" · ")
}

/**
 * 孤儿工具结果（调用已被压缩切点切掉、结果还在）：同样走 [TraceBlock]，头行换成工具名，
 * 免得它看起来像一条「没有来源的正文」。
 */
@Composable
internal fun ToolResultCard(tr: Part.ToolResult, onLongClick: (() -> Unit)? = null) {
    var open by rememberSaveable(tr.toolCallId) { mutableStateOf(false) }
    val tokens = remember(tr.content) { Compaction.estimateTextTokens(tr.content) }
    val expandable = tr.content.isNotBlank()
    val browserOpen = LocalBrowserPanelOpen.current
    val browserAction: (@Composable () -> Unit)? = if (tr.toolName.startsWith("browser_") && browserOpen != null) {
        {
            Text(
                stringResource(R.string.browser_open_in_panel),
                style = MonoTraceMeta,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clickable { browserOpen() }
                    .padding(start = 8.dp),
            )
        }
    } else {
        null
    }
    TraceBlock(
        title = tr.toolName,
        icon = toolGlyph(tr.toolName),
        usage = if (expandable) "${formatTokenCount(tokens)} tok" else null,
        accent = if (tr.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        action = browserAction,
        onToggle = if (expandable) ({ open = !open }) else null,
        onLongClick = onLongClick,
        meta = toolMeta(
            isError = tr.isError,
            expandable = expandable,
            expanded = open,
            pending = false,
            writingArgs = false,
        ).ifBlank { null },
        metaColor = if (tr.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        content = if (open && expandable) {
            { ToolOutputBody(tr.content, isError = tr.isError, diff = tr.toolName == "edit") }
        } else {
            null
        },
    )
}

/**
 * 思考块：与工具同一个板式，正文是模型的自言自语。
 *
 * U108 起与工具卡统一：折叠态给一行尾部 ticker（看得出还在写），展开态走
 * [TailText] —— 只保留最近 5k 字、贴底跟随、上滑暂停。此前思考是整段直接交给
 * `Text`：模型写多少就排版多少，长思考会把整张卡撑到几屏，流式时每帧重排全量。
 */
@Composable
internal fun ThinkingBlock(text: String, onLongClick: (() -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    val tokens = remember(text) { Compaction.estimateTextTokens(text) }
    val detail = remember(text) { recentTailSummary(text) }
    TraceBlock(
        title = stringResource(R.string.chat_thinking),
        icon = PluginGlyph.Spark,
        usage = "${formatTokenCount(tokens)} tok",
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
        detail = if (open) null else detail,
        onToggle = { open = !open },
        onLongClick = onLongClick,
        meta = stringResource(if (open) R.string.action_collapse else R.string.action_expand),
        content = if (open) {
            { TailText(raw = text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            null
        },
    )
}

/** diff 行的语义分类，决定取色（见 [diffLineColor]）。 */
internal enum class DiffLineKind { ADDED, REMOVED, HUNK, HEADER, CONTEXT }

internal fun diffLineKind(line: String): DiffLineKind = when {
    line.startsWith("+++") || line.startsWith("---") -> DiffLineKind.HEADER
    line.startsWith("@@") -> DiffLineKind.HUNK
    line.startsWith("+") -> DiffLineKind.ADDED
    line.startsWith("-") -> DiffLineKind.REMOVED
    else -> DiffLineKind.CONTEXT
}

/**
 * 新增行蓝、删除行朱红、hunk 头取次要灰；文件头与上下文行取正文色。
 *
 * 只有「变了的那几行」才有颜色 —— 上下文行染色会让整块 diff 变成一片彩色底噪，
 * 反而看不出改动在哪。hunk 头用灰而不是蓝：它只是位置标记，不能和新增行抢同一个颜色。
 */
@Composable
private fun diffLineColor(line: String): Color = when (diffLineKind(line)) {
    DiffLineKind.ADDED -> MaterialTheme.colorScheme.primary
    DiffLineKind.REMOVED -> MaterialTheme.colorScheme.error
    DiffLineKind.HUNK -> MaterialTheme.colorScheme.onSurfaceVariant
    DiffLineKind.HEADER, DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
}

/** 工具语义归类：把同类调用计数成一句话（保持首次出现顺序）。 */
internal fun toolGroupCounts(calls: List<Part.ToolCall>): List<Pair<String, Int>> {
    val order = linkedMapOf<String, Int>()
    calls.forEach { order.merge(toolGroupCategory(it.name), 1, Int::plus) }
    return order.entries.map { it.key to it.value }
}

internal fun toolGroupCategory(name: String): String = when (name) {
    // 旧名（`bash` / `local_shell`）只用于历史会话的展示兼容：改名前的 tool_call 还留在历史里。
    "container_shell", "android_shell", "bash", "local_shell" -> "command"
    "read" -> "read"
    "write", "edit" -> "edit"
    "ls" -> "browse"
    "grep", "find" -> "search"
    "subagent" -> "subagent"
    "skill" -> "skill"
    "install_plugin", "uninstall_plugin" -> "plugin"
    "browser_open", "browser_snapshot", "browser_click", "browser_type",
    "browser_press", "browser_scroll", "browser_console", "browser_network" -> "browser"
    "ask_user" -> "ask"
    else -> "other"
}

@Composable
private fun toolGroupTitle(calls: List<Part.ToolCall>): String {
    // 用 for 循环而非 joinToString：stringResource 只能在 @Composable 上下文调用，
    // 而 joinToString 的变换 lambda 是普通函数上下文。
    val parts = mutableListOf<String>()
    for ((cat, n) in toolGroupCounts(calls)) {
        parts += when (cat) {
            "command" -> stringResource(R.string.tool_group_command, n)
            "read" -> stringResource(R.string.tool_group_read, n)
            "edit" -> stringResource(R.string.tool_group_edit, n)
            "browse" -> stringResource(R.string.tool_group_browse, n)
            "search" -> stringResource(R.string.tool_group_search, n)
            "subagent" -> stringResource(R.string.tool_group_subagent, n)
            "skill" -> stringResource(R.string.tool_group_skill, n)
            "plugin" -> stringResource(R.string.tool_group_plugin, n)
            "browser" -> stringResource(R.string.tool_group_browser, n)
            "ask" -> stringResource(R.string.tool_group_ask, n)
            else -> stringResource(R.string.tool_group_other, n)
        }
    }
    return parts.joinToString(stringResource(R.string.tool_group_separator))
}
