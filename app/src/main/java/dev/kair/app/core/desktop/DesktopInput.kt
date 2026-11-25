package dev.kair.app.core.desktop

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 输入映射纯函数层（U86 §4 D）：keysym 表 + 手势归约器。
 * 归约器吃「(id, x, y, tMs) 采样」吐 RFB PointerEvent，不碰 Compose、不碰 Socket，
 * JVM 可测（判据见 `DesktopInputTest`）。
 */

/** 归约器输出：一个 RFB PointerEvent（mask 位：1=左 2=右 8=滚轮上 16=滚轮下）。 */
internal data class PointerOut(val x: Int, val y: Int, val mask: Int)

/**
 * 字符 → X keysym。
 *
 * Latin-1 可打印区（`0x20..0x7E`）的 keysym **就等于码位**，所以整段直给 ——
 * 曾经只放行字母/数字/空格，所有符号一律返回 null 静默丢掉，真机上表现为
 * 「输入框里打不出 `.` `/` `-`」，见 U89H。
 * 中文 / emoji 等非 Latin-1 仍返回 null（绝不发 keysym 0，任务书 §4 D）。
 */
internal fun keysymForChar(c: Char): Int? = when (c.code) {
    in 0x20..0x7E -> c.code
    else -> null
}

/** 键名 → X keysym（软键盘功能键区用）；认不出的键名返回 null。 */
internal fun keysymForName(name: String): Int? = when (name) {
    "Enter" -> 0xFF0D
    "BackSpace" -> 0xFF08
    "Tab" -> 0xFF09
    "Esc" -> 0xFF1B
    "Left" -> 0xFF51
    "Up" -> 0xFF52
    "Right" -> 0xFF53
    "Down" -> 0xFF54
    "Delete" -> 0xFFFF
    "Space" -> 0x20
    "Shift" -> 0xFFE1
    "Control" -> 0xFFE3
    "Alt" -> 0xFFE9
    // F1–F12：X11 里是连续的一段（0xFFBE + n-1），扩展键行靠这一段
    else -> {
        val n = name.removePrefix("F").toIntOrNull()
        if (name.startsWith("F") && n != null && n in 1..12) 0xFFBE + (n - 1) else null
    }
}

/**
 * 软键盘「换行」键的按键序列：Shift 按下 → Enter 按下/抬起 → Shift 抬起。
 *
 * 输入框的回车已经被 `ImeAction.Send` 独占成「发送」，桌面应用里要换行只剩
 * Shift+Enter 这条标准组合。顺序固定成一次按键和弦，避免 Shift 悬空或重复按下。
 */
internal fun newlineKeySequence(): List<Pair<Int, Boolean>> = listOf(
    keysymForName("Shift")!! to true,
    keysymForName("Enter")!! to true,
    keysymForName("Enter")!! to false,
    keysymForName("Shift")!! to false,
)

/** xdotool 的一个目标键：物理键名 + 是否要自己补 Shift 层。 */
private data class XKey(val name: String, val shift: Boolean = false)

/**
 * keysym → xdotool 键名（x11vnc 删除后，输入改由容器内常驻 `xdotool -` 执行）。
 *
 * `keydown` **不会**替我们补 Shift 层（它只按 keycode），所以 US 布局上那些
 * 「Shift + 数字 / 符号键」的字符必须显式标 [XKey.shift] —— 否则 `!` 会打成 `1`、
 * `_` 会打成 `-`。
 */
private fun xKey(keysym: Int): XKey? = when (keysym) {
    0xFF0D -> XKey("Return")
    0xFF08 -> XKey("BackSpace")
    0xFF09 -> XKey("Tab")
    0xFF1B -> XKey("Escape")
    0xFF51 -> XKey("Left")
    0xFF52 -> XKey("Up")
    0xFF53 -> XKey("Right")
    0xFF54 -> XKey("Down")
    0xFFFF -> XKey("Delete")
    0xFFE1 -> XKey("Shift_L")
    0xFFE3 -> XKey("Control_L")
    0xFFE9 -> XKey("Alt_L")
    in 0xFFBE..0xFFC9 -> XKey("F${keysym - 0xFFBE + 1}")
    // 大写字母：小写键 + Shift
    in 0x41..0x5A -> XKey((keysym + 0x20).toChar().toString(), shift = true)
    in 0x30..0x39, in 0x61..0x7A -> XKey(keysym.toChar().toString())
    // 基础符号键：US 布局上不用 Shift 就能打出来的那批
    0x20 -> XKey("space")
    0x27 -> XKey("apostrophe")
    0x2C -> XKey("comma")
    0x2D -> XKey("minus")
    0x2E -> XKey("period")
    0x2F -> XKey("slash")
    0x3B -> XKey("semicolon")
    0x3D -> XKey("equal")
    0x5B -> XKey("bracketleft")
    0x5C -> XKey("backslash")
    0x5D -> XKey("bracketright")
    0x60 -> XKey("grave")
    // 需要 Shift 的符号：同一物理键的上档
    0x21 -> XKey("1", shift = true)
    0x40 -> XKey("2", shift = true)
    0x23 -> XKey("3", shift = true)
    0x24 -> XKey("4", shift = true)
    0x25 -> XKey("5", shift = true)
    0x5E -> XKey("6", shift = true)
    0x26 -> XKey("7", shift = true)
    0x2A -> XKey("8", shift = true)
    0x28 -> XKey("9", shift = true)
    0x29 -> XKey("0", shift = true)
    0x2B -> XKey("equal", shift = true)
    0x3A -> XKey("semicolon", shift = true)
    0x3C -> XKey("comma", shift = true)
    0x3E -> XKey("period", shift = true)
    0x3F -> XKey("slash", shift = true)
    0x5F -> XKey("minus", shift = true)
    0x7B -> XKey("bracketleft", shift = true)
    0x7C -> XKey("backslash", shift = true)
    0x7D -> XKey("bracketright", shift = true)
    0x7E -> XKey("grave", shift = true)
    else -> null
}

/** 一次按键事件 → 一到两条 xdotool 命令；需要 Shift 的字符自己补（xdotool 不做隐式修饰）。 */
internal fun xdotoolKeyCommands(keysym: Int, down: Boolean): List<String> {
    val key = xKey(keysym) ?: return emptyList()
    return if (key.shift) {
        if (down) {
            listOf("keydown Shift_L", "keydown ${key.name}")
        } else {
            listOf("keyup ${key.name}", "keyup Shift_L")
        }
    } else {
        listOf((if (down) "keydown " else "keyup ") + key.name)
    }
}

/**
 * 修饰键和弦的完整按键序列（U89H）：修饰键按下 → 主键按下/抬起 → 修饰键**逆序**抬起。
 *
 * 屏幕上没法真的同时按两个钮，所以修饰键做成本地状态：点 Alt 高亮，再点 F4 时
 * 展开成这串事件，发完由调用方解除。逆序抬起是习惯写法 —— `keyup Shift` 必须在
 * `keyup 主键` 之后，否则主键会按字面值出。
 */
internal fun chordKeySequence(modifierKeysyms: List<Int>, keysym: Int): List<Pair<Int, Boolean>> {
    val mods = modifierKeysyms.distinct()
    return buildList {
        mods.forEach { add(it to true) }
        add(keysym to true)
        add(keysym to false)
        mods.asReversed().forEach { add(it to false) }
    }
}

/**
 * 指针事件 → xdotool 命令序列（[previousMask] 是上一次事件的按键状态）。
 *
 * RFB mask 语义：1=左键、2=右键、8=滚轮上、16=滚轮下。xdotool 的按钮号：
 * 1=左、3=右；滚轮是 `click 4/5`（按下+抬起一次），不是可按住的状态。
 * 只有按键位发生变化才发 mousedown/mouseup —— 拖动时 mask 不变，只该有 mousemove。
 */
internal fun xdotoolPointerCommands(x: Int, y: Int, mask: Int, previousMask: Int): List<String> = buildList {
    add("mousemove $x $y")
    for ((bit, button) in listOf(1 to 1, 2 to 3)) {
        val was = previousMask and bit != 0
        val now = mask and bit != 0
        if (was != now) add(if (now) "mousedown $button" else "mouseup $button")
    }
    // 滚轮只认「刚出现」的那一下；回到 0 不补事件。
    if (mask and 8 != 0 && previousMask and 8 == 0) add("click 4")
    if (mask and 16 != 0 && previousMask and 16 == 0) add("click 5")
}

/**
 * 手势归约器（U89 §C：**无模式** —— 一次触摸是「点」还是「移」，抬手那一刻看手指动没动就够）：
 * - 单指按下、**没滑过 slop 就抬起** → 在**光标当前位置**单击（不看手指在哪），光标一动不动；
 * - 单指**滑过 slop** → 光标按手指位移量相对跟随；这一手永不点击，抬手也不补；
 * - **单指长按（≥[longPressMs] 不动）→ 按住左键，此后的滑动 = 拖拽**，抬手释放；
 * - 双指点击（两指 down 间隔 ≤150ms、各自位移 ≤tapMaxMovePx、总时长 ≤[twoFingerTapMaxMs]）→ 右键 (2)→(0)；
 * - 双指上下拖 → 滚轮：上=button4（mask 8）、下=button5（mask 16），每 [wheelStepPx] 一格；
 * - 三指 → 一律不产生 RFB 事件（交 UI 唤出胶囊）。
 */
internal class DesktopGestures(
    private val slopPx: Float,
    private val tapMaxMovePx: Float,
    private val wheelStepPx: Float,
    private val twoFingerMaxGapMs: Long = 150L,
    private val twoFingerTapMaxMs: Long = 300L,
    /**
     * 长按阈值：按住不动这么久就进入「按住左键」（唯一的「按住」入口）。
     * 宿主用同一个值做计时器（`DesktopScreen.DesktopGestureLayer`）。
     */
    val longPressMs: Long = 600L,
) {
    private class Finger(val startX: Float, val startY: Float, val tDown: Long, var x: Float, var y: Float)

    /**
     * 视口矩形（屏幕坐标）：把光标钳在画面内、未就绪时算初始中心；`0` = 未同步。
     * 每段手势开头由宿主赋一次值（缩放模式切换会改它）。x/y 是画面在层里的偏移（Fit 的黑边）。
     */
    var viewportX: Float = 0f
    var viewportY: Float = 0f
    var viewportW: Float = 0f
    var viewportH: Float = 0f

    /** 光标位置（屏幕坐标）。**跨手势保持** —— 下一手接着上次的位置继续走，不清零。 */
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorReady = false

    /**
     * 供 UI 画本地光标用（2026-11-14）：null = 还没定位过（面板刚进、从没滑/点过）。
     *
     * 为什么 App 要自己画：Xvfb 现在用 `-nocursor`，指针不再进帧缓冲；
     * 而“触摸 → xdotool → X 重绘 sprite”那段回路实测会让光标一顿一顿（齿轮不顿）。
     * 本地画 = 位置跟手指走，零回路延迟。
     */
    val cursorPosition: Pair<Float, Float>?
        get() = if (cursorReady) cursorX to cursorY else null

    /** 把光标放到（屏幕坐标）。UI 在视口就绪时调一次，让面板一进入就有可见指针。 */
    fun placeCursor(x: Float, y: Float) = initCursor(x, y)

    private enum class Mode { None, Pending, Sliding, Holding, Two, Three }

    private val fingers = mutableMapOf<Long, Finger>()
    private var mode = Mode.None
    private var firstDownT = 0L
    private var secondGapOk = false
    private var lastCentroidY = 0f
    private var wheelAccum = 0f
    private var wheelFired = false
    private var movedTooMuch = false

    fun reset() {
        fingers.clear()
        mode = Mode.None
        wheelAccum = 0f
        wheelFired = false
        secondGapOk = false
        movedTooMuch = false
    }

    fun down(id: Long, x: Float, y: Float, tMs: Long): List<PointerOut> {
        fingers[id] = Finger(x, y, tMs, x, y)
        return when (fingers.size) {
            1 -> {
                mode = Mode.Pending
                firstDownT = tMs
                wheelFired = false
                secondGapOk = false
                emptyList()
            }
            2 -> {
                secondGapOk = tMs - firstDownT <= twoFingerMaxGapMs
                mode = Mode.Two
                lastCentroidY = centroid().second
                wheelAccum = 0f
                movedTooMuch = false
                emptyList()
            }
            else -> {
                mode = Mode.Three
                emptyList()
            }
        }
    }

    /**
     * 长按到点（由宿主的计时器调用，归约器自己没有时钟）。
     *
     * 只在单指 + 仍在 `Pending`（没滑过）时成立：发一条 `mask=1` 把左键按在
     * **光标处**，此后 [move] 就是拖拽。宿主靠震动告诉用户「按住了」。
     */
    fun hold(): List<PointerOut> {
        if (mode != Mode.Pending) return emptyList()
        val f = fingers.values.firstOrNull() ?: return emptyList()
        if (!cursorReady) initCursor(f.startX, f.startY)
        mode = Mode.Holding
        return listOf(PointerOut(cursorX.roundToInt(), cursorY.roundToInt(), 1))
    }

    fun move(id: Long, x: Float, y: Float, tMs: Long): List<PointerOut> {
        val f = fingers[id] ?: return emptyList()
        val dx = x - f.x
        val dy = y - f.y
        f.x = x
        f.y = y
        return when (mode) {
            Mode.Pending ->
                if (hypot((x - f.startX).toDouble(), (y - f.startY).toDouble()) > slopPx) {
                    // 越过 slop 就结算为「移光标」：起点取按下处（首次 = 视口中心），不可逆。
                    mode = Mode.Sliding
                    if (!cursorReady) initCursor(f.startX, f.startY)
                    stepCursor(dx, dy)
                } else {
                    emptyList()
                }
            Mode.Sliding -> stepCursor(dx, dy)
            // 长按之后：光标照样跟手，但左键是按住的 —— 这就是拖窗口 / 拖选文字。
            Mode.Holding -> stepCursor(dx, dy, mask = 1)
            Mode.Two -> {
                if (hypot((x - f.startX).toDouble(), (y - f.startY).toDouble()) > tapMaxMovePx) movedTooMuch = true
                wheelStep()
            }
            else -> emptyList()
        }
    }

    fun up(id: Long, x: Float, y: Float, tMs: Long): List<PointerOut> {
        val f = fingers.remove(id) ?: return emptyList()
        f.x = x
        f.y = y
        val out = when {
            // 没滑过 slop 就抬手 = 点击，落在光标处。快速一甩没有中间采样时按「搬光标」算，不点击。
            mode == Mode.Pending && fingers.isEmpty() ->
                if (hypot((x - f.startX).toDouble(), (y - f.startY).toDouble()) <= slopPx) {
                    clickAtCursor()
                } else {
                    emptyList()
                }
            mode == Mode.Sliding && fingers.isEmpty() ->
                listOf(PointerOut(cursorX.roundToInt(), cursorY.roundToInt(), 0))
            mode == Mode.Holding && fingers.isEmpty() ->
                // 抬手 = 松开左键，拖拽到此为止。
                listOf(PointerOut(cursorX.roundToInt(), cursorY.roundToInt(), 0))
            mode == Mode.Two && fingers.isEmpty() -> {
                val isRightTap = secondGapOk &&
                    !wheelFired &&
                    !movedTooMuch &&
                    allFingersStill() &&
                    tMs - firstDownT <= twoFingerTapMaxMs
                // 右键是鼠标按键，不是指针位移：落在**光标处**，不能把光标拽到手指中间。
                if (isRightTap) pressAtCursor(2) else emptyList()
            }
            else -> emptyList()
        }
        if (fingers.isEmpty()) {
            mode = Mode.None
            wheelAccum = 0f
            wheelFired = false
        }
        return out
    }

    /**
     * 鼠标左键单击：在**光标当前位置**按下 + 抬起。手指点在哪、滑了多远都不参与 ——
     * 这就是「轻点不移动光标」的全部实现。
     */
    private fun clickAtCursor(): List<PointerOut> = pressAtCursor(1)

    /**
     * 在**光标处**按一下再松开（[mask]：1=左 2=右 8/16=滚轮）。三个鼠标按键共用这一个落点 ——
     * 光标还没有位置时（刚进面板、从没滑过）落在**画面中心**，绝不退化成手指处：
     * 退回去等于又变成触摸屏的「戳哪点哪」，真机上就是「双击 / 右键把光标拽走」。
     */
    private fun pressAtCursor(mask: Int): List<PointerOut> {
        if (!cursorReady) initCursor(viewportX + viewportW / 2f, viewportY + viewportH / 2f)
        val cx = cursorX.roundToInt()
        val cy = cursorY.roundToInt()
        return listOf(PointerOut(cx, cy, mask), PointerOut(cx, cy, 0))
    }

    private fun initCursor(x: Float, y: Float) {
        cursorX = clampCursor(x, viewportX, viewportW)
        cursorY = clampCursor(y, viewportY, viewportH)
        cursorReady = true
    }

    /** 相对移动一格：手指走多少光标走多少（屏幕坐标），并钳在**画面矩形**内。[mask] 1 = 按着左键拖。 */
    private fun stepCursor(dx: Float, dy: Float, mask: Int = 0): List<PointerOut> {
        cursorX = clampCursor(cursorX + dx, viewportX, viewportW)
        cursorY = clampCursor(cursorY + dy, viewportY, viewportH)
        return listOf(PointerOut(cursorX.roundToInt(), cursorY.roundToInt(), mask))
    }

    /**
     * 钳在画面矩形内。右/下边界是开区间（`toFramebuffer` 拒绝 `>= dst.x + dst.w`），
     * 落在边界上的事件会被丢掉，所以只钳到**最后一个可映射的点**（`origin + size - 1`）。
     */
    private fun clampCursor(v: Float, origin: Float, size: Float): Float =
        if (size >= 1f) v.coerceIn(origin, origin + size - 1f) else v

    /** 双指质心每移动一格吐一次滚轮点击（**在光标处**，滚轮同样不许挪指针）。 */
    private fun wheelStep(): List<PointerOut> {
        val (_, cy) = centroid()
        wheelAccum += cy - lastCentroidY
        lastCentroidY = cy
        if (abs(wheelAccum) < wheelStepPx) return emptyList()
        val up = wheelAccum < 0
        wheelAccum = 0f
        wheelFired = true
        return pressAtCursor(if (up) 8 else 16)
    }

    private fun centroid(): Pair<Float, Float> {
        val list = fingers.values.toList()
        if (list.isEmpty()) return 0f to 0f
        return list.map { it.x }.average().toFloat() to list.map { it.y }.average().toFloat()
    }

    private fun allFingersStill(): Boolean = fingers.values.all {
        hypot((it.x - it.startX).toDouble(), (it.y - it.startY).toDouble()) <= tapMaxMovePx
    }
}

/** [textChangeKeys] 的结果：**过滤后**的文本（要回填输入框）与要发出去的 keysym 序列。 */
internal data class TextChange(val text: String, val keysyms: List<Int>)

/**
 * 软键盘输入框的文本变化 → 要发给远端的按键。
 *
 * 为什么要有这个纯函数（U88 真机两处坑，都出在「回车」上）：
 * 1. 回车走 `ImeAction.Send` 之后**仍然**可能进 `onValueChange` —— 部分中文输入法会往里插一个
 *    `'\n'`。这里统一滤掉：否则同一次回车会被发两遍 Enter（`sl` 收到第二次回车直接退出，
 *    看起来像「APP 把程序关了」）。`'\n'` 一律**不**产生按键，回车只认 `onSend`。
 * 2. 输入框要清空，但清空是宿主直接赋值、不走这里，所以不会把「清空」误发成一串 BackSpace。
 *
 * 认不出的字符（中文、符号）静默跳过，绝不发 keysym 0（`keysymForChar` 的口径）。
 */
internal fun textChangeKeys(old: String, new: String): TextChange {
    val text = new.replace("\n", "")
    val keysyms = when {
        text.length > old.length -> text.substring(old.length).mapNotNull { keysymForChar(it) }
        text.length < old.length -> List(old.length - text.length) { keysymForName("BackSpace")!! }
        else -> emptyList()
    }
    return TextChange(text, keysyms)
}
