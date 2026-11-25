package dev.kair.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kair.app.core.term.TerminalKeys

private val ExtraKeysBackground = Color(0xFF000000)
private val ExtraKeysForeground = Color(0xFFE6E6E6)
private val ExtraKeysArmed = Color(0xFF23435F)

/** Termux 默认的第一行：ESC / - HOME ↑ END PGUP。 */
private val FIRST_ROW = listOf(
    TerminalKeys.ExtraKey.ESC,
    TerminalKeys.ExtraKey.SLASH,
    TerminalKeys.ExtraKey.DASH,
    TerminalKeys.ExtraKey.HOME,
    TerminalKeys.ExtraKey.UP,
    TerminalKeys.ExtraKey.END,
    TerminalKeys.ExtraKey.PGUP,
)

/** Termux 默认的第二行：TAB CTRL ALT ← ↓ → PGDN，末尾追加 `FN` 切换键。 */
private val SECOND_ROW = listOf(
    TerminalKeys.ExtraKey.TAB,
    TerminalKeys.ExtraKey.CTRL,
    TerminalKeys.ExtraKey.ALT,
    TerminalKeys.ExtraKey.LEFT,
    TerminalKeys.ExtraKey.DOWN,
    TerminalKeys.ExtraKey.RIGHT,
    TerminalKeys.ExtraKey.PGDN,
    TerminalKeys.ExtraKey.FN,
)

/** 点 `FN` 后的第一行：F1–F7。 */
private val FN_FIRST_ROW = listOf(
    TerminalKeys.ExtraKey.F1,
    TerminalKeys.ExtraKey.F2,
    TerminalKeys.ExtraKey.F3,
    TerminalKeys.ExtraKey.F4,
    TerminalKeys.ExtraKey.F5,
    TerminalKeys.ExtraKey.F6,
    TerminalKeys.ExtraKey.F7,
)

/** 点 `FN` 后的第二行：F8–F12，`FN` 仍在原位置，再点一次回到默认布局。 */
private val FN_SECOND_ROW = listOf(
    TerminalKeys.ExtraKey.F8,
    TerminalKeys.ExtraKey.F9,
    TerminalKeys.ExtraKey.F10,
    TerminalKeys.ExtraKey.F11,
    TerminalKeys.ExtraKey.F12,
    TerminalKeys.ExtraKey.FN,
)

/**
 * 终端扩展按键行（Termux 形态，U59 改版）：
 *
 * - 两行等宽按键贴底排列，黑底白字，无边框与提示；
 * - CTRL / ALT 是**一次性修饰键**：点一下点亮，下一次输入生效后自动熄灭；
 * - `FN` 在默认两行里，点一下把两行换成 F1–F12（高度不变，再点一次切回）；
 * - 只负责「按了哪个键」，字节编码在 [TerminalKeys.extra]，便于 JVM 用例钉死。
 */
@Composable
fun TerminalExtraKeys(
    ctrlArmed: Boolean,
    altArmed: Boolean,
    fnMode: Boolean,
    onKey: (TerminalKeys.ExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleFn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(ExtraKeysBackground)) {
        if (fnMode) {
            ExtraKeysRow(FN_FIRST_ROW, ctrlArmed, altArmed, fnMode, onKey, onToggleCtrl, onToggleAlt, onToggleFn)
            ExtraKeysRow(FN_SECOND_ROW, ctrlArmed, altArmed, fnMode, onKey, onToggleCtrl, onToggleAlt, onToggleFn)
        } else {
            ExtraKeysRow(FIRST_ROW, ctrlArmed, altArmed, fnMode, onKey, onToggleCtrl, onToggleAlt, onToggleFn)
            ExtraKeysRow(SECOND_ROW, ctrlArmed, altArmed, fnMode, onKey, onToggleCtrl, onToggleAlt, onToggleFn)
        }
    }
}

@Composable
private fun ExtraKeysRow(
    keys: List<TerminalKeys.ExtraKey>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    fnMode: Boolean,
    onKey: (TerminalKeys.ExtraKey) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleFn: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        keys.forEach { key ->
            ExtraKeyButton(
                key = key,
                armed = when (key) {
                    TerminalKeys.ExtraKey.CTRL -> ctrlArmed
                    TerminalKeys.ExtraKey.ALT -> altArmed
                    TerminalKeys.ExtraKey.FN -> fnMode
                    else -> false
                },
                onClick = when (key) {
                    TerminalKeys.ExtraKey.CTRL -> onToggleCtrl
                    TerminalKeys.ExtraKey.ALT -> onToggleAlt
                    TerminalKeys.ExtraKey.FN -> onToggleFn
                    else -> {
                        { onKey(key) }
                    }
                },
            )
        }
    }
}

@Composable
private fun RowScope.ExtraKeyButton(
    key: TerminalKeys.ExtraKey,
    armed: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(42.dp)
            .background(if (armed) ExtraKeysArmed else ExtraKeysBackground)
            .clickable(onClick = onClick)
            .semantics { contentDescription = key.label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = ExtraKeysForeground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
        )
    }
}
