package dev.kair.app.core.desktop

import java.util.Base64

/**
 * 文本输入映射层（U89I）：把「输入框里打出来的东西」翻译成远端能接收的两种动作。
 *
 * 从 `DesktopInput.kt` 拆出来是为了过体量守卫（那一份已经 500+ 行，而键表与手势归约器
 * 是另一回事）。这一份只管**文本**：能不能用 keysym 表达、要退几格、要追加什么、
 * 中文怎么敲进去。
 */

/**
 * 这个字符能不能用 X keysym 直接表达。
 *
 * 只有 Latin-1 可打印区（`0x20..0x7E`）可以 —— X 的 keysym 本质是「键盘上某个键」，
 * 汉字没有对应的物理键，必须走 `xdotool type` 的 keycode 动态重映射（见
 * [xdotoolTypeShellCommand]）。
 */
internal fun isKeysymTypable(c: Char): Boolean = c.code in 0x20..0x7E

/** 一次文本编辑对远端要做的动作：先退 [backspaces] 个，再追加 [appended]。 */
internal data class EditPlan(val backspaces: Int, val appended: String)

/**
 * 输入框文本「从 [baseline] 变成 [new]」时，远端要怎么跟。
 *
 * 为什么不用长度差（`textChangeKeys` 那套）：**中文输入法上屏会让长度变短**。
 * 输入法组合时本地是 `zhong`（5 字符），选词上屏后变成 `中`（1 字符）—— 长度差会判成
 * 「删掉 4 个字符」，远端就被误退 4 格。这里按**最长公共前缀**算：`zhong → 中` 的前缀是空，
 * 于是结论是「追加一个 `中`」，正是想要的。
 *
 * 前缀相同的前提下，`backspaces` 恒等于 `baseline.length - 公共前缀长度`，
 * 而 `baseline` 的新值就是 [new]（尾部差异已由这两个字段补齐）。
 */
internal fun planEdit(baseline: String, new: String): EditPlan {
    val common = prefixLength(baseline, new)
    return EditPlan(
        backspaces = baseline.length - common,
        appended = new.substring(common),
    )
}

private fun prefixLength(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var i = 0
    while (i < limit && a[i] == b[i]) i++
    return i
}

/** 一段可整批发出去的文本：ASCII 段走 keysym，其余走 `xdotool type`。 */
internal data class TextRun(val ascii: Boolean, val text: String)

/**
 * 把要追加的文本按「能不能用 keysym」切成连续的段。
 *
 * 必须保序：用户打 `a中b`，三段的发送顺序就是远端收到的顺序。单靠两种发送方式各自的
 * 队列保证不了这点，所以两段都走**同一条**串行队列（见 `DesktopInputChannel` 的 writer）。
 */
internal fun splitRuns(text: String): List<TextRun> {
    if (text.isEmpty()) return emptyList()
    val runs = mutableListOf<TextRun>()
    val buffer = StringBuilder()
    var currentAscii: Boolean? = null
    for (c in text) {
        val ascii = isKeysymTypable(c)
        if (currentAscii != null && currentAscii != ascii) {
            runs += TextRun(currentAscii, buffer.toString())
            buffer.clear()
        }
        currentAscii = ascii
        buffer.append(c)
    }
    runs += TextRun(currentAscii == true, buffer.toString())
    return runs
}

/**
 * 跑一段 `xdotool type` 的 shell 命令（中文等非 keysym 字符）。
 *
 * 三处非它不可，都是实测撞出来的（2026-11-14，容器内 Xvfb + xterm + 抓 stdin 字节）：
 * 1. **不能用常驻 `xdotool -` 的 stdin**：`-` 模式按空白切分参数、**不认引号**，
 *    `type '中文 空格'` 会被拆成 `type` + `'中文` + `空格`，后半段当成新命令丢掉。
 * 2. **用 base64 传文本**：命令里不出现空格 / 引号 / 反斜杠，就不需要任何转义，
 *    中文、emoji、换行都安全。
 * 3. **`--delay` 不能小**：xdotool 打非 Latin-1 字符靠动态改 keymap 绑一个空闲 keycode，
 *    连续 remap 来不及生效就会吞字 —— 实测 `--delay 20` 时「中文 空格测试 v2」只剩
 *    「测  v2」，`--delay 150` 才一字不差。
 */
internal fun xdotoolTypeShellCommand(text: String): String {
    val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    return "printf %s '$b64' | base64 -d | xdotool type --clearmodifiers " +
        "--delay $TYPE_DELAY_MS --file -"
}

/** xdotool type 每字符间隔：见 [xdotoolTypeShellCommand] 第 3 条（低了吞字）。 */
internal const val TYPE_DELAY_MS = 150
