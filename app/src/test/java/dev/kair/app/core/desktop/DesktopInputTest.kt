package dev.kair.app.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U86 §5.1 · DesktopInput 判据：keysym 表 / 单指三段 / 双指右键 / 双指滚轮 / 三指不产生事件。
 */
class DesktopInputTest {

    private fun gestures() = DesktopGestures(slopPx = 12f, tapMaxMovePx = 30f, wheelStepPx = 90f)

    // ---- keysym ----

    @Test
    fun `letters and digits map to ascii keysyms`() {
        assertEquals(0x61, keysymForChar('a'))
        assertEquals(0x41, keysymForChar('A'))
        assertEquals(0x30, keysymForChar('0'))
        assertEquals(0x39, keysymForChar('9'))
        assertEquals(0x20, keysymForChar(' '))
    }

    @Test
    fun `printable ascii symbols map to their code points`() {
        // U89H 真机：输入框打不出 `.` `/` `-`。原实现只放行字母/数字/空格，
        // 所有符号静默丢弃 —— 命令里的路径、选项、dotfile 全都敲不出来。
        for (c in ".,/-_=+[]{}()\\|;:'\"`~!@#$%^&*<>?") {
            assertEquals("符号 '$c' 必须映射成自己的码位", c.code, keysymForChar(c))
        }
        // 中文 / 控制字符仍然不发（绝不发 keysym 0）。
        assertNull(keysymForChar('中'))
    }

    @Test
    fun `named keys map to x keysyms`() {
        assertEquals(0xFF0D, keysymForName("Enter"))
        assertEquals(0xFF08, keysymForName("BackSpace"))
        assertEquals(0xFF09, keysymForName("Tab"))
        assertEquals(0xFF1B, keysymForName("Esc"))
        assertEquals(0xFF51, keysymForName("Left"))
        assertEquals(0xFF52, keysymForName("Up"))
        assertEquals(0xFF53, keysymForName("Right"))
        assertEquals(0xFF54, keysymForName("Down"))
        assertEquals(0xFFFF, keysymForName("Delete"))
        assertEquals(0xFFE1, keysymForName("Shift"))
        assertEquals(0xFFE3, keysymForName("Control"))
        assertEquals(0xFFE9, keysymForName("Alt"))
    }

    @Test
    fun `function keys span the x11 f key range`() {
        assertEquals(0xFFBE, keysymForName("F1"))
        assertEquals(0xFFC9, keysymForName("F12"))
        assertEquals(0xFFC3, keysymForName("F6"))
        // 越界仍然不发（F0 / F13 不在这一段）。
        assertNull(keysymForName("F0"))
        assertNull(keysymForName("F13"))
    }

    @Test
    fun `unknown input maps to nothing, never zero`() {
        assertNull(keysymForChar('中'))
        assertNull(keysymForChar('\u0007'))
        assertNull(keysymForName("F13"))
    }

    @Test
    fun `newline key sends shift enter as one balanced chord`() {
        // 软键盘条只有一个「发送」回车（ImeAction.Send），桌面应用里要换行就得给 Shift+Enter。
        // 顺序固定：Shift 先按下、Enter 敲一下、Shift 最后松开；中途不能出现同一键重复按下。
        assertEquals(
            listOf(
                0xFFE1 to true,
                0xFF0D to true,
                0xFF0D to false,
                0xFFE1 to false,
            ),
            newlineKeySequence(),
        )
    }

    @Test
    fun `xdotool key maps named keys and uppercase chords`() {
        assertEquals(listOf("keydown Return"), xdotoolKeyCommands(0xFF0D, true))
        assertEquals(listOf("keyup Return"), xdotoolKeyCommands(0xFF0D, false))
        assertEquals(listOf("keydown space"), xdotoolKeyCommands(0x20, true))
        assertEquals(listOf("keydown Shift_L", "keydown a"), xdotoolKeyCommands(0x41, true))
        assertEquals(listOf("keyup a", "keyup Shift_L"), xdotoolKeyCommands(0x41, false))
        assertEquals(emptyList<String>(), xdotoolKeyCommands(0x4E2D, true))
    }

    @Test
    fun `xdotool key sends symbols with the right shift level`() {
        // US 布局：`. / -` 等基础键直接按；`! _ :` 等在同一个物理键的上档，必须自己补 Shift ——
        // `keydown` 只按 keycode，不会替你换层，不补就是「打 ! 出 1」。
        assertEquals(listOf("keydown period"), xdotoolKeyCommands(0x2E, true))
        assertEquals(listOf("keyup slash"), xdotoolKeyCommands(0x2F, false))
        assertEquals(listOf("keydown minus"), xdotoolKeyCommands(0x2D, true))
        assertEquals(listOf("keydown Shift_L", "keydown 1"), xdotoolKeyCommands(0x21, true))
        assertEquals(listOf("keyup 1", "keyup Shift_L"), xdotoolKeyCommands(0x21, false))
        assertEquals(listOf("keydown Shift_L", "keydown minus"), xdotoolKeyCommands(0x5F, true))
        assertEquals(listOf("keydown Shift_L", "keydown semicolon"), xdotoolKeyCommands(0x3A, true))
    }

    @Test
    fun `xdotool key names function keys`() {
        assertEquals(listOf("keydown F1"), xdotoolKeyCommands(0xFFBE, true))
        assertEquals(listOf("keyup F12"), xdotoolKeyCommands(0xFFC9, false))
    }

    @Test
    fun `chord sequence presses modifiers and releases them in reverse order`() {
        // Alt+F4：Alt 按下 → F4 一下 → Alt 松开。屏幕上没法真的同时按两个钮，
        // 这串事件就是「同时按」在远端的样子。
        assertEquals(
            listOf(0xFFE9 to true, 0xFFC2 to true, 0xFFC2 to false, 0xFFE9 to false),
            chordKeySequence(listOf(0xFFE9), 0xFFC2),
        )
        // 多修饰键：按下按传入顺序、松开严格逆序（先松 Shift 会把主键打成字面值）。
        assertEquals(
            listOf(
                0xFFE1 to true, 0xFFE3 to true, 0xFFE9 to true,
                0x74 to true, 0x74 to false,
                0xFFE9 to false, 0xFFE3 to false, 0xFFE1 to false,
            ),
            chordKeySequence(listOf(0xFFE1, 0xFFE3, 0xFFE9), 0x74),
        )
        // 重复的修饰键只按一次，否则远端会收到两次 keydown 而只抬一次。
        assertEquals(4, chordKeySequence(listOf(0xFFE9, 0xFFE9), 0xFFBE).size)
    }

    @Test
    fun `xdotool pointer sends moves and button deltas only`() {
        // 拖动时 mask 不变：只该有 mousemove，不能每帧补一对 mouseup/mousedown。
        assertEquals(listOf("mousemove 10 20"), xdotoolPointerCommands(10, 20, 0, 0))
        assertEquals(listOf("mousemove 10 20", "mousedown 1"), xdotoolPointerCommands(10, 20, 1, 0))
        assertEquals(listOf("mousemove 11 21"), xdotoolPointerCommands(11, 21, 1, 1))
        assertEquals(listOf("mousemove 11 21", "mouseup 1"), xdotoolPointerCommands(11, 21, 0, 1))
        // RFB 的 2 = 右键；xdotool 的右键是按钮 3。
        assertEquals(listOf("mousemove 5 5", "mousedown 3"), xdotoolPointerCommands(5, 5, 2, 0))
        // 滚轮是 click（按下+抬起一次），不是可按住的状态。
        assertEquals(listOf("mousemove 5 5", "click 4"), xdotoolPointerCommands(5, 5, 8, 0))
        assertEquals(listOf("mousemove 5 5", "click 5"), xdotoolPointerCommands(5, 5, 16, 0))
        assertEquals(listOf("mousemove 5 5"), xdotoolPointerCommands(5, 5, 0, 8))
    }

    // ---- 单指（U89：无模式 —— 是「点」还是「移」，抬手那一刻看手指动没动）----

    @Test
    fun `tap clicks at the cursor and never moves it`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        val out = g.down(1, 100f, 200f, 0) +
            g.up(1, 100f, 200f, 50)
        assertEquals(listOf(1, 0), out.map { it.mask })
        // 点击落在**光标**处：从没拖过 → 画面中心，而不是手指的 (100,200)。
        assertEquals(500, out.first().x)
        assertEquals(500, out.first().y)
    }

    @Test
    fun `small jitter below slop still counts as a tap`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        val out = g.down(1, 100f, 100f, 0) +
            g.move(1, 105f, 100f, 20) +
            g.up(1, 105f, 100f, 60)
        assertEquals(listOf(1, 0), out.map { it.mask })
        // 抖动不算滑动：整个过程一条位移事件都不许有。
        assertEquals(500, out[0].x)
        assertEquals(500, out[0].y)
    }

    @Test
    fun `slide moves the cursor by the finger delta and never clicks`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        val out = g.down(1, 100f, 100f, 0) +
            g.move(1, 130f, 100f, 40) +
            g.move(1, 160f, 140f, 80) +
            g.up(1, 160f, 140f, 120)
        // 全程不带按键：手指走 30/40，光标就跟着走 30/40；抬手也不补点击。
        assertEquals(listOf(0, 0, 0), out.map { it.mask })
        assertEquals(130, out[0].x)
        assertEquals(100, out[0].y)
        assertEquals(160, out[1].x)
        assertEquals(140, out[1].y)
        assertEquals(160, out[2].x)
        assertEquals(140, out[2].y)
    }

    @Test
    fun `slide out and back still produces no click`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        val out = g.down(1, 100f, 100f, 0) +
            g.move(1, 200f, 100f, 40) +
            g.move(1, 100f, 100f, 80) +
            g.up(1, 100f, 100f, 120)
        // 已经滑出去的手，滑回原点也仍是「移光标」—— 结算发生在越过 slop 那一刻，不可逆。
        assertEquals(listOf(0, 0, 0), out.map { it.mask })
    }

    @Test
    fun `tap after a slide clicks at the new cursor position`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        // 先把光标滑到 (330,200)
        g.down(1, 100f, 100f, 0)
        g.move(1, 130f, 100f, 40)
        g.move(1, 330f, 200f, 80)
        g.up(1, 330f, 200f, 120)
        g.reset()
        // 下一手在完全不同的地方轻点：点击必须落在新光标处，不在手指处。
        val out = g.down(2, 800f, 800f, 200) + g.up(2, 800f, 800f, 250)
        assertEquals(listOf(1, 0), out.map { it.mask })
        assertEquals(330, out[0].x)
        assertEquals(200, out[0].y)
    }

    @Test
    fun `cursor stops at the viewport edge`() {
        val g = gestures()
        g.viewportW = 200f
        g.viewportH = 200f
        val out = g.down(1, 100f, 100f, 0) + g.move(1, 500f, 500f, 40)
        // 右/下边界是开区间：钳到最后一个能映射进 framebuffer 的点。
        assertEquals(199, out.single().x)
        assertEquals(199, out.single().y)
    }

    @Test
    fun `cursor stops at the letterboxed image edge`() {
        val g = gestures()
        g.viewportX = 100f
        g.viewportY = 50f
        g.viewportW = 800f
        g.viewportH = 600f
        val out = g.down(1, 500f, 350f, 0) +
            g.move(1, 540f, 350f, 40) +
            g.move(1, 2000f, 2000f, 80)
        // 真机现象：Fit 有黑边时光标停在内侧一条竖线，走不到画面右/下边缘。
        // 光标必须钳在**画面矩形** [100,900]×[50,650] 内，而不是层的 [0,800]×[0,600]；
        // 右/下是开区间，所以最后一个可映射的点是 899 / 649。
        assertEquals(899, out.last().x)
        assertEquals(649, out.last().y)
    }

    @Test
    fun `first tap lands at the center of the letterboxed image`() {
        val g = gestures()
        g.viewportX = 100f
        g.viewportY = 50f
        g.viewportW = 800f
        g.viewportH = 600f
        val out = g.down(1, 120f, 80f, 0) + g.up(1, 120f, 80f, 50)
        assertEquals(500, out.first().x) // 100 + 800/2
        assertEquals(350, out.first().y) // 50 + 600/2
    }

    // ---- 长按拖拽（U89：唯一的「按住」入口，不再分模式）----

    @Test
    fun `long press presses at the cursor and drags until release`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        // 先把光标放到 (330,200)：按下必须发生在光标处，而不是手指处。
        g.down(1, 100f, 100f, 0)
        g.move(1, 130f, 100f, 40)
        g.move(1, 330f, 200f, 80)
        g.up(1, 330f, 200f, 120)
        g.reset()
        g.down(2, 800f, 800f, 200)
        val out = g.hold() +
            g.move(2, 850f, 830f, 900) +
            g.move(2, 900f, 880f, 1000) +
            g.up(2, 900f, 880f, 1100)
        // 按下 → 带着左键一路挪 → 抬手释放。这就是拖窗口 / 拖选文字的全部形状。
        assertEquals(listOf(1, 1, 1, 0), out.map { it.mask })
        assertEquals(330, out[0].x)
        assertEquals(200, out[0].y)
        assertEquals(380, out[1].x)
        assertEquals(230, out[1].y)
        assertEquals(430, out[2].x)
        assertEquals(280, out[2].y)
        assertEquals(430, out[3].x)
        assertEquals(280, out[3].y)
    }

    @Test
    fun `long press after the finger already slid is ignored`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        g.down(1, 100f, 100f, 0)
        g.move(1, 200f, 100f, 100) // 已经越过 slop → 这一手是「移光标」
        // 计时器到点时这一手已经是「滑动」了，不能再补一次按下（否则松手会变成一个意外的拖拽/点击）。
        assertEquals(emptyList<Int>(), g.hold().map { it.mask })
    }

    @Test
    fun `long press then release without moving is a plain press and release`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        g.down(1, 100f, 100f, 0)
        val out = g.hold() + g.up(1, 100f, 100f, 900)
        assertEquals(listOf(1, 0), out.map { it.mask })
    }

    // ---- 双指 ----

    @Test
    fun `two finger tap emits right button click`() {
        val g = gestures()
        val out = g.down(1, 200f, 300f, 0) +
            g.down(2, 210f, 310f, 100) +
            g.up(1, 200f, 300f, 200) +
            g.up(2, 210f, 310f, 250)
        assertEquals(2, out.size)
        assertEquals(2, out[0].mask) // button 2 = 右键
        assertEquals(0, out[1].mask)
    }

    @Test
    fun `two finger tap right clicks at the cursor without moving it`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        // 先把光标滑到 (330,200)
        g.down(1, 100f, 100f, 0)
        g.move(1, 130f, 100f, 40)
        g.move(1, 330f, 200f, 80)
        g.up(1, 330f, 200f, 120)
        g.reset()
        val out = g.down(1, 800f, 800f, 200) +
            g.down(2, 810f, 810f, 250) +
            g.up(1, 800f, 800f, 300) +
            g.up(2, 810f, 810f, 350)
        // 真机现象：双指点按（右键）把光标拽到手指处。鼠标右键必须发生在光标处，光标不动。
        assertEquals(listOf(2, 0), out.map { it.mask })
        assertEquals(330, out[0].x)
        assertEquals(200, out[0].y)
    }

    @Test
    fun `two fingers down too far apart in time is not a right tap`() {
        val g = gestures()
        val out = g.down(1, 200f, 300f, 0) +
            g.down(2, 210f, 310f, 400) + // 间隔 > 150ms
            g.up(1, 200f, 300f, 500) +
            g.up(2, 210f, 310f, 550)
        assertEquals(emptyList<Int>(), out.map { it.mask })
    }

    @Test
    fun `two finger drag up sends wheel button four clicks`() {
        val g = gestures()
        val out = g.down(1, 200f, 300f, 0) +
            g.down(2, 260f, 300f, 50) +
            g.move(1, 200f, 200f, 150) + // 两指合计上移：质心上移 100px > 90px 一格
            g.move(2, 260f, 200f, 150) +
            g.up(1, 200f, 200f, 250) +
            g.up(2, 260f, 200f, 250)
        // 每格 = down(button4, mask 8) + up(mask 0)
        assertEquals(listOf(8, 0), out.map { it.mask })
    }

    @Test
    fun `two finger drag down sends wheel button five clicks`() {
        val g = gestures()
        val out = g.down(1, 200f, 300f, 0) +
            g.down(2, 260f, 300f, 50) +
            g.move(1, 200f, 400f, 150) +
            g.move(2, 260f, 400f, 150) +
            g.up(1, 200f, 400f, 250) +
            g.up(2, 260f, 400f, 250)
        assertEquals(listOf(16, 0), out.map { it.mask }) // button 5 = mask 16
    }

    @Test
    fun `two finger wheel clicks at the cursor without moving it`() {
        val g = gestures()
        g.viewportW = 1000f
        g.viewportH = 1000f
        // 先把光标滑到 (330,200)
        g.down(1, 100f, 100f, 0)
        g.move(1, 130f, 100f, 40)
        g.move(1, 330f, 200f, 80)
        g.up(1, 330f, 200f, 120)
        g.reset()
        val out = g.down(1, 600f, 500f, 200) +
            g.down(2, 660f, 500f, 250) +
            g.move(1, 600f, 400f, 300) +
            g.move(2, 660f, 400f, 300) +
            g.up(1, 600f, 400f, 400) +
            g.up(2, 660f, 400f, 400)
        // 滚轮是按键，不是指针位移：必须发生在光标处，不能把光标拽到两指中间。
        assertEquals(listOf(8, 0), out.map { it.mask })
        assertEquals(330, out[0].x)
        assertEquals(200, out[0].y)
    }

    // ---- 三指 ----

    @Test
    fun `three finger swipe down produces no events`() {
        val g = gestures()
        val out = g.down(1, 100f, 100f, 0) +
            g.down(2, 200f, 100f, 30) +
            g.down(3, 300f, 100f, 60) +
            g.move(1, 100f, 400f, 200) +
            g.move(2, 200f, 400f, 200) +
            g.move(3, 300f, 400f, 200) +
            g.up(1, 100f, 400f, 300) +
            g.up(2, 200f, 400f, 300) +
            g.up(3, 300f, 400f, 300)
        assertEquals(emptyList<Int>(), out.map { it.mask })
    }

    // ---- 软键盘输入框（U88）----

    @Test
    fun `typing characters emits their keysyms in order`() {
        val change = textChangeKeys("", "sl")
        assertEquals("sl", change.text)
        assertEquals(listOf(0x73, 0x6C), change.keysyms)
    }

    @Test
    fun `carriage return from the IME never becomes a keypress or a newline`() {
        // 真机坑：`ImeAction.Send` 之后部分中文输入法仍往输入框塞一个 '\n'。
        // 若这里放行，回车会被发两遍（onSend 一次 + '\n' 一次），`sl` 收到第二个回车直接退出。
        val change = textChangeKeys("sl", "sl\n")
        assertEquals("sl", change.text)
        assertEquals(emptyList<Int>(), change.keysyms)
    }

    @Test
    fun `deleting characters emits one backspace each`() {
        val change = textChangeKeys("abc", "a")
        assertEquals("a", change.text)
        assertEquals(listOf(0xFF08, 0xFF08), change.keysyms)
    }

    @Test
    fun `unknown characters are skipped instead of sending keysym zero`() {
        val change = textChangeKeys("", "中")
        assertEquals("中", change.text)
        assertEquals(emptyList<Int>(), change.keysyms)
    }

    // ---- 中文输入（U89I）----

    @Test
    fun `only latin-1 printable is keysym typable`() {
        // X 的 keysym 本质是「键盘上某个键」：Latin-1 可打印区有对应表示，汉字没有。
        assertTrue(isKeysymTypable('a'))
        assertTrue(isKeysymTypable('~'))
        assertTrue(isKeysymTypable(' '))
        assertFalse(isKeysymTypable('中'))
        assertFalse(isKeysymTypable('，'))
        assertFalse(isKeysymTypable('\n'))
    }

    @Test
    fun `ime committing a shorter string is an append not a delete`() {
        // 这是 U89I 的核心：中文输入法组合时本地是 `zhong`，选词上屏变 `中`。
        // 按长度差会判成「删 4 格」（远端被误退），按最长公共前缀才是「追加 `中`」。
        val plan = planEdit(baseline = "", new = "中")
        assertEquals("上屏不能退格", 0, plan.backspaces)
        assertEquals("中", plan.appended)

        val composing = planEdit(baseline = "", new = "zhong")
        assertEquals(0, composing.backspaces)
        assertEquals("zhong", composing.appended)
    }

    @Test
    fun `planEdit appends only the new tail`() {
        val plan = planEdit(baseline = "abc", new = "abcdef")
        assertEquals(0, plan.backspaces)
        assertEquals("def", plan.appended)
    }

    @Test
    fun `planEdit emits backspaces for a shrinking tail`() {
        val plan = planEdit(baseline = "abc", new = "a")
        assertEquals(2, plan.backspaces)
        assertEquals("", plan.appended)
    }

    @Test
    fun `planEdit rewrites the diverging tail`() {
        val plan = planEdit(baseline = "abcd", new = "abXY")
        assertEquals(2, plan.backspaces)
        assertEquals("XY", plan.appended)
    }

    @Test
    fun `splitting keeps ascii and non-ascii in order`() {
        // 必须保序：用户打 `a中b`，远端就得按 a → 中 → b 收到。
        val runs = splitRuns("a中b")
        assertEquals(3, runs.size)
        assertEquals(TextRun(ascii = true, text = "a"), runs[0])
        assertEquals(TextRun(ascii = false, text = "中"), runs[1])
        assertEquals(TextRun(ascii = true, text = "b"), runs[2])

        assertEquals(listOf(TextRun(ascii = true, text = "abc")), splitRuns("abc"))
        assertEquals(emptyList<TextRun>(), splitRuns(""))
    }

    @Test
    fun `xdotool type command is shell safe and uses a slow enough delay`() {
        val cmd = xdotoolTypeShellCommand("中文 it's \$HOME `x`")
        // 文本以 base64 传：命令里不能再出现原文，否则空格/引号会被 xdotool 的 stdin 解析吃掉。
        assertFalse("不能把原文塞进命令行", cmd.contains("中文"))
        assertFalse(cmd.contains("it's"))
        assertTrue("要走 stdin 而不是参数", cmd.contains("--file -"))
        // 慢到不吞字：delay 20 实测会丢前面的字符（见交接）。
        assertTrue("delay 不能低于 100ms", TYPE_DELAY_MS >= 100)
        assertTrue(cmd.contains("--delay $TYPE_DELAY_MS"))
    }
}
