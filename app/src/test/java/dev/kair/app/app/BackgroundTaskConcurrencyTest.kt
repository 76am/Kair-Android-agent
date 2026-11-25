package dev.kair.app.app

import dev.kair.app.core.proot.Mount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 任务表的并发语义：所有读-改-写都必须 CAS 重试，不能拿旧快照直接写回。
 *
 * 两个真实故障（都只在并发下出现，单线程用例抓不到）：
 * 1. **丢记录**：两个 `start` 同时读表、各自写回 → 后写的覆盖先写的；被覆盖的 watcher
 *    下一轮找不到自己直接退出，进程却还在容器里跑。
 * 2. **终态复活**：`start` 持有旧快照期间，watcher 把某任务 CAS 成终态；`start` 再把旧快照
 *    写回 → 任务回到 RUNNING。它的 watcher 已经返回，没人再推进，`hasRunning` 永久为真
 *    （会话不判空闲、保活不释放；通知里会多出一个「会话」）。
 *
 * 用例用门控 [MutableStateFlow] 把「读」停在写入之前，所以红/绿是确定性的，不靠抢时序。
 */
class BackgroundTaskConcurrencyTest {

    @Test
    fun `concurrent starts keep every task in the table`() {
        val count = 8
        val table = GatedStateFlow(emptyList())
        // 前 count 次读互相等：保证所有插入方都拿到同一个旧快照，之后才允许写。
        table.awaitFirstReads(count)
        val threads = (1..count).map { i ->
            thread(isDaemon = true, start = false, name = "insert-$i") {
                insertTask(table, running("t$i"))
            }
        }
        threads.forEach { it.start() }
        threads.forEach {
            it.join(10_000)
            assertFalse("插入线程不能挂住", it.isAlive)
        }
        assertEquals(
            "并发插入不能互相覆盖；旧实现（读-改-写）下这里只剩最后一条",
            (1..count).map { "t$it" }.toSet(),
            table.value.map { it.id }.toSet(),
        )
    }

    @Test
    fun `a settle landing during start is not resurrected`() {
        val table = GatedStateFlow(listOf(running("old")))
        val failure = AtomicReference<Throwable?>()
        val inserter = thread(isDaemon = true, start = false, name = "insert-new") {
            runCatching { insertTask(table, running("new")) }.onFailure { failure.set(it) }
        }
        table.gateReadOf(inserter)
        try {
            inserter.start()
            assertTrue("插入方必须先读到旧快照", table.awaitGatedRead())
            // 插入方握着旧快照（old 仍为 RUNNING）期间，watcher 把它落成 SUCCEEDED。
            assertTrue(transitionTask(table, "old", BackgroundTaskState.SUCCEEDED, 0, 2_000) != null)
        } finally {
            table.releaseGatedRead()
            inserter.join(10_000)
        }
        assertNull("插入线程不能抛异常", failure.get())
        assertFalse("已结束的任务不能被旧快照写回 RUNNING", table.value.first { it.id == "old" }.running)
        assertEquals(BackgroundTaskState.SUCCEEDED, table.value.first { it.id == "old" }.state)
        assertTrue(table.value.any { it.id == "new" })
    }

    @Test
    fun `a session closed during startup cancels the just-inserted task`() = runBlocking {
        val root = Files.createTempDirectory("kair-bg-close").toFile()
        val workspace = File(root, "ws").apply { mkdirs() }
        val commands = CopyOnWriteArrayList<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exists = AtomicBoolean(true)
        val notes = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = BackgroundTaskManager(
                scope = scope,
                launch = { spec ->
                    entered.complete(Unit)
                    release.await()
                    DetachResult.Started(
                        DetachedProcess(
                            pid = 42L,
                            graceful = { commands += "${spec.id}:TERM" },
                            force = { commands += "${spec.id}:KILL" },
                            alive = { true },
                        ),
                    )
                },
                mounts = { listOf(Mount("/projects/ws", workspace)) },
                sessionExists = { exists.get() },
                deliver = { _, _, note -> notes += note },
                pollMillis = 60_000,
                idFactory = { "t1" },
            )
            val result = CompletableDeferred<StartResult>()
            val job = launch(Dispatchers.Default) {
                result.complete(mgr.start("s1", workspace, "echo hi"))
            }
            entered.await()
            // 会话在 launch 期间关闭：cancelAll 此刻看到的还是空表。
            exists.set(false)
            mgr.cancelAll("s1")
            release.complete(Unit)
            job.join()

            val failed = result.await()
            assertTrue("会话已关闭时不能报 Started：$failed", failed is StartResult.Failure)
            assertEquals(TASK_ERR_NOT_SESSION, (failed as StartResult.Failure).message)
            assertEquals(BackgroundTaskState.CANCELLED, mgr.list("s1").single().state)
            assertFalse(mgr.hasRunning("s1"))
            assertTrue("刚起起来的进程要收掉", commands.any { it == "t1:KILL" })
            assertTrue("会话已关，不得再投递通知", notes.isEmpty())
            assertFalse(File(workspace, "$TASKS_DIR/t1.sh").exists())
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `a concurrent start in the same workspace keeps the in-flight task script`() = runBlocking {
        val root = Files.createTempDirectory("kair-bg-prune").toFile()
        val workspace = File(root, "ws").apply { mkdirs() }
        val nextId = AtomicInteger()
        val execEntered = CompletableDeferred<Unit>()
        val releaseExec = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = BackgroundTaskManager(
                scope = scope,
                launch = { spec ->
                    // A 卡在 launch：此时它已写好 .sh，但还没进任务表。
                    if (spec.id == "a") {
                        execEntered.complete(Unit)
                        releaseExec.await()
                    }
                    DetachResult.Started(DetachedProcess(42L, {}, {}, alive = { true }))
                },
                mounts = { listOf(Mount("/projects/ws", workspace)) },
                sessionExists = { true },
                deliver = { _, _, _ -> },
                pollMillis = 60_000,
                idFactory = { if (nextId.getAndIncrement() == 0) "a" else "b" },
            )

            val first = launch(Dispatchers.Default) { mgr.start("s1", workspace, "echo a") }
            execEntered.await()
            val aScript = File(workspace, "$TASKS_DIR/a.sh")
            assertTrue("A 的脚本应先写好", aScript.isFile)

            // 同一工作区的 B 完成插入并清理目录；A 还没入表，旧实现的 keep 快照里没有它。
            val second = mgr.start("s2", workspace, "echo b")
            assertTrue("B 应能启动：$second", second is StartResult.Started)
            assertTrue(
                "同目录清理不能删掉在途任务的脚本（否则 A 的进程启动后没人能再读到它）",
                aScript.isFile,
            )
            assertTrue("B 自己的脚本当然也要在", File(workspace, "$TASKS_DIR/b.sh").isFile)

            releaseExec.complete(Unit)
            first.join()
            assertEquals("两条任务都应入表", setOf("a", "b"), mgr.tasks.value.map { it.id }.toSet())
            assertTrue("A 入表后两份脚本都还在", aScript.isFile && File(workspace, "$TASKS_DIR/b.sh").isFile)
        } finally {
            releaseExec.complete(Unit)
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun running(id: String) = BackgroundTask(
        id = id,
        sessionId = "s1",
        command = "sleep 100",
        cwd = "",
        state = BackgroundTaskState.RUNNING,
        startedAt = 1_000,
        pid = 42L,
        timeoutSeconds = 1_800,
        dir = File("build/tmp"),
    )

    /**
     * 把「读」卡在写之前的任务表。
     *
     * - [awaitFirstReads]：前 N 次读用栅栏互等，保证都拿到同一份旧快照；
     * - [gateReadOf]：只卡指定线程的第一次读，读到的快照在卡住**之前**就取好。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class GatedStateFlow(initial: List<BackgroundTask>) : MutableStateFlow<List<BackgroundTask>> {
        private val backing = MutableStateFlow(initial)
        private val firstReads = AtomicInteger()

        @Volatile private var readBarrier: CyclicBarrier? = null

        @Volatile private var gatedThread: Thread? = null
        private val gatedRead = CountDownLatch(1)
        private val gatedRelease = CountDownLatch(1)

        fun awaitFirstReads(parties: Int) {
            readBarrier = CyclicBarrier(parties)
        }

        fun gateReadOf(thread: Thread) {
            gatedThread = thread
        }

        fun awaitGatedRead(): Boolean = gatedRead.await(5_000, TimeUnit.MILLISECONDS)

        fun releaseGatedRead() {
            gatedRelease.countDown()
        }

        override var value: List<BackgroundTask>
            get() {
                // 快照必须在卡住之前取：放行后表可能已被 transition 改写。
                val snapshot = backing.value
                val barrier = readBarrier
                if (barrier != null && firstReads.getAndIncrement() < barrier.parties) barrier.await()
                if (Thread.currentThread() === gatedThread && gatedRead.count == 1L) {
                    gatedRead.countDown()
                    gatedRelease.await()
                }
                return snapshot
            }
            set(v) {
                backing.value = v
            }

        override fun compareAndSet(expect: List<BackgroundTask>, update: List<BackgroundTask>): Boolean =
            backing.compareAndSet(expect, update)

        override val replayCache: List<List<BackgroundTask>> get() = backing.replayCache

        override val subscriptionCount: StateFlow<Int> get() = backing.subscriptionCount

        override suspend fun emit(value: List<BackgroundTask>) {
            this.value = value
        }

        override fun tryEmit(value: List<BackgroundTask>): Boolean {
            this.value = value
            return true
        }

        override fun resetReplayCache() {
            backing.resetReplayCache()
        }

        override suspend fun collect(collector: FlowCollector<List<BackgroundTask>>): Nothing =
            backing.collect(collector)
    }
}
