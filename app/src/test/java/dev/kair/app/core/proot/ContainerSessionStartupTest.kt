package dev.kair.app.core.proot

import dev.kair.app.core.tools.LocalCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动期生命周期（U44）：READY 与「流提前结束」必须是**可区分**的两个结果。
 *
 * 缺陷原形：`onStreamClosed` 无条件 `ready.complete(Unit)`，于是进程在 READY 前断流时
 * 先写 `Error`、又被 `start()` 覆盖成 `Running`（面板显示「运行中」而进程已死）。
 *
 * 用假进程（[FakeSessionProcess]）确定性驱动，不需要真 su / proot / 设备。
 */
class ContainerSessionStartupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 假进程 ---------------------------------------------------------------

    /**
     * 可脚本化的会话进程：测试决定它「吐出什么」（容器 → 宿主）与「何时断流」；
     * 宿主写进去的命令行按行回调，用例可据此模拟容器内 shell 的回应。
     */
    private class FakeSessionProcess : Process() {
        /** 宿主每写一行命令就回调一次（模拟容器内循环脚本）。 */
        @Volatile
        var onHostLine: ((String) -> Unit)? = null

        /** 收到 `__KAIR_STOP__` 时是否按正常路径退出。 */
        @Volatile
        var exitOnStop: Boolean = true

        /** `waitFor` 是否立即返回 false（模拟不响应优雅停止的进程）。 */
        @Volatile
        var gracefulStop: Boolean = true

        /** `destroy` 时是否关闭 stdout（false = 制造「旧代迟到 EOF」的窗口）。 */
        @Volatile
        var closeStreamOnDestroy: Boolean = true

        private val stdout = FeedStream()
        private val exited = CountDownLatch(1)
        private val lineBuf = StringBuilder()
        private val hostLines = mutableListOf<String>()

        @Volatile
        private var alive = true

        /** 宿主写进 stdin 的全部命令行（断言用）。 */
        val commands: List<String> get() = synchronized(hostLines) { hostLines.toList() }

        fun emit(text: String) = stdout.feed(if (text.endsWith("\n")) text else "$text\n")

        fun closeStdout() = stdout.finish()

        fun exit() {
            if (!alive) return
            alive = false
            exited.countDown()
            stdout.finish()
        }

        private val hostOut = object : OutputStream() {
            override fun write(b: Int) = accept(b.toChar())

            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) accept(b[i].toInt().toChar())
            }

            private fun accept(c: Char) {
                if (c == '\n') {
                    val line = synchronized(lineBuf) {
                        lineBuf.toString().also { lineBuf.setLength(0) }
                    }
                    synchronized(hostLines) { hostLines += line }
                    if (line == ContainerSession.STOP_TOKEN && exitOnStop) exit()
                    onHostLine?.invoke(line)
                } else {
                    synchronized(lineBuf) { lineBuf.append(c) }
                }
            }
        }

        override fun getOutputStream(): OutputStream = hostOut
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            if (gracefulStop) exited.await(timeout, unit) else false

        override fun exitValue(): Int =
            if (exited.count == 0L) throw IllegalThreadStateException() else 0

        override fun destroy() { if (closeStreamOnDestroy) exit() else alive = false }
        override fun destroyForcibly(): Process = also { destroy() }
        override fun isAlive(): Boolean = alive
    }

    /** 可「喂」的 stdout：读到 EOF 只在 [finish] 之后出现，读到没有数据时阻塞。 */
    private class FeedStream : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var closed = false

        private var current = ByteArray(0)
        private var pos = 0

        fun feed(text: String) {
            chunks.put(text.toByteArray())
        }

        fun finish() {
            closed = true
            chunks.put(ByteArray(0))
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) {
                if (closed && chunks.isEmpty()) return -1
                val next = chunks.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (next.isEmpty()) {
                    if (closed) return -1 else continue
                }
                current = next
                pos = 0
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }
    }

    // ---- 装配 -----------------------------------------------------------------

    /** 处于 Ready 的最小 rootfs：`version.txt` + `distro.txt` + `rootfs/bin/sh` 就够（不跑真正的解包）。 */
    private fun readyManager(): RootfsManager {
        val dir = tmp.newFolder("proot-${System.nanoTime()}")
        File(dir, "rootfs/bin").mkdirs()
        File(dir, "rootfs/bin/sh").writeText("#!/bin/sh\n")
        File(dir, "rootfs/tmp").mkdirs()
        File(dir, "version.txt").writeText("test")
        File(dir, "distro.txt").writeText("ubuntu")
        return RootfsManager(dir, "x86_64")
    }

    /**
     * 走 proot 档启动（进程由 [spawn] 提供，真 proot 不执行）。
     *
     * 启动三元组只生成 argv / env；二进制是否存在不影响，进程一律由 [spawn] 顶替。
     */
    private fun session(
        beforeMounts: () -> Unit = {},
        spawn: (List<String>, Map<String, String>) -> Process,
    ): ContainerSession {
        val manager = readyManager()
        val workspace = tmp.newFolder("ws")
        val proot = ProotCommandRunner(
            manager = manager,
            prootExec = File(tmp.newFolder("proot-bin"), "libproot_exec.so").apply { writeText("fake") },
            loader = File(tmp.newFolder("proot-ld"), "libproot_loader.so").apply { writeText("fake") },
            prootTmpDir = tmp.newFolder("proot-tmp"),
            fallback = LocalCommandRunner("/bin/sh"),
        )
        return ContainerSession(
            manager = manager,
            mounts = {
                beforeMounts()
                workspaceMounts(workspace)
            },
            proot = proot,
            resolveChannel = { SessionChannel.PROOT },
            spawnProcess = spawn,
        )
    }

    // ---- V1：READY 前 EOF ------------------------------------------------------

    @Test
    fun `stream closing before READY must not report Running`() = runBlocking {
        val fake = FakeSessionProcess()
        // 进程一启动就断流：READY 永不出现
        fake.closeStdout()
        val session = session { _, _ -> fake }

        withTimeout(15_000) { session.start() }

        assertTrue(
            "启动期断流必须报错（实际状态：${session.state.value}）",
            session.state.value is SessionState.Error,
        )
        assertFalse("伪运行状态：进程已死却报 Running", session.state.value is SessionState.Running)
        assertTrue(
            "启动失败必须释放本次挂载状态（实际残留：${session.mountedMounts}）",
            session.mountedMounts.isEmpty(),
        )
    }

    // ---- V2：READY 后紧接 EOF --------------------------------------------------

    @Test
    fun `EOF right after READY ends in Error and never stays Running`() = runBlocking {
        val fake = FakeSessionProcess()
        fake.emit(ContainerSession.READY_TOKEN)
        fake.closeStdout()
        val session = session { _, _ -> fake }

        withTimeout(15_000) { session.start() }

        assertTrue(
            "READY 后立即断流：最终状态必须是 Error（实际：${session.state.value}）",
            session.state.value is SessionState.Error,
        )
    }

    // ---- V3：旧代迟到 EOF ------------------------------------------------------

    @Test
    fun `late EOF from a previous generation does not touch the new session`() = runBlocking {
        val first = FakeSessionProcess().apply {
            // 不响停止帧、不响优雅停止、destroy 也不关流：把旧读线程的 EOF 拖到第二次启动之后
            exitOnStop = false
            gracefulStop = false
            closeStreamOnDestroy = false
            emit(ContainerSession.READY_TOKEN)
        }
        val second = FakeSessionProcess().apply { emit(ContainerSession.READY_TOKEN) }
        val fakes = ArrayDeque(listOf(first, second))
        val session = session { _, _ -> fakes.removeFirst() }

        withTimeout(15_000) { session.start() }
        assertTrue("第一次启动应该成功", session.state.value is SessionState.Running)

        withTimeout(15_000) { session.stop() }
        withTimeout(15_000) { session.start() }
        assertTrue("第二次启动应该成功", session.state.value is SessionState.Running)

        // 旧代的流现在才结束：回调不得改新会话的状态
        first.closeStdout()
        Thread.sleep(300)

        assertTrue(
            "旧代迟到 EOF 把新会话改成了 ${session.state.value}",
            session.state.value is SessionState.Running,
        )
    }

    // ---- V4：正常启动 + 一条命令（主路径不许伤） --------------------------------

    @Test
    fun `normal start keeps exec frame pairing`() = runBlocking {
        val fake = FakeSessionProcess()
        fake.onHostLine = { line ->
            if (line == "echo hi") {
                fake.emit("hi")
                fake.emit("${ContainerSession.RC_PREFIX}0${ContainerSession.RC_SUFFIX}")
            }
        }
        fake.emit(ContainerSession.READY_TOKEN)
        val session = session { _, _ -> fake }

        withTimeout(15_000) { session.start() }
        assertTrue(session.state.value is SessionState.Running)
        assertFalse("正常启动必须记下挂载清单", session.mountedMounts.isEmpty())

        val result = withTimeout(15_000) { session.exec("echo hi") }
        assertEquals("hi", result.output)
        assertEquals(0, result.exitCode)
        assertEquals(listOf("echo hi"), fake.commands)
    }

    // ---- V5：失败路径释放 ------------------------------------------------------

    @Test
    fun `failed start releases the process so the next start can run`() = runBlocking {
        val broken = FakeSessionProcess().apply { closeStdout() }
        val healthy = FakeSessionProcess().apply { emit(ContainerSession.READY_TOKEN) }
        val fakes = ArrayDeque(listOf(broken, healthy))
        val session = session { _, _ -> fakes.removeFirst() }

        withTimeout(15_000) { session.start() }
        assertTrue(session.state.value is SessionState.Error)
        assertFalse("失败路径必须清掉 busy", session.busy.value)
        assertTrue("失败路径必须释放挂载状态", session.mountedMounts.isEmpty())

        // 残留的 Starting / 未释放的进程会让第二次启动直接返回（拒绝重启）
        withTimeout(15_000) { session.start() }
        assertTrue(
            "失败后再启动必须能起来（实际：${session.state.value}）",
            session.state.value is SessionState.Running,
        )
    }

    // ---- V6：旧代 EOF 落在**下一次启动的准备窗口**里 ---------------------------

    /**
     * V3 只覆盖「新会话已经进入 Running 之后」才到的旧代 EOF。这里卡的是另一半：
     * 新启动已经置 `Starting`、但**尚未换代**的那段（解析挂载、写入口脚本、起进程）。
     * 那段里读线程的 `isCurrent(旧代)` 仍然为真 —— 旧代 EOF 会在新会话身上写 `Error`，
     * 随后 `start()` 起完的进程既没发布 `Running`、也没人回收（活进程泄漏）。
     */
    @Test
    fun `late EOF landing during the next start must not kill the new session`() = runBlocking {
        val first = FakeSessionProcess().apply {
            // 与 V3 同款：不响停止帧、不响优雅停止、destroy 也不关流 —— 旧读线程一直挂在读上
            exitOnStop = false
            gracefulStop = false
            closeStreamOnDestroy = false
            emit(ContainerSession.READY_TOKEN)
        }
        val second = FakeSessionProcess().apply { emit(ContainerSession.READY_TOKEN) }
        val fakes = ArrayDeque(listOf(first, second))
        val inSetup = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val session = session(
            spawn = { _, _ -> fakes.removeFirst() },
            beforeMounts = {
                // beforeMounts 在「已置 Starting、尚未开始等 READY」之间被调用，正好是那个窗口
                if (armed.get()) {
                    inSetup.countDown()
                    proceed.await(5, TimeUnit.SECONDS)
                }
            },
        )

        withTimeout(15_000) { session.start() }
        assertTrue("第一次启动应该成功", session.state.value is SessionState.Running)
        withTimeout(15_000) { session.stop() }

        armed.set(true)
        val restarted = launch(Dispatchers.IO) { session.start() }
        assertTrue("第二次启动没有走到挂载那一步", inSetup.await(5, TimeUnit.SECONDS))
        first.closeStdout() // 旧代 EOF 落在「已置 Starting、尚未换代」的窗口里
        Thread.sleep(500)
        proceed.countDown()
        restarted.join()

        assertTrue(
            "旧代迟到 EOF 把新会话打断了（实际：${session.state.value}）",
            session.state.value is SessionState.Running,
        )
        assertFalse("新会话的挂载清单被旧代回调清空", session.mountedMounts.isEmpty())
    }

    // ---- V7：起进程就失败也要收掉本次挂载 --------------------------------------

    @Test
    fun `spawn failure releases this attempt's mounts`() = runBlocking {
        val session = session { _, _ -> throw IllegalStateException("boom") }

        withTimeout(15_000) { session.start() }

        val state = session.state.value
        assertTrue("起进程失败必须报错（实际：$state）", state is SessionState.Error)
        assertTrue(
            "错误文案必须由已登记的内核文件组装成中文（AGENTS.md §4）：${(state as SessionState.Error).message}",
            state.message.startsWith("无法启动"),
        )
        assertTrue(
            "起进程失败后不得留下本次挂载（实际：${session.mountedMounts}）",
            session.mountedMounts.isEmpty(),
        )
    }

    // ---- V8：Running 之后流自己断了（**不是** stop）也要释放 -------------------

    /**
     * 启动期断流的释放有 `start()` 的 `Closed` 分支兜着，所以只有 [V1]/[V5] 时，
     * `onStreamClosed` 不释放也看不出来。「已经在 Running、进程却没了」这条路径没有别的兜底：
     * 不释放的话 `state` 落 `Error`、`mountedMounts` 却仍然挂着 —— 面板与后台任务据此
     * 认为容器还挂着（`BackgroundTaskDetach` 就是用 `mountedMounts` 拼提权脚本）。
     *
     * 假进程的 `isAlive` 故意保持 true：流断了但进程没被收，是真实情形之一
     * （`killTree` 之前的那一小段、或管道先断）。断言只看状态 / 挂载 / 能否重启三件事。
     */
    @Test
    fun `stream closing while Running releases the session`() = runBlocking {
        val first = FakeSessionProcess()
        first.emit(ContainerSession.READY_TOKEN)
        val second = FakeSessionProcess().apply { emit(ContainerSession.READY_TOKEN) }
        val fakes = ArrayDeque(listOf(first, second))
        val session = session { _, _ -> fakes.removeFirst() }

        withTimeout(15_000) { session.start() }
        assertTrue("正常启动应该成功", session.state.value is SessionState.Running)
        assertFalse("正常启动必须记下挂载清单", session.mountedMounts.isEmpty())

        // 不调 stop()：先吐一行普通输出，再让流自己断（读线程走 onStreamClosed）
        first.emit("常驻服务还在跑的时候管道断了")
        first.closeStdout()

        assertTrue(
            "Running 期断流必须落 Error（实际：${session.state.value}）",
            awaitTrue(5_000) { session.state.value is SessionState.Error },
        )
        assertTrue(
            "Running 期断流必须释放本次挂载（实际：${session.mountedMounts}）",
            awaitTrue(5_000) { session.mountedMounts.isEmpty() },
        )

        // 句柄与状态没被残留污染：同一实例能再起一个新的
        withTimeout(15_000) { session.start() }
        assertTrue(
            "残留状态让第二次启动起不来（实际：${session.state.value}）",
            session.state.value is SessionState.Running,
        )
        assertFalse("第二次启动必须重新记下挂载清单", session.mountedMounts.isEmpty())
    }

    /** 等条件成立（有上界），返回是否成立 —— 让断言消息能带上实际值，而不是靠 sleep 猜时机。 */
    private suspend fun awaitTrue(timeoutMs: Long, cond: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!cond()) delay(10)
            true
        } ?: false
}
