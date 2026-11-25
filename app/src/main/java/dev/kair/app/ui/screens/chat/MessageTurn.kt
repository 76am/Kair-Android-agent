package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * 一条消息的外壳：只保留条目间距，不再画左侧轨道。
 *
 * 旧版本的「竖线 + 圆点」按「谁说的」着色（朱红 / 蓝 / 灰），而折叠卡按「哪一步」
 * 分组 —— 两套时间轴叠在一起反而看不清边界。步的分界交给折叠体内的分隔线
 * （见 TurnFold 的 `StepDivider`），来源区分靠位置与「谁 · 时间」行。
 */
@Composable
internal fun MessageTurn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 2.dp, bottom = 18.dp),
    ) {
        content()
    }
}

/** 「谁」那一行：等宽 + 拉开字距（设计稿 `.who`），不抢正文的字号。 */
@Composable
internal fun WhoLine(who: String, color: Color) {
    Text(
        text = who,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.em,
        ),
        color = color,
    )
}
