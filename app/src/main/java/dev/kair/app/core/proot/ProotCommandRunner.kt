package dev.kair.app.core.proot

import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.TailTextBuffer
import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * proot 容器后端：`container_shell` 工具的命令跑在完整 Ubuntu Linux 用户态里
 * （git / python / apt 包管理器开箱即用，apt-get install 即装即用）。
 *
 * 职责边界（对应 架构与内核边界.md 第 9 节）：
 * - rootfs 未就绪时**透明回退**到 [fallback]（本地 shell），保证工具永远可用；
 * - 宿主「工作区父目录」按真实路径 bind 到容器内 `/projects`，项目是其下的真实子目录，不复制文件；
 * - 一次性执行模型：进程退出即回收（--kill-on-exit 保证容器内不残留子进程）。
 *
 * 路径全部由装配层注入，本类不感知 Android Context（core 不依赖 android.*）。
 */
class ProotCommandRunner(
    private val manager: RootfsManager,
    /** libproot_exec.so 的宿主绝对路径（nativeLibraryDir 下）。 */
    private val prootExec: File,
    /** libproot_loader.so 的宿主绝对路径。 */
    private val loader: File,
    /** 32 位 loader；设备上有则注入，无则不设 PROOT_LOADER_32。 */
    private val loader32: File? = null,
    /** proot 自身可写的临时目录（loader 注入文件落这里；不需要 guest 可见，故留在 rootfs 外）。 */
    private val prootTmpDir: File,
    /** rootfs 未就绪时的回退后端。 */
    private val fallback: CommandRunner,
    /** 是否把宿主共享存储 bind 到容器 [SdcardMounts.CONTAINER]（需已授权）。 */
    private val mountSdcard: () -> Boolean = { false },
    /** 全局回收站目录（宿主绝对路径）；不为 null 时 bind 到容器 [TrashMounts.CONTAINER]。 */
    private val trashDir: File? = null,
) : CommandRunner {

    override val id = "proot-ubuntu"
    override val displayName = "Linux 容器（Ubuntu）"
    override val description =
        "在 proot 容器内运行 Ubuntu Linux（自带 apt 包管理器，可安装 git / python 等）。" +
            "rootfs 未就绪时自动回退到本地 shell。"

    /** rootfs 就绪才真的进容器；未就绪时回退本地 shell，cwd 即宿主工作区路径。 */
    override val containerWorkspacePath: String? get() = if (manager.isReady) PROJECTS_ROOT else null

    /** 本工作区在容器里的真实 cwd（`/projects/<目录名>`），未进容器时为 null。 */
    override fun containerWorkspacePathFor(workspace: File): String? =
        if (manager.isReady) containerWorkspacePath(workspace) else null

    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
        if (!manager.isReady) return fallback.run(command, workspace, timeoutSeconds)
        return withContext(Dispatchers.IO) {
            prootTmpDir.mkdirs()
            val process = ProcessBuilder(buildArgs(command, workspace, timeoutSeconds))
                .directory(manager.rootfsDir.parentFile) // proot 自身在宿主上的 cwd，无实际语义
                .apply { environment().clear(); environment().putAll(buildEnv()) }
                .redirectErrorStream(true)
                .start()

            val output = TailTextBuffer()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            output.append(buf, n)
                        }
                    }
                } catch (_: Exception) {
                    // 读管道断裂（进程被杀）属正常路径，输出以已收到的为准
                }
            }.apply { isDaemon = true; start() }

            // 容器内 timeout(T) 负责正常超时路径（TERM 后 proot --kill-on-exit
            // 连子进程一起收）；宿主侧 T+宽限 才强杀——SIGKILL 掉 proot 会绕过
            // 它的退出清理，留下孤儿 tracee。
            val hardWaitSeconds = timeoutSeconds?.takeIf { it > 0 }?.let { it + HARD_KILL_GRACE_SECONDS }
            // 等待进程结束：包在 runInterruptible 里，使调用协程被取消（用户点停止）时
            // 能中断阻塞的 waitFor，从而在 finally 里强杀整个 proot —— 否则停止按钮
            // 只停流式输出，容器内的编译命令会继续跑。
            //
            // 轮询而不是一次 waitFor：每 400ms 看一次容器进程树 RSS 与设备可用内存，
            // 在系统 LMK 杀掉整个 app 之前主动中止（见 ContainerGuard）。
            val startedAt = System.currentTimeMillis()
            var wait: WaitResult? = null
            try {
                wait = runInterruptible { awaitProcess(process, hardWaitSeconds) }
            } finally {
                // 取消（未正常结束）时强杀 proot 进程树。SIGKILL 会绕过 proot 的退出清理，
                // 但 --kill-on-exit 下常规路径仍尽量由容器内 timeout 收尾；这里以「立刻
                // 停住」优先，连容器内残留子进程一并收掉。
                if (wait?.finished != true) process.killTree()
            }
            reader.join(1000)

            val exit = try { process.exitValue() } catch (_: Exception) { -1 }
            val kind = classifyExit(
                exitCode = exit,
                timedOut = wait?.timedOut == true,
                memoryAborted = wait?.memoryAborted == true,
            )
            val diagnostic = exitDiagnostic(
                kind = kind,
                exitCode = exit,
                peakRssKb = wait?.peakRssKb ?: 0L,
                durationMs = System.currentTimeMillis() - startedAt,
            )
            val raw = output.toString() + (diagnostic?.let { "\n\n$it" } ?: "")
            CommandOutput(raw, exit, timedOut = wait?.timedOut == true)
        }
    }

    /**
     * 容器内固定挂载点：宿主「工作区父目录」→ `/projects`（项目是其下的真实子目录）；
     * 共享存储（可选）→ `/sdcard`；全局回收站 → [TrashMounts.CONTAINER]。
     * 带超时且 rootfs 里有 busybox `timeout` 时在容器内包一层（TERM 主命令，
     * proot --kill-on-exit 负责收尾），宿主侧强杀只作脚本卡死的兜底。
     */
    internal fun buildArgs(command: String, workspace: File, timeoutSeconds: Long? = null): List<String> = buildList {
        addAll(baseArgs(workspaceMounts(workspace), containerWorkspacePath(workspace)))
        val timeout = timeoutSeconds?.takeIf { it > 0 }
        if (timeout != null && containerTimeoutAvailable()) {
            add("/usr/bin/timeout")
            add("-k"); add("5")
            add(timeout.toString())
        }
        add("/bin/sh")
        add("-c")
        add(command)
    }

    /**
     * 常驻会话入口：与 [buildArgs] 共用挂载与环境，但入口换成容器内的**长跑**循环脚本
     * （[RootCommandRunner.SESSION_ENTRY]），**不**包 timeout —— 会话本就该一直活着。
     * 结束由宿主经 stdin 下发停止帧；宿主停止时 proot 退出，`--kill-on-exit` 连容器内
     * 全部后代（含常驻的 python / node 服务）一并收掉。
     *
     * [mounts] 只有「工作区父目录 → `/projects`」一条；起始目录交给入口脚本 `cd`，
     * `-w` 固定 `/` —— 空清单（自定义存储没有父目录）时 rootfs 里没有 `/projects`，
     * `-w` 指向它 proot 会起不来。
     */
    internal fun buildSessionArgs(mounts: List<Mount>): List<String> = buildList {
        addAll(baseArgs(mounts, "/"))
        add("/bin/sh")
        add(RootCommandRunner.SESSION_ENTRY)
    }

    /**
     * 用户终端（PTY）档的 proot 参数：同一个 [baseArgs]，只换容器内命令。
     * 入口脚本的 `cd /projects` 在这里用一行 `-c` 代替 —— PTY 里 shell 必须交互式（`-i`）。
     */
    internal fun buildPtyArgs(mounts: List<Mount>): List<String> = buildList {
        addAll(baseArgs(mounts, "/"))
        add("/bin/sh")
        add("-c")
        add("cd $PROJECTS_ROOT 2>/dev/null || cd /; exec /bin/sh -i")
    }

    /** proot 语义参数 / bind 挂载 / 干净环境：一次性命令与常驻会话两条入口共用。 */
    private fun baseArgs(mounts: List<Mount>, startDir: String): List<String> = buildList {
        add(prootExec.absolutePath)
        // fake root：apt 等包管理器要求 uid 0
        add("--root-id")
        // 硬链接模拟：**无条件启用**（2026-11-16）。dpkg 每次运行都要
        // `link(status, status-old)`、覆盖已有文件还要建备份链接；未 root + SELinux
        // 的设备上这些全部 `Permission denied`，表现为「有的包能装、有的装不上」。
        // termux 的 proot-distro 与 OperitTerminalCore 都是这么跑的：由 proot 把每次
        // link 换成「符号链接 + 副本」，与设备策略无关。
        add("--link2symlink")
        // proot 退出时杀掉容器内全部子进程，避免残留
        add("--kill-on-exit")
        add("-r"); add(manager.rootfsDir.absolutePath)
        add("-w"); add(startDir)
        // 一次性命令与常驻会话都是「工作区父目录 → /projects」（见 Mount.kt）
        mounts.forEach { add("-b"); add("${it.hostPath.absolutePath}:${it.sessionPath}") }
        add("-b"); add("/dev")
        // devpts 是 /dev 的子挂载：proot 的单路径 bind 不含子挂载，容器里开 /dev/ptmx 会 ENODEV
        // （D2-0 / D2-6）。
        add("-b"); add("/dev/pts")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        if (mountSdcard()) {
            add("-b"); add("${SdcardMounts.HOST}:${SdcardMounts.CONTAINER}")
        }
        // 回收站：与工作区同宿主文件系统，容器里看到的 /trash 就是它（见 TrashMounts）。
        trashDir?.let {
            add("-b"); add("${it.absolutePath}:${TrashMounts.CONTAINER}")
        }
        // env -i：宿主环境（含 Android 的 LD_PRELOAD 等）绝不泄进容器
        add("/usr/bin/env")
        add("-i")
        ContainerEnv.pairs().forEach(::add)
        // 诊断标记：apt 入口的 `kair:` 自检行会把它打出来。
        add("KAIR_LINK2SYMLINK=1")
    }

    /** 容器内 busybox `timeout` 是否可用（每次 buildArgs 时 stat 一次，开销可忽略）。 */
    private fun containerTimeoutAvailable(): Boolean =
        File(manager.rootfsDir, "usr/bin/timeout").canExecute()

    /**
     * 在容器内启动一个**长跑**子进程（供 MCP stdio 等自管 stdin/stdout 的协议用）。
     *
     * 与 [run] 的区别：不合并 stderr、不限时、进程生命周期由调用方负责关闭。
     * rootfs 未就绪时返回 null，由调用方如实报错（绝不回退到本地 shell ——
     * 那会让「容器内命令」这个语义变成谎话）。
     */
    internal fun spawnProcess(shellCommand: String, workspace: File): Process? {
        if (!manager.isReady) return null
        prootTmpDir.mkdirs()
        return runCatching {
            ProcessBuilder(
                baseArgs(workspaceMounts(workspace), containerWorkspacePath(workspace)) +
                    listOf("/bin/sh", "-c", shellCommand),
            )
                .directory(manager.rootfsDir.parentFile)
                .apply {
                    environment().clear()
                    environment().putAll(buildEnv())
                }
                .start()
        }.getOrNull()
    }

    /**
     * 图形桌面档（U86 §4 E）：同一个 [baseArgs]，入口换成容器内启动脚本 ——
     * 桌面是**独立长跑进程**（与帧协议会话、PTY 并列的第三条通道），
     * 宿主持有这个 Process，`stop()` 时 killTree 连 Xvfb / jwm 一起收。
     */
    internal fun buildDesktopArgs(mounts: List<Mount>, script: String): List<String> = buildList {
        addAll(baseArgs(mounts, "/"))
        add("/bin/sh")
        add(script)
    }

    /**
     * 桌面的**旁路长跑命令**（输入通道等）：与桌面共用同一套 proot 环境，入口是 `sh -c`。
     * 与 [buildDesktopArgs] 的区别只在最后一个参数是命令串而不是脚本路径。
     */
    internal fun buildDesktopCommandArgs(mounts: List<Mount>, command: String): List<String> = buildList {
        addAll(baseArgs(mounts, "/"))
        add("/bin/sh")
        add("-c")
        add(command)
    }

    /** 以给定挂载清单跑一条一次性命令（桌面依赖安装：fetch/add 没有 120s 会话超时上限）。 */
    internal fun spawnOneShot(mounts: List<Mount>, command: String): Process = ProcessBuilder(
        baseArgs(mounts, "/") + listOf("/bin/sh", "-c", command),
    )
        .directory(manager.rootfsDir.parentFile)
        .apply {
            environment().clear()
            environment().putAll(buildEnv())
        }
        .redirectErrorStream(true)
        .start()

    /** 宿主侧 rootfs 目录（桌面脚本写进容器 `/tmp` 用；U86 §4 E）。 */
    internal val rootfsDir: File get() = manager.rootfsDir

    /** proot 宿主侧需要的环境（loader 定位 + 自身临时目录）。 */
    internal fun buildEnv(): Map<String, String> = buildMap {
        put("PROOT_LOADER", loader.absolutePath)
        loader32?.let { put("PROOT_LOADER_32", it.absolutePath) }
        put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
        // 不设 PROOT_L2S_DIR：l2s 扩展退回「副本放在源文件旁边」（`.l2s.<name>` 前缀，
        // 与 Operit / termux proot-distro 的默认形态一致）。U68 的不变量（store 必须在
        // rootfs 内）由构造保证——副本就在源文件所在目录里。真机 2026-11-16：设置
        // PROOT_L2S_DIR 时 `ln` 直接失败、报错与「store 目录缺失」逐字一致
        // （`groupadd: /etc/group.lock: No such file or directory`），目录存在性成了
        // 一条多余且验证不了的依赖；沙箱两种模式都实测 groupadd rc=0，去掉更稳。
    }

    companion object {
        /** 宿主侧强杀前的宽限期，给容器内 timeout + proot 退出清理留时间。 */
        internal const val HARD_KILL_GRACE_SECONDS = 15L
    }
}
