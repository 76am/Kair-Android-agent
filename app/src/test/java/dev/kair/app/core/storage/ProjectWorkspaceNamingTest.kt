package dev.kair.app.core.storage

import dev.kair.app.core.ai.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工作区目录命名（2026-10-30「整棵父目录一次挂载」的前置改动）。
 *
 * 目录名 = 项目名（清洗后），展示名唯一；改名时目录跟着搬。旧版 UUID 目录在启动时迁移。
 * 这些行为决定容器里 `/projects/<目录名>` 与系统提示的 `<cwd>`，改错会直接让 agent 跑错目录。
 */
class ProjectWorkspaceNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun storage(root: File) = LocalStorage(root)

    @Test
    fun `workspace base is stable even before the first project exists`() {
        val root = tmp.newFolder("root")
        val s = storage(root)

        // 先起常驻终端、后建项目：父目录必须已经存在且可挂载。
        assertEquals(File(root, "projects/workspaces"), s.workspaceBase())
        assertTrue(s.workspaceBase().isDirectory)
    }

    @Test
    fun `create names the workspace directory after the project`() {
        val s = storage(tmp.newFolder("root"))
        val project = s.createProject("程序员")

        val dir = s.workspaceDir(project)
        assertEquals("程序员", dir.name)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `duplicate names are rejected ignoring case`() {
        val s = storage(tmp.newFolder("root"))
        s.createProject("Demo")

        val failure = runCatching { s.createProject(" demo ") }.exceptionOrNull()
        assertTrue("同名项目必须拒绝：$failure", failure is IllegalArgumentException)
    }

    @Test
    fun `unsafe characters are sanitized and collisions get a numeric suffix`() {
        assertEquals("a-b", projectDirName("a/b"))
        assertEquals("a-b-c", projectDirName("a\\b?c"))
        assertEquals("project", projectDirName("..."))
        assertEquals("测试1", projectDirName("测试1"))

        val s = storage(tmp.newFolder("root"))
        val first = s.createProject("a/b")
        val second = s.createProject("a?b")

        assertEquals("a-b", s.workspaceDir(first).name)
        assertEquals("a-b-2", s.workspaceDir(second).name)
        assertFalse(s.workspaceDir(first).absolutePath == s.workspaceDir(second).absolutePath)
    }

    @Test
    fun `rename moves the directory with its files`() {
        val s = storage(tmp.newFolder("root"))
        val project = s.createProject("Old")
        val oldDir = s.workspaceDir(project)
        File(oldDir, "keep.txt").writeText("body")

        s.renameProject(project.id, "New")

        val newDir = s.workspaceDir(project)
        assertEquals("New", newDir.name)
        assertFalse("旧目录必须搬走而不是并存", oldDir.exists())
        assertEquals("body", File(newDir, "keep.txt").readText())
        assertEquals("New", s.listProjects().single().name)
    }

    @Test
    fun `rename rejects a duplicate name but allows the same name`() {
        val s = storage(tmp.newFolder("root"))
        val a = s.createProject("A")
        s.createProject("B")

        assertTrue(
            "与其它项目重名必须拒绝",
            runCatching { s.renameProject(a.id, "b") }.exceptionOrNull() is IllegalArgumentException,
        )
        s.renameProject(a.id, "A") // 同名是幂等，不抛
        assertEquals("A", s.workspaceDir(a).name)
    }

    @Test
    fun `rename to a name with the same sanitized directory keeps the directory`() {
        val s = storage(tmp.newFolder("root"))
        val project = s.createProject("a/b")
        val before = s.workspaceDir(project)
        File(before, "keep.txt").writeText("x")

        s.renameProject(project.id, "a?b")

        assertEquals("展示名要更新", "a?b", s.listProjects().single().name)
        assertEquals("目录名相同就不搬", before.absolutePath, s.workspaceDir(project).absolutePath)
        assertEquals("x", File(before, "keep.txt").readText())
    }

    @Test
    fun `legacy uuid workspace is migrated to the project name on startup`() {
        val root = tmp.newFolder("root")
        val projectsDir = File(root, "projects").apply { mkdirs() }
        val project = Project(name = "Legacy")
        File(projectsDir, "projects.json").writeText(Json.encodeToString(listOf(project)))
        val legacy = File(projectsDir, "workspaces/${project.id}").apply { mkdirs() }
        File(legacy, "old.txt").writeText("data")

        val s = storage(root)
        val dir = s.workspaceDir(project)

        assertEquals("Legacy", dir.name)
        assertEquals("data", File(dir, "old.txt").readText())
        assertFalse("旧 UUID 目录已搬空", legacy.exists())
    }

    @Test
    fun `lost directory map is recovered by claiming the name directory`() {
        val root = tmp.newFolder("root")
        val first = storage(root)
        val project = first.createProject("Work")
        File(first.workspaceDir(project), "keep.txt").writeText("x")
        // 模拟映射文件丢失 / 损坏：必须按名字认领已有目录，而不是建成 Work-2。
        File(root, "projects/project-dirs.json").delete()

        val second = storage(root)
        val dir = second.workspaceDir(second.listProjects().single())

        assertEquals("Work", dir.name)
        assertEquals("x", File(dir, "keep.txt").readText())
    }

    @Test
    fun `map loss after a rename still claims the renamed directory`() {
        val root = tmp.newFolder("root")
        val first = storage(root)
        val project = first.createProject("Before")
        first.renameProject(project.id, "After")
        File(first.workspaceDir(project), "keep.txt").writeText("x")
        File(root, "projects/project-dirs.json").delete()

        val second = storage(root)
        val dir = second.workspaceDir(second.listProjects().single())

        assertEquals("After", dir.name)
        assertEquals("x", File(dir, "keep.txt").readText())
        assertNotNull(second.workspaceBase())
    }

    @Test
    fun `a failed mapping write rolls back to the directory holding the data`() {
        val root = tmp.newFolder("root")
        val s = storage(root)
        val project = s.createProject("Old")
        val oldDir = s.workspaceDir(project)
        File(oldDir, "keep.txt").writeText("body")
        // 让 project-dirs.json 的写入必然失败：用非空目录占住它的位置（改名 / 建目录都搬不动它）。
        File(root, "projects/project-dirs.json").apply {
            delete()
            File(this, "occupied").mkdirs()
        }

        assertTrue(
            "映射写不进去时改名必须报错",
            runCatching { s.renameProject(project.id, "New") }.exceptionOrNull() != null,
        )

        // 目录已搬回、映射与展示名都没变：否则 workspaceDir 指向不存在的目录，agent 在新目录里空跑。
        assertEquals(oldDir.absolutePath, s.workspaceDir(project).absolutePath)
        assertEquals("body", File(s.workspaceDir(project), "keep.txt").readText())
        assertEquals("Old", s.listProjects().single().name)
    }

    @Test
    fun `an orphan mapping left by an interrupted delete is pruned on startup`() {
        val root = tmp.newFolder("root")
        val first = storage(root)
        val doomed = first.createProject("测试2")
        val doomedDir = first.workspaceDir(doomed)
        assertNotNull(first.createProject("Keep"))
        // 模拟 delete 的三步之间被系统强杀：索引已落盘（不含 doomed），映射还是旧的。
        File(root, "projects/projects.json")
            .writeText(Json.encodeToString(first.listProjects().filterNot { it.id == doomed.id }))
        val mapFile = File(root, "projects/project-dirs.json")
        assertTrue("前提：映射里还留着孤儿", mapFile.readText().contains(doomed.id))

        val second = storage(root)

        assertFalse("孤儿映射必须被剔除", mapFile.readText().contains(doomed.id))
        assertFalse("残留的空目录也要收掉", doomedDir.exists())
        assertEquals("名字应该能回收", "测试2", second.workspaceDir(second.createProject("测试2")).name)
    }

    @Test
    fun `an unreferenced workspace that still holds data is never deleted`() {
        val root = tmp.newFolder("root")
        val first = storage(root)
        val project = first.createProject("Data")
        val dir = first.workspaceDir(project)
        File(dir, "keep.txt").writeText("x")
        File(root, "projects/projects.json").writeText(Json.encodeToString(emptyList<Project>()))

        storage(root)

        assertEquals("非空目录是数据、不是垃圾", "x", File(dir, "keep.txt").readText())
    }
}
