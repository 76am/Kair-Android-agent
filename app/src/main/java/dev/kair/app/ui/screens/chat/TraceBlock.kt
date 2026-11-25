package dev.kair.app.ui.screens.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.SvgPathIcon
import dev.kair.app.ui.theme.MonoTrace
import dev.kair.app.ui.theme.MonoTraceMeta

/**
 * 运行中转圈的测试标记。
 *
 * 为什么不按 `hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)` 找：
 * 2026-11-17 在 MIX 2S 上实测找不到节点（matcher 打印出来的值也不是 Indeterminate 那一份），
 * 而「有没有转圈」是这一批唯一新增的可验证行为 —— 判据不该依赖框架内部怎么填 range。
 */
internal const val TRACE_RUNNING_TAG = "trace-running"

/**
 * 12dp 的转圈。
 *
 * **不用 M3 的 `CircularProgressIndicator`**：它在 12dp 上会经历「弧长收到 0」的帧，
 * 此时只剩圆头笔帽的一个点 —— 真机第一帧就像一颗亮点，2026-11-17 的截图抓的正是这一帧。
 * 自绘 270° 的弧，**任何一帧都是一段可见的环**，旋转起来才是「在转」。
 *
 * 尺寸小到 12dp 时描边必须收到 1.5dp：M3 默认 4dp 会把内径吃光，糊成一个实心点。
 */
@Composable
private fun RunningSpinner(color: Color) {
    val angle = rememberInfiniteTransition(label = "trace-running")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
            label = "angle",
        )
    Canvas(
        Modifier
            .size(12.dp)
            .testTag(TRACE_RUNNING_TAG),
    ) {
        val stroke = 1.5.dp.toPx()
        drawArc(
            color = color,
            // -90° 起手：从 12 点方向开始转，与「时钟」同一直觉。
            startAngle = angle.value - 90f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
        )
    }
}

/**
 * 转录里「不是正文」的那一层（工具调用、工具输出、思考）的统一板式（U98，U100 改版）。
 *
 * 形态：**一块淡板**（surfaceContainerLow + 1dp 分界线 + 8dp 圆角），板里是
 * **线稿图标 + 等宽头行**（名字着色 + 右上用量 + 展开/失败提示），
 * 需要时再给一行参数摘要（[detail]），展开后是 **IN / OUT 两块**（[input] 与 [content]）。
 *
 * 为什么有底板：设计稿 `.tool` 只有等宽字与一条 `border-left`，在 6 寸屏上与正文挤在
 * 一起时边界完全消失 —— 一屏扫下来分不清「它在说话」还是「它在干活」。
 *
 * ## U100 / U105 的取舍
 *
 * 1. **头行右侧是 token 用量**（[usage]）。原来写「12 行」，回答的是「多长」；
 *    12 行可能是 12 个数字，也可能是 12 行 JSON。token 才是它真正占掉的东西。
 * 2. **折叠态只有头行**（+ 可选参数摘要）。U100 曾在头行下加过一行「agent 这次想干什么」，
 *    U105 返工时负责人判定它没用、已删除；展开 / 收起 / 失败 / 运行中改挂在头行右端，
 *    不再单独占一条 48dp 的注行 —— 否则折叠态一块只有一行字的卡片会有大片空白。
 * 3. **输入可以完整看见**：[input] 传原始命令（保留换行），与 [content] 之间有一条
 *    「IN · 输入 / OUT · 输出」的分界。上一版把参数 `maxLines = 1` 省略掉，
 *    而展开只展开输出 —— 那条真正下发的命令**是丢了**，不是藏起来。
 *
 * 所有工具块默认全折叠：短输出也折。「跑完立刻想看结果」按一下的代价，
 * 换来一屏能放下五六个调用。
 *
 * @param detail 折叠态第二行的参数摘要。单行、超长省略；为空则只画头行。
 * @param usage 头行右侧的用量，如 `2.1k tok`。
 * @param onToggle 传 null 表示这块没有可展开的内容，整块不做点击（不许出现点了没反应的假入口）。
 * @param onLongClick 折叠体内长按弹消息菜单。块自身是点击面，长按必须由块自己接管，
 *   否则父级的手势会被这里的 `combinedClickable` 吃掉（折叠体里的删除 / 选字入口就是这么消失的）。
 * @param busy 这次调用还没回结果。头行右侧给一个 12dp 转圈（注行同时写「运行中」，见 [toolMeta]）。
 * @param input 输入区（命令原文）。非空时与 [content] 各带一个 IN/OUT 标签。
 *   **输入与输出同色**（正文色）：区分靠标签与底板，不靠染色 —— 一屏里正文 / 命令 /
 *   输出 / diff 四套颜色就是五颜六色，而 diff 本身已经有增红删绿。
 * @param content 输出 / 正文；传 null 表示折叠态或没有输出。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TraceBlock(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    detail: String? = null,
    usage: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    busy: Boolean = false,
    onToggle: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    meta: String? = null,
    metaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** 头行右侧的额外交互（如浏览器的「在面板中查看」）；null = 不渲染。 */
    action: (@Composable () -> Unit)? = null,
    input: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val rule = MaterialTheme.colorScheme.outlineVariant
    // 按下态走 state layer：整卡压一层矩形半透明，不用圆形涟漪 —— 圆形涟漪从触点扩散，
    // 在带边框的整块板上像「点到了一个按钮」，而不是「这块按下了」（口径见 PressLayer）。
    val interaction = remember { MutableInteractionSource() }
    val layerAlpha = rememberPressLayer(interaction)
    val layerColor = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, rule, MaterialTheme.shapes.extraSmall)
            // 整块都是点击面：展开后点正文 / 输出任意位置也能收起，不必再回去找头行。
            // 长按在块内接管（折叠体的消息菜单），点按仍只做展开 / 收起。
            .then(
                when {
                    onToggle != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = onLongClick,
                        onClick = onToggle,
                    )
                    onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onLongClick = onLongClick,
                        onClick = {},
                    )
                    else -> Modifier
                },
            )
            .pressLayer(layerAlpha, layerColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 头行补到 48dp（U105）：原先只有 23dp 的头行可点，低于 Android 48dp 下限。
        // 展开 / 收起 / 失败 / 运行中都挤在右端，不再单独占一行 —— 折叠态因此只有一块 48dp 的头行。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    SvgPathIcon(icon, accent, Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                }
                // `weight(1f)`：名字吃掉剩余宽度，用量 / 展开提示固定贴右端；
                // 名字过长时省略（`container_shell` 这类常见名远不到这个宽度）。
                Text(
                    title,
                    style = MonoTrace,
                    color = accent,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                action?.invoke()
                if (usage != null) {
                    Text(usage, style = MonoTraceMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (meta != null) {
                    Text(
                        meta,
                        style = MonoTraceMeta,
                        color = metaColor,
                        maxLines = 1,
                        modifier = Modifier.padding(start = if (usage != null) 8.dp else 0.dp),
                    )
                }
                if (busy) {
                    Spacer(Modifier.width(7.dp))
                    RunningSpinner(accent)
                }
            }
            // 折叠态第二行：参数 / 思考的尾端 ticker。没有就只剩头行。
            detail?.takeIf { it.isNotBlank() }?.let { subtitle ->
                DetailTicker(subtitle)
            }
        }
        if (input != null) {
            // 两个小标同色：它们靠字面（IN / OUT）区分，不靠颜色 —— 一块里已经够多信息，
            // 再分色只是在添色。正文一律用正文色（见 ToolOutputBody）。
            IoLabel("IN · " + stringResource(R.string.chat_tool_input))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(rule, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), strokeWidth = stroke)
                    }
                    .padding(start = 9.dp, top = 2.dp),
                content = input,
            )
        }
        if (content != null) {
            // 只有「同时有 IN」时才画 OUT 标：孤儿结果卡没有输入区，多一个 OUT 小标
            // 会让人去找并不存在的输入。运行中的调用只有 IN（参数在流式增长），
            // 此时也不能因为 content 还没来就把 IN 一起吞掉。
            if (input != null) IoLabel("OUT · " + stringResource(R.string.chat_tool_output))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(rule, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), strokeWidth = stroke)
                    }
                    .padding(start = 9.dp, top = 2.dp),
                content = content,
            )
        }
    }
}

/**
 * 折叠态第二行：单行 ticker。
 *
 * 内容本身是「取尾部」（见 `recentTailSummary`），这里再把整行横滑到最右 ——
 * 双保险：即使一行装不下预算字数，最新字符也一定在视口里。流式时文字因此像
 * 从左往右滑过，旧字符被推出左边。
 *
 * 手指拖动关掉（`enabled = false`）：折叠行是整卡的点击面，不该被一行字的横向
 * 滚动抢走手势。
 */
@Composable
private fun DetailTicker(text: String) {
    val scroll = rememberScrollState()
    // maxValue 进 key：首帧下标为 0，量完才知道要贴到哪里。
    LaunchedEffect(text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Text(
        text = text,
        style = MonoTrace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .horizontalScroll(scroll, enabled = false),
    )
}

/** IN / OUT 分界：一个等宽小标 + 一条淡线。两标同色 —— 靠字面区分，不靠颜色（见调用处）。 */
@Composable
private fun IoLabel(text: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MonoTraceMeta, color = color)
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(color.copy(alpha = 0.22f)),
        )
    }
}
