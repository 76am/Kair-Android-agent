package dev.kair.app.core.storage

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun plugin(id: String, backend: StorageBackend) = object : Plugin {
        override val id = id
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            ctx.storage(backend)
        }
    }

    @Test
    fun `no plugin means no backend`() {
        assertNull(PluginRegistry().storage())
    }

    @Test
    fun `plugin contributes backend and later registration overrides`() {
        val first = LocalStorage(tmp.newFolder("a"))
        val second = LocalStorage(tmp.newFolder("b"))

        val registry = PluginRegistry()
            .install(plugin("p1", first))
            .install(plugin("p2", second))

        assertSame(second, registry.storage())
    }

    @Test
    fun `local storage round-trips messages in order and without loss`() {
        val storage = LocalStorage(tmp.newFolder("root"))
        val project = storage.createProject("demo")
        val session = storage.createSession(project.id, "s1", "model-x")

        val messages = listOf(
            ChatMessage(role = Role.USER, parts = listOf(Part.Text("hello"))),
            ChatMessage(role = Role.ASSISTANT, parts = listOf(Part.Text("hi"), Part.Thinking("hmm"))),
            ChatMessage(role = Role.TOOL_RESULT, parts = listOf(Part.ToolResult("call-1", "read", "file body"))),
        )
        messages.forEach { storage.appendMessage(session.id, it) }

        val loaded = storage.loadMessages(session.id)

        assertEquals(messages.map { it.id }, loaded.map { it.id })
        assertEquals(messages.map { it.role }, loaded.map { it.role })
        assertEquals("file body", loaded[2].toolResults.single().content)
    }

    @Test
    fun `replace rewrites the transcript wholesale`() {
        val storage = LocalStorage(tmp.newFolder("root"))
        val project = storage.createProject("demo")
        val session = storage.createSession(project.id, "s1", "model-x")

        storage.appendMessage(session.id, ChatMessage(role = Role.USER, parts = listOf(Part.Text("a"))))
        storage.appendMessage(session.id, ChatMessage(role = Role.USER, parts = listOf(Part.Text("b"))))
        val checkpoint = ChatMessage(role = Role.USER, parts = listOf(Part.Text("summary")), isCompaction = true)

        storage.replaceMessages(session.id, listOf(checkpoint))

        assertEquals(listOf(checkpoint.id), storage.loadMessages(session.id).map { it.id })
    }

    @Test
    fun `project deletion removes it from the index`() {
        val storage = LocalStorage(tmp.newFolder("root"))
        val project = storage.createProject("gone")

        storage.deleteProject(project.id)

        assertTrue(storage.listProjects().isEmpty())
    }

    @Test
    fun `project deletion cascades to its sessions and transcript files`() {
        val root = tmp.newFolder("root")
        val storage = LocalStorage(root)
        val gone = storage.createProject("gone")
        val kept = storage.createProject("kept")
        val a = storage.createSession(gone.id, "a", "model-x")
        val b = storage.createSession(gone.id, "b", "model-x")
        val c = storage.createSession(kept.id, "c", "model-x")
        storage.appendMessage(a.id, ChatMessage(role = Role.USER, parts = listOf(Part.Text("x"))))

        storage.deleteProject(gone.id)

        // 会话是项目的数据：索引项与落盘日志必须一起消失，不能只在 UI 里隐藏。
        assertTrue(storage.listSessions(gone.id).isEmpty())
        assertFalse(File(root, "sessions/sessions/${a.id}.jsonl").exists())
        assertFalse(File(root, "sessions/sessions/${b.id}.jsonl").exists())
        assertEquals(listOf(c.id), storage.listSessions(null).map { it.id })
        assertTrue(File(root, "sessions/sessions/${c.id}.jsonl").exists())
    }

    @Test
    fun `session deletion removes its transcript file`() {
        val root = tmp.newFolder("root")
        val storage = LocalStorage(root)
        val project = storage.createProject("demo")
        val session = storage.createSession(project.id, "s", "model-x")
        storage.appendMessage(session.id, ChatMessage(role = Role.USER, parts = listOf(Part.Text("x"))))
        val file = File(root, "sessions/sessions/${session.id}.jsonl")
        assertTrue(file.exists())

        storage.deleteSession(session.id)

        assertFalse(file.exists())
        assertTrue(storage.listSessions(null).isEmpty())
        assertTrue(storage.loadMessages(session.id).isEmpty())
    }

    @Test
    fun `unreadable transcript loads as empty instead of crashing`() {
        val root = tmp.newFolder("root")
        val storage = LocalStorage(root)
        val project = storage.createProject("demo")
        val session = storage.createSession(project.id, "s", "model-x")

        // 同名目录占位：open 必然失败，模拟外部工具拷进 App 私有目录后 uid / SELinux
        // 上下文不对的会话文件（真机上表现为 EACCES）。
        val file = File(root, "sessions/sessions/${session.id}.jsonl")
        assertTrue(file.isFile)
        file.delete()
        file.mkdirs()

        // 打不开时打开会话不能崩：读不到就返回空历史。
        assertEquals(emptyList<ChatMessage>(), storage.loadMessages(session.id))
        // 读别的会话成功，不能抹掉本会话的失败原因（附件回收 / 记忆摘要会遍历全项目）。
        val healthy = storage.createSession(project.id, "healthy", "model-x")
        storage.appendMessage(healthy.id, ChatMessage(role = Role.USER, parts = listOf(Part.Text("x"))))
        assertEquals(1, storage.loadMessages(healthy.id).size)
        // 失败原因必须能被界面按会话取走（路径 + 原因），否则空会话看起来像数据被清空。
        val issue = storage.takeLoadError(session.id)
        assertTrue(issue != null && issue.contains("${session.id}.jsonl"))
        // 只提示一次：取走后清空。
        assertNull(storage.takeLoadError(session.id))
    }
}
