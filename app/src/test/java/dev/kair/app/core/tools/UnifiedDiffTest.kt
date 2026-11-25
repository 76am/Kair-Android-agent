package dev.kair.app.core.tools

import dev.kair.app.ui.screens.chat.DiffLineKind
import dev.kair.app.ui.screens.chat.diffLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `edit` 工具的统一 diff（见 [unifiedDiff]）与 UI 取色分类（[diffLineKind]）的行为契约。
 * 这两处都是纯函数，改动时最容易踩的是 hunk 头行号与「无变更不产 hunk」。
 */
class UnifiedDiffTest {

    @Test
    fun `single line replacement carries surrounding context`() {
        val diff = unifiedDiff(
            path = "f.txt",
            original = "a\nb\nc\nd\ne",
            hunks = listOf(EditHunk(startLine = 2, oldText = "c", newText = "C")),
        )

        assertEquals(
            listOf(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -1,5 +1,5 @@",
                " a",
                " b",
                "-c",
                "+C",
                " d",
                " e",
            ).joinToString("\n"),
            diff,
        )
    }

    @Test
    fun `border lines stay in the hunk body as context`() {
        val diff = unifiedDiff(
            path = "f.txt",
            original = "x\ny\nKEEP\nz",
            hunks = listOf(EditHunk(startLine = 0, oldText = "x\ny\nKEEP\nz", newText = "x\ny\nKEEP2\nz")),
        )

        // 公共前后缀行不能被当增删行重复成 -x / +x，但必须以上下文行留在体内，
        // 否则 hunk 体与实际文件对不上。
        assertEquals(
            listOf(
                "--- a/f.txt",
                "+++ b/f.txt",
                "@@ -1,4 +1,4 @@",
                " x",
                " y",
                "-KEEP",
                "+KEEP2",
                " z",
            ).joinToString("\n"),
            diff,
        )
    }

    @Test
    fun `hunk body keeps the edit block's own border lines as context`() {
        // 回归：编辑块自身的公共前 / 后缀行被裁掉后，hunk 体少行而头行数不变，
        // 上下文就错位到编辑块之后——模型看到的新增行会落在文件开头。
        val original = "# T\n\n> summary\n\n---\n\n## 1. X\n\n### Body"
        val diff = unifiedDiff(
            path = "f.md",
            original = original,
            hunks = listOf(
                EditHunk(
                    startLine = 2,
                    oldText = "> summary\n\n---\n\n## 1. X",
                    newText = "> summary\n>\n> note\n\n---\n\n## 1. X",
                ),
            ),
        )

        assertEquals(
            listOf(
                "--- a/f.md",
                "+++ b/f.md",
                "@@ -1,9 +1,11 @@",
                " # T",
                " ",
                " > summary",
                "+>",
                "+> note",
                " ",
                " ---",
                " ",
                " ## 1. X",
                " ",
                " ### Body",
            ).joinToString("\n"),
            diff,
        )
    }

    @Test
    fun `trailing newline in the edit text does not swallow the next line`() {
        // 回归：oldText 尾随换行曾被 split 成幻影空行，hunk 凭空多占一行，
        // 编辑块**之后的第一行**于是从上下文里消失。
        val original = "- A\n- B\n- C\n- D"
        val diff = unifiedDiff(
            path = "f.md",
            original = original,
            hunks = listOf(EditHunk(startLine = 1, oldText = "- B\n", newText = "")),
        )

        assertEquals(
            listOf(
                "--- a/f.md",
                "+++ b/f.md",
                "@@ -1,4 +1,3 @@",
                " - A",
                "-- B",
                " - C",
                " - D",
            ).joinToString("\n"),
            diff,
        )
    }

    @Test
    fun `deleting a whole block reports only removed lines`() {
        val original = "a\nb\nc"
        val diff = unifiedDiff(
            path = "f.txt",
            original = original,
            hunks = listOf(EditHunk(startLine = 1, oldText = "b\n", newText = "")),
        )

        assertTrue(diff.contains("-b"))
        // 空 newText 不能变成一条空白的 `+` 行。
        assertFalse(diff.contains("\n+\n"))
        assertTrue(diff.endsWith(" c"))
    }

    @Test
    fun `no-op hunk produces no hunk header`() {
        val diff = unifiedDiff(
            path = "f.txt",
            original = "same",
            hunks = listOf(EditHunk(startLine = 0, oldText = "same", newText = "same")),
        )

        assertFalse(diff.contains("@@"))
    }

    @Test
    fun `hunk at file start has no leading context`() {
        val diff = unifiedDiff(
            path = "f.txt",
            original = "one\ntwo",
            hunks = listOf(EditHunk(startLine = 0, oldText = "one", newText = "ONE")),
        )

        assertTrue(diff.contains("@@ -1,2 +1,2 @@"))
        assertTrue(diff.contains("-one"))
        assertTrue(diff.contains("+ONE"))
    }

    @Test
    fun `line index counts newlines before the offset`() {
        assertEquals(0, lineIndexOf("abc", 0))
        assertEquals(1, lineIndexOf("a\nb\nc", 2))
        assertEquals(2, lineIndexOf("a\nb\nc", 4))
    }

    @Test
    fun `diff lines are classified for coloring`() {
        assertEquals(DiffLineKind.HEADER, diffLineKind("--- a/f.txt"))
        assertEquals(DiffLineKind.HEADER, diffLineKind("+++ b/f.txt"))
        assertEquals(DiffLineKind.HUNK, diffLineKind("@@ -1,2 +1,2 @@"))
        assertEquals(DiffLineKind.ADDED, diffLineKind("+added"))
        assertEquals(DiffLineKind.REMOVED, diffLineKind("-removed"))
        assertEquals(DiffLineKind.CONTEXT, diffLineKind(" context"))
    }
}
