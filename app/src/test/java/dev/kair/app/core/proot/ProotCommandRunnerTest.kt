package dev.kair.app.core.proot

import dev.kair.app.core.assumeLinux
import dev.kair.app.core.tools.LocalCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * proot 命令后端：argv / env 构造（纯函数）与 rootfs 未就绪时的透明回退。
 * 真实容器执行不在 JVM 单测范围（需 Android 设备 + 原生库）。
 */
class ProotCommandRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(
        manager: RootfsManager = RootfsManager(tmp.newFolder("proot"), "x86_64"),
        loader32: File? = null,
        mountSdcard: () -> Boolean = { false },
        fallback: dev.kair.app.core.tools.CommandRunner = LocalCommandRunner("/bin/sh"),
        trashDir: File? = null,
    ): ProotCommandRunner = ProotCommandRunner(
        manager = manager,
        prootExec = tmp.newFile("libproot_exec.so").apply { writeText("fake") },
        loader = tmp.newFile("libproot_loader.so").apply { writeText("fake") },
        loader32 = loader32,
        prootTmpDir = tmp.newFolder("proot-tmp"),
        fallback = fallback,
        mountSdcard = mountSdcard,
        trashDir = trashDir,
    )

    // ---- argv 构造 -----------------------------------------------------------

    @Test
    fun `argv wires proot flags, binds and a clean guest env`() {
        val workspace = tmp.newFolder("ws")
        val base = workspace.parentFile!!
        val args = runner().buildArgs("git status", workspace)

        assertTrue(args[0].endsWith("libproot_exec.so"))
        // 容器语义参数
        assertTrue(args.contains("--root-id"))
        // 硬链接模拟无条件开启（2026-11-16）：dpkg 每次运行都要 link(status, status-old)，
        // SELinux 拒绝时「有的包能装、有的装不上」。termux proot-distro 与 Operit 都是这么跑的。
        assertTrue(args.contains("--link2symlink"))
        assertTrue("自检标记要如实反映已启用", args.contains("KAIR_LINK2SYMLINK=1"))
        assertTrue(args.contains("--kill-on-exit"))
        // 根与工作区父目录 bind：宿主真实路径 → 容器内 /projects
        assertTrue(args[args.indexOf("-r") + 1].endsWith("rootfs"))
        assertEquals("/projects/ws", args[args.indexOf("-w") + 1])
        assertTrue(args.contains("${base.absolutePath}:$PROJECTS_ROOT"))
        // 1 个父目录 bind + 4 个系统 bind（/dev /dev/pts /proc /sys）
        assertEquals(5, args.count { it == "-b" })
        assertTrue(args.contains("/dev") && args.contains("/proc") && args.contains("/sys"))
        // devpts 子挂载必须单独 bind（D2-0：只 bind /dev 时容器里开 /dev/ptmx 会 ENODEV）
        assertTrue(args.contains("/dev/pts"))
        // 宿主环境不泄进容器
        assertTrue(args.contains("/usr/bin/env"))
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("HOME=/root"))
        assertTrue(args.contains("CI=true"))
        assertTrue(args.contains("NO_COLOR=1"))
        assertTrue(args.contains("PAGER=cat"))
        // 命令本体经 argv 直传，不经 shell 拼接
        assertEquals("git status", args.last())
        assertEquals("/bin/sh", args[args.size - 3])
        assertEquals("-c", args[args.size - 2])
    }

    @Test
    fun `argv adds sdcard bind when enabled`() {
        val args = runner(mountSdcard = { true }).buildArgs("ls /sdcard", tmp.newFolder("ws"))

        // 工作区 + /dev /dev/pts /proc /sys + 共享存储 = 6 个 bind
        assertEquals(6, args.count { it == "-b" })
        assertTrue(args.contains("${SdcardMounts.HOST}:${SdcardMounts.CONTAINER}"))
        // 尾部结构不受影响
        assertEquals("ls /sdcard", args.last())
    }

    @Test
    fun `desktop sidecar command goes through sh -c not script path`() {
        // 2026-11-14：xdotool 输入通道与桌面脚本共用 baseArgs，但入口不同 ——
        // 命令串必须走 `sh -c`；误用 buildDesktopArgs 会把整条命令当成脚本路径，
        // 真机表现是 `xdotool -` 启动即退（画面能看、点哪都没反应）。
        val args = runner().buildDesktopCommandArgs(emptyList(), "export DISPLAY=:1; exec xdotool -")
        assertEquals("/bin/sh", args[args.size - 3])
        assertEquals("-c", args[args.size - 2])
        assertEquals("export DISPLAY=:1; exec xdotool -", args.last())
    }

    @Test
    fun `argv binds global trash and exposes KAIR_TRASH`() {
        val trash = tmp.newFolder("trash")
        val args = runner(trashDir = trash).buildArgs("echo hi", tmp.newFolder("ws"))

        // 工作区 + /dev /dev/pts /proc /sys + 回收站 = 6 个 bind；回收站挂在容器 /trash
        assertEquals(6, args.count { it == "-b" })
        assertTrue(args.contains("${trash.absolutePath}:${TrashMounts.CONTAINER}"))
        // 环境变量把容器内回收站路径交给模型（与 chroot 通道一致，见 ContainerEnv）
        assertTrue(args.contains("${TrashMounts.ENV}=${TrashMounts.CONTAINER}"))
    }

    @Test
    fun `pty argv starts an interactive shell with the same binds`() {
        val base = tmp.newFolder("pty-workspaces")
        val args = runner().buildPtyArgs(projectsMount(base))

        // 与常驻会话同一套 baseArgs：devpts 子挂载、干净环境、工作区绑定都在
        assertTrue(args.contains("/dev/pts"))
        assertTrue(args.contains("${base.absolutePath}:$PROJECTS_ROOT"))
        assertTrue(args.contains("/usr/bin/env") && args.contains("-i"))
        // 尾部是交互式 shell（PTY 里 vim/top 需要 tty）
        assertEquals("/bin/sh", args[args.size - 3])
        assertEquals("-c", args[args.size - 2])
        assertTrue(args.last().contains("cd $PROJECTS_ROOT"))
        assertTrue(args.last().contains("exec /bin/sh -i"))
    }

    @Test
    fun `argv exposes the proot channel for the model to self-check`() {
        val r = runner()
        val args = r.buildArgs("echo hi", tmp.newFolder("ws"))

        // 与 chroot 通道只差这一项：模型据此知道 `setsid` 在这里逃不出去（见 ContainerEnv.CHANNEL）。
        assertTrue("proot 的 env 表要带 KAIR_CHANNEL=proot", args.contains("KAIR_CHANNEL=proot"))
        // 常驻终端走同一张表（ContainerSession 用 buildSessionArgs）—— 不能只给一次性命令发变量。
        assertTrue(
            "会话 argv 也要带 KAIR_CHANNEL=proot",
            r.buildSessionArgs(projectsMount(tmp.newFolder("workspaces"))).contains("KAIR_CHANNEL=proot"),
        )
    }

    @Test
    fun `argv omits trash bind when not provided`() {
        val args = runner().buildArgs("echo hi", tmp.newFolder("ws"))

        assertFalse(args.any { it.endsWith(":${TrashMounts.CONTAINER}") })
    }

    @Test
    fun `argv wraps command with container timeout when available`() {
        val manager = RootfsManager(tmp.newFolder("proot"), "x86_64")
        // rootfs 里装上 busybox timeout
        val binDir = File(manager.rootfsDir, "usr/bin").apply { mkdirs() }
        File(binDir, "timeout").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

        val args = runner(manager = manager).buildArgs("sleep 999", tmp.newFolder("ws"), timeoutSeconds = 30)

        // 容器内 timeout 包在 /bin/sh 之外：timeout -k 5 30 sh -c cmd
        val at = args.indexOf("/usr/bin/timeout")
        assertTrue(at > 0)
        assertEquals("-k", args[at + 1])
        assertEquals("5", args[at + 2])
        assertEquals("30", args[at + 3])
        assertEquals("/bin/sh", args[at + 4])
        assertEquals("-c", args[at + 5])
        assertEquals("sleep 999", args[at + 6])
        // env 段在 timeout 之前（环境变量对 timeout 自身也生效）
        assertTrue(args.indexOf("-i") < at)
    }

    @Test
    fun `argv skips timeout wrap when rootfs lacks timeout binary`() {
        // 默认 manager 的 rootfs 目录为空 → 容器内无 timeout
        val args = runner().buildArgs("sleep 999", tmp.newFolder("ws"), timeoutSeconds = 30)

        assertFalse(args.contains("/usr/bin/timeout"))
        assertEquals("/bin/sh", args[args.size - 3])
        assertEquals("sleep 999", args.last())
    }

    @Test
    fun `session argv mounts the projects root and leaves the cwd to the entry script`() {
        val base = tmp.newFolder("workspaces")
        val args = runner().buildSessionArgs(projectsMount(base))

        // 与一次性命令相同的 proot 标志（挂载一次一直持有，服务得以常驻）
        assertTrue(args.contains("--root-id"))
        assertTrue(args.contains("--kill-on-exit"))
        // 起始目录由入口脚本 `cd /projects` 决定：空清单时 rootfs 里没有 /projects
        assertEquals("/", args[args.indexOf("-w") + 1])
        // 父目录 1 条 + /dev /dev/pts /proc /sys，且没有旧的单工作区固定点
        assertEquals(5, args.count { it == "-b" })
        assertTrue(args.contains("${base.absolutePath}:$PROJECTS_ROOT"))
        assertFalse(args.any { it.endsWith(":/workspace") })
        // 干净环境仍在（宿主环境不泄进容器）
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("HOME=/root"))
        // 入口是容器内常驻循环脚本：`sh <entry>`，不包 timeout、不经 `-c`
        assertFalse(args.contains("/usr/bin/timeout"))
        assertFalse(args.contains("-c"))
        assertEquals(RootCommandRunner.SESSION_ENTRY, args.last())
        assertEquals("/bin/sh", args[args.size - 2])
    }

    // ---- env 构造 ------------------------------------------------------------

    @Test
    fun `env points loaders and tmp dir`() {
        val env = runner().buildEnv()

        assertTrue(env.getValue("PROOT_LOADER").endsWith("libproot_loader.so"))
        assertTrue(env.getValue("PROOT_TMP_DIR").isNotEmpty())
        assertFalse(env.containsKey("PROOT_LOADER_32"))
        // l2s 副本放源文件旁边（不设 store 目录）：与 Operit / proot-distro 一致，
        // 少一条「目录必须存在」的依赖（真机 2026-11-16 那条依赖上模拟整体失效）。
        assertFalse("不该再设 PROOT_L2S_DIR", env.containsKey("PROOT_L2S_DIR"))
    }

    @Test
    fun `env creates no l2s store directory under rootfs`() {
        val manager = RootfsManager(tmp.newFolder("proot-l2s"), "x86_64")
        val env = runner(manager = manager).buildEnv()
        // 钉住「去掉 store 目录」这个决定本身：把 put / mkdirs 加回来必须变红。
        assertFalse("env 不该再有 store：$env", env.containsKey("PROOT_L2S_DIR"))
        assertFalse(
            "buildEnv 不该在 rootfs 下建 tmp/l2s：${manager.rootfsDir}",
            File(manager.rootfsDir, "tmp/l2s").exists(),
        )
        // PROOT_TMP_DIR 是 loader 注入文件，不需要 guest 可见，留在 rootfs 外是对的。
        assertFalse(
            File(env.getValue("PROOT_TMP_DIR")).canonicalPath
                .startsWith(manager.rootfsDir.canonicalPath + File.separator),
        )
    }

    @Test
    fun `link2symlink is always on`() {
        // 真机 2026-11-16（未 root + SELinux Enforcing，AVC 明确 deny link）：dpkg 的
        // status-old 与解包备份链接全部 Permission denied。termux proot-distro 与
        // OperitTerminalCore 都无条件带这个 flag，由 proot 把 link 换成「符号链接 + 副本」。
        val runner = runner(manager = RootfsManager(tmp.newFolder("proot-l2s-on"), "x86_64"))

        assertTrue(runner.buildArgs("true", tmp.newFolder("ws")).contains("--link2symlink"))
        // 面板里的 `kair:` 自检行会把这个标记打出来 —— 真机据此判断模拟是否真的启用。
        assertTrue(runner.buildArgs("true", tmp.newFolder("ws-2")).contains("KAIR_LINK2SYMLINK=1"))
    }

    @Test
    fun `env includes 32-bit loader when provided`() {
        val l32 = tmp.newFile("libproot_loader32.so")
        val env = runner(loader32 = l32).buildEnv()

        assertEquals(l32.absolutePath, env["PROOT_LOADER_32"])
    }

    // ---- 回退 ----------------------------------------------------------------

    @Test
    fun `delegates to fallback when rootfs not installed`() = runBlocking {
        assumeLinux("proot 通道")
        val ws = tmp.newFolder("ws")
        val out = runner().run("echo from-fallback", ws, timeoutSeconds = null)

        assertEquals("from-fallback\n", out.output)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun `delegates to fallback when abi unsupported`() = runBlocking {
        assumeLinux("proot 通道")
        val manager = RootfsManager(tmp.newFolder("proot"), abi = "armeabi-v7a")
        val ws = tmp.newFolder("ws")
        val out = runner(manager = manager).run("echo from-abi-fallback", ws, timeoutSeconds = null)

        assertEquals("from-abi-fallback\n", out.output)
    }

    @Test
    fun `runner metadata is stable`() {
        val r = runner()
        assertEquals("proot-ubuntu", r.id)
        // displayName / description 是稳定事实，不含逐回合易变状态（缓存安全）
        assertTrue(r.description.contains("Ubuntu"))
        assertFalse(r.description.contains("current") || r.description.contains("当前"))
    }
}
