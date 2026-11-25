package dev.kair.app.core.proot

import dev.kair.app.core.assumeLinux
import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * chroot 后端：提权脚本结构（挂载顺序 / trap 清理 / 路径转义 / sdcard 条件挂载）、
 * 命令文件内容、未就绪与未 root 的回退、探测结论（含超时不缓存）。
 * 真实 su / chroot 路径不测（依赖设备环境）。
 */
class RootCommandRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingRunner : CommandRunner {
        var calls = 0
        override val id = "recording"
        override val displayName = "recording"
        override val description = "recording"
        override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
            calls++
            return CommandOutput("rec", 0)
        }
    }

    private var readyManager: RootfsManager? = null

    /** 构造一个处于 Ready 状态的 RootfsManager（最小 tar 归档安装）。 */
    private fun readyManager(): RootfsManager {
        readyManager?.let { return it }
        val dir = tmp.newFolder("proot-${System.nanoTime()}")
        val m = RootfsManager(dir, "x86_64")
        val tar = TarWriter().apply {
            dir("./bin/")
            file("./bin/sh", "#!/bin/sh\n")
            dir("./tmp/")
            dir("./etc/")
        }
        val buf = ByteArrayOutputStream()
        GZIPOutputStream(buf).use { gz -> gz.write(tar.bytes()) }
        runBlocking { m.installFromArchive(ByteArrayInputStream(buf.toByteArray()), gzip = true) }
        readyManager = m
        return m
    }

    private fun runner(
        manager: RootfsManager = readyManager(),
        probe: RootProbeResult = RootProbeResult.GRANTED,
        mountSdcard: () -> Boolean = { false },
        fallback: RecordingRunner = RecordingRunner(),
        trashDir: File? = null,
    ): Triple<RootCommandRunner, RecordingRunner, RootfsManager> {
        val r = RootCommandRunner(
            manager = manager,
            fallback = fallback,
            suLocator = { "/fake/su" },
            rootProbe = { probe },
            mountSdcard = mountSdcard,
            trashDir = trashDir,
        )
        return Triple(r, fallback, manager)
    }

    /**
     * 一次性命令的清单形态：宿主工作区父目录 → `/projects`（唯一一条）。
     *
     * C1 起 `buildScript` 只认挂载清单；2026-10-30 起一次性命令与常驻会话用同一份清单，
     * 所以测试用这个助手把它写出来，避免每条用例重复 `workspaceMounts(...)`。
     */
    private fun RootCommandRunner.oneShotScript(
        workspace: File,
        timeoutSeconds: Long? = null,
        entry: String = RootCommandRunner.ENTRY_COMMAND,
        pidFile: File? = null,
    ): String = buildScript(workspaceMounts(workspace), timeoutSeconds, entry, pidFile)

    @Test
    fun `maintenance script carries protected commands into the reap section`() {
        val (r, _, _) = runner()
        val workspace = tmp.newFolder("ws")

        val withProtected = r.buildScript(
            workspaceMounts(workspace),
            entry = RootCommandRunner.SESSION_ENTRY,
            reapStale = ContainerEnv.instance,
            protected = listOf("python3 -m http.server 8080"),
        )
        assertTrue(
            "受保护命令行必须进维护脚本的清扫段（否则用户登记过的常驻服务会被一起清掉）",
            withProtected.contains("'python3 -m http.server 8080') continue"),
        )

        // 没有登记项时不生成 `case "$cmd" in`（空模式在 shell 里是语法错误）。
        val plain = r.buildScript(
            workspaceMounts(workspace),
            entry = RootCommandRunner.SESSION_ENTRY,
            reapStale = ContainerEnv.instance,
        )
        assertFalse("没有受保护项时不该生成空 case", plain.contains("case \"\$cmd\" in"))
    }

    @Test
    fun `maintenance script reaps stale processes before mounting`() {
        val (r, _, _) = runner()
        val workspace = tmp.newFolder("ws")

        val script = r.buildScript(
            workspaceMounts(workspace),
            entry = RootCommandRunner.SESSION_ENTRY,
            reapStale = ContainerEnv.instance,
        )

        val reap = script.indexOf("kair: reaping stale container process")
        val mount = script.indexOf("try_mount proc /proc")
        assertTrue("维护脚本必须清扫上一代 App 遗留的容器进程", reap > 0)
        // 关键顺序：清扫时还没 chroot，/proc 是宿主进程表；chroot 之后同一个 /proc
        // 也还是宿主表（容器没有 PID namespace），但那时脚本自己带上了 KAIR_CONTAINER，
        // 会把自己算进 victims —— 所以必须在挂载 / chroot 之前。
        assertTrue("清扫必须在挂载之前（否则会把自己算成上一代）", reap < mount)
        assertTrue("清扫要带上当前代际", script.contains("cur='${ContainerEnv.instance}'"))
        assertTrue("维护入口仍是持久循环脚本路径", script.contains(RootCommandRunner.SESSION_ENTRY))
    }

    @Test
    fun `maintenance script injects the single environment table`() {
        val (r, _, _) = runner()
        val workspace = tmp.newFolder("ws")

        val script = r.oneShotScript(workspace)

        // U78：只有一张环境表，通道值恒 proot（chroot 会话通道已删除，见 ADR 0007）。
        assertTrue(
            "一次性脚本要注入唯一的容器环境表",
            script.contains("KAIR_CHANNEL=proot"),
        )
    }

    @Test
    fun `plain one shot script has no reap section`() {
        val (r, _, _) = runner()
        val workspace = tmp.newFolder("ws")

        val script = r.oneShotScript(workspace)

        // 一次性命令的进程在每条结束时已被组收尾；多扫一遍 /proc（宿主上千条）是纯浪费。
        assertFalse("一次性命令不该带上一代清扫", script.contains("kair: reaping stale container process"))
        assertTrue(script.contains(RootCommandRunner.ENTRY_COMMAND))
    }

    @Test
    fun `script mounts idempotently and never unmounts`() {
        val (r, _, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"))

        // 幂等判据：一律 /proc/mounts —— Android toybox 的 mountpoint 对同 fs 的 bind 挂载
        // 误报「未挂」（真机实测 /projects 叠了 24 层），所以 script 里不得再出现 mountpoint。
        assertTrue(script.contains("already_mounted() {"))
        assertTrue(script.contains("grep -q \" \$R/\$1 \" /proc/mounts"))
        assertFalse("不得再用 mountpoint：bind 挂载会被误判为未挂", script.contains("mountpoint"))
        // 已挂直接跳过（不重挂、不记录）
        assertTrue(script.contains("already_mounted \"\$1\" && return 0"))
        // 关键：一次性脚本里一次 umount 都不能出现 —— 并发下会收掉别人正在用的挂载层。
        // 清理脚本是另一个函数（buildPurgeScript），不在这里。
        assertFalse("一次性脚本不得卸载挂载（并发下会拆掉别人的层）：\n$script", script.contains("umount"))
        assertFalse("trap cleanup 的回收语义已取消", script.contains("trap "))
        assertFalse(script.contains("MOUNTED"))
    }

    @Test
    fun `script mounts proc sys dev projects then chroots`() {
        val (r, _, mgr) = runner()
        val rootfs = mgr.rootfsDir.absolutePath
        val workspace = tmp.newFolder("ws")
        val base = workspace.parentFile!!

        val script = r.oneShotScript(workspace)

        // 挂载顺序：proc / sys / dev / dev/pts / projects，全部 -o bind
        val order = listOf(
            "try_mount proc /proc",
            "try_mount sys /sys",
            "try_mount dev /dev",
            // devpts 是 /dev 的子挂载：不单独 bind，容器里开 /dev/ptmx 会 ENODEV（D2-0）
            "try_mount dev/pts /dev/pts",
            "try_mount projects",
        )
        var last = -1
        order.forEach { line ->
            val at = script.indexOf(line)
            assertTrue("missing/unordered: $line", at > last)
            last = at
        }
        assertTrue(script.contains("mount -o bind"))
        // 不开 sdcard 挂载时不出现
        assertFalse(script.contains("try_mount sdcard"))
        // chroot 进 rootfs 前经容器内 env -i 重建干净环境（防宿主 Android PATH 泄漏）
        assertTrue(
            script.contains(
                "chroot \"\$R\" /usr/bin/env -i ${ContainerEnv.pairs().joinToString(" ")} /bin/sh /tmp/.kair-cmd",
            ),
        )
        // 无超时的脚本以 exec 接替容器入口（退出码由 exec 透传，不再需要 `exit $?`）
        assertTrue(script.contains("exec chroot \"\$R\""))
        // rootfs 路径已安全引注
        assertTrue(script.contains("R='${rootfs.replace("'", "'\\''")}'"))
        // 挂的是工作区**父目录**（整棵项目树），不是单个工作区
        assertTrue(script.contains("try_mount projects '${base.absolutePath.replace("'", "'\\''")}'"))
    }

    @Test
    fun `script sets umask 000 before chroot so root-created files stay writable`() {
        val (r, _, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"))

        // 真 root 下沿用 022 会产出 root:644，应用进程（另一 uid）能读不能写。
        assertTrue(script.contains("umask 000"))
        assertTrue(script.indexOf("umask 000") < script.indexOf("chroot "))
    }

    @Test
    fun `script writes its own pid first so host can kill the tree`() {
        val (r, _, _) = runner()
        val pidFile = tmp.newFile("run.pid")

        val script = r.oneShotScript(tmp.newFolder("ws"), pidFile = pidFile)

        // Android 运行时没有 Process.pid()，宿主拿不到 su 子进程的 pid；
        // 只能让脚本自己写。必须写在 mount 之前——mount 失败/卡死也要能收树。
        assertTrue(script.contains("echo \$\$ > '${pidFile.absolutePath}'"))
        assertTrue(script.indexOf("echo \$\$") < script.indexOf("try_mount proc"))
    }

    @Test
    fun `script omits pid write when no pidfile is given`() {
        val (r, _, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"))

        assertFalse(script.contains("echo \$\$"))
    }

    @Test
    fun `script without timeout runs chroot in foreground`() {
        val (r, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"), timeoutSeconds = null)

        // 无超时：直接前台执行，无看门狗
        assertFalse(script.contains("setsid"))
        assertFalse(script.contains("WATCH="))
        // T 变量存在但为空（宿主环境里 PATH 等不受影响）
        assertTrue(script.contains("T=''"))
    }

    @Test
    fun `script with timeout uses container-side watchdog`() {
        val (r, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"), timeoutSeconds = 60)

        assertTrue(script.contains("T='60'"))
        // setsid 让命令独立成组，超时先 TERM 后 KILL 整组（含子进程）
        assertTrue(script.contains("if command -v setsid"))
        assertTrue(script.contains("kill -TERM -- \"-\$CMD\""))
        assertTrue(script.contains("kill -KILL -- \"-\$CMD\""))
        assertTrue(script.contains("sleep 5"))
        // 主 shell wait 命令退出后收掉看门狗，退出码透传
        assertTrue(script.contains("wait \"\$CMD\""))
        assertTrue(script.contains("exit \$RC"))
        // 看门狗在挂载之后启动（挂完才能 chroot）
        assertTrue(script.indexOf("try_mount projects") < script.indexOf("WATCHDOG()"))
        // 看门狗必须是 1 秒粒度轮询（kill -0 探活），不能单次 sleep T：命令提前
        // 结束时孤儿 sleep 会拖住 Magisk/KernelSU 的 su 等到超时秒数才返回。
        assertTrue(script.contains("while [ \"\$ELAPSED\" -lt \"\$T\" ]"))
        assertTrue(script.contains("sleep 1 </dev/null >/dev/null 2>&1"))
        assertTrue(script.contains("kill -0 \"\$CMD\" 2>/dev/null || exit 0"))
        assertFalse(script.contains("sleep \"\$T\""))
        // 看门狗内所有 sleep 脱离 stdio：孤儿进程不得持有 su 的 pty/管道 fd
        assertTrue(script.contains("sleep 5 </dev/null >/dev/null 2>&1"))
        // 超时提示仍走真实 stderr（真超时发生时主脚本还活着，消息可见）
        assertTrue(script.contains("echo \"kair-chroot: timed out after \${T}s, killing\" >&2"))
    }

    @Test
    fun `script reaps the command process group after normal exit`() {
        val (r, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"), timeoutSeconds = 60)

        // 正常结束也要收组：`cmd &` / `nohup` 启动的服务不是 wait 的直接子进程，
        // 主 shell 退出后会被 reparent；必须在 wait 之后按 setsid 组 TERM→KILL。
        val wait = script.indexOf("wait \"\$CMD\"")
        assertTrue("脚本缺少 wait CMD：$script", wait >= 0)
        val term = script.indexOf("kill -TERM -- \"-\$CMD\"", wait)
        val kill = script.indexOf("kill -KILL -- \"-\$CMD\"", wait)
        assertTrue("正常退出路径没有 TERM 进程组", term > wait)
        assertTrue("正常退出路径没有 KILL 进程组", kill > term)
        // 收组前必须留一行说明：调用方拿到的是 RC=0，不打这行，「服务怎么没了」无从查起；
        // 同时告诉 agent 常驻进程要用 setsid 逃逸（bash 工具说明里也有同样指引）。
        val note = script.indexOf("reaped them", wait)
        assertTrue("收组没留说明（应 echo 到 stderr）", note > wait && note < term)
        assertTrue("收组说明应指向 setsid 出口", script.substring(note, term).contains("setsid"))
        // 没有 setsid 时 $CMD 可能和脚本同组，绝不能按组杀（会杀掉 su / 自己）。
        assertTrue(script.contains("setsid "))
        assertTrue(script.contains("& GROUP=1"))
        assertTrue(script.contains("& GROUP=0"))
        assertTrue(script.contains("if [ \"\$GROUP\" = 1 ] && kill -0 -- \"-\$CMD\""))
    }

    @Test
    fun `script mounts sdcard after workspace when enabled`() {
        val (r, _, _) = runner(mountSdcard = { true })

        val script = r.oneShotScript(tmp.newFolder("ws"))

        // 挂在 workspace 之后、chroot 执行之前。
        // 注意不能用 indexOf("chroot")：try_mount 函数体里的 "kair-chroot:" 更早。
        assertTrue(script.contains("try_mount sdcard ${SdcardMounts.HOST}"))
        assertTrue(script.indexOf("try_mount workspace") < script.indexOf("try_mount sdcard"))
        assertTrue(script.indexOf("try_mount sdcard") < script.indexOf("chroot \"\$R\""))
    }

    @Test
    fun `script mounts global trash outside workspace when provided`() {
        val trash = tmp.newFolder("trash")
        val (r, _, _) = runner(trashDir = trash)

        val script = r.oneShotScript(tmp.newFolder("ws"))

        // 挂载点建在 rootfs 内（$R/trash），宿主回收站 bind 过去 —— 工作区/仓库里
        // 不会出现任何回收站目录。挂在 workspace 之后、chroot 之前。
        val line = "try_mount ${TrashMounts.CONTAINER.removePrefix("/")} ${RootCommandRunner.shellQuote(trash.absolutePath)}"
        assertTrue(script.contains(line))
        assertTrue(script.indexOf("try_mount workspace") < script.indexOf("try_mount trash"))
        assertTrue(script.indexOf("try_mount trash") < script.indexOf("chroot \"\$R\""))
        // chroot 内环境仍注入 KAIR_TRASH（与 proot 通道一致）
        assertTrue(script.contains("${TrashMounts.ENV}=${TrashMounts.CONTAINER}"))
    }

    @Test
    fun `script omits trash mount when not provided`() {
        val (r, _, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"))

        assertFalse(script.contains("try_mount trash"))
    }

    @Test
    fun `script with session entry runs the long-running loop inside chroot`() {
        val (r, _) = runner()

        val script = r.oneShotScript(tmp.newFolder("ws"), entry = RootCommandRunner.SESSION_ENTRY)

        // chroot 内入口换成常驻循环脚本；一次性命令路径不再出现
        assertTrue(script.contains("/bin/sh ${RootCommandRunner.SESSION_ENTRY}"))
        assertFalse(script.contains("/tmp/.kair-cmd"))
        // 会话不做一次性超时：无看门狗（会话本就该一直活着）
        assertFalse(script.contains("WATCHDOG()"))
        assertFalse(script.contains("setsid"))
        // 会话与一次性命令共享幂等挂载段：退出不卸挂载
        assertTrue(script.contains("already_mounted"))
        assertFalse(script.contains("umount"))
    }

    @Test
    fun `script mounts every entry of the list in order and nothing else`() {
        val (r, _, _) = runner()
        val a = tmp.newFolder("pa")
        val b = tmp.newFolder("pb")
        val mounts = listOf(Mount("$PROJECTS_ROOT/pa", a), Mount("$PROJECTS_ROOT/pb", b))

        val script = r.buildScript(mounts)

        // 清单里有几条就挂几条，顺序 = 清单顺序；嵌套挂载点用相对路径拼在 $R 下
        assertTrue(script.contains("try_mount projects/pa '${a.absolutePath}'"))
        assertTrue(script.contains("try_mount projects/pb '${b.absolutePath}'"))
        assertTrue(script.indexOf("try_mount dev") < script.indexOf("try_mount projects/pa"))
        assertTrue(script.indexOf("try_mount projects/pa") < script.indexOf("try_mount projects/pb"))
        // 不再有单工作区固定点 `/workspace`（C1 明确不保留兼容层）
        assertFalse(script.contains("try_mount workspace"))
    }

    @Test
    fun `suExecutable locates su without probing root`() {
        val (r, _, _) = runner() // suLocator = { "/fake/su" }

        assertEquals("/fake/su", r.suExecutable())
        // 只定位、不探测：rootState 保持未检测
        assertNull(r.rootState.value)

        val missing = RootCommandRunner(
            manager = readyManager(),
            fallback = RecordingRunner(),
            suLocator = { null },
        )
        assertNull(missing.suExecutable())
    }

    @Test
    fun `script escapes single quotes in workspace path`() {
        val (r, _) = runner()
        val base = tmp.newFolder("it's a base")
        val workspace = File(base, "ws").apply { mkdirs() }

        val script = r.oneShotScript(workspace)

        assertTrue(script.contains("try_mount projects '") && script.contains("'\\''"))
        // 拼接结果可被 POSIX sh 正确还原：单引号闭合-转义-重开（挂的是工作区父目录）
        assertTrue(script.contains("try_mount projects '${base.absolutePath.replace("'", "'\\''")}'"))
    }

    @Test
    fun `rootfs not ready falls back without probing root`() = runBlocking {
        val notInstalled = RootfsManager(tmp.newFolder("empty-${System.nanoTime()}"), "x86_64")
        val (r, fallback, _) = runner(manager = notInstalled, probe = RootProbeResult.GRANTED)

        val out = r.run("echo hi", tmp.newFolder("ws2"), null)

        assertEquals(1, fallback.calls)
        assertEquals("rec", out.output)
        assertNull(r.rootState.value)
        assertNull(r.probeResult.value)
    }

    @Test
    fun `no root falls back to local shell`() = runBlocking {
        val (r, fallback, _) = runner(probe = RootProbeResult.DENIED)

        val out = r.run("echo hi", tmp.newFolder("ws3"), null)

        assertEquals(1, fallback.calls)
        assertEquals("rec", out.output)
        assertEquals(false, r.rootState.value)
        assertEquals(RootProbeResult.DENIED, r.probeResult.value)
    }

    @Test
    fun `no su binary reports NO_SU without probing`() = runBlocking {
        val fallback = RecordingRunner()
        val r = RootCommandRunner(
            manager = readyManager(),
            fallback = fallback,
            suLocator = { null },
        )

        r.ensureRootChecked()

        assertEquals(RootProbeResult.NO_SU, r.probeResult.value)
        assertEquals(false, r.rootState.value)
    }

    @Test
    fun `timeout is not cached and next check re-probes`() = runBlocking {
        var result = RootProbeResult.TIMEOUT
        val r = RootCommandRunner(
            manager = readyManager(),
            fallback = RecordingRunner(),
            suLocator = { "/fake/su" },
            rootProbe = { result },
        )

        r.ensureRootChecked()
        assertEquals(RootProbeResult.TIMEOUT, r.probeResult.value)
        assertNull(r.rootState.value)

        // 超时后用户终于在弹窗上点了允许：无需 reset，下条命令自然重测
        result = RootProbeResult.GRANTED
        r.ensureRootChecked()
        assertEquals(RootProbeResult.GRANTED, r.probeResult.value)
        assertEquals(true, r.rootState.value)
    }

    @Test
    fun `resetRootCheck allows re-probe`() = runBlocking {
        var result = RootProbeResult.DENIED
        val r = RootCommandRunner(
            manager = readyManager(),
            fallback = RecordingRunner(),
            suLocator = { "/fake/su" },
            rootProbe = { result },
        )

        r.ensureRootChecked()
        assertEquals(false, r.rootState.value)

        // 用户在管理器里授完权，手动重测
        result = RootProbeResult.GRANTED
        r.resetRootCheck()
        r.ensureRootChecked()
        assertEquals(true, r.rootState.value)
    }

    @Test
    fun `locateSu resolves relative names via PATH and falls back to absolute candidates`() {
        assumeLinux("PATH 解析语义（which / 可执行位）")
        val binDir = tmp.newFolder("bin")
        File(binDir, "su").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

        // 相对名沿给定 PATH 串展开
        assertEquals(
            File(binDir, "su").absolutePath,
            RootCommandRunner.locateInPath("su", "/nonexistent:${binDir.absolutePath}"),
        )
        assertNull(RootCommandRunner.locateInPath("su", "/nonexistent"))
        assertNull(RootCommandRunner.locateInPath("su", null))

        // 绝对路径候选直接检查可执行位
        val su = tmp.newFile("su").apply { setExecutable(true) }
        assertEquals(su.absolutePath, RootCommandRunner.locateSu(listOf("/nonexistent/su", su.absolutePath)))
        assertNull(RootCommandRunner.locateSu(listOf("/nonexistent/su")))
    }

    @Test
    fun `shellQuote handles apostrophes`() {
        assertEquals("'/a/b'", RootCommandRunner.shellQuote("/a/b"))
        assertEquals("'/a'\\''b'", RootCommandRunner.shellQuote("/a'b"))
    }

    /** 最小 tar 构造器（与 RootfsManagerTest 同构）。 */
    private class TarWriter {
        private val out = ByteArrayOutputStream()

        private fun header(name: String, type: Char, size: Int, mode: Int) {
            val h = ByteArray(512)
            fun str(off: Int, len: Int, s: String) {
                System.arraycopy(s.toByteArray(Charsets.UTF_8).copyOf(len), 0, h, off, len)
            }
            fun oct(off: Int, len: Int, v: Long) = str(off, len - 1, v.toString(8).padStart(len - 1, '0'))
            str(0, 100, name)
            oct(100, 8, mode.toLong()); oct(108, 8, 0); oct(116, 8, 0)
            oct(124, 12, size.toLong()); oct(136, 12, 0)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            str(257, 6, "ustar"); h[263] = '0'.code.toByte()
            oct(148, 8, h.sumOf { it.toInt() and 0xFF }.toLong())
            out.write(h)
        }

        fun file(path: String, content: String) {
            header(path, '0', content.length, 420)
            val data = content.toByteArray().copyOf((content.length + 511) / 512 * 512)
            out.write(data)
        }

        fun dir(path: String) = header(path, '5', 0, 493)

        fun bytes(): ByteArray = out.toByteArray() + ByteArray(1024)
    }
}
