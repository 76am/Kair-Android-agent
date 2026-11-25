package dev.kair.app.ui.icons

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 内置图标集的机械校验（U97）。
 *
 * 图标是一串手写的 SVG path —— 最容易出的错是**打错一个字符**（`M4 4h16v16H4x`），
 * 或者干脆把一段 emoji 塞回 `icon` 字段。两种错在编译期都看不出来，运行时只表现成
 * 「某个格子里空了一块」。这里把它们变成红灯：
 * 1. 每个常量必须是路径字符集里的东西（[isGlyphPath]）；
 * 2. 每个路径必须真的含**绘制**命令（只有 `M` 的路径画不出任何东西）；
 * 3. 认得字符的判据必须把 emoji 挡在外面。
 */
class PluginGlyphTest {

    private val glyphs: Map<String, String> = PluginGlyph::class.java.declaredFields
        .filter { it.type == String::class.java && Modifier.isStatic(it.modifiers) }
        .associate { it.name to (it.get(null) as String) }

    @Test
    fun `图标集不为空`() {
        assertTrue("反射没取到任何图标常量 —— 扫描落空不等于通过", glyphs.size >= 20)
    }

    @Test
    fun `每个图标都是合法路径字符`() {
        val bad = glyphs.filterValues { !isGlyphPath(it) }
        assertTrue("这些图标不是合法的 SVG path：$bad", bad.isEmpty())
    }

    @Test
    fun `每个图标都含绘制命令`() {
        // 只有 M/m 的路径 = 一个点，描边也画不出来（没有线段可描）。
        val empty = glyphs.filterValues { g ->
            Regex("[LlHhVvCcSsQqTtAa]").findAll(g).count() < 2
        }
        assertTrue("这些图标只有 moveto，画出来是空白：${empty.keys}", empty.isEmpty())
    }

    @Test
    fun `字符不会被当成路径`() {
        // 静态分析挡不住的东西全在这：emoji、汉字、单词、名字。
        assertFalse(isGlyphPath("\uD83D\uDD0C")) // 插头
        assertFalse(isGlyphPath("\u2699\uFE0F")) // 齿轮
        assertFalse(isGlyphPath("记忆"))
        assertFalse(isGlyphPath("icon-name"))
        assertFalse(isGlyphPath(""))
        assertFalse(isGlyphPath("  "))
        // 反例：正常路径要通过（防止判据把真路径也挡了）
        assertTrue(isGlyphPath(PluginGlyph.Memory))
        assertTrue(isGlyphPath("M3 5h18v14H3z"))
    }
}
