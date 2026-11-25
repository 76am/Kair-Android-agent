package dev.kair.app.ui.components.markdown

/**
 * 聊天气泡里 Markdown 的块级节点（2026-11-21 立）。
 *
 * 只解析模型正文真正会出现的结构：标题 / 段落 / 列表 / 引用 / 分隔线 / 围栏代码 / 表格。
 * 不追求 CommonMark 完整度 —— 认不出的写法一律退回段落，宁可难看也不丢字。
 *
 * 为什么不用现成渲染库：表格与代码块要走自己的卡片（复制 / 放大 / 流式骨架），
 * 库的默认块样式反而要逐项关掉；块级模型留在本文件，解析与渲染都能单独测。
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    /**
     * 围栏代码。[closed] 为 false 表示写到文本结尾都没等到收尾围栏 ——
     * 流式期间很常见，照样按代码渲染（比先把反引号露出来再换卡片稳）。
     */
    data class Code(
        val language: String,
        val code: String,
        val closed: Boolean,
    ) : MdBlock

    /**
     * 表格。[source] 是原始 Markdown（复制给用户的就这一份，不重新拼）。
     *
     * [open] 表示表格一直写到文本结尾都没遇到空行：流式期间最后一块表格随时会再多一行，
     * 宿主按「还在写」渲染骨架；流结束时（running=false）即使 open 也按完整表渲染。
     */
    data class Table(
        val header: List<String>,
        val aligns: List<MdAlign>,
        val rows: List<List<String>>,
        val source: String,
        val open: Boolean,
    ) : MdBlock

    data class BulletList(val items: List<String>) : MdBlock

    data class OrderedList(
        val start: Int,
        val items: List<String>,
    ) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock
}

internal enum class MdAlign { Start, Center, End }

/**
 * 行内片段：用**标志位**而不是嵌套树。
 *
 * 聊天里最常见的是 `**加粗 `代码`**` 这种叠加；树模型得为「粗体里的代码」再定义一层，
 * 而渲染端最终也只是把标志翻成 [androidx.compose.ui.text.SpanStyle]。
 */
internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** 链接地址；渲染成可点的 `LinkAnnotation.Url`。 */
    val href: String? = null,
)
