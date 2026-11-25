package dev.kair.app.core.term

/**
 * VT100 / xterm **子集**解析器：字节流 → 字符网格。
 *
 * 目标不是「支持所有序列」，而是让 `vim` / `top` 能正常用（任务文档 §2.1）：
 * 光标定位、清屏 / 清行、SGR（16 色 + 256 色 + 真彩色）、滚动区、alternate screen、
 * 光标显隐、自动换行、DEC 特殊图形字符集（`ESC ( 0`，见 [acsChar]）。
 * 未实现：滚动回看、鼠标事件、链接、组合字符（见任务文档 §9 挂账）。
 *
 * 纯 Kotlin、无 Android 依赖：黄金用例在 JVM 里喂字节流断言网格（V1）。
 * 线程模型：调用方串行（PtySession 的读循环写、UI 线程只取 [snapshot]）。
 */
class TerminalParser(initialRows: Int = 24, initialCols: Int = 80) {

    private var main = TerminalBuffer(initialRows, initialCols)
    private var alt: TerminalBuffer? = null
    private var active = main
    private var altActive = false

    private var cursorVisible = true
    private var applicationCursorKeys = false
    private var autoWrap = true

    // ---- 当前 SGR 属性 ------------------------------------------------------
    private var fg = TerminalColor.DEFAULT
    private var bg = TerminalColor.DEFAULT
    private var bold = false
    private var underline = false
    private var reverse = false

    private var saved: SavedCursor? = null

    /** G0 当前是否选中 DEC 特殊图形字符集（`ESC ( 0`），见 [acsChar]。 */
    private var acs = false

    /** 刚看到的字符集选择符（`(` = G0，`)` = G1 …），下一个字符是具体集合。 */
    private var charsetTarget = '\u0000'

    // ---- 状态机 ------------------------------------------------------------
    private enum class State {
        GROUND,
        ESC,
        ESC_CHARSET,
        ESC_HASH,
        CSI,
        OSC,
        OSC_ESC,
        DCS,
        DCS_ESC,
    }

    private var state = State.GROUND
    private val csi = StringBuilder()

    /** UTF-8 跨读不完整时留下的尾巴。 */
    private var pending = ByteArray(0)

    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        main.resize(rows, cols)
        alt?.resize(rows, cols)
        active = if (altActive) alt ?: main else main
    }

    fun snapshot(): TerminalSnapshot =
        active.snapshot(cursorVisible, applicationCursorKeys)

    fun reset() {
        main.clear()
        alt?.clear()
        alt = null
        active = main
        altActive = false
        resetAttrs()
        saved = null
        cursorVisible = true
        applicationCursorKeys = false
        autoWrap = true
        state = State.GROUND
        csi.setLength(0)
        pending = ByteArray(0)
        acs = false
        charsetTarget = '\u0000'
    }

    fun feed(bytes: ByteArray) {
        var i = 0
        var data = bytes
        if (pending.isNotEmpty()) {
            data = ByteArray(pending.size + bytes.size)
            System.arraycopy(pending, 0, data, 0, pending.size)
            System.arraycopy(bytes, 0, data, pending.size, bytes.size)
            pending = ByteArray(0)
        }
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> 1 // 非法首字节：按单字节处理，避免整条流卡死
            }
            if (i + len > data.size) {
                pending = data.copyOfRange(i, data.size)
                return
            }
            if (len == 1) {
                advance(b.toChar())
            } else {
                val text = String(data, i, len, Charsets.UTF_8)
                advance(text[0])
                // 4 字节码点（emoji 等）超出 BMP：子集只保留高位代理，见任务文档 §9 挂账。
            }
            i += len
        }
    }

    // ---- 字节 → 状态机 ------------------------------------------------------

    private fun advance(ch: Char) {
        when (state) {
            State.GROUND -> ground(ch)
            State.ESC -> escape(ch)
            State.ESC_CHARSET -> {
                // `ESC ( 0` 切 DEC 制表符字符集，`ESC ( B` 切回 ASCII；G1 的选择符只消费不生效。
                if (charsetTarget == '(') acs = ch == '0'
                state = State.GROUND
            }

            State.ESC_HASH -> state = State.GROUND
            State.CSI -> csi(ch)
            State.OSC -> when (ch) {
                '\u0007' -> state = State.GROUND
                '\u001b' -> state = State.OSC_ESC
            }

            State.OSC_ESC -> state = if (ch == '\\') State.GROUND else State.OSC
            State.DCS -> when (ch) {
                '\u001b' -> state = State.DCS_ESC
            }

            State.DCS_ESC -> state = if (ch == '\\') State.GROUND else State.DCS
        }
    }

    private fun ground(ch: Char) {
        when {
            ch == '\u001b' -> state = State.ESC
            ch == '\n' -> active.lineFeed()
            ch == '\r' -> {
                active.cursorCol = 0
                active.pendingWrap = false
            }

            ch == '\b' -> {
                active.cursorCol = (active.cursorCol - 1).coerceAtLeast(0)
                active.pendingWrap = false
            }

            ch == '\t' -> tab(1)
            ch.code < 0x20 || ch == '\u007f' -> Unit
            else -> {
                val out = if (acs) acsChar(ch) ?: ch else ch
                active.putChar(out, widthOf(out), fg, bg, bold, underline, reverse, autoWrap)
            }
        }
    }

    private fun escape(ch: Char) {
        state = State.GROUND
        when (ch) {
            '[' -> {
                csi.setLength(0)
                state = State.CSI
            }

            ']' -> state = State.OSC
            'P' -> state = State.DCS
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> active.lineFeed()
            'M' -> active.reverseIndex()
            'E' -> {
                active.cursorCol = 0
                active.lineFeed()
            }

            'c' -> reset()
            '(', ')', '*', '+', '-', '.', '/' -> {
                charsetTarget = ch
                state = State.ESC_CHARSET
            }

            '#' -> state = State.ESC_HASH
            else -> Unit
        }
    }

    private fun csi(ch: Char) {
        val code = ch.code
        if (code in 0x40..0x7E) {
            val raw = csi.toString()
            csi.setLength(0)
            state = State.GROUND
            applyCsi(ch, raw)
        } else if (code in 0x20..0x3F) {
            csi.append(ch)
        } else {
            // 序列里混进控制字符：按终止处理，避免吞掉后面的正文
            state = State.GROUND
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        val private = raw.startsWith('?')
        val body = raw.trimStart('?', '>', '!', '<', '=')
        val params = body.split(';').map { it.toIntOrNull() ?: 0 }
        when (final) {
            'A' -> moveCursor(0, -count(params))
            'B' -> moveCursor(0, count(params))
            'C' -> moveCursor(count(params), 0)
            'D' -> moveCursor(-count(params), 0)
            'E' -> {
                active.cursorCol = 0
                repeat(count(params)) { active.lineFeed() }
            }

            'F' -> {
                active.cursorCol = 0
                repeat(count(params)) { active.reverseIndex() }
            }

            'G', '`' -> moveTo(active.cursorRow, count(params) - 1)
            'd' -> moveTo(count(params) - 1, active.cursorCol)
            'H', 'f' -> moveTo(param(params, 0, 1) - 1, param(params, 1, 1) - 1)

            'a' -> moveCursor(count(params), 0)
            'e' -> moveCursor(0, count(params))
            'J' -> active.eraseInDisplay(param(params, 0, 0), bg)
            'K' -> active.eraseInLine(param(params, 0, 0), bg)
            'X' -> active.eraseChars(count(params), bg)
            'L' -> active.insertLines(count(params))
            'M' -> active.deleteLines(count(params))
            '@' -> active.insertChars(count(params))
            'P' -> active.deleteChars(count(params))
            'S' -> active.scrollUp(count(params))
            'T' -> active.scrollDown(count(params))
            'm' -> sgr(params)
            'r' -> {
                val top = (param(params, 0, 1) - 1).coerceIn(0, active.rows - 1)
                val bottom = (param(params, 1, active.rows) - 1).coerceIn(top, active.rows - 1)
                active.scrollTop = top
                active.scrollBottom = bottom
                active.cursorRow = top
                active.cursorCol = 0
                active.pendingWrap = false
            }

            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> setMode(private, params, true)
            'l' -> setMode(private, params, false)
            'I' -> tab(count(params))
            'Z' -> repeat(count(params)) { tab(-1) }
            else -> Unit
        }
    }

    private fun setMode(private: Boolean, params: List<Int>, on: Boolean) {
        if (!private) return
        for (p in params) {
            when (p) {
                1 -> applicationCursorKeys = on
                7 -> autoWrap = on
                25 -> cursorVisible = on
                47, 1047 -> switchAlt(on, saveRestore = false)
                1048 -> if (on) saveCursor() else restoreCursor()
                1049 -> switchAlt(on, saveRestore = true)
                else -> Unit
            }
        }
    }

    private fun switchAlt(on: Boolean, saveRestore: Boolean) {
        if (on) {
            if (altActive) return
            if (saveRestore) saveCursor()
            val buffer = alt ?: TerminalBuffer(main.rows, main.cols).also { alt = it }
            buffer.clear()
            active = buffer
            altActive = true
        } else {
            if (!altActive) return
            active = main
            altActive = false
            if (saveRestore) restoreCursor()
        }
    }

    private fun count(params: List<Int>, index: Int = 0): Int =
        param(params, index, 1).let { if (it <= 0) 1 else it }

    private fun param(params: List<Int>, index: Int, fallback: Int): Int =
        params.getOrNull(index)?.takeIf { it != 0 } ?: fallback

    /**
     * 绝对定位。**必须清 pendingWrap**：光标停在最后一列后，下一个可打印字符会先换行；
     * 若定位后仍带着 pendingWrap，写入会先滚屏一行再落字（vim 画标尺时踩过，U59 §12.6）。
     */
    private fun moveTo(row: Int, col: Int) {
        active.cursorRow = row.coerceIn(0, active.rows - 1)
        active.cursorCol = col.coerceIn(0, active.cols - 1)
        active.pendingWrap = false
    }

    private fun moveCursor(dx: Int, dy: Int) {
        active.cursorCol = (active.cursorCol + dx).coerceIn(0, active.cols - 1)
        active.cursorRow = (active.cursorRow + dy).coerceIn(0, active.rows - 1)
        active.pendingWrap = false
    }

    private fun tab(direction: Int) {
        val step = 8
        var col = active.cursorCol
        if (direction > 0) {
            col = ((col / step) + 1) * step
        } else {
            col = ((col - 1) / step) * step
        }
        active.cursorCol = col.coerceIn(0, active.cols - 1)
        active.pendingWrap = false
    }

    private fun sgr(params: List<Int>) {
        if (params.isEmpty() || params == listOf(0)) {
            resetAttrs()
            return
        }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetAttrs()
                1 -> bold = true
                4 -> underline = true
                7 -> reverse = true
                22 -> bold = false
                24 -> underline = false
                27 -> reverse = false
                in 30..37 -> fg = p - 30
                39 -> fg = TerminalColor.DEFAULT
                in 40..47 -> bg = p - 40
                49 -> bg = TerminalColor.DEFAULT
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
                38, 48 -> {
                    val target = if (p == 38) Target.FG else Target.BG
                    val consumed = extendedColor(params, i, target)
                    i += consumed
                }

                else -> Unit
            }
            i++
        }
    }

    private enum class Target { FG, BG }

    /** 返回额外消费的参数个数（不含当前 38/48 本身）。 */
    private fun extendedColor(params: List<Int>, index: Int, target: Target): Int {
        val kind = params.getOrNull(index + 1) ?: return 0
        return when (kind) {
            5 -> {
                val n = params.getOrNull(index + 2) ?: return 0
                setColor(target, n.coerceIn(0, 255))
                2
            }

            2 -> {
                val r = params.getOrNull(index + 2) ?: return 0
                val g = params.getOrNull(index + 3) ?: return 0
                val b = params.getOrNull(index + 4) ?: return 0
                setColor(target, TerminalColor.rgb(r, g, b))
                4
            }

            else -> 0
        }
    }

    private fun setColor(target: Target, color: Int) {
        if (target == Target.FG) fg = color else bg = color
    }

    private fun resetAttrs() {
        fg = TerminalColor.DEFAULT
        bg = TerminalColor.DEFAULT
        bold = false
        underline = false
        reverse = false
    }

    private data class SavedCursor(
        val row: Int,
        val col: Int,
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val underline: Boolean,
        val reverse: Boolean,
    )

    private fun saveCursor() {
        saved = SavedCursor(active.cursorRow, active.cursorCol, fg, bg, bold, underline, reverse)
    }

    private fun restoreCursor() {
        val s = saved ?: return
        active.cursorRow = s.row.coerceIn(0, active.rows - 1)
        active.cursorCol = s.col.coerceIn(0, active.cols - 1)
        fg = s.fg
        bg = s.bg
        bold = s.bold
        underline = s.underline
        reverse = s.reverse
        active.pendingWrap = false
    }

    private companion object {
        /**
         * DEC 特殊图形字符集（`ESC ( 0`）的映射，见 VT100 手册 Table 3-9。
         *
         * ncurses 的 `+`/`|`/`-` 画线在 UTF-8 环境下多数已换成 Unicode，但 vim、mc 等
         * 仍会走 ACS；不翻译就会把 `q`/`x` 原样画成字母（U59 §12.5）。
         */
        fun acsChar(ch: Char): Char? = when (ch) {
            '_' -> ' '
            '`' -> '\u25C6' // ◆
            'a' -> '\u2592' // ▒
            'b' -> '\u2409' // ␉
            'c' -> '\u240C' // ␌
            'd' -> '\u240D' // ␍
            'e' -> '\u240A' // ␊
            'f' -> '\u00B0' // °
            'g' -> '\u00B1' // ±
            'h' -> '\u2424' // ␤
            'i' -> '\u240B' // ␋
            'j' -> '\u2518' // ┘
            'k' -> '\u2510' // ┐
            'l' -> '\u250C' // ┌
            'm' -> '\u2514' // └
            'n' -> '\u253C' // ┼
            'o' -> '\u23BA' // ⎺
            'p' -> '\u23BB' // ⎻
            'q' -> '\u2500' // ─
            'r' -> '\u23BC' // ⎼
            's' -> '\u23BD' // ⎽
            't' -> '\u251C' // ├
            'u' -> '\u2524' // ┤
            'v' -> '\u2534' // ┴
            'w' -> '\u252C' // ┬
            'x' -> '\u2502' // │
            'y' -> '\u2264' // ≤
            'z' -> '\u2265' // ≥
            '{' -> '\u03C0' // π
            '|' -> '\u2260' // ≠
            '}' -> '\u00A3' // £
            '~' -> '\u00B7' // ·
            else -> null
        }

        /** 宽字符（两列）判定：CJK、全角标点等常用区段；emoji 见类注释的挂账。 */
        fun widthOf(ch: Char): Int = when {
            ch.code in 0x1100..0x115F -> 2
            ch.code in 0x2E80..0xA4CF -> 2
            ch.code in 0xAC00..0xD7A3 -> 2
            ch.code in 0xF900..0xFAFF -> 2
            ch.code in 0xFE30..0xFE4F -> 2
            ch.code in 0xFF00..0xFF60 -> 2
            ch.code in 0xFFE0..0xFFE6 -> 2
            else -> 1
        }
    }
}
