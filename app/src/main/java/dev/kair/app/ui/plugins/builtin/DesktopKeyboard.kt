package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.desktop.chordKeySequence
import dev.kair.app.core.desktop.keysymForChar
import dev.kair.app.core.desktop.keysymForName
import dev.kair.app.core.desktop.newlineKeySequence
import dev.kair.app.core.desktop.planEdit
import dev.kair.app.core.desktop.splitRuns

/** 功能键行的固定顺序（`←↑↓→` 用箭头字形，省宽度）。 */
private val FUNCTION_KEYS = listOf("Esc", "Tab", "BackSpace", "Enter", "Left", "Up", "Down", "Right")

/** 修饰键行：循环顺序 = 显示顺序 = 和弦里的按下顺序。 */
private val MODIFIER_KEYS = listOf("Shift", "Control", "Alt")

/** 修饰键的显示名（`Control` 显示成 `Ctrl`，跟键帽一致）。 */
private fun modifierLabel(name: String): String = if (name == "Control") "Ctrl" else name

/**
 * 软键盘条（任务书 §4 F）：默认不弹，胶囊 ⌨ 控制；**只遮画面**——不改 Xvfb 分辨率、
 * 不 resize 画面（U85 教训）。字符经 keysym 表逐键发 down+up。
 *
 * 回车（U88 两处坑）：
 * - `ImeAction.Send` + [KeyboardActions.onSend] 是「回车 = 发送」的唯一触发点，输入框
 *   `singleLine` 保证 Enter 不再往本地文本里插换行；
 * - 部分中文输入法仍会硬塞一个 `'\n'`，由 [textChangeKeys] 统一滤掉 —— 不滤就是「一次回车
 *   发两遍 Enter」（远端 `sl` 收到两次回车直接退出）；
 * - 发送后**清空输入框**：留着会让下一次输入接在旧行后面，看起来像「刚敲的命令还挂在里面」。
 *   清空是直接赋值、不走 `onValueChange`，所以不会多送一串 BackSpace 去删远端的内容。
 * - 输入法的回车只够「发送」，功能键行另给一个「换行」键（Shift+Enter），
 *   桌面里的编辑器 / 终端才能真的换行（U89 §F §6 挂账）。
 *
 * 修饰键（U89H）：屏幕上没法真的同时按两个钮，所以 [MODIFIER_KEYS] 是**本地状态**：
 * 点一下高亮并带一个 `·` 标记，下一次按键展开成和弦（[chordKeySequence]）后自动解除。
 * 自动解除是刻意的 —— 保持按下会让后面每一个键都莫名带上 Alt。
 *
 * 中文输入（U89I）：X 的 keysym 本质是「键盘上某个键」，汉字没有物理键，所以非 Latin-1
 * 字符走 [onText]（宿主开一个 `xdotool type` 进程做 keycode 动态重映射）。两个坑：
 * 1. **输入法组合中一个键都不发**（`value.composition != null`）。否则用户打 `zhong` 选词时，
 *    远端先收到 `zhong` 再收到 `中`。
 * 2. **差异按最长公共前缀算**（[planEdit]），不能按长度：上屏会让长度变短
 *    （`zhong` 5 → `中` 1），按长度会判成「删掉 4 个字符」，远端被误退 4 格。
 */
@Composable
internal fun DesktopKeyboardBar(
    modifier: Modifier,
    onKey: (Int, Boolean) -> Unit,
    onText: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    // 远端已经收到的文本。与 `value.text` 的差就是「还欠远端多少」（[planEdit]）。
    var baseline by remember { mutableStateOf("") }
    var modifiers by remember { mutableStateOf(emptyList<Int>()) }
    var fKeysOpen by remember { mutableStateOf(false) }
    // 点 ⌨ 就是要打字：自动聚焦，软键盘随之弹出。不聚焦的话用户还得再点一次输入框
    // —— 键盘条已经占了画面底部，那一步很容易被当成「按钮没用」。
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    /** 发一个键：有修饰键就展开成和弦，发完解除修饰（见本函数上面的说明）。 */
    fun emit(keysym: Int) {
        if (modifiers.isEmpty()) {
            onKey(keysym, true)
            onKey(keysym, false)
        } else {
            for ((ks, down) in chordKeySequence(modifiers, keysym)) onKey(ks, down)
            modifiers = emptyList()
        }
    }

    fun submit() {
        val enter = keysymForName("Enter") ?: return
        emit(enter)
        value = TextFieldValue("")
        baseline = ""
    }

    /**
     * 退一格：远端与本地**同时**退 —— 只退远端或只退本地都会让 [baseline] 与远端内容错位，
     * 之后每次差异都算错。
     */
    fun backspace() {
        keysymForName("BackSpace")?.let { emit(it) }
        val current = value.text
        if (current.isNotEmpty()) {
            val next = current.dropLast(1)
            value = TextFieldValue(next, TextRange(next.length))
            baseline = next
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 输入框看不见之后，**必须有一个可见且可点的东西把焦点抢回来**（U89K）：
            // 1dp 的输入框根本点不中，输入法一收起就再也唤不起，整个键盘条就废了。
            // 点空白处（chip 之间的空隙 / 行间）即重新聚焦；chip 自己的点击会先消费掉。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // 不画涟漪：整条闪一下很像误触。
                indication = null,
            ) { runCatching { focus.requestFocus() } }
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 行 1：修饰键 + F 键扩展开关。点修饰键只切本地状态，不产生远端事件。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in MODIFIER_KEYS) {
                val keysym = keysymForName(name)
                if (keysym == null) continue
                val locked = keysym in modifiers
                KeyChip(
                    label = (if (locked) "·" else "") + modifierLabel(name),
                    active = locked,
                ) {
                    modifiers = if (locked) modifiers - keysym else modifiers + keysym
                }
            }
            KeyChip(
                label = stringResource(R.string.desktop_key_fkeys) + if (fKeysOpen) " ▴" else " ▾",
                active = fKeysOpen,
            ) {
                fKeysOpen = !fKeysOpen
            }
        }
        // 行 2：功能键。Enter 与输入框回车同一条语义（发完清空输入框）。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (key in FUNCTION_KEYS) {
                KeyChip(label = functionLabel(key)) {
                    when (key) {
                        "Enter" -> submit()
                        "BackSpace" -> backspace()
                        else -> keysymForName(key)?.let { emit(it) }
                    }
                }
            }
            KeyChip(label = stringResource(R.string.desktop_key_newline)) {
                // 输入法只能「发送」，换行单独给一个 Shift+Enter 的真换行键。
                for ((ks, down) in newlineKeySequence()) onKey(ks, down)
            }
        }
        // 行 3（按需展开）：F1–F12，两排各 6 个 —— 一排 12 个在手机竖屏上按不准。
        if (fKeysOpen) {
            for (row in 0 until 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in (row * 6 + 1)..(row * 6 + 6)) {
                        KeyChip(label = "F$n") { keysymForName("F$n")?.let { emit(it) } }
                    }
                }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = { nv ->
                if (nv.composition != null) {
                    // 输入法组合中（拼音未上屏）：只落本地，一个键都不发。
                    value = nv
                } else {
                    val plan = planEdit(baseline, nv.text)
                    // 只发远端、不碰本地：本地马上会被 nv 整体替换，这里改本地会互相打架。
                    repeat(plan.backspaces) { keysymForName("BackSpace")?.let { emit(it) } }
                    for (run in splitRuns(plan.appended)) {
                        if (run.ascii) {
                            for (c in run.text) keysymForChar(c)?.let { emit(it) }
                        } else {
                            onText(run.text)
                            modifiers = emptyList()
                        }
                    }
                    baseline = nv.text
                    value = nv
                }
            },
            // 输入框**不显示**（U89K）：它保留只为给 Android 输入法当按键接收器 ——
            // 去掉它键盘就弹不出来、中文也选不了词。视觉上收到 1dp + alpha 0，
            // 用户看的是桌面上的回显（负责人 2026-11-14 定的交互）。
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focus)
                // 输入法删除键的兜底（U89H）：本地文本已经是空串时，`planEdit` 看不到
                // 长度变化，什么都不会发 —— 真机上表现为「按输入法的删除键没反应，只有虚拟
                // BackSpace 能删」。这里把它接成一个远端 BackSpace。
                .onPreviewKeyEvent { event ->
                    val isBackspace = event.key == Key.Backspace
                    if (isBackspace && event.type == KeyEventType.KeyDown && value.text.isEmpty()) {
                        keysymForName("BackSpace")?.let { emit(it) }
                        true
                    } else {
                        false
                    }
                },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
    }
}

/** 功能键的显示字形：方向键用箭头，省宽度也更好认。 */
private fun functionLabel(key: String): String = when (key) {
    "Left" -> "←"
    "Up" -> "↑"
    "Down" -> "↓"
    "Right" -> "→"
    else -> key
}

/**
 * 键盘条上的一个键帽。[active] = 修饰键 / 扩展开关的「已按下」态：
 * 换底色 + 文字反色，选中的修饰键标签前另带 `·`（双保险，色弱也能看出选中）。
 */
@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
