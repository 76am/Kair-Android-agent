package dev.kair.app.app

import dev.kair.app.core.proot.Mount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * U49：`RUNNING ⇒ 宿主进程仍存活`。
 *
 * 钉的是「进程死了、但没写下退出码」这一路：`.rc` 由容器内 `trap rc EXIT` 写出，而 SIGKILL /
 * 崩溃 / 任务自杀都不执行 trap。旧实现只看 `.rc`，任务会永久停在 RUNNING —— 于是
 * `hasRunning` 恒真（会话不被淘汰、保活常开）、`timeout = 0` 的常驻任务永不结束、通知永不投递。
 *
 * 所有用例都带超时：这条链路上「红」的形状是**永远等不到**，不加上界会把 CI 挂住。
 */
class BackgroundTaskLifecycleTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val root: File = Files.createTempDirectory("kair-bg-life").toFile()
    private val workspace = File(root, "ws").apply { mkdirs() }
    private val mounts = listOf(Mount("/projects/ws", workspace))
    private val stops = CopyOnWriteArrayList<String>()
    private val notes = CopyOnWriteArrayList<String>()
    private val delivered = CompletableDeferred<String>()

    private fun manager(
        alive: () -> Boolean,
        pollMillis: Long = 10,
        now: () -> Long = { System.currentTimeMillis() },
    ) = BackgroundTaskManager(
        scope = scope,
        launch = { spec ->
            DetachResult.Started(
                DetachedProcess(
                    pid = 42L,
                    graceful = { stops += "${spec.id}:TERM" },
                    force = { stops += "${spec.id}:KILL" },
                    alive = alive,
                ),
            )
        },
        mounts = { mounts },
        sessionExists = { it == "s1" },
        deliver = { _, _, note ->
            notes += note
            delivered.complete(note)
        },
        now = now,
        pollMillis = pollMillis,
        idFactory = { "t1" },
    )

    private fun terminatedTask() = BackgroundTask(
        id = "t1",
        sessionId = "s1",
        command = "sleep 100",
        cwd = "",
        state = BackgroundTaskState.TERMINATED,
        startedAt = 1_000,
        finishedAt = 6_000,
        exitCode = null,
        pid = 42L,
        timeoutSeconds = 1_800,
        dir = File(workspace, TASKS_DIR),
    )

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test(timeout = 10_000)
    fun `a task whose process died without an exit code is terminated, not left running`() = runBlocking {
        val mgr = manager(alive = { false })
        assertTrue(mgr.start("s1", workspace, "sleep 100", timeoutSeconds = 1_800) is StartResult.Started)

        val note = withTimeout(5_000) { delivered.await() }
        assertEquals("进程已死 + 无 .rc → 终态必须是 TERMINATED", BackgroundTaskState.TERMINATED, mgr.list("s1").single().state)
        assertTrue("通知要说清是进程死了，不能让模型以为还在跑：$note", note.contains("was terminated"))
        assertEquals("通知只投一次", 1, notes.size)
        withTimeout(5_000) {
            while (mgr.hasRunning("s1")) delay(10)
        }
        assertTrue("已死的进程不得再收到任何信号：$stops", stops.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a process that exits right after its exit code lands is still finished normally`() = runBlocking {
        val probed = CompletableDeferred<Unit>()
        val mgr = manager(pollMillis = 100, alive = {
            // 第一次存活探测 = 「进程已退、.rc 还没落盘」的那个窗口。
            probed.complete(Unit)
            false
        })
        mgr.start("s1", workspace, "echo hi", timeoutSeconds = 1_800)

        withTimeout(5_000) { probed.await() }
        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")

        val note = withTimeout(5_000) { delivered.await() }
        assertEquals(
            "窗口内落盘的 .rc 必须赢过存活判定：早判一拍会把正常完成记成异常终止",
            BackgroundTaskState.SUCCEEDED,
            mgr.list("s1").single().state,
        )
        assertTrue(note.contains("exit code 0"))
        assertTrue("正常完成不得发任何信号：$stops", stops.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `an unbounded task whose process died still reaches a terminal state`() = runBlocking {
        val clock = AtomicLong(0)
        val mgr = manager(now = { clock.get() }, alive = { false })
        mgr.start("s1", workspace, "python3 -m http.server 8080", timeoutSeconds = 0)
        // 常驻任务的「很久以后」：存活判定若被塞进 `timeoutSeconds > 0` 分支，这里会永远等不到通知。
        clock.set(6 * 3_600_000L)

        val note = withTimeout(5_000) { delivered.await() }
        assertEquals(BackgroundTaskState.TERMINATED, mgr.list("s1").single().state)
        assertTrue(note.contains("was terminated"))
        assertFalse(mgr.hasRunning("s1"))
        assertTrue("已死的进程不得再收到信号：$stops", stops.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a dead process past its deadline is terminated, not timed out`() = runBlocking {
        val clock = AtomicLong(0)
        // 轮询周期放大，保证「时钟已经越过期限」在第一次轮询之前成立。
        val mgr = manager(now = { clock.get() }, pollMillis = 500, alive = { false })
        mgr.start("s1", workspace, "sleep 100", timeoutSeconds = 1)
        // 同一次轮询里既「已死」又「已到点」：顺序必须是 .rc → 存活 → 超时。
        clock.set(10_000)

        val note = withTimeout(5_000) { delivered.await() }
        assertEquals("已死不该报成超时", BackgroundTaskState.TERMINATED, mgr.list("s1").single().state)
        assertFalse("通知不能写成 timed out：$note", note.contains("timed out"))
        assertTrue("已死的进程不得再收到信号（超时会 kill）：$stops", stops.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a dead process gets no signal when the task is cancelled`() = runBlocking {
        // 轮询周期拉到测试窗口之外：这里单独验证取消路径，不让 watcher 抢先落 TERMINATED。
        val mgr = manager(pollMillis = 60_000, alive = { false })
        mgr.start("s1", workspace, "sleep 100", timeoutSeconds = 1_800)

        assertTrue(mgr.cancel("s1", "t1"))
        assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
        assertEquals("已死的进程不得再收到信号：$stops", emptyList<String>(), stops.toList())
    }

    @Test(timeout = 10_000)
    fun `a dead process gets no signal when the session closes`() = runBlocking {
        val mgr = manager(pollMillis = 60_000, alive = { false })
        mgr.start("s1", workspace, "sleep 100", timeoutSeconds = 1_800)

        mgr.cancelAll("s1")
        assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
        assertFalse(mgr.hasRunning("s1"))

        // 等过强制收树的宽限期（KILL_GRACE_MS = 2s，这里多留 0.5s）：若保护缺失，TERM 与 KILL 都会落在 stops 里。
        delay(2_500)
        assertEquals("已死的进程不得再收到信号：$stops", emptyList<String>(), stops.toList())
    }

    @Test(timeout = 10_000)
    fun `a live process that writes its exit code still finishes normally`() = runBlocking {
        val mgr = manager(alive = { true })
        mgr.start("s1", workspace, "echo hi")

        File(workspace, "$TASKS_DIR/t1.rc").writeText("0")

        val note = withTimeout(5_000) { delivered.await() }
        assertEquals("正常路径不变：.rc 在前、存活在后", BackgroundTaskState.SUCCEEDED, mgr.list("s1").single().state)
        assertTrue(note.contains("exit code 0"))
        assertTrue("正常完成不得发信号：$stops", stops.isEmpty())
    }

    @Test
    fun `terminated has its own notice wording`() {
        assertEquals("terminated", modelState(BackgroundTaskState.TERMINATED))
        val note = taskNote(terminatedTask(), "")
        assertTrue("面向模型的通知要写明终止原因：$note", note.contains("was terminated"))
        assertFalse("不是用户取消 —— 写成 cancelled 会让模型误判", note.contains("cancelled"))
        assertFalse("没有退出码 —— 不能标成 failed with exit code", note.contains("failed with exit code"))
    }
}
