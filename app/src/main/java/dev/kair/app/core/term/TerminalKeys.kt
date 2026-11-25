package dev.kair.app.core.term

/**
 * 终端按键编码（纯 Kotlin，JVM 可测）。
 *
 * 只做「输入 → 字节」的确定性映射，不持有状态：
 * 方向键是否走应用光标键模式（DECCKM）由 [TerminalSnapshot.applicationCursorKeys] 传入。
 */
object TerminalKeys {
    val ENTER: ByteArray = byteArrayOf(0x0D)
    val TAB: ByteArray = byteArrayOf(0x09)
    val ESC: ByteArray = byteArrayOf(0x1B)
    val BACKSPACE: ByteArray = byteArrayOf(0x7F)

    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    /**
     * 扩展按键（Termux 默认两行布局的顺序）。
     *
     * [label] 是按键上的符号（ASCII / 箭头，与语言无关）；[char] 非空时支持 CTRL 组合
     * （`CTRL` + `/` → `Ctrl-/`），纯动作键（ESC / HOME / 方向键）为 null。
     */
    enum class ExtraKey(val label: String, val char: Char?) {
        ESC("ESC", null),
        SLASH("/", '/'),
        DASH("-", '-'),
        HOME("HOME", null),
        UP("\u2191", null),
        END("END", null),
        PGUP("PGUP", null),
        TAB("TAB", null),
        CTRL("CTRL", null),
        ALT("ALT", null),
        LEFT("\u2190", null),
        DOWN("\u2193", null),
        RIGHT("\u2192", null),
        PGDN("PGDN", null),
        FN("FN", null),
        F1("F1", null),
        F2("F2", null),
        F3("F3", null),
        F4("F4", null),
        F5("F5", null),
        F6("F6", null),
        F7("F7", null),
        F8("F8", null),
        F9("F9", null),
        F10("F10", null),
        F11("F11", null),
        F12("F12", null),
    }

    /** xterm 的 F1–F12 序列（F1–F4 走 SS3，F5 起走 CSI `n~`）。 */
    fun fKey(number: Int): ByteArray {
        val suffix = when (number) {
            1 -> "\u001BOP"
            2 -> "\u001BOQ"
            3 -> "\u001BOR"
            4 -> "\u001BOS"
            5 -> "\u001B[15~"
            6 -> "\u001B[17~"
            7 -> "\u001B[18~"
            8 -> "\u001B[19~"
            9 -> "\u001B[20~"
            10 -> "\u001B[21~"
            11 -> "\u001B[23~"
            12 -> "\u001B[24~"
            else -> return ByteArray(0)
        }
        return suffix.toByteArray(Charsets.US_ASCII)
    }

    /** 方向键：应用光标键模式发 `ESC O x`，否则发 `ESC [ x`。 */
    fun arrow(direction: Arrow, applicationCursorKeys: Boolean): ByteArray {
        val suffix = when (direction) {
            Arrow.UP -> "A"
            Arrow.DOWN -> "B"
            Arrow.RIGHT -> "C"
            Arrow.LEFT -> "D"
        }
        return cursorKey(suffix, applicationCursorKeys)
    }

    /** HOME / END：应用光标键模式发 `ESC O H/F`，否则发 `ESC [ H/F`。 */
    fun home(applicationCursorKeys: Boolean): ByteArray = cursorKey("H", applicationCursorKeys)

    fun end(applicationCursorKeys: Boolean): ByteArray = cursorKey("F", applicationCursorKeys)

    val PAGE_UP: ByteArray = "\u001B[5~".toByteArray(Charsets.US_ASCII)
    val PAGE_DOWN: ByteArray = "\u001B[6~".toByteArray(Charsets.US_ASCII)

    /** 扩展按键 → 字节。CTRL / ALT 是修饰键，本身不发字节（返回空数组）。 */
    fun extra(key: ExtraKey, applicationCursorKeys: Boolean): ByteArray = when (key) {
        ExtraKey.ESC -> ESC
        ExtraKey.TAB -> TAB
        ExtraKey.SLASH -> key.label.toByteArray(Charsets.US_ASCII)
        ExtraKey.DASH -> key.label.toByteArray(Charsets.US_ASCII)
        ExtraKey.HOME -> home(applicationCursorKeys)
        ExtraKey.END -> end(applicationCursorKeys)
        ExtraKey.PGUP -> PAGE_UP
        ExtraKey.PGDN -> PAGE_DOWN
        ExtraKey.UP -> arrow(Arrow.UP, applicationCursorKeys)
        ExtraKey.DOWN -> arrow(Arrow.DOWN, applicationCursorKeys)
        ExtraKey.LEFT -> arrow(Arrow.LEFT, applicationCursorKeys)
        ExtraKey.RIGHT -> arrow(Arrow.RIGHT, applicationCursorKeys)
        ExtraKey.F1 -> fKey(1)
        ExtraKey.F2 -> fKey(2)
        ExtraKey.F3 -> fKey(3)
        ExtraKey.F4 -> fKey(4)
        ExtraKey.F5 -> fKey(5)
        ExtraKey.F6 -> fKey(6)
        ExtraKey.F7 -> fKey(7)
        ExtraKey.F8 -> fKey(8)
        ExtraKey.F9 -> fKey(9)
        ExtraKey.F10 -> fKey(10)
        ExtraKey.F11 -> fKey(11)
        ExtraKey.F12 -> fKey(12)
        ExtraKey.CTRL, ExtraKey.ALT, ExtraKey.FN -> ByteArray(0)
    }

    private fun cursorKey(suffix: String, applicationCursorKeys: Boolean): ByteArray {
        val prefix = if (applicationCursorKeys) "\u001BO" else "\u001B["
        return (prefix + suffix).toByteArray(Charsets.US_ASCII)
    }

    /** Ctrl + 字符 → 控制字节；无法映射时返回 null（调用方按普通文本处理）。 */
    fun ctrl(ch: Char): ByteArray? {
        val lower = ch.lowercaseChar()
        if (lower in 'a'..'z') return byteArrayOf((lower.code - 'a'.code + 1).toByte())
        return when (ch) {
            ' ', '@' -> byteArrayOf(0x00)
            '[' -> byteArrayOf(0x1B)
            '\\' -> byteArrayOf(0x1C)
            ']' -> byteArrayOf(0x1D)
            '^' -> byteArrayOf(0x1E)
            // xterm / Termux 惯例：Ctrl-/ 与 Ctrl-- 都发 US（readline 的 undo）。
            '/', '_', '-' -> byteArrayOf(0x1F)
            '?' -> byteArrayOf(0x7F)
            else -> null
        }
    }

    /**
     * 输入层文本变化 → 需要下发的字节。
     *
     * 光标视为在行尾：先按公共前缀回退（`0x7F`），再补新后缀。
     * 相同文本返回空数组；程序化清空（发送回车后）不走本函数，因此不会补发退格。
     *
     * [ctrl] / [alt] 是扩展键里预按下的修饰键（一次性）：Ctrl 按控制字符编码，
     * Alt 加 `ESC` 前缀；无法映射的字符按原样发（与 xterm 一致）。
     */
    fun diff(old: String, new: String, ctrl: Boolean = false, alt: Boolean = false): ByteArray {
        if (old == new) return ByteArray(0)
        val common = commonPrefix(old, new)
        val bytes = mutableListOf<Byte>()
        repeat(old.length - common) { bytes.add(BACKSPACE[0]) }
        new.substring(common).forEach { ch ->
            val base = (if (ctrl) ctrl(ch) else null) ?: ch.toString().toByteArray(Charsets.UTF_8)
            if (alt) bytes.addAll(ESC.toList())
            bytes.addAll(base.toList())
        }
        return bytes.toByteArray()
    }

    private fun commonPrefix(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }
}
