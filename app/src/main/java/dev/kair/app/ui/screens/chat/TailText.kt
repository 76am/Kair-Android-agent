package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.ui.theme.MonoTrace
import dev.kair.app.ui.theme.MonoTraceMeta

/** 展开区正文的高度上限（U108，对齐设计稿）：再长就在块内滚动，不再把整张卡撑开。 */
private val TailBodyMaxHeight = 200.dp

/**
 * 流式长文本的展开区（U108）：**尾部窗口 + 贴底跟随**。
 *
 * 与设计稿一致的三条行为：
 * 1. 只显示最近 [TextTailWindow] 字（头部丢弃并补省略号），避免几十万字符整段交给 Text；
 * 2. 内容增长时贴住底部 —— 新行从下面进来、旧行向上推，而不是停在第 1 行看模型「静默」；
 * 3. 用户往上滑就**暂停跟随**，右下角给「回到最新」；滑回底部或点它才续上。
 *
 * 暂停必须由「用户手势」触发，不能只看「当前不在底部」：内容每帧变长都会让 maxValue
 * 变大，一帧内的差值就足以让按钮闪出来。所以只有 `isScrollInProgress` 时才算用户意图。
 */
@Composable
internal fun TailText(
    raw: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxHeight: Dp = TailBodyMaxHeight,
) {
    val text = remember(raw) { tailText(raw) }
    val trimmed = raw.length > TextTailWindow
    val scroll = rememberScrollState()
    var pinned by remember { mutableStateOf(true) }
    // 程序滚动自己也会把 `isScrollInProgress` 置 true，不能拿它当「用户手势」：
    // `scrollTo` 的瞬间新内容刚到、value 还没追上 maxValue，会被判成「用户往上滑」，
    // 跟随就此断开 —— 之后内容继续涨、视口停在原处，看起来就是流式卡住。
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.value >= scroll.maxValue) }
            .collect { (inProgress, atBottom) ->
                when {
                    // 用户跟手 / 惯性滚动离开底部：暂停跟随（程序滚动不算）。
                    inProgress && !autoScrolling && !atBottom -> pinned = false
                    // 回到或停在底部：续上跟随。
                    !inProgress && atBottom -> pinned = true
                }
            }
    }
    // maxValue 也进 key：首帧布局时它还是 0，量完才知道内容多高，那时要再贴一次。
    LaunchedEffect(text, scroll.maxValue, pinned) {
        if (pinned) {
            autoScrolling = true
            try {
                scroll.scrollTo(scroll.maxValue)
            } finally {
                autoScrolling = false
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        if (trimmed) {
            Text(
                text = stringResource(R.string.chat_tail_window, TextTailWindow / 1_000),
                style = MonoTraceMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Box {
            Text(
                text = text,
                style = MonoTrace,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(scroll),
            )
            if (!pinned) {
                // 底右角浮着，不占正文高度；点它之前不打扰阅读。
                Surface(
                    onClick = { pinned = true },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text(
                        text = stringResource(R.string.chat_tail_latest),
                        style = MonoTraceMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
