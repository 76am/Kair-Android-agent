package dev.kair.app.core.term

/**
 * 可变字符缓冲（主屏 / alternate screen 各一份，由 [TerminalParser] 持有）。
 *
 * 只实现 VT100 / xterm 子集需要的操作；滚动区、自动换行、插入/删除行都在这里，
 * 解析器只负责把控制序列翻译成这些操作。
 */
internal class TerminalBuffer(rows: Int, cols: Int) {
    var rows: Int = rows
        private set
    var cols: Int = cols
        private set

    private var cells: Array<TerminalCell> = Array(rows * cols) { TerminalCell() }

    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    /** 自动换行是「滞后」的：写在最后一列后光标仍停在最后一格，下一个字符才换行。 */
    var pendingWrap: Boolean = false

    fun cell(row: Int, col: Int): TerminalCell = cells[row * cols + col]

    fun clear() {
        cells = Array(rows * cols) { TerminalCell() }
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    /** 尺寸变化保留左上角已有内容（C4：旋转 / 分屏后内容不得丢失）。 */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val next = Array(newRows * newCols) { TerminalCell() }
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) {
                next[r * newCols + c] = cells[r * cols + c]
            }
        }
        cells = next
        rows = newRows
        cols = newCols
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        pendingWrap = false
        scrollTop = 0
        scrollBottom = newRows - 1
    }

    fun putChar(
        char: Char,
        width: Int,
        fg: Int,
        bg: Int,
        bold: Boolean,
        underline: Boolean,
        reverse: Boolean,
        autoWrap: Boolean = true,
    ) {
        if (pendingWrap && autoWrap) {
            cursorCol = 0
            lineFeed()
            pendingWrap = false
        }
        val wide = width.coerceIn(1, 2)
        if (wide == 2 && cursorCol >= cols - 1) {
            if (autoWrap) {
                // 宽字符落在最后一列：先换行，避免把半格写到屏幕外。
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = (cols - 1).coerceAtLeast(0)
            }
        }
        cells[cursorRow * cols + cursorCol] = TerminalCell(
            char = char, fg = fg, bg = bg, bold = bold, underline = underline, reverse = reverse,
        )
        if (wide == 2 && cursorCol + 1 < cols) {
            cells[cursorRow * cols + cursorCol + 1] = TerminalCell(
                char = ' ', fg = fg, bg = bg, bold = bold, underline = underline,
                reverse = reverse, continuation = true,
            )
        }
        if (!autoWrap) {
            cursorCol = (cursorCol + wide).coerceAtMost(cols - 1)
            pendingWrap = false
        } else if (cursorCol + wide >= cols) {
            cursorCol = (cols - 1).coerceAtLeast(0)
            pendingWrap = true
        } else {
            cursorCol += wide
        }
    }

    fun lineFeed() {
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    fun scrollUp(count: Int) = scroll(count, down = false)

    fun scrollDown(count: Int) = scroll(count, down = true)

    private fun scroll(count: Int, down: Boolean) {
        val n = count.coerceIn(1, scrollBottom - scrollTop + 1)
        if (down) {
            for (r in scrollBottom downTo scrollTop + n) {
                copyRow(r - n, r)
            }
            for (r in scrollTop until scrollTop + n) blankRow(r)
        } else {
            for (r in scrollTop..scrollBottom - n) {
                copyRow(r + n, r)
            }
            for (r in scrollBottom - n + 1..scrollBottom) blankRow(r)
        }
    }

    private fun copyRow(from: Int, to: Int) {
        System.arraycopy(cells, from * cols, cells, to * cols, cols)
    }

    private fun blankRow(row: Int) {
        for (c in 0 until cols) cells[row * cols + c] = TerminalCell()
    }

    /** ED：0 = 光标到屏幕尾，1 = 屏首到光标，2/3 = 整屏。 */
    fun eraseInDisplay(mode: Int, bg: Int = TerminalColor.DEFAULT) {
        when (mode) {
            0 -> {
                eraseInLine(0, bg)
                for (r in cursorRow + 1 until rows) fillRow(r, bg)
            }

            1 -> {
                for (r in 0 until cursorRow) fillRow(r, bg)
                eraseInLine(1, bg)
            }

            else -> {
                for (r in 0 until rows) fillRow(r, bg)
            }
        }
        pendingWrap = false
    }

    /** EL：0 = 光标到行尾，1 = 行首到光标，2 = 整行。 */
    fun eraseInLine(mode: Int, bg: Int = TerminalColor.DEFAULT) {
        when (mode) {
            0 -> for (c in cursorCol until cols) cells[cursorRow * cols + c] = blank(bg)
            1 -> for (c in 0..cursorCol.coerceAtMost(cols - 1)) cells[cursorRow * cols + c] = blank(bg)
            else -> fillRow(cursorRow, bg)
        }
    }

    private fun blank(bg: Int) = TerminalCell(bg = bg)

    private fun fillRow(row: Int, bg: Int) {
        for (c in 0 until cols) cells[row * cols + c] = blank(bg)
    }

    fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val n = count.coerceIn(1, scrollBottom - cursorRow + 1)
        for (r in scrollBottom downTo cursorRow + n) copyRow(r - n, r)
        for (r in cursorRow until cursorRow + n) blankRow(r)
        pendingWrap = false
    }

    fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val n = count.coerceIn(1, scrollBottom - cursorRow + 1)
        for (r in cursorRow..scrollBottom - n) copyRow(r + n, r)
        for (r in scrollBottom - n + 1..scrollBottom) blankRow(r)
        pendingWrap = false
    }

    fun insertChars(count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cols - 1 downTo cursorCol + n) {
            cells[cursorRow * cols + c] = cells[cursorRow * cols + c - n]
        }
        for (c in cursorCol until cursorCol + n) cells[cursorRow * cols + c] = TerminalCell()
        pendingWrap = false
    }

    fun deleteChars(count: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cursorCol until cols - n) {
            cells[cursorRow * cols + c] = cells[cursorRow * cols + c + n]
        }
        for (c in cols - n until cols) cells[cursorRow * cols + c] = TerminalCell()
        pendingWrap = false
    }

    fun eraseChars(count: Int, bg: Int) {
        val n = count.coerceIn(1, cols - cursorCol)
        for (c in cursorCol until cursorCol + n) cells[cursorRow * cols + c] = blank(bg)
        pendingWrap = false
    }

    fun snapshot(cursorVisible: Boolean, applicationCursorKeys: Boolean): TerminalSnapshot =
        TerminalSnapshot(
            rows = rows,
            cols = cols,
            cells = cells.toList(),
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            applicationCursorKeys = applicationCursorKeys,
        )
}
