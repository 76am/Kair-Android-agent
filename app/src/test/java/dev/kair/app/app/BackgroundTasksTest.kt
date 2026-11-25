package dev.kair.app.app

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.proot.Mount
import dev.kair.app.core.tools.ToolContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台任务的纯逻辑与状态机：路径解析、脚本生成、启动解析、退出码 / 日志尾部、
 * 完成通知文本，以及「启动 → 完成 / 超时 / 取消」的投递行为。
 *
 * 不依赖 Android：管理器只吃注入的 exec / mounts / sessionExists / deliver。
 */
class BackgroundTasksTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: File = Files.createTempDirectory("kair-bg").toFile()
    private val workspace = File(root, "ws").apply { mkdirs() }
    private val mounts = listOf(Mount("/projects/ws", workspace))
    private val launches = CopyOnWriteArrayList<DetachSpec>()
    private val stops = CopyOnWriteArrayList<String>()
    private val notes = CopyOnWriteArrayList<String>()
    private val delivered = CompletableDeferred<String>()

    private fun process(id: String) = DetachedProcess(
        pid = 42L,
        graceful = { stops += "$id:TERM" },
        force = { stops += "$id:KILL" },
        // 这些用例的注入进程都活着：完成检测仍靠 .rc，路径与 U49 之前一致。
        alive = { true },
    )

    private fun manager(
        launch: suspend (DetachSpec) -> DetachResult = { spec ->
            launches += spec
            DetachResult.Started(process(spec.id))
        },
        mounted: List<Mount>? = mounts,
        exists: (String) -> Boolean = { it == "s1" },
        now: () -> Long = { System.currentTimeMillis() },
        maxRunning: Int = 2,
        id: String = "t1",
        deliverNote: suspend (String, String, String) -> Unit = { _, _, note ->
            notes += note
            delivered.complete(note)
        },
    ) = BackgroundTaskManager(
        scope = scope,
        launch = launch,
        mounts = { mounted },
        sessionExists = exists,
        deliver = deliverNote,
        now = now,
        pollMillis = 10,
        maxRunning = maxRunning,
        idFactory = { id },
    )

    private fun task(
        state: BackgroundTaskState = BackgroundTaskState.SUCCEEDED,
        exit: Int? = 0,
        timeout: Long = 1_800,
    ) = BackgroundTask(
        id = "t1",
        sessionId = "s1",
        command = "echo hi",
        cwd = "",
        state = state,
        startedAt = 1_000,
        finishedAt = 6_000,
        exitCode = exit,
        pid = 42L,
        timeoutSeconds = timeout,
        dir = File(workspace, TASKS_DIR),
    )

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ 纯逻辑

    @Test
    fun `containerPathOf maps the workspace to its mount`() {
        assertEquals("/projects/ws", containerPathOf(workspace, mounts))
        assertNull(containerPathOf(File(root, "other"), mounts))
    }

    @Test
    fun `containerPathOf maps a project under the shared projects root`() {
        val base = File(root, "workspaces").apply { mkdirs() }
        val project = File(base, "demo").apply { mkdirs() }
        val parent = listOf(Mount("/projects", base))

        // 父目录挂载：项目路径 = 挂载点 + 相对路径；父目录本身就是 /projects。
        assertEquals("/projects/demo", containerPathOf(project, parent))
        assertEquals("/projects", containerPathOf(base, parent))
        // 前缀相近的兄弟目录不能被误判成在挂载里（按路径段判断，不按字符串前缀）。
        val sibling = File(root, "workspaces-other").apply { mkdirs() }
        assertNull(containerPathOf(sibling, parent))
    }

    @Test
    fun `normalizeRelativeCwd accepts inside paths and rejects escapes`() {
        assertEquals("", normalizeRelativeCwd(null))
        assertEquals("", normalizeRelativeCwd(" . "))
        assertEquals("src/main", normalizeRelativeCwd("src//main"))
        assertNull(normalizeRelativeCwd("/etc"))
        assertNull(normalizeRelativeCwd("~"))
        assertNull(normalizeRelativeCwd("../x"))
        assertNull(normalizeRelativeCwd("a/../b"))
        assertNull(normalizeRelativeCwd("a\\b"))
    }

    @Test
    fun `taskScript records the exit code and pins the working directory`() {
        val script = taskScript("t1", "/projects/ws/src", "echo hi", "/projects/ws/.kair/tasks/t1.rc")
        assertTrue(script.contains("cd '/projects/ws/src' || exit 126"))
        assertTrue(script.contains("trap rc EXIT"))
        assertTrue(script.contains("echo \$? > '/projects/ws/.kair/tasks/t1.rc'"))
        assertTrue(script.contains("echo hi"))
        // 钉**值**与**口径**：1.5 GiB = 3_145_728 个 512B 块，且只降 soft（`-Sf`）。
        // `ulimit -f` 不带 `-S` 会连 hard 一起压下去，而抬 hard 要 `CAP_SYS_RESOURCE` ——
        // proot 档（app uid fork 的模拟 root）没有它，hard 一降就是永久墙：真机在
        // 134217728 字节处收 SIGXFSZ，`ulimit -f unlimited` 报 EPERM。
        assertTrue(script.contains("ulimit -Sf 3145728"))
        assertFalse(
            "不能压低 hard：留原值任务才能在需要时自己 ulimit -f unlimited",
            script.contains("ulimit -f "),
        )
        // 给模型看的口径由同一个常量换算，别在工具描述里手抄第二个数字。
        assertEquals("1.5 GiB", MAX_FILE_SOFT_LABEL)
    }

    @Test
    fun `runnerScript redirects task output to the log inside the container`() {
        // 宿主进程的 stdout 是没人读的管道：任务输出必须在容器内重定向到 .log，
        // 否则写满 64KB 缓冲会把任务自己阻塞住。
        val script = runnerScript("/projects/ws/.kair/tasks/t1.sh", "/projects/ws/.kair/tasks/t1.log")
        assertTrue(script.contains("exec /bin/sh '/projects/ws/.kair/tasks/t1.sh'"))
        assertTrue(script.contains("> '/projects/ws/.kair/tasks/t1.log' 2>&1"))
        assertTrue(
            "runner 自己不能后台化：前台跑才是「进程活着 = 任务活着」",
            script.lineSequence().none { it.trim().endsWith("&") },
        )
    }

    @Test
    fun `statusText shows no timeout for an unbounded task`() {
        // 面向模型的文本不能出现「timeout 0s」—— 会被读成 0 秒超时。
        val text = statusText(task(state = BackgroundTaskState.RUNNING, exit = null, timeout = 0), "")
        assertTrue(text.contains("no timeout"))
        assertFalse("不得出现误导的 0s：$text", text.contains("timeout 0s"))
        val bounded = statusText(task(state = BackgroundTaskState.RUNNING, exit = null, timeout = 60), "")
        assertTrue(bounded.contains("timeout 60s"))
    }

    @Test
    fun `tailText keeps the last lines`() {
        val log = File(root, "log.txt").apply { writeText((1..10).joinToString("\n") { "line$it" }) }
        val tail = tailText(log, 1_000, 3)
        assertEquals("line8\nline9\nline10", tail)
    }

    @Test
    fun `tailText drops the partial first line of the byte window`() {
        val log = File(root, "log2.txt").apply { writeText("aaaa\nbbbb\ncccc\ndddd") }
        val tail = tailText(log, 10, 10)
        assertFalse("不能以半行开头：$tail", tail.startsWith("aaa"))
        assertTrue(tail.endsWith("dddd"))
    }

    @Test
    fun `taskNote reports the state, log path and tail`() {
        val note = taskNote(task(), "hello\nworld")
        assertTrue(note.contains("[background task t1: finished with exit code 0 after 5s]"))
        assertTrue(note.contains("Command: echo hi"))
        assertTrue(note.contains("Log: .kair/tasks/t1.log"))
        assertTrue(note.contains("hello\nworld"))
        assertTrue(note.contains("automated completion notice"))
    }

    @Test
    fun `hasNote sees the note in history and in the queue`() {
        val note = ChatMessage(role = Role.USER, parts = listOf(Part.Text("[background task t1: done]")), isSystemNote = true)
        val user = ChatMessage(role = Role.USER, parts = listOf(Part.Text("hello")))
        assertTrue(hasNote(listOf(note), emptyList(), "t1"))
        assertTrue(hasNote(emptyList(), listOf(note), "t1"))
        assertFalse(hasNote(listOf(user), emptyList(), "t1"))
        assertFalse(hasNote(listOf(note), emptyList(), "other"))
    }

    @Test
    fun `hasNote does not count another task's note that merely mentions the id`() {
        // 通知正文带日志尾部，而 `ls .kair/tasks` 这类命令的输出里天然会出现别的任务 id。
        // 按子串判会把这条当「t2 已送达」→ t2 的通知被静默丢掉，且 markDelivered 已打、
        // 没有任何重试（B 审 P2）。判据必须是通知头部 `[background task <id>:`。
        val mentionsOther = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Text("[background task t1: done]\n--- last output ---\n.kair/tasks/t2.log\n.kair/tasks/t2.rc")),
            isSystemNote = true,
        )
        assertFalse(hasNote(listOf(mentionsOther), emptyList(), "t2"))
        assertFalse(hasNote(emptyList(), listOf(mentionsOther), "t2"))
        assertTrue(hasNote(listOf(mentionsOther), emptyList(), "t1"))
    }

    // ------------------------------------------------------------------ 启动与失败

    @Test
    fun `start rejects empty commands, subagents and a missing session`() = runBlocking {
        val mgr = manager(exists = { false })
        assertTrue((mgr.start("s1", workspace, " ") as StartResult.Failure).message == TASK_ERR_EMPTY)
        assertTrue((mgr.start("s1", workspace, "echo hi") as StartResult.Failure).message == TASK_ERR_NOT_SESSION)
    }

    @Test
    fun `start rejects an unmounted workspace and a bad cwd`() = runBlocking {
        val noSession = manager(mounted = null)
        assertEquals(
            TASK_ERR_NO_SESSION,
            (noSession.start("s1", workspace, "echo hi") as StartResult.Failure).message,
        )
        val mgr = manager()
        assertEquals(
            TASK_ERR_NOT_MOUNTED,
            (mgr.start("s1", File(root, "other"), "echo hi") as StartResult.Failure).message,
        )
        assertEquals(
            TASK_ERR_BAD_CWD,
            (mgr.start("s1", workspace, "echo hi", cwd = "../x") as StartResult.Failure).message,
        )
    }

    @Test
    fun `tool start passes timeout 0 to the manager as unbounded`() = runBlocking {
        // proot 档唯一的常驻出口：常驻服务必须能不限时跑。工具层若把 0 钳进 30..3600，
        // 服务器最多活 30 秒 —— 这条钉住「0 原样透传给管理器」。
        val mgr = manager()
        val tool = BackgroundTaskTool(mgr)
        val args = Json.parseToJsonElement(
            """{"action":"start","command":"python3 -m http.server 8080","timeout":0}""",
        ) as JsonObject
        val result = tool.execute(args, ToolContext(workspace, "s1"))
        assertFalse("timeout=0 不得报错：${result.content}", result.isError)
        assertEquals(
            "0 必须原样透传（不限时），不能被钳成 30",
            0L,
            mgr.find("s1", "t1")!!.timeoutSeconds,
        )
        assertTrue("启动回执要说 no timeout，不能让模型以为 0 秒超时：${result.content}", result.content.contains("no timeout"))
        mgr.cancelAll("s1")
    }

    @Test
    fun `start returns immediately with a running task`() = runBlocking {
        val mgr = manager()
        val started = mgr.start("s1", workspace, "echo hi") as StartResult.Started
        assertEquals(BackgroundTaskState.RUNNING, started.task.state)
        assertTrue(mgr.hasRunning("s1"))
        assertTrue(File(workspace, "$TASKS_DIR/t1.sh").isFile)
        assertTrue("runner 脚本也要先写好", File(workspace, "$TASKS_DIR/t1.run.sh").isFile)
        val spec = launches.single()
        assertEquals("/projects/ws/.kair/tasks/t1.sh", spec.containerScript)
        assertEquals("/projects/ws/.kair/tasks/t1.run.sh", spec.containerRunner)
        assertTrue("启动不等待：任务刚起就应该返回", started.task.pid == 42L)
        mgr.cancelAll("s1")
    }

    @Test
    fun `start returns within one second without waiting for any session shell`() = runBlocking {
        // V1：会话卡死时后台任务仍能启动。新结构里 manager 根本不接会话 exec，
        // 只接宿主侧 launch；这条钉住「1s 内返回」，防止有人把 launch 改回阻塞调用。
        val mgr = manager()
        val result = withTimeout(1_000) { mgr.start("s1", workspace, "echo hi") }
        assertTrue(result is StartResult.Started)
        mgr.cancelAll("s1")
    }

    @Test
    fun `a second start over the per-session limit fails`() = runBlocking {
        val mgr = manager(maxRunning = 1, id = "t1")
        mgr.start("s1", workspace, "echo hi")
        val second = mgr.start("s1", workspace, "echo hi")
        assertTrue(second is StartResult.Failure)
        assertTrue((second as StartResult.Failure).message.contains("limit 1"))
        mgr.cancelAll("s1")
    }

    // ------------------------------------------------------------------ 完成 / 超时 / 取消

    @Test
    fun `an exit code file finishes the task and delivers a note`() = runBlocking {
        val mgr = manager()
        mgr.start("s1", workspace, "echo hi")
        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")
        val note = withTimeout(5_000) { delivered.await() }
        assertTrue(note.contains("exit code 0"))
        assertEquals(BackgroundTaskState.SUCCEEDED, mgr.list("s1").single().state)
        // 通知在 deliver 内完成，delivering 标记要等投递返回才摘；满载时 await 可能先醒，
        // 同一帧断言 hasRunning=false 会撞上 watcher 的收尾窗口。等标记摘除，别用固定时长。
        withTimeout(5_000) {
            while (mgr.hasRunning("s1")) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun `a non-zero exit code marks the task failed`() = runBlocking {
        val mgr = manager()
        mgr.start("s1", workspace, "false")
        File(workspace, "$TASKS_DIR/t1.rc").writeText("2")
        val note = withTimeout(5_000) { delivered.await() }
        assertTrue(note.contains("exit code 2"))
        assertEquals(BackgroundTaskState.FAILED, mgr.list("s1").single().state)
    }

    @Test
    fun `timeout kills the process group and delivers`() = runBlocking {
        val clock = AtomicLong(0)
        val mgr = manager(now = { clock.get() })
        mgr.start("s1", workspace, "sleep 100", timeoutSeconds = 1)
        clock.set(10_000)
        val note = withTimeout(5_000) { delivered.await() }
        assertTrue(note.contains("timed out"))
        assertEquals(BackgroundTaskState.TIMED_OUT, mgr.list("s1").single().state)
        assertTrue("超时要先礼后兵：TERM 必须发出去", stops.contains("t1:TERM"))
    }

    @Test
    fun `an unbounded task survives the clock and is never timed out`() = runBlocking {
        // 判据的反面：watcher 的 `elapsed >= timeoutSeconds*1000` 对 0 恒真 —— 不加
        // 「>0」守卫，不限时任务会在**第一次轮询**就被杀（比钳成 30 更糟）。
        val clock = AtomicLong(0)
        val mgr = manager(now = { clock.get() })
        mgr.start("s1", workspace, "python3 -m http.server 8080", timeoutSeconds = 0)
        clock.set(10L * 3_600_000) // 假时钟拨到 10 小时后（远超旧的 3600 上限）
        delay(100) // pollMillis=10：够 watcher 转若干轮
        assertEquals("不限时任务不得被超时收尾", BackgroundTaskState.RUNNING, mgr.list("s1").single().state)
        assertTrue("不得有完成通知", notes.isEmpty())
        assertTrue("不得发出停止信号", stops.isEmpty())
        mgr.cancelAll("s1")
    }

    @Test
    fun `cancel marks the task cancelled and delivers`() = runBlocking {
        val mgr = manager()
        mgr.start("s1", workspace, "sleep 100")
        assertTrue(mgr.cancel("s1", "t1"))
        assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
        assertTrue(notes.single().contains("cancelled by the user"))
        assertTrue(stops.contains("t1:TERM"))
    }

    @Test
    fun `cancelling delivers the cancelled notice without waiting for the turn to end`() = runBlocking {
        val mgr = manager()
        mgr.start("s1", workspace, "sleep 100")

        // U18 起投递不等空闲：cancel 返回时通知已经交给投递层（忙时入 STEER 队列）。
        assertTrue(mgr.cancel("s1", "t1"))
        assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
        assertTrue("停止信号必须在返回前发出去", stops.contains("t1:TERM"))
        assertEquals(1, notes.count { it.contains("cancelled by the user") })
        assertFalse("通知交出去后不再占忙", mgr.hasRunning("s1"))
    }

    @Test
    fun `a task waiting to deliver keeps the session busy`() = runBlocking {
        val entering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mgr = manager(
            deliverNote = { _, _, note ->
                entering.complete(Unit)
                release.await()
                notes += note
                delivered.complete(note)
            },
        )
        mgr.start("s1", workspace, "echo hi")
        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")
        withTimeout(5_000) { entering.await() }
        assertTrue("通知投递完成前会话不能算空闲（否则淘汰会让通知丢失）", mgr.hasRunning("s1"))
        release.complete(Unit)
        withTimeout(5_000) {
            while (mgr.hasRunning("s1")) kotlinx.coroutines.delay(10)
        }
        assertTrue(notes.single().contains("exit code 0"))
    }

    @Test
    fun `cancelling while the note is being delivered does not drop the note`() = runBlocking {
        val entering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mgr = manager(
            deliverNote = { _, _, note ->
                entering.complete(Unit)
                release.await()
                notes += note
                delivered.complete(note)
            },
        )
        mgr.start("s1", workspace, "echo hi")
        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")
        withTimeout(5_000) { entering.await() }

        // 已落终态、通知正在投递：cancel 返回 false，且不能把在途投递掐掉。
        assertFalse(mgr.cancel("s1", "t1"))
        release.complete(Unit)
        withTimeout(5_000) { delivered.await() }
        assertTrue("投递中的通知不能被取消分支掐掉", notes.single().contains("exit code 0"))
    }

    @Test
    fun `cancelAll does not deliver`() = runBlocking {
        val mgr = manager()
        mgr.start("s1", workspace, "sleep 100")
        mgr.cancelAll("s1")
        assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
        assertFalse(mgr.hasRunning("s1"))
        assertTrue("会话关闭时不得回注", notes.isEmpty())
    }

    @Test
    fun `cancelAll still kills the process whose handle the watcher also drops`() = runBlocking {
        // watcher 的 finally 与 cancelAll 摘的是同一把句柄。先 cancel 再摘会让 finally 抢在前面，
        // cancelAll 扑空 → 进程成孤儿（只能等下一代 reapCommand）。这里等 graceful 真的发生，
        // 不靠 sleep：句柄丢了就等不到，5s 超时即红。
        val killed = CompletableDeferred<String>()
        val mgr = manager(
            launch = { spec ->
                launches += spec
                DetachResult.Started(
                    DetachedProcess(
                        pid = 42L,
                        graceful = { killed.complete(spec.id) },
                        force = { stops += "${spec.id}:KILL" },
                        alive = { true },
                    ),
                )
            },
        )
        mgr.start("s1", workspace, "sleep 100")
        mgr.cancelAll("s1")
        assertEquals("t1", withTimeout(5_000) { killed.await() })
    }

    // ------------------------------------------------------------------ 脱离启动失败

    @Test
    fun `a failed launch removes the scaffolding and reports the reason`() = runBlocking {
        val mgr = manager(launch = { spec ->
            launches += spec
            DetachResult.Failed("mount failed")
        })

        val result = mgr.start("s1", workspace, "echo hi")

        assertTrue(result is StartResult.Failure)
        assertTrue((result as StartResult.Failure).message.contains("mount failed"))
        assertFalse("失败后不留任务脚本", File(workspace, "$TASKS_DIR/t1.sh").exists())
        assertFalse("失败后不留 runner 脚本", File(workspace, "$TASKS_DIR/t1.run.sh").exists())
        assertTrue("失败不能入表", mgr.list("s1").isEmpty())
    }

    // ------------------------------------------------------------------ 保留 20 条 / 文件清理

    @Test
    fun `trimTasks never drops a running task`() {
        val tasks = listOf(
            task(state = BackgroundTaskState.SUCCEEDED).copy(id = "new", startedAt = 3_000),
            task(state = BackgroundTaskState.SUCCEEDED).copy(id = "mid", startedAt = 2_000),
            task(state = BackgroundTaskState.RUNNING, exit = null).copy(id = "old", startedAt = 1_000),
        )
        // 最旧的那条正在跑：截断只能丢已终态的 mid，不能丢 old。
        // （若把 running 放在最新处，丢最旧的终态条目两种实现结果相同 —— 这条用例就没判别力。）
        assertEquals(listOf("new", "old"), trimTasks(tasks, 2)!!.map { it.id })
        assertEquals(tasks, trimTasks(tasks, 3))
    }

    @Test
    fun `trimTasks refuses when every kept task is still running`() {
        val tasks = listOf(
            task(state = BackgroundTaskState.RUNNING, exit = null).copy(id = "b", startedAt = 2_000),
            task(state = BackgroundTaskState.RUNNING, exit = null).copy(id = "a", startedAt = 1_000),
        )
        assertNull(trimTasks(tasks, 1))
    }

    @Test
    fun `trimTasks never drops a task whose note is still being delivered`() {
        val tasks = listOf(
            task().copy(id = "new", startedAt = 3_000),
            task().copy(id = "mid", startedAt = 2_000),
            task().copy(id = "delivering", startedAt = 1_000, delivering = true),
        )
        // 最旧的那条已终态但通知还在投递：截断只能丢次旧的 mid，不能丢 delivering。
        assertEquals(listOf("new", "delivering"), trimTasks(tasks, 2)!!.map { it.id })
        assertEquals(
            "没有 delivering 标记时仍按旧规则丢最旧的终态条目",
            listOf("new", "mid"),
            trimTasks(tasks.map { it.copy(delivering = false) }, 2)!!.map { it.id },
        )
    }

    @Test
    fun `a task whose note is being delivered is not evicted by a new start`() = runBlocking {
        val entering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var seq = 0
        val mgr = BackgroundTaskManager(
            scope = scope,
            launch = { spec -> DetachResult.Started(process(spec.id)) },
            mounts = { mounts },
            sessionExists = { it == "s1" },
            deliver = { _, taskId, _ ->
                if (taskId == "t1") {
                    entering.complete(Unit)
                    release.await()
                }
            },
            now = { 1_000L },
            pollMillis = 10,
            maxRunning = MAX_TASKS,
            idFactory = { "t${++seq}" },
        )
        // t1 跑完，但通知卡在投递里。
        assertTrue(mgr.start("s1", workspace, "echo hi") is StartResult.Started)
        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")
        withTimeout(5_000) { entering.await() }

        // 再把表填满：t2..t20 都正常完成并投递。
        for (i in 2..MAX_TASKS) {
            val id = "t$i"
            assertTrue(mgr.start("s1", workspace, "echo hi") is StartResult.Started)
            File(workspace, "$TASKS_DIR/$id.rc").writeText("0")
            withTimeout(5_000) {
                while (mgr.find("s1", id)?.state == BackgroundTaskState.RUNNING) delay(10)
            }
        }
        assertEquals(MAX_TASKS, mgr.list("s1").size)

        // 表满时再启动：最旧的 t1 在投递，不能被挤掉（否则 hasRunning 误判空闲）。
        assertTrue(mgr.start("s1", workspace, "echo hi") is StartResult.Started)
        assertEquals(MAX_TASKS, mgr.list("s1").size)
        assertTrue("投递中的任务必须留在表里", mgr.find("s1", "t1") != null)
        assertNull("被挤出的是次旧的 t2", mgr.find("s1", "t2"))

        release.complete(Unit)
        mgr.cancelAll("s1")
    }

    @Test
    fun `start keeps running tasks and prunes files left by a previous process`() = runBlocking {
        val taskDir = File(workspace, TASKS_DIR).apply { mkdirs() }
        // 上一次进程留下的孤儿：任务表在内存，重启后没人再认领它。
        val orphan = File(taskDir, "gone.log").apply { writeText("stale") }
        val mgr = manager()
        mgr.start("s1", workspace, "echo hi")
        assertFalse("上一进程的孤儿文件应被清理", orphan.exists())
        assertTrue(File(taskDir, "t1.sh").isFile)
        mgr.cancelAll("s1")
    }

    @Test
    fun `start refuses when the table is full of running tasks`() = runBlocking {
        var seq = 0
        val mgr = BackgroundTaskManager(
            scope = scope,
            launch = { spec -> DetachResult.Started(process(spec.id)) },
            mounts = { mounts },
            sessionExists = { true },
            deliver = { _, _, _ -> },
            now = { 1_000L },
            pollMillis = 60_000,
            maxRunning = MAX_TASKS, // 放开单会话并发，好把「保留条数」填满
            idFactory = { "f${++seq}" },
        )
        repeat(MAX_TASKS) { i ->
            val session = if (i % 2 == 0) "s1" else "s2"
            assertTrue(mgr.start(session, workspace, "echo hi") is StartResult.Started)
        }
        // 两个会话各 10 条，都低于单会话并发上限 —— 只有「保留条数」这一层能拦。
        val overflow = mgr.start("s1", workspace, "echo hi")
        assertTrue(overflow is StartResult.Failure)
        assertTrue((overflow as StartResult.Failure).message.contains("kept in memory"))
        assertEquals("20 条全在跑时不能挤掉任何一条", MAX_TASKS, mgr.list("s1").size + mgr.list("s2").size)
        assertTrue("被拒的那条要把已经起起来的进程收掉", stops.any { it.endsWith(":KILL") })
        mgr.cancelAll("s1")
    }
}
