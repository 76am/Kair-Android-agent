package dev.kair.app.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseTest {

    @Test
    fun `标题、段落与分隔线各成一块`() {
        val blocks = parseMarkdown("## 改动清单\n\n改了 3 个文件\n\n---\n")

        assertEquals(
            listOf(
                MdBlock.Heading(2, "改动清单"),
                MdBlock.Paragraph("改了 3 个文件"),
                MdBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun `段落遇到标题就收口，不把下一块吞进段落`() {
        val blocks = parseMarkdown("正文一行\n## 小标题\n继续正文")

        assertEquals(
            listOf(
                MdBlock.Paragraph("正文一行"),
                MdBlock.Heading(2, "小标题"),
                MdBlock.Paragraph("继续正文"),
            ),
            blocks,
        )
    }

    @Test
    fun `无序列表合并成一个块并保留续行`() {
        val blocks = parseMarkdown("- 抽屉：右滑起手\n- 圆环：可见底收窄\n  仍保留命中区\n")

        assertEquals(
            listOf(MdBlock.BulletList(listOf("抽屉：右滑起手", "圆环：可见底收窄\n仍保留命中区"))),
            blocks,
        )
    }

    @Test
    fun `有序列表记住起始号`() {
        val blocks = parseMarkdown("3. 第三项\n4. 第四项\n")

        assertEquals(
            listOf(MdBlock.OrderedList(3, listOf("第三项", "第四项"))),
            blocks,
        )
    }

    @Test
    fun `日期不会被当成有序列表`() {
        val blocks = parseMarkdown("2026.11.21 的改动\n")

        assertEquals(listOf(MdBlock.Paragraph("2026.11.21 的改动")), blocks)
    }

    @Test
    fun `围栏代码保留语言与内容`() {
        val blocks = parseMarkdown("```bash\n./gradlew :app:testDebugUnitTest\n```\n")

        assertEquals(
            listOf(MdBlock.Code("bash", "./gradlew :app:testDebugUnitTest", closed = true)),
            blocks,
        )
    }

    @Test
    fun `流式中的未闭合围栏照样是代码块`() {
        val blocks = parseMarkdown("说明\n\n```kotlin\nval x = 1")

        assertEquals(
            listOf(
                MdBlock.Paragraph("说明"),
                MdBlock.Code("kotlin", "val x = 1", closed = false),
            ),
            blocks,
        )
    }

    @Test
    fun `表格解析表头、对齐与行`() {
        val source = "| 文件 | 改了什么 |\n| --- | :---: |\n| PushDrawer.kt | 位移补乘 65% |\n"
        val table = parseMarkdown(source).single() as MdBlock.Table

        assertEquals(listOf("文件", "改了什么"), table.header)
        assertEquals(listOf(MdAlign.Start, MdAlign.Center), table.aligns)
        assertEquals(listOf(listOf("PushDrawer.kt", "位移补乘 65%")), table.rows)
        assertEquals("| 文件 | 改了什么 |\n| --- | :---: |\n| PushDrawer.kt | 位移补乘 65% |", table.source)
        assertFalse("有收尾空行就算写完", table.open)
    }

    @Test
    fun `写到结尾的表格标记为未闭合`() {
        val table = parseMarkdown("| A | B |\n| --- | --- |\n| 1 | 2 |").single() as MdBlock.Table

        assertTrue(table.open)
    }

    @Test
    fun `缺少分隔行的竖线文本仍是段落`() {
        val blocks = parseMarkdown("| 这不是表格 | 只是正文 |\n")

        assertEquals(listOf(MdBlock.Paragraph("| 这不是表格 | 只是正文 |")), blocks)
    }

    @Test
    fun `引用去掉尖括号前缀`() {
        assertEquals(listOf(MdBlock.Quote("第一行\n第二行")), parseMarkdown("> 第一行\n> 第二行\n"))
    }

    @Test
    fun `星号分隔线不会渲染成列表`() {
        assertEquals(listOf(MdBlock.Rule), parseMarkdown("***\n"))
    }
}
