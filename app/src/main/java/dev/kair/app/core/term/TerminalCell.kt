package dev.kair.app.core.term

/**
 * 终端单元格与颜色的纯数据模型（**无 Android 依赖**，JVM 可测）。
 *
 * 颜色编码约定（全包唯一事实源）：
 * - [TerminalColor.DEFAULT]（-1）：用主题默认前景 / 背景；
 * - 0..255：调色板索引（16 色 + 256 色 cube）；
 * - [TerminalColor.TRUE_COLOR_FLAG] | 0xRRGGBB：24 位真彩色。
 */
object TerminalColor {
    const val DEFAULT: Int = -1
    const val TRUE_COLOR_FLAG: Int = 0x1000000

    fun rgb(r: Int, g: Int, b: Int): Int =
        TRUE_COLOR_FLAG or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun isTrueColor(color: Int): Boolean = color and TRUE_COLOR_FLAG != 0

    /** 真彩色 → 0xRRGGBB（不含 alpha）。 */
    fun rgbOf(color: Int): Int = color and 0xFFFFFF
}

/** 一个字符格。宽字符占两格：左格带字符，右格 [continuation] = true。 */
data class TerminalCell(
    val char: Char = ' ',
    val fg: Int = TerminalColor.DEFAULT,
    val bg: Int = TerminalColor.DEFAULT,
    val bold: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val continuation: Boolean = false,
)

/**
 * 交给 UI 的不可变快照：整屏格子 + 光标状态。
 *
 * 每帧都整屏拷贝是有意的：解析器在 IO 线程改内部缓冲，UI 线程只读快照，
 * 两者不需要共享可变状态（行数 × 列数最多几千个对象，`top` 的刷新频率能承受）。
 */
data class TerminalSnapshot(
    val rows: Int,
    val cols: Int,
    /** 行优先，长度恒为 [rows] × [cols]。 */
    val cells: List<TerminalCell>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    /** 应用光标键模式（DECCKM）：方向键发 `ESC O A` 而不是 `ESC [ A`。 */
    val applicationCursorKeys: Boolean,
) {
    fun cell(row: Int, col: Int): TerminalCell = cells[row * cols + col]

    companion object {
        fun empty(rows: Int, cols: Int): TerminalSnapshot = TerminalSnapshot(
            rows = rows,
            cols = cols,
            cells = List(rows * cols) { TerminalCell() },
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            applicationCursorKeys = false,
        )
    }
}

/**
 * xterm 调色板（16 基色 + 6×6×6 cube + 24 级灰），输出 ARGB。
 * 终端底色固定深色，但颜色本身跟随标准调色板，深浅主题下都可读（见 C5）。
 */
object TerminalPalette {
    private val base16: IntArray = intArrayOf(
        0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
        0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
        0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
        0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt(),
    )

    private val cube: IntArray = intArrayOf(0, 95, 135, 175, 215, 255)

    fun argb(color: Int, bold: Boolean = false): Int = when {
        color == TerminalColor.DEFAULT -> 0xFFD5DEE7.toInt()
        TerminalColor.isTrueColor(color) -> 0xFF000000.toInt() or TerminalColor.rgbOf(color)
        color in 0..15 -> {
            val c = base16[color]
            // 粗体在 0-7 上使用 8-15 的亮色（传统终端行为）
            if (bold && color < 8) base16[color + 8] else c
        }

        color in 16..231 -> {
            val n = color - 16
            val r = cube[n / 36]
            val g = cube[(n / 6) % 6]
            val b = cube[n % 6]
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }

        color in 232..255 -> {
            val level = 8 + (color - 232) * 10
            0xFF000000.toInt() or (level shl 16) or (level shl 8) or level
        }

        else -> 0xFFD5DEE7.toInt()
    }
}
