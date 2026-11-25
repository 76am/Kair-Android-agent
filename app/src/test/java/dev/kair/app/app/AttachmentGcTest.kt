package dev.kair.app.app

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.agent.ScriptedTransport
import dev.kair.app.core.agent.harness
import dev.kair.app.core.model.AttachmentRef
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.storage.StorageBackend
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Method

/**
 * U31 第二刀的宿主侧探针 P-1…P-4。
 *
 * **这四条是现状描述，不是新行为**：它们必须在搬家前的 `AppContainer` 上就全绿
 * （第一刀的教训是「先搬、探针后补」，结果 §1.5 那条缺口是计划的漏）。
 * 搬家后任一条红 = 搬丢了，而不是「新用例发现了 bug」。
 *
 * - P-1 引用集合是并集：日志里没有、但在场会话内存历史里有的 sha 不得删（缓存前缀）。
 * - P-2 `NEW_BLOB_GRACE_MS` 真的传进了 `sweep`：刚落盘的本体豁免，过期本体照删。
 * - P-3 同一项目连排两次只起一个后台 job（去抖 CAS）。
 * - P-4 `deleteProject` 先取工作区路径、再删存储（「占名孤儿」回归）。
 * - P-5 去抖槽跑完要复位（终审补：删掉 `gcScheduled.remove` 时 P-1…P-4 全绿，只有这条红）。
 *
 * P-3 计数用的是 `scope` 的子 job，不是文件数：两个 job 先后 sweep 同一个项目时，
 * 最终文件数完全一样（第一个删完、第二个空跑），**文件计数区分不出合并与否**。
 * 这条偏差记在计划 §9。
 */
class AttachmentGcTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun container(): AppContainer = AppContainer(
        AppEnvironment(
            storageRoot = File(tmp.root, "kair"),
            nativeLibraryDir = "/lib",
            primaryAbi = "arm64-v8a",
        ),
        SettingsStore(FakeSharedPreferences()),
    )

    /** 把本体时间拨到豁免窗口之外；返回值失败会让 P-2 静默失去意义。 */
    private fun stale(file: File) {
        assertTrue("setLastModified 失败：$file", file.setLastModified(System.currentTimeMillis() - 200_000L))
    }

    private fun scheduleMethod(): Method =
        AppContainer::class.java
            .getDeclaredMethod("scheduleAttachmentGc", Project::class.java)
            .apply { isAccessible = true }

    // ---- P-1 -----------------------------------------------------------------

    @Test
    fun `P-1 a sha referenced only by live session memory survives gc`() {
        val container = container()
        val project = container.storage.createProject("P-1")
        val workspace = container.storage.workspaceDir(project)
        val store = AttachmentStore.forWorkspace(workspace)
        val kept = store.putBytes("memory-only".toByteArray(), "image/png")!!
        val orphan = store.putBytes("orphan".toByteArray(), "image/png")!!
        stale(store.fileFor(kept))
        stale(store.fileFor(orphan))

        // 日志那半边是空的：这个引用只存在于在场会话的内存历史里。
        val harness = harness(ScriptedTransport(emptyList()), sessionId = "p1-session", workspace = workspace)
        try {
            harness.session.loadHistory(
                listOf(
                    ChatMessage(
                        role = Role.USER,
                        parts = listOf(Part.Image(ref = kept)),
                    ),
                ),
            )
            container.sessions.open("p1-session", keep = emptySet()) { harness.session }

            val removed = container.gcAttachments(project)

            assertEquals("只应删掉没有引用的那一张", 1, removed)
            assertTrue(
                "在场会话内存历史仍引用的本体不得删除（漏掉 sessions.all() 那半边就会删掉它）",
                store.fileFor(kept).isFile,
            )
            assertFalse("无引用的本体应被回收", store.fileFor(orphan).exists())
        } finally {
            harness.close()
        }
    }

    // ---- P-2 -----------------------------------------------------------------

    @Test
    fun `P-2 the grace duration is really passed to sweep`() {
        val container = container()
        val project = container.storage.createProject("P-2")
        val workspace = container.storage.workspaceDir(project)
        val store = AttachmentStore.forWorkspace(workspace)
        val fresh = store.putBytes("fresh".toByteArray(), "image/png")!!
        val expired = store.putBytes("expired".toByteArray(), "image/png")!!
        stale(store.fileFor(expired))

        val removed = container.gcAttachments(project)

        assertEquals("只有过期的那一张应被删（传 0L 会把刚落盘的也删掉）", 1, removed)
        assertTrue("刚落盘的本体必须被 NEW_BLOB_GRACE_MS 豁免", store.fileFor(fresh).isFile)
        assertFalse("超过豁免时长的本体应被回收", store.fileFor(expired).exists())
    }

    // ---- P-3 -----------------------------------------------------------------

    @Test
    fun `P-3 two schedules for one project collapse into one job`() {
        val container = container()
        val project = container.storage.createProject("P-3")
        // 先关掉共用宽限窗口：这里只数 job，不碰回收结果。
        val rootJob = container.scope.coroutineContext[Job]!!
        val before = rootJob.children.toSet()
        val schedule = scheduleMethod()
        schedule.invoke(container, project)
        schedule.invoke(container, project)
        val added = rootJob.children.toSet() - before
        try {
            assertEquals(
                "同一项目连排两次只应起一个后台 job（去掉 gcScheduled 的 CAS 会变成两个）",
                1,
                added.size,
            )
        } finally {
            // 两个 job 都会等 GC_DEBOUNCE_MS；用例结束先收掉，别把 2s 的等待留给测试进程。
            added.forEach { it.cancel() }
        }
    }

    // ---- P-4 -----------------------------------------------------------------

    @Test
    fun `P-4 deleteProject takes the workspace path before deleting storage`() {
        val container = container()
        val recording = RecordingStorage(container.storage)
        container.plugins.setStorage("attachment-gc-probe", recording)
        val project = recording.createProject("P-4")

        container.deleteProject(project)

        val firstWorkspaceRead = recording.calls.indexOfFirst { it == "workspaceDir" }
        val deleteAt = recording.calls.indexOfFirst { it == "deleteProject" }
        assertTrue(
            "deleteProject 必须**先取工作区路径再删存储**：删完再调 workspaceDir() 会走 ensureDirName，" +
                "它 mkdirs() 重建刚删的目录并把映射写回 project-dirs.json（占名孤儿）。" +
                "实测调用序：${recording.calls}",
            firstWorkspaceRead >= 0 && deleteAt >= 0 && firstWorkspaceRead < deleteAt,
        )
        assertTrue(
            "项目删除后不应还留在索引里",
            container.storage.listProjects().none { it.id == project.id },
        )
    }

    // ---- P-5（终审补：第四处没牙的地方）--------------------------------------

    /**
     * 去抖槽跑完必须复位。少了 `gcScheduled.remove(project.id)`，那个项目的槽会**永久占住**：
     * 之后每一次历史改写都不再排队，被压缩 / 撤回挤成的无引用本体一直占盘 —— 而这条只有上机才看得出来。
     *
     * 为什么 P-3 抓不到：P-3 只看「连排两次只起一个 job」，不复位同样满足它。
     * 所以这里排**两轮**，中间 `join()` 等第一轮真跑完，再看第二轮能不能起 job。
     * （终审实证：把 `remove` 那行删掉，P-1…P-4 全绿，本条红。）
     */
    @Test
    fun `P-5 the debounce slot is released once the job finished`() = runBlocking {
        val container = container()
        val project = container.storage.createProject("P-5")
        val rootJob = container.scope.coroutineContext[Job]!!
        val before = rootJob.children.toSet()
        val schedule = scheduleMethod()

        schedule.invoke(container, project)
        val first = (rootJob.children.toSet() - before).single()
        first.join() // 含 GC_DEBOUNCE_MS 的延时：等它真的跑完，不等墙钟

        schedule.invoke(container, project)
        val second = rootJob.children.toSet() - before - first
        assertEquals(
            "跑完之后同一项目再排期必须能起第二个 job —— 槽不复位就等于这个项目再也不回收",
            1,
            second.size,
        )
        second.forEach { it.cancel() }
    }

    // ---- P-6（U33）-----------------------------------------------------------

    /**
     * 删项目之后那次 `gcAttachments` 是**有害的**：附件本体就在 `<workspace>/.kair/attachments/v1`，
     * 已随工作区一起被 `deleteRecursively()` 删掉，再回收一次无事可做；但它的入口要经
     * `workspaceDir()` 解析工作区，而映射刚被摘掉 → `ensureDirName` 会 `mkdirs()` 把刚删的
     * 空目录**重建**并把映射写回 `project-dirs.json`（占名孤儿）。
     *
     * 判据取「删完之后的磁盘事实」，不看调用序（P-4 只看先后，删掉那一行照样成立）。
     */
    @Test
    fun `P-6 deleting a project leaves neither its workspace nor a dir mapping`() {
        val container = container()
        val project = container.storage.createProject("P-6")
        val workspace = container.storage.workspaceDir(project)
        assertTrue("前置：工作区目录应已创建", workspace.isDirectory)
        // 项目里有一张图：它随工作区一起删，不需要第二次回收。
        assertTrue(AttachmentStore.forWorkspace(workspace).putBytes("p6".toByteArray(), "image/png") != null)

        container.deleteProject(project)

        assertFalse(
            "删除项目后工作区目录不得残留 —— 残留说明又被 workspaceDir() 的 mkdirs() 重建了（占名孤儿）",
            workspace.exists(),
        )
        val dirsFile = File(File(tmp.root, "kair"), "projects/project-dirs.json")
        assertTrue("前置：目录映射文件应存在（路径变了就要改这条用例）", dirsFile.isFile)
        assertFalse(
            "删除项目后 project-dirs.json 不得仍含该项目 id（否则下次建同名工作区会拿到意外路径）",
            dirsFile.readText().contains(project.id),
        )
    }

    // ---- P-7 / P-8（U50）-----------------------------------------------------

    /** P-7 / P-8 的现场：一个持有 [kept] 的在场子智能体，外加一张无人引用的 [orphan]。 */
    private class GcFixture(
        val container: AppContainer,
        val project: Project,
        val store: AttachmentStore,
        val kept: AttachmentRef,
        val orphan: AttachmentRef,
        val subagentId: String,
    )

    /**
     * 起一个**真实接线**的子智能体并让它持有一张图（P-7 / P-8 共用）。
     *
     * 走 `container.subagents` 而不是直接造 `AttachmentGc`：这一步同时守住 [AppContainer] 里
     * 「把子智能体并入回收引用集合」的那行接线。子智能体在测试环境里没有可用传输层，回合必然
     * 失败，但**会话对象会登记并留在 `SubagentManager`**（面板还要看它的历史）—— 这正是
     * 回收必须覆盖的「在场」形态。
     */
    private fun withImageHoldingSubagent(block: (GcFixture) -> Unit) {
        val container = container()
        val project = container.storage.createProject("P-7")
        val workspace = container.storage.workspaceDir(project)
        val store = AttachmentStore.forWorkspace(workspace)
        val kept = store.putBytes("subagent-image".toByteArray(), "image/png")!!
        val orphan = store.putBytes("orphan".toByteArray(), "image/png")!!
        stale(store.fileFor(kept))
        stale(store.fileFor(orphan))

        // 子智能体的工作区按「发起委派的会话」反查注册表 —— 先得有一个已打开的父亲。
        val harness = harness(ScriptedTransport(emptyList()), sessionId = "p7-parent", workspace = workspace)
        try {
            container.sessions.open("p7-parent", keep = emptySet()) { harness.session }
            runBlocking { container.subagents.spawnAndWait("p7-parent", "look at the picture", "p7", null) }
            val sub = container.subagents.agents.value.last()
            val subSession = container.subagents.sessionOf(sub.id)
                ?: error("子智能体会话应已登记：${container.subagents.agents.value}")
            subSession.loadHistory(
                listOf(ChatMessage(role = Role.USER, parts = listOf(Part.Image(ref = kept)))),
            )
            block(GcFixture(container, project, store, kept, orphan, sub.id))
        } finally {
            harness.close()
        }
    }

    @Test
    fun `P-7 a sha referenced only by a live subagent survives gc`() {
        withImageHoldingSubagent { f ->
            val removed = f.container.gcAttachments(f.project)

            assertEquals("只应删掉没有引用的那一张", 1, removed)
            assertTrue(
                "在场子智能体引用的本体不得删除 —— 子智能体不进 SessionRegistry，漏掉这一路就是静默丢图",
                f.store.fileFor(f.kept).isFile,
            )
            assertFalse(
                "无引用的本体仍应被回收（判据不得放宽成只增不减）",
                f.store.fileFor(f.orphan).exists(),
            )
        }
    }

    @Test
    fun `P-8 a subagent record dropped from the panel stops protecting its images`() {
        withImageHoldingSubagent { f ->
            f.container.subagents.remove(f.subagentId)
            f.container.gcAttachments(f.project)

            assertFalse(
                "记录被移除后其会话对象随之释放，引用消失 → 本体应可回收（否则判据变成永久泄漏）",
                f.store.fileFor(f.kept).exists(),
            )
        }
    }

    // ---- P-9（B 审补：U33 的同类路径）-----------------------------------------

    /**
     * U33 只删掉了 `deleteProject` 里那**一次**回收，但「删除后又去解析工作区」还剩一条路：
     * 历史改写排的去抖回收（[AppContainer.scheduleAttachmentGc]，2 秒窗口）在窗口内删掉项目时，
     * job 醒来照样调 `gcAttachments(project)` → `workspaceDir()` → `ensureDirName` →
     * `mkdirs()` 把刚删的目录重建、映射写回 `project-dirs.json`（与 P-6 同一个症状）。
     *
     * 判据同样取磁盘事实，且**必须让 job 真的跑完**（`join()`），否则只是证明它还没跑。
     */
    @Test
    fun `P-9 a debounced sweep firing after the delete must not rebuild the workspace`() = runBlocking {
        val container = container()
        val project = container.storage.createProject("P-9")
        val workspace = container.storage.workspaceDir(project)
        val rootJob = container.scope.coroutineContext[Job]!!
        val before = rootJob.children.toSet()

        scheduleMethod().invoke(container, project)
        val job = (rootJob.children.toSet() - before).single()
        // 去抖窗口之内删项目：job 还没醒来，醒来时项目已经不在了。
        container.deleteProject(project)
        assertFalse("前置：删除后工作区应已消失（否则本用例测不到东西）", workspace.exists())

        job.join()

        assertFalse(
            "去抖回收不得把刚删的工作区重建回来 —— 它是 U33 的同类路径（同样经 workspaceDir() 解析）",
            workspace.exists(),
        )
        val dirsFile = File(File(tmp.root, "kair"), "projects/project-dirs.json")
        assertFalse(
            "映射也不得被写回（占名孤儿会让下次建同名工作区拿到意外路径）",
            dirsFile.readText().contains(project.id),
        )
    }

    /** 记录调用顺序的存储包装：P-4 只关心 `workspaceDir` 与 `deleteProject` 的先后。 */
    private class RecordingStorage(private val delegate: StorageBackend) : StorageBackend {
        val calls = mutableListOf<String>()

        override fun listProjects(): List<Project> = delegate.listProjects().also { calls += "listProjects" }

        override fun createProject(name: String): Project = delegate.createProject(name).also { calls += "createProject" }

        override fun deleteProject(id: String) {
            calls += "deleteProject"
            delegate.deleteProject(id)
        }

        override fun renameProject(id: String, name: String) {
            calls += "renameProject"
            delegate.renameProject(id, name)
        }

        override fun workspaceDir(project: Project): File = delegate.workspaceDir(project).also { calls += "workspaceDir" }

        override fun workspaceBase(): File? = delegate.workspaceBase().also { calls += "workspaceBase" }

        override fun listSessions(projectId: String?): List<SessionMeta> =
            delegate.listSessions(projectId).also { calls += "listSessions" }

        override fun createSession(projectId: String, name: String, model: String): SessionMeta =
            delegate.createSession(projectId, name, model).also { calls += "createSession" }

        override fun renameSession(id: String, name: String) {
            calls += "renameSession"
            delegate.renameSession(id, name)
        }

        override fun touchSession(id: String) {
            calls += "touchSession"
            delegate.touchSession(id)
        }

        override fun deleteSession(id: String) {
            calls += "deleteSession"
            delegate.deleteSession(id)
        }

        override fun appendMessage(sessionId: String, message: ChatMessage) {
            calls += "appendMessage"
            delegate.appendMessage(sessionId, message)
        }

        override fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
            calls += "replaceMessages"
            delegate.replaceMessages(sessionId, messages)
        }

        override fun loadMessages(sessionId: String): List<ChatMessage> =
            delegate.loadMessages(sessionId).also { calls += "loadMessages" }
    }
}
