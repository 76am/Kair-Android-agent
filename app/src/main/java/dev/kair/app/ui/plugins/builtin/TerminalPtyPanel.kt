package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.proot.PtySession
import dev.kair.app.core.proot.PtyState
import dev.kair.app.core.term.TerminalKeys
import dev.kair.app.ui.components.TerminalExtraKeys
import dev.kair.app.ui.components.TerminalScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 面板尺寸变化去抖：布局回调会连续触发（键盘弹出 / 旋转），只认最后一次。 */
private const val RESIZE_DEBOUNCE_MS = 120L

/**
 * PTY 终端界面（Termux 形态）：
 *
 * - 终端网格占满上半部分，**底部两行扩展按键贴边**（见 [TerminalExtraKeys]），
 *   没有独立输入框 / 底部提示；
 * - 点一下终端 = 聚焦隐藏的输入层并弹出软键盘，打字直接进 shell；
 * - 隐藏输入层只做「文本变化 → 字节」转码（[TerminalKeys.diff]），硬件键盘的
 *   Enter / Backspace / 方向键 / Ctrl 组合在 `onPreviewKeyEvent` 里拦下并直发字节。
 *
 * 为什么用隐藏输入层而不是自绘键盘：软键盘的输入（含 IME、退格）只能经
 * `InputConnection` 拿到，`BasicTextField` 是 Compose 里唯一稳定入口；把它的高度压到
 * 1dp 并全透明，用户看到的就是「点终端直接打字」。
 */
@Composable
internal fun PtyTerminalBody(pty: PtySession, modifier: Modifier = Modifier) {
    val state by pty.state.collectAsState()
    val snapshot by pty.screen.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val description = stringResource(R.string.terminal_pty_input)
    // 键盘遮挡的高度：外层 imePadding 已把它从视口扣掉，这里再加回去还原完整网格（U85）。
    val imeHeightPx = WindowInsets.ime.getBottom(LocalDensity.current)

    var typed by remember { mutableStateOf("") }
    var pendingSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var fnMode by remember { mutableStateOf(false) }
    val running = state is PtyState.Running

    // 去抖后只发最后一次尺寸：旋转 / 键盘弹出会连续触发布局回调。
    LaunchedEffect(pendingSize) {
        val target = pendingSize ?: return@LaunchedEffect
        delay(RESIZE_DEBOUNCE_MS)
        pty.resize(target.first, target.second)
    }

    fun send(bytes: ByteArray) {
        if (bytes.isNotEmpty()) scope.launch { pty.send(bytes) }
    }

    fun enter() {
        send(TerminalKeys.ENTER)
        // 程序化清空不会触发 onValueChange，所以不会补发退格。
        typed = ""
    }

    fun pressExtra(key: TerminalKeys.ExtraKey) {
        when (key) {
            TerminalKeys.ExtraKey.CTRL -> {
                ctrlArmed = !ctrlArmed
                return
            }
            TerminalKeys.ExtraKey.ALT -> {
                altArmed = !altArmed
                return
            }
            TerminalKeys.ExtraKey.FN -> {
                fnMode = !fnMode
                return
            }
            else -> Unit
        }
        var bytes = TerminalKeys.extra(key, snapshot.applicationCursorKeys)
        // 扩展键里的 CTRL 只对单字符键生效（`/`、`-`）；方向键保持原样，避免出现
        // 少见的 `ESC[1;5A` 变体（真机没场景，且多一种编码就多一处要测的字节）。
        if (ctrlArmed) key.char?.let { ch -> TerminalKeys.ctrl(ch)?.let { bytes = it } }
        if (altArmed) bytes = TerminalKeys.ESC + bytes
        send(bytes)
        ctrlArmed = false
        altArmed = false
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // 硬件键盘的 Ctrl 组合（Ctrl-C 中断等）：优先按控制字符发。
        if (event.isCtrlPressed) {
            ctrlBytes(event)?.let {
                send(it)
                return true
            }
        }
        val bytes = when (event.key) {
            Key.Backspace -> {
                // 直发的退格也需要被 diff 模型看见，否则下一次 IME 整词替换会按旧缓冲多退格。
                if (typed.isNotEmpty()) typed = typed.dropLast(1)
                TerminalKeys.BACKSPACE
            }
            Key.Escape -> TerminalKeys.ESC
            Key.Tab -> TerminalKeys.TAB
            Key.DirectionUp -> TerminalKeys.arrow(TerminalKeys.Arrow.UP, snapshot.applicationCursorKeys)
            Key.DirectionDown -> TerminalKeys.arrow(TerminalKeys.Arrow.DOWN, snapshot.applicationCursorKeys)
            Key.DirectionLeft -> TerminalKeys.arrow(TerminalKeys.Arrow.LEFT, snapshot.applicationCursorKeys)
            Key.DirectionRight -> TerminalKeys.arrow(TerminalKeys.Arrow.RIGHT, snapshot.applicationCursorKeys)
            Key.Enter -> {
                enter()
                return true
            }
            else -> return false
        }
        send(bytes)
        return true
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TerminalScreen(
                snapshot = snapshot,
                hiddenHeightPx = imeHeightPx,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                    .semantics { contentDescription = description },
                onGridSizeChanged = { rows, cols -> pendingSize = rows to cols },
            )
            BasicTextField(
                value = typed,
                onValueChange = { next ->
                    val bytes = TerminalKeys.diff(typed, next, ctrlArmed, altArmed)
                    typed = next
                    if (bytes.isNotEmpty()) {
                        send(bytes)
                        ctrlArmed = false
                        altArmed = false
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent(::handleKey),
                enabled = running,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onGo = { enter() }),
            )
        }
        TerminalExtraKeys(
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            fnMode = fnMode,
            onKey = ::pressExtra,
            onToggleCtrl = { ctrlArmed = !ctrlArmed },
            onToggleAlt = { altArmed = !altArmed },
            onToggleFn = { fnMode = !fnMode },
        )
    }
}

/**
 * 硬件键盘 Ctrl 组合 → 控制字节。
 *
 * `utf16CodePoint` 在多数键盘上直接给控制字符（Ctrl-C → 3），但部分输入法给 0；
 * 这一格再按 Android keyCode 的 A–Z / Space 映射回字母，保证 Ctrl-C 一定发得出去。
 */
private fun ctrlBytes(event: KeyEvent): ByteArray? {
    val point = event.utf16CodePoint
    when (point) {
        in 1..26 -> return byteArrayOf(point.toByte())
        in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> return TerminalKeys.ctrl(point.toChar())
    }
    val keyCode = event.nativeKeyEvent.keyCode
    if (keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
        val letter = ('a'.code + (keyCode - android.view.KeyEvent.KEYCODE_A)).toChar()
        return TerminalKeys.ctrl(letter)
    }
    if (keyCode == android.view.KeyEvent.KEYCODE_SPACE) return TerminalKeys.ctrl(' ')
    return null
}
