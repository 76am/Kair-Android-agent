package dev.kair.app.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SKILL.md 解析与注册表行为。 */
class SkillTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- frontmatter 解析 ------------------------------------------------------

    @Test
    fun `parses frontmatter name and description`() {
        val skill = SkillParser.parse(
            """
            ---
            name: pdf-export
            description: Export the current document to PDF
            ---
            # Instructions
            Do the thing.
            """.trimIndent(),
            fallbackName = "fallback",
        )!!
        assertEquals("pdf-export", skill.name)
        assertEquals("Export the current document to PDF", skill.description)
        assertTrue(skill.body.startsWith("# Instructions"))
        assertTrue(skill.body.endsWith("Do the thing."))
    }

    @Test
    fun `falls back to directory name without frontmatter`() {
        val skill = SkillParser.parse("Just a body.\nSecond line.", fallbackName = "my-dir")!!
        assertEquals("my-dir", skill.name)
        assertEquals("", skill.description)
        assertTrue(skill.body.startsWith("Just a body."))
    }

    @Test
    fun `empty name in frontmatter falls back to directory name`() {
        val skill = SkillParser.parse(
            "---\nname: \"\"\ndescription: d\n---\nbody",
            fallbackName = "dir-name",
        )!!
        assertEquals("dir-name", skill.name)
    }

    @Test
    fun `leading blank lines before frontmatter are tolerated`() {
        val skill = SkillParser.parse("\n\n---\nname: x\ndescription: y\n---\nbody", "fb")!!
        assertEquals("x", skill.name)
        assertEquals("body", skill.body)
    }

    @Test
    fun `quoted frontmatter values are unquoted`() {
        val skill = SkillParser.parse("---\nname: 'quoted'\ndescription: \"dq\"\n---\nbody", "fb")!!
        assertEquals("quoted", skill.name)
        assertEquals("dq", skill.description)
    }

    @Test
    fun `empty body is invalid`() {
        assertNull(SkillParser.parse("---\nname: x\n---\n   \n", "fb"))
        assertNull(SkillParser.parse("", "fb"))
    }

    @Test
    fun `crlf is normalized`() {
        val skill = SkillParser.parse("---\r\nname: x\r\ndescription: y\r\n---\r\nbody line", "fb")!!
        assertEquals("x", skill.name)
        assertEquals("body line", skill.body)
    }

    // ---- 注册表 ---------------------------------------------------------------

    @Test
    fun `registry scans roots and stays sorted and deduped`() {
        val rootA = tmp.newFolder("a")
        val rootB = tmp.newFolder("b")
        writeSkill(rootA, "zeta", "from a")
        writeSkill(rootA, "alpha", "from a")
        writeSkill(rootB, "alpha", "from b") // 同名：先加入者胜
        writeSkill(rootB, "mid", "from b")

        val registry = SkillRegistry()
        registry.addRoot(rootA)
        registry.addRoot(rootB)

        assertEquals(listOf("alpha", "mid", "zeta"), registry.list().map { it.name })
        assertEquals("from a", registry.find("alpha")!!.description)
        // 技能带上真实目录：同名时先加入者胜，目录也必须指向胜出的那一份。
        assertEquals(java.io.File(rootA, "alpha").absolutePath, registry.find("alpha")!!.dir)
        assertEquals(java.io.File(rootB, "mid").absolutePath, registry.find("mid")!!.dir)
    }

    @Test
    fun `builtins are listed and can be shadowed by earlier roots`() {
        val registry = SkillRegistry()
        registry.addBuiltin(Skill("builtin-skill", "d", "b"))
        assertEquals(1, registry.list().size)

        val root = tmp.newFolder("skills")
        writeSkill(root, "builtin-skill", "override")
        registry.addRoot(root)
        // builtins 在 roots 之前加入：builtin 胜出。
        assertEquals("d", registry.find("builtin-skill")!!.description)
    }

    @Test
    fun `find is case-insensitive and missing root is ignored`() {
        val registry = SkillRegistry()
        registry.addRoot(tmp.newFolder("nonexistent"))
        registry.addBuiltin(Skill("Foo", "", "bar"))
        assertEquals("bar", registry.find("foo")!!.body)
        assertNull(registry.find("nope"))
    }

    @Test
    fun `removeRoot drops its skills`() {
        val root = tmp.newFolder("skills")
        writeSkill(root, "gone", "d")
        val registry = SkillRegistry()
        registry.addRoot(root)
        assertEquals(1, registry.list().size)
        registry.removeRoot(root)
        assertEquals(0, registry.list().size)
    }

    @Test
    fun `unreadableSkillFileIsSkipped`() {
        val root = tmp.newFolder("skills")
        writeSkill(root, "good", "ok")
        // 空正文 = 内容非法：解析返回 null，不得让整份技能索引构造失败。
        val broken = java.io.File(root, "broken").apply { mkdirs() }
        java.io.File(broken, "SKILL.md").writeText("---\nname: broken\n---\n   \n")

        val registry = SkillRegistry()
        registry.addRoot(root)

        assertEquals(listOf("good"), registry.list().map { it.name })
        // 跳过不是静默：坏文件的原因按技能目录名留下来，供诊断面板展示。
        assertEquals(setOf("broken"), registry.loadFailures.keys)
        assertTrue(registry.loadFailures.getValue("broken").contains("SKILL.md"))

        // 修好后恢复正常，且旧失败记录消失（整表替换，不留过期条目）。
        writeSkill(root, "broken", "fixed")
        assertEquals(listOf("broken", "good"), registry.list().map { it.name })
        assertTrue(registry.loadFailures.isEmpty())
    }

    private fun writeSkill(root: java.io.File, name: String, description: String) {
        val dir = java.io.File(root, name).apply { mkdirs() }
        java.io.File(dir, "SKILL.md").writeText(
            "---\nname: $name\ndescription: $description\n---\nBody of $name",
        )
    }
}
