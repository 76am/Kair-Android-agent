package dev.kair.app.core.proot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kair.app.app.KairApplication
import dev.kair.app.core.term.TerminalSnapshot
import dev.kair.app.core.tools.LocalCommandRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * U59 第 0 步真机冒烟（U78 起只有 proot 一档）。
 *
 * 跑的是产品真实路径：`PtySession.start()` → `SessionLaunchFactory` → native `openpty/execve`。
 * **不是**假 native 替身（V4）；它只能连真机跑：
 *
 * ```bash
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=dev.kair.app.core.proot.PtySmokeTest
 * ```
 *
 * 约束（负责人 2026-11-08）：手机上的 Kair 有重要数据 —— 这里**不做任何卸载 / 清数据**；
 * 冒烟只读写自己启动的 PTY，rootfs 复用当前 app 已装好的那份。
 */
@RunWith(AndroidJUnit4::class)
class PtySmokeTest {

    private val app: KairApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KairApplication

    private class Deps(
        val manager: RootfsManager,
        val proot: ProotCommandRunner,
    )

    private fun deps(): Deps {
        val container = app.container
        val exec = File(container.nativeLibraryDir, "libproot_exec.so")
        val loader = File(container.nativeLibraryDir, "libproot_loader.so")
        val loader32 = File(container.nativeLibraryDir, "libproot_loader32.so").takeIf { it.exists() }
        check(exec.exists() && loader.exists()) {
            "APK 里缺少 proot 二进制：${exec.absolutePath} / ${loader.absolutePath}"
        }
        val prootDir = File(container.storageRoot, "proot")
        val manager = RootfsManager(
            prootDir,
            container.primaryAbi,
            mirror = { ContainerMirror.fromId(container.settings.containerMirrorId) },
        )
        val local = LocalCommandRunner()
        val proot = ProotCommandRunner(
            manager = manager,
            prootExec = exec,
            loader = loader,
            loader32 = loader32,
            prootTmpDir = File(prootDir, "tmp"),
            fallback = local,
        )
        return Deps(manager, proot)
    }

    // ---- 冒烟 ----------------------------------------------------------------

    @Test(timeout = 600_000)
    fun proot_channel_pty_smoke() = runBlocking {
        smoke()
    }

    @Test(timeout = 600_000)
    fun repeated_open_close_leaves_no_fd_growth() = runBlocking {
        repeatedOpenClose()
    }

    @Test(timeout = 600_000)
    fun proot_child_environ_has_four_proot_vars() = runBlocking {
        val deps = deps()
        ensureRootfs(deps)
        val session = ContainerSession(
            deps.manager, { emptyList() }, deps.proot, { SessionChannel.PROOT },
        )
        val pty = PtySession(deps.manager, { emptyList() }, session) { SessionChannel.PROOT }
        try {
            pty.start()
            waitRunning(pty, "PROOT environ")
            val pid = pty.currentChildPid()
            val text = File("/proc/$pid/environ").let { f ->
                runCatching { f.readBytes().toString(Charsets.UTF_8) }.getOrNull()
            } ?: ""
            // U69：envp 只剩三个 PROOT_* 变量，l2s store 整个删掉。
            for (key in listOf("PROOT_LOADER=", "PROOT_LOADER_32=", "PROOT_TMP_DIR=")) {
                assertTrue("proot 子进程 environ 缺 $key（全文=${text.take(400)}）", text.contains(key))
            }
            assertFalse(
                "U69：environ 不该再有 PROOT_L2S_DIR（全文=${text.take(400)}）",
                text.contains("PROOT_L2S_DIR="),
            )
        } finally {
            pty.stop()
        }
    }

    private suspend fun repeatedOpenClose() {
        val deps = deps()
        ensureRootfs(deps)
        val session = ContainerSession(
            deps.manager, { emptyList() }, deps.proot, { SessionChannel.PROOT },
        )
        val before = fdCount()
        val counts = mutableListOf<Int>()
        repeat(5) { i ->
            val pty = PtySession(deps.manager, { emptyList() }, session) { SessionChannel.PROOT }
            pty.start()
            waitRunning(pty, "PROOT 第 ${i + 1} 次")
            val pid = pty.currentChildPid()
            pty.stop()
            assertZombieGone(pid, "PROOT 第 ${i + 1} 次")
            counts.add(fdCount())
        }
        val after = counts.last()
        // U71：判据只数 PTY 自有 fd（/dev/ptmx 与 /dev/pts/ 下），非 PTY 的 ashmem/ged/mali 抖动不计；
        // 稳定性语义不变：后三轮 ±1 且末值 ≤ 首值 +3。过滤与判定在 PtyFdLeak（JVM 单测已覆盖）。
        assertFalse(
            "PTY fd 泄漏：连开 5 次会话后 $before → $after；逐轮 PTY 计数=$counts；当前 fd 表=${fdList()}",
            PtyFdLeak.hasLeak(before, counts),
        )
    }

    /**
     * N1（回读）/ N2（Ctrl-C）/ N3（resize）/ 退出码。
     * 输出用带前缀的 `printf`，避免把 tty 回显（输入行本身）当成命令执行结果。
     */
    private suspend fun smoke() {
        val deps = deps()
        ensureRootfs(deps)

        val session = ContainerSession(
            deps.manager, { emptyList() }, deps.proot, { SessionChannel.PROOT },
        )
        val pty = PtySession(deps.manager, { emptyList() }, session) { SessionChannel.PROOT }
        try {
            pty.start(rows = 24, cols = 80)
            waitRunning(pty, "PROOT")

            // N1：回读
            pty.sendText("printf 'SMOKE1:%s\\n' ok\r")
            awaitText(pty, "SMOKE1:ok")

            // N2：Ctrl-C 中断 sleep 100（写 0x03 走 line discipline → SIGINT）
            pty.sendText("sleep 100\r")
            delay(500)
            pty.send(byteArrayOf(0x03))
            delay(300)
            pty.sendText("printf 'SMOKE2:%s\\n' alive\r")
            awaitText(pty, "SMOKE2:alive")
            assertTrue("Ctrl-C 后会话不再是 Running：${pty.state.value}", pty.state.value is PtyState.Running)

            // N3：resize 后 stty size 跟着变
            pty.sendText("printf 'SMOKE3:%s\\n' \"\$(stty size)\"\r")
            awaitText(pty, "SMOKE3:24 80")
            pty.resize(rows = 30, cols = 100)
            delay(300)
            pty.sendText("printf 'SMOKE3:%s\\n' \"\$(stty size)\"\r")
            awaitText(pty, "SMOKE3:30 100")

            // 退出码（D2-6 对 H 同样重要：子进程退出码要能取到）。
            pty.sendText("exit 7\r")
            val exited = withTimeout(20_000) {
                pty.state.first { it is PtyState.Exited || it is PtyState.Error }
            }
            assertTrue("期望 ${PtyState.Exited(7)}，实际 $exited", exited == PtyState.Exited(7))
        } finally {
            pty.stop()
        }
    }

    // ---- 辅助 ----------------------------------------------------------------

    private suspend fun ensureRootfs(deps: Deps) {
        if (deps.manager.isReady) return
        deps.manager.install()
        check(deps.manager.isReady) { "rootfs 安装失败：${deps.manager.state.value}（本机需先能在 Kair 里装好 Linux 工作区）" }
    }

    private suspend fun waitRunning(pty: PtySession, label: String) {
        val state = withTimeout(40_000) {
            pty.state.first { it is PtyState.Running || it is PtyState.Error || it is PtyState.Exited }
        }
        assertTrue("$label：PTY 未进入 Running，实际 $state", state is PtyState.Running)
    }

    private suspend fun awaitText(pty: PtySession, marker: String, timeoutMs: Long = 20_000) {
        try {
            withTimeout(timeoutMs) {
                pty.screen.first { snapshot -> snapshot.text().contains(marker) }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "等待 \"$marker\" 超时（${timeoutMs}ms）；state=${pty.state.value}；屏幕非空行=\n" +
                    pty.screen.value.text().lines().filter { it.isNotBlank() }.joinToString("\n"),
                e,
            )
        }
    }

    private fun TerminalSnapshot.text(): String = buildString {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = cell(r, c)
                if (!cell.continuation) append(cell.char)
            }
            append('\n')
        }
    }

    /** N4：close 之后子进程必须已被回收（pid 从 /proc 消失）。 */
    private suspend fun assertZombieGone(pid: Int, label: String) {
        if (pid <= 0) return
        repeat(20) {
            if (!File("/proc/$pid").exists()) return
            delay(50)
        }
        assertFalse("$label：close 后子进程 $pid 仍在（僵尸或被漏收）", File("/proc/$pid").exists())
    }

    private fun fdCount(): Int = PtyFdLeak.countPtyFds(fdTargets())

    /** U71：判据只数 PTY 自有 fd——读 fd 目标路径（canonicalPath），读不到的跳过。 */
    private fun fdTargets(): List<String> =
        File("/proc/self/fd").listFiles()?.mapNotNull { f ->
            runCatching { f.canonicalPath }.getOrNull()
        } ?: emptyList()

    /** 诊断信息不变：失败文案仍带全表 fd 列表（fd号->canonicalPath）。 */
    private fun fdList(): List<String> =
        File("/proc/self/fd").listFiles()?.mapNotNull { f ->
            runCatching { "${f.name}->${f.canonicalPath}" }.getOrNull()
        }?.sorted() ?: emptyList()

    @Test
    fun native_library_is_loadable() {
        // 只证明 .so 能加载、四个 JNI 符号能解析；真正的通路由上面两条通道验。
        val fd = runCatching { PtyNative.open(24, 80) }.getOrElse { error("kair_pty 未加载：$it") }
        assertTrue("openpty 返回 $fd", fd >= 0)
        PtyNative.close(fd)
    }

    // ---- 全屏应用与重启清屏（把 C 层「目视项」里能自动判的那几条做掉）----------------

    /**
     * `vi` 打开 → ESC → `:q!` → 回车 → 回到 shell。
     *
     * 真机反馈过「vim 退不出去，Ctrl-C 没反应」：那条路径的终点就是这条断言 ——
     * 只有在 vi 真的退出后，shell 才会执行下一行 `printf`。
     */
    @Test(timeout = 600_000)
    fun vi_opens_and_quits_back_to_shell() = runBlocking {
        withProotPty { pty ->
            pty.sendText("vi /tmp/kair-vi-smoke.txt\r")
            awaitViScreen(pty)
            pty.send(byteArrayOf(0x1B))
            delay(150)
            pty.sendText(":q!\r")
            delay(300)
            pty.sendText("printf 'VIQUIT:%s\\n' ok\r")
            awaitText(pty, "VIQUIT:ok")
        }
    }

    /** `top` 起来后按 `q` 退出：全屏程序刷新后 shell 仍能接命令（不花屏、不退不出去）。 */
    @Test(timeout = 600_000)
    fun top_runs_and_quits() = runBlocking {
        withProotPty { pty ->
            pty.sendText("top\r")
            awaitText(pty, "PID", timeoutMs = 30_000)
            pty.sendText("q")
            delay(300)
            pty.sendText("printf 'TOPQUIT:%s\\n' ok\r")
            awaitText(pty, "TOPQUIT:ok")
        }
    }

    /**
     * 同一实例 stop → start 后必须是白屏：上一轮 vi 的 `~` 行一条都不许留
     * （真机反馈「点重启会话还有 vim 画面」，根因是 `PtySession.start()` 没清网格）。
     */
    @Test(timeout = 600_000)
    fun restart_clears_the_vi_screen() = runBlocking {
        val deps = deps()
        ensureRootfs(deps)
        val session = ContainerSession(
            deps.manager, { emptyList() }, deps.proot, { SessionChannel.PROOT },
        )
        val pty = PtySession(deps.manager, { emptyList() }, session) { SessionChannel.PROOT }
        try {
            pty.start(rows = 24, cols = 80)
            waitRunning(pty, "restart-1")
            pty.sendText("vi /tmp/kair-vi-smoke.txt\r")
            awaitViScreen(pty)
            pty.stop()
            pty.start(rows = 24, cols = 80)
            waitRunning(pty, "restart-2")
            delay(500)
            val leftovers = pty.screen.value.text().lines().count { it.trim() == "~" }
            assertTrue("重启后仍有 $leftovers 行 vi 残影：\n${pty.screen.value.text()}", leftovers == 0)
            pty.sendText("printf 'RESTART:%s\\n' ok\r")
            awaitText(pty, "RESTART:ok")
        } finally {
            pty.stop()
        }
    }

    private suspend fun withProotPty(block: suspend (PtySession) -> Unit) {
        val deps = deps()
        ensureRootfs(deps)
        val session = ContainerSession(
            deps.manager, { emptyList() }, deps.proot, { SessionChannel.PROOT },
        )
        val pty = PtySession(deps.manager, { emptyList() }, session) { SessionChannel.PROOT }
        try {
            pty.start(rows = 24, cols = 80)
            waitRunning(pty, "proot")
            block(pty)
        } finally {
            pty.stop()
        }
    }

    /** vi 的空文件画面：连续 `~` 行（不像 shell 提示符里的单个 `~`）。 */
    private suspend fun awaitViScreen(pty: PtySession, timeoutMs: Long = 30_000) {
        try {
            withTimeout(timeoutMs) {
                pty.screen.first { snapshot -> snapshot.text().lines().count { it.trim() == "~" } >= 5 }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(
                "等待 vi 空文件画面（>=5 行 `~`）超时（${timeoutMs}ms）；state=${pty.state.value}；屏幕=\n" +
                    pty.screen.value.text().lines().filter { it.isNotBlank() }.joinToString("\n"),
                e,
            )
        }
    }
}
