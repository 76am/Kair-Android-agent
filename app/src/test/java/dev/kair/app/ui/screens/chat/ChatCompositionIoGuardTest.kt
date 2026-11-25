package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U52 源码守卫：会话的解析与打开**不得在组合期（`remember` 块）同步读盘**。
 *
 * 为什么是源码守卫而不是行为用例：`ChatScreen` 是 `@Composable`，JVM 单测渲染不了；
 * 「长会话首帧卡不卡」只在真机上稳定复现。而这条缺陷的全部内容就是**调用位置** ——
 * 把它挪回 `remember`，行为用例仍全绿，只有真机看得出又卡了。
 *
 * 结构扫描走 [KotlinSourceScanner.stripped]（注释与字符串被抹成空格、偏移不变）：
 * 留一行含 `openSession(` 的注释不该把守卫带绿。
 */
class ChatCompositionIoGuardTest {

    private val chatScreen = "app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt"

    /** 会同步读盘或触发 `loadHistory` 的调用：组合线程上跑就是首帧读整份日志。 */
    private val diskCalls = listOf("listProjects(", "listSessions(", "openSession(")

    @Test
    fun `chat screen must not open a session inside composition`() {
        val body = chatScreenBody()
        val offenders = callBlocks(body, "remember")
            .filter { block -> diskCalls.any { it in block.text } }
        assertTrue(
            "ChatScreen 的 remember 块里不得出现 ${diskCalls.joinToString(" / ")}：" +
                "它们会在组合线程上同步读会话日志（U52）。当前命中 ${offenders.size} 处：\n" +
                offenders.joinToString("\n---\n") { it.text },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `session resolution runs in produceState off the main thread`() {
        val body = chatScreenBody()
        val produce = callBlocks(body, "produceState")
        assertTrue(
            "ChatScreen 里找不到 produceState —— 会话解析 / 打开必须放在 produceState 里（U52），" +
                "守卫扫描落空不等于通过",
            produce.isNotEmpty(),
        )
        val ranges = produce.map { it.start..it.endInclusive }
        val leaks = diskCalls.flatMap { needle ->
            Regex(Regex.escape(needle)).findAll(body)
                .map { it.range.first }
                .filter { at -> ranges.none { at in it } }
                .map { at -> "$needle@${body.take(at).count { c -> c == '\n' } + 1}" }
        }.toList()
        assertTrue(
            "这些调用只允许出现在 produceState 块里（不许挪到组合体其他地方继续同步读）：$leaks",
            leaks.isEmpty(),
        )

        // produceState 的块本身仍在组合线程的协程里跑（默认 Main），所以必须显式下到 IO。
        val block = produce.singleOrNull { candidate -> diskCalls.all { it in candidate.text } }
        assertTrue(
            "会话解析与打开必须集中在同一个 produceState 块里（一次快照，避免 project / meta / session 错位）",
            block != null,
        )
        assertTrue(
            "该 produceState 必须 withContext(Dispatchers.IO)：只换成 produceState 而不下 IO，" +
                "等于把 remember 换了个名字继续在主线程读盘（U52）。当前块：\n${block?.text}",
            block != null && "withContext(Dispatchers.IO)" in block.text,
        )
    }

    /** `ChatScreen` 的函数体（剥注释 / 字符串后，行号与原文件对齐）。 */
    private fun chatScreenBody(): String {
        val source = repoFile(chatScreen).readText(Charsets.UTF_8)
        val fn = KotlinSourceScanner.scan(source).singleOrNull { it.name == "ChatScreen" }
            ?: error("ChatScreen.kt 里找不到 ChatScreen —— 守卫扫描落空，不等于通过")
        return KotlinSourceScanner.stripped(source)
            .lines()
            .subList(fn.startLine - 1, fn.endLine)
            .joinToString("\n")
    }

    private data class Block(val start: Int, val endInclusive: Int, val text: String)

    /**
     * 取源码里所有 `name(...) { ... }` 调用的整块文本（含尾随 lambda）。
     *
     * 不能用简单正则：块里还有嵌套 lambda 与括号。这里跳过可选的参数列表，再对尾随
     * lambda 做括号配对 —— 与 [KotlinSourceScanner] 自身的配平口径一致。
     * 没有尾随 lambda 的调用（如 `remember { }` 之外的普通函数）直接跳过。
     */
    private fun callBlocks(body: String, name: String): List<Block> {
        val result = mutableListOf<Block>()
        val pattern = Regex("""(?<![\w.])${Regex.escape(name)}\b""")
        var searchFrom = 0
        while (searchFrom < body.length) {
            val match = pattern.find(body, searchFrom) ?: break
            var i = skipWhitespace(body, match.range.last + 1)
            // 泛型实参：`produceState<ChatSessionState>(...)`，先配平 `< >`
            if (i < body.length && body[i] == '<') {
                i = matchTypeArguments(body, i) ?: break
                i = skipWhitespace(body, i + 1)
            }
            if (i < body.length && body[i] == '(') {
                i = matchPair(body, i, '(', ')') ?: break
                i = skipWhitespace(body, i + 1)
            }
            if (i < body.length && body[i] == '{') {
                val end = matchPair(body, i, '{', '}') ?: break
                result += Block(match.range.first, end, body.substring(match.range.first, end + 1))
                searchFrom = end + 1
            } else {
                searchFrom = match.range.last + 1
            }
        }
        return result
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** 配平类型实参的 `< >`；`->` 里的 `>` 不关闭，与 `KotlinSourceScanner` 同口径。 */
    private fun matchTypeArguments(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '<' -> depth++
                '>' -> {
                    if (depth > 0 && text.getOrNull(i - 1) != '-') depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 返回与 [open] 配平的闭括号下标；不配平返回 null。 */
    private fun matchPair(text: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
