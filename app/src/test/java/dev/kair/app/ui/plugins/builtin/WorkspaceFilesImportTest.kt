package dev.kair.app.ui.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceFilesImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 上限内完整拷贝，字节不变。 */
    @Test
    fun copyWithLimit_copiesWithinLimit() {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        copyWithLimit(ByteArrayInputStream(payload), out, maxBytes = 1024)
        assertTrue(payload.contentEquals(out.toByteArray()))
    }

    /** 超限立即中止：不超过上限的部分已写入，但整体报 ImportTooLarge。 */
    @Test
    fun copyWithLimit_rejectsOverLimit() {
        val out = ByteArrayOutputStream()
        try {
            copyWithLimit(ByteArrayInputStream(ByteArray(2048)), out, maxBytes = 1024)
            fail("expected ImportTooLarge")
        } catch (_: ImportTooLarge) {
            assertTrue(out.size() <= 1024)
        }
    }

    /** 同名不覆盖：第二个、第三个依次带序号后缀。 */
    @Test
    fun uniqueImportTarget_avoidsOverwrite() {
        val dir = temp.newFolder("files").absolutePath
        val first = uniqueImportTarget(dir, "SKILL.md")
        assertEquals(joinPath(dir, "SKILL.md"), first)
        File(first).writeText("a")

        val second = uniqueImportTarget(dir, "SKILL.md")
        assertEquals(joinPath(dir, "SKILL (1).md"), second)
        File(second).writeText("b")

        assertEquals(joinPath(dir, "SKILL (2).md"), uniqueImportTarget(dir, "SKILL.md"))
    }

    /** 无扩展名时序号直接追加在末尾。 */
    @Test
    fun uniqueImportTarget_handlesMissingExtension() {
        val dir = temp.newFolder("files2").absolutePath
        File(joinPath(dir, "LICENSE")).writeText("a")
        assertEquals(joinPath(dir, "LICENSE (1)"), uniqueImportTarget(dir, "LICENSE"))
    }
}
