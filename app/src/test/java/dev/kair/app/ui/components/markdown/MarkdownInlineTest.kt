package dev.kair.app.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownInlineTest {

    @Test
    fun `粗体斜体删除线代码与链接各带标志位`() {
        val spans = parseInline("**粗** *斜* ~~删~~ `码` [文](https://kair.dev)")

        assertEquals(MdSpan("粗", bold = true), spans[0])
        assertEquals(MdSpan(" "), spans[1])
        assertEquals(MdSpan("斜", italic = true), spans[2])
        assertEquals(MdSpan(" "), spans[3])
        assertEquals(MdSpan("删", strike = true), spans[4])
        assertEquals(MdSpan(" "), spans[5])
        assertEquals(MdSpan("码", code = true), spans[6])
        assertEquals(MdSpan(" "), spans[7])
        assertEquals(MdSpan("文", href = "https://kair.dev"), spans[8])
    }

    @Test
    fun `粗体里可以再叠行内代码`() {
        val spans = parseInline("**粗 `码`**")

        assertEquals(
            listOf(
                MdSpan("粗 ", bold = true),
                MdSpan("码", bold = true, code = true),
            ),
            spans,
        )
    }

    @Test
    fun `下划线包住的词才是斜体，蛇形命名原样保留`() {
        assertEquals(listOf(MdSpan("foo_bar_baz")), parseInline("foo_bar_baz"))
        assertEquals(
            listOf(MdSpan("斜体", italic = true), MdSpan("后续")),
            parseInline("_斜体_后续"),
        )
    }

    @Test
    fun `未配对的标记原样保留`() {
        assertEquals(listOf(MdSpan("**没收尾")), parseInline("**没收尾"))
        assertEquals(listOf(MdSpan("`没收尾")), parseInline("`没收尾"))
    }

    @Test
    fun `反斜杠转义不吃掉字符`() {
        assertEquals(listOf(MdSpan("*不是斜体*")), parseInline("\\*不是斜体\\*"))
    }

    @Test
    fun `乘号不会被当成斜体`() {
        assertEquals(listOf(MdSpan("2 * 3 = 6")), parseInline("2 * 3 = 6"))
    }
}
