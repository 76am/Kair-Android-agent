package dev.kair.app.core.proot

import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.TailTextBuffer
import dev.kair.app.core.tools.killTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 一次 root 探测的结论。 */
enum class RootProbeResult {
    /** su 可用且已授权（uid 0）。 */
    GRANTED,

    /** 全部候选路径都找不到 su：设备未 root，或 Root 管理器对本应用隐藏了 su。 */
    NO_SU,

    /** su 存在但执行被拒 / 输出非 0：Root 管理器拒绝了授权。 */
    DENIED,

    /** 等待授权超时：弹窗可能仍挂着或被用户错过；不缓存，下次再试。 */
    TIMEOUT,
}

/**
 * 真 root 维护后端（U78 起**不再**是会话通道，见 ADR 0007）。
 *
 * 继续负责需要真 root 的内部动作：挂载 bind 脚本、工作区权限迁移（[runAsRoot]）、
 * 显式提权。会话 / 终端 / 后台任务都不再经过本类 —— 它们只有 proot 一档。
 *
 * 未 root 时由 [ContainerRunner] 的旧路由交给 proot 的历史已结束；本类的 [run] 仅供
 * 维护动作与单测使用，[fallback] 仍保证未 root 时不崩（回退本地 shell）。
 *
 * root 探测：su 不一定在应用 PATH 上（部分管理器装在 /system/xbin 等），先按候选表定位再执行；
 * 结论细分（未找到 / 拒绝 / 超时）供面板如实告知用户原因，超时不缓存以免错过弹窗后永远判负。
 *
 * 路径全部由装配层注入，本类不感知 Android Context（core 不依赖 android.*）。
 */
class RootCommandRunner(
    private val manager: RootfsManager,
    /** rootfs 未就绪 / 未 root 时的回退后端。 */
    private val fallback: CommandRunner,
    /** su 候选：PATH 内的名字 + 常见绝对路径，首个可执行者生效。 */
    private val suCandidates: List<String> = DEFAULT_SU_CANDIDATES,
    /** 定位 su 可执行文件，返回实际路径；可注入替换（单测）。 */
    private val suLocator: (List<String>) -> String? = ::locateSu,
    /** root 检测函数（入参为定位到的 su 路径）；可注入替换（单测）。 */
    private val rootProbe: (String) -> RootProbeResult = ::probeRoot,
    /** 容器内是否挂载手机共享存储（[SdcardMounts.CONTAINER]）。 */
    private val mountSdcard: () -> Boolean = { false },
    /** 全局回收站目录（宿主绝对路径）；不为 null 时 bind 到容器 [TrashMounts.CONTAINER]。 */
    private val trashDir: File? = null,
) : CommandRunner {

    override val id = "root-chroot"
    override val displayName = "Linux 容器（Ubuntu · root 维护）"
    override val description =
        "在真实 root 域内运行 Ubuntu Linux 维护命令（挂载 / 权限迁移 / 显式提权；" +
            "U78 起不再作为会话通道）。需要设备已 root；rootfs 未就绪或未 root 时回退本地 shell。"

    /** 进容器时返回项目根挂载点 `/projects`（非空即通道生效）；否则 null。 */
    override val containerWorkspacePath: String?
        get() = if (manager.isReady && _rootState.value == true) PROJECTS_ROOT else null

    /** 本工作区在容器里的真实 cwd（`/projects/<目录名>`），未进容器时为 null。 */
    override fun containerWorkspacePathFor(workspace: File): String? =
        if (manager.isReady && _rootState.value == true) containerWorkspacePath(workspace) else null

    /** null = 尚未检测；true/false = 本次进程内的检测结果。 */
    val rootState: StateFlow<Boolean?> get() = _rootState.asStateFlow()
    private val _rootState = MutableStateFlow<Boolean?>(null)

    /** 最近一次探测的细分结论（面板展示原因用）；null = 尚未探测。 */
    val probeResult: StateFlow<RootProbeResult?> get() = _probeResult.asStateFlow()
    private val _probeResult = MutableStateFlow<RootProbeResult?>(null)

    @Volatile
    private var checked = false

    /** 探测发现的 su 路径；run 时复用，避免二次定位。 */
    @Volatile
    private var suPath: String? = null

    /**
     * 检测一次 root 可用性。GRANTED / NO_SU / DENIED 缓存到进程生命周期内
     * （不重复弹授权框）；TIMEOUT 不缓存 —— 授权弹窗可能还挂着，下条命令
     * 再给用户一次机会。只在 IO 线程调用。
     */
    suspend fun ensureRootChecked() = withContext(Dispatchers.IO) {
        if (!checked) {
            val su = runCatching { suLocator(suCandidates) }.getOrNull()
            val result = if (su == null) {
                RootProbeResult.NO_SU
            } else {
                suPath = su
                runCatching { rootProbe(su) }.getOrDefault(RootProbeResult.DENIED)
            }
            _probeResult.value = result
            _rootState.value = when (result) {
                RootProbeResult.GRANTED -> true
                RootProbeResult.TIMEOUT -> null
                else -> false
            }
            if (result != RootProbeResult.TIMEOUT) checked = true
        }
    }

    /** 清除检测缓存（用户刚在 Root 管理器授完权后手动重测用）。 */
    fun resetRootCheck() {
        checked = false
        _rootState.value = null
        _probeResult.value = null
    }

    /**
     * 以真 root 执行一条**宿主侧**命令（不 chroot、不挂宿主目录），返回输出与退出码。
     *
     * 找不到 su 时返回 null —— 调用方必须把这当成「做不到」，不能拿容器后端的假 root 顶替
     * （U78：工作区权限修复曾因此在 proot 档报「已修复」）。[timeoutSeconds] 到期会先
     * `killTree` 再返回 `timedOut = true`。
     */
    suspend fun runAsRoot(command: String, timeoutSeconds: Long = ROOT_COMMAND_TIMEOUT_SECONDS): CommandOutput? =
        withContext(Dispatchers.IO) {
            val su = suExecutable() ?: return@withContext null
            val process = runCatching {
                ProcessBuilder(su, "-c", command)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(System.getenv())
                    }
                    .start()
            }.getOrNull() ?: return@withContext null
            val output = TailTextBuffer()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            output.append(buf, n)
                        }
                    }
                } catch (_: Exception) {
                    // 管道断裂以已收到的输出为准
                }
            }.apply { isDaemon = true; start() }
            val finished = runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
            if (!finished) process.killTree()
            reader.join(1000)
            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            CommandOutput(output.toString(), exit, timedOut = !finished)
        }

    /**
     * 已定位的 su 可执行文件路径；尚未定位过就按候选表现定位一次
     * （只做定位检查，**不**执行探测、不弹授权框）。常驻会话需要先拿到 su
     * 才能起长跑进程，故独立暴露；探测结果仍以 [rootState] / [probeResult] 为准。
     */
    fun suExecutable(): String? = suPath ?: runCatching { suLocator(suCandidates) }.getOrNull()

    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput {
        if (!manager.isReady) return fallback.run(command, workspace, timeoutSeconds)
        ensureRootChecked()
        if (_rootState.value != true) return fallback.run(command, workspace, timeoutSeconds)
        return withContext(Dispatchers.IO) {
            // 每次执行用唯一路径：固定名在并发 container_shell（主会话 + 子智能体）时会互相覆盖，
            // A 可能执行 B 写入的命令、用 B 的工作区挂载。
            val token = UUID.randomUUID().toString().take(8)
            val entry = "$ENTRY_COMMAND-$token"
            val entryFile = File(manager.rootfsDir, entry.removePrefix("/")).apply {
                parentFile?.mkdirs()
                // cwd 是项目在 /projects 下的真实子目录；目录名来自项目名，可能含空格 / 引号。
                writeText("cd ${shellQuote(containerWorkspacePath(workspace))}\n$command\n")
            }
            val scriptDir = manager.rootfsDir.parentFile!!.resolve("tmp").apply { mkdirs() }
            val script = scriptDir.resolve("$SCRIPT_NAME-$token")
            // 脚本自己把 pid 写给宿主：Android 运行时没有 Process.pid()，宿主取不到 su 子进程
            // 的 pid 就无法让 root 递归收树（编译 / 下载进程会残留并占住挂载点）。
            val pidFile = scriptDir.resolve("$SCRIPT_NAME-$token.pid")
            // 一次性命令与常驻会话挂同一份：宿主工作区父目录 → /projects（见 Mount.kt）。
            val mounts = workspaceMounts(workspace)
            script.writeText(buildScript(mounts, timeoutSeconds, entry, pidFile))

            val su = suPath ?: runCatching { suLocator(suCandidates) }.getOrNull()
            if (su == null) {
                entryFile.delete()
                script.delete()
                return@withContext fallback.run(command, workspace, timeoutSeconds)
            }
            val process: Process = try {
                ProcessBuilder(su, "-c", "sh ${shellQuote(script.absolutePath)}")
                    .directory(manager.rootfsDir.parentFile)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                entryFile.delete()
                script.delete()
                // 定位到的 su 在执行期被拒（SELinux / 管理器策略变化）：如实报告而非崩溃
                return@withContext CommandOutput("kair-chroot: 无法启动 su（$su）：${e.message}", 126)
            }

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

            // 容器侧看门狗在 T 秒杀命令；宿主侧再多等一段宽限期，
            // 只在脚本本身卡死（进程不收尾）时才强杀兜底。
            val hardWaitSeconds = timeoutSeconds?.takeIf { it > 0 }?.let { it + SCRIPT_GRACE_SECONDS }
            // 等待进程结束：包在 runInterruptible 里，使调用协程被取消（用户点停止）时
            // 能中断阻塞的 waitFor，从而在 finally 里强杀 su 整棵进程树 —— 否则停止按钮
            // 只停流式输出，chroot 里的编译命令会继续跑。
            var finished = false
            try {
                finished = runInterruptible {
                    if (hardWaitSeconds != null) {
                        process.waitFor(hardWaitSeconds, TimeUnit.SECONDS)
                    } else {
                        process.waitFor(); true
                    }
                }
            } finally {
                // 取消 / 超时（未正常结束）时强杀进程树。chroot 里的命令以 root 运行，
                // 应用进程无权直接 SIGKILL，先让 su 以 root 收掉本次进程树（下载中的
                // curl / wget 也在这里），再走通用 killTree 兜底。挂载常驻，这里不卸。
                if (!finished) {
                    val rootPid = readPidFile(pidFile)
                    killRootDescendants(rootPid, su)
                    process.killTree(rootPid)
                }
            }
            reader.join(1000)

            val raw = output.toString()
            val exit = try { process.exitValue() } catch (_: Exception) { -1 }
            // 唯一路径的临时文件用完即删，避免 rootfs /tmp 越积越多。
            entryFile.delete()
            script.delete()
            pidFile.delete()
            CommandOutput(raw, exit, timedOut = !finished)
        }
    }

    /**
     * 真 root 命令的取消清理：命令以 root 运行，应用进程无权直接 SIGKILL。
     *
     * root 脚本只递归杀掉本次进程树（含下载中的 curl / wget），**不**碰挂载。
     * 最多等 [ROOT_KILL_WAIT_MS]，超时则强杀卡住的 su。
     */
    private fun killRootDescendants(pid: Long, su: String) {
        if (pid <= 0L) return
        val scriptDir = manager.rootfsDir.parentFile?.resolve("tmp")?.apply { mkdirs() } ?: return
        // 唯一文件名：主会话与子智能体同时取消时不会互相截断脚本。
        val script = File(scriptDir, "kill-tree-${UUID.randomUUID().toString().take(8)}.sh")
        runCatching { script.writeText(killTreeScriptBody()) }
        val procRef = java.util.concurrent.atomic.AtomicReference<Process?>()
        // 让 root 自己递归遍历 /proc：应用进程受 SELinux 限制可能看不到 root 的子进程。
        val worker = Thread {
            try {
                val proc = ProcessBuilder(su, "-c", "sh ${shellQuote(script.absolutePath)} $pid")
                    .redirectErrorStream(true)
                    .start()
                procRef.set(proc)
                proc.waitFor(ROOT_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            } finally {
                script.delete()
            }
        }.apply { isDaemon = true }
        worker.start()
        runCatching { worker.join(ROOT_KILL_WAIT_MS + 500) }
        // su 卡在授权弹窗 / 脚本卡死时别留下一个常驻进程。
        procRef.get()?.let { proc ->
            if (runCatching { proc.isAlive }.getOrDefault(false)) {
                runCatching { proc.destroyForcibly() }
            }
        }
    }

    /**
     * U60：宿主侧后台任务的收尾 —— 让 root 递归收掉 [rootPid] 的整棵树。
     *
     * 后台任务以 root 运行，应用进程无权直接 SIGKILL；必须经 su 让 root 自己遍历 /proc。
     * 与一次性命令共用 [killRootDescendants]，不另写一套收树脚本。
     *
     * U78 起后台任务只有 proot 一档，本方法不再有调用点；保留是为了维护动作（ADR 0007
     * 的「显式提权」）不重新长第二套收树逻辑。
     */
    internal suspend fun killDetachedTree(rootPid: Long) {
        if (rootPid <= 0L) return
        withContext(Dispatchers.IO) {
            val su = suPath ?: runCatching { suLocator(suCandidates) }.getOrNull() ?: return@withContext
            killRootDescendants(rootPid, su)
        }
    }

    /** 读脚本写入的 pidfile；尚未写入 / 内容损坏时返回 0（调用方按「pid 未知」处理）。 */
    private fun readPidFile(file: File): Long =
        runCatching { file.readText().trim().toLong() }.getOrDefault(0L)

    /** root 侧递归杀进程脚本；`$1` = su PID。挂载常驻，这里不顺带 umount。 */
    private fun killTreeScriptBody(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# Generated by Kair RootCommandRunner; kill a process tree.")
        appendLine("kill_tree() {")
        appendLine("  for c in /proc/[0-9]*; do")
        appendLine("    p=\${c#/proc/}")
        appendLine("    while read -r line; do")
        appendLine("      rest=\${line##*') '}")
        appendLine("      ppid=\${rest#* }")
        appendLine("      ppid=\${ppid%% *}")
        appendLine("      [ \"\$ppid\" = \"\$1\" ] && kill_tree \"\$p\"")
        appendLine("    done < \"\$c/stat\" 2>/dev/null")
        appendLine("  done")
        appendLine("  kill -9 \"\$1\" 2>/dev/null")
        appendLine("}")
        appendLine("kill_tree \"\$1\"")
        appendLine("exit 0")
    }

    /**
     * 生成提权脚本：幂等 bind 挂载伪文件系统与 [mounts] → chroot 执行。
     *
     * - 挂载顺序是**契约**：`proc` / `sys` / `dev` → [mounts]（宿主工作区父目录 →
     *   `/projects`）→ 共享存储 → 回收站；两处都由清单驱动，不再有常量表；
     * - 挂载是**幂等**的：目标已挂就跳过（判据**只用** `/proc/mounts` —— toybox `mountpoint` 对同
     *   fs 的 bind 误报「未挂」）；并发脚本的挂载点逐字相同，所以「谁挂都一样」，不必每脚本各挂一层；
     * - 挂载失败即退出（exit 90+），**不回滚**已挂项 —— 并发下回滚会卸掉别的命令
     *   正在用的那一层。本脚本**不**卸挂载；清理归维护动作（U78 起会话不再产生宿主挂载）；
     * - chroot 经容器内 `env -i` 重建干净环境（[ContainerEnv]），否则 `su` 继承的宿主 Android 环境（PATH=/product/bin:…）会泄漏进容器；
     * - 超时由**容器侧看门狗**处理：setsid 让命令独立成组，超时与正常结束都按同一组 TERM→KILL（要常驻就加 `setsid` 逃逸）；宿主侧 destroyForcibly 只兜脚本自身卡死。
     *
     * [entry] 是容器内待执行脚本的路径：一次性命令用 [ENTRY_COMMAND]；常驻会话传
     * [SESSION_ENTRY]（长跑循环脚本，其 stdio 即 su 的 stdio）。两者共享同一套幂等挂载段。
     */
    internal fun buildScript(
        mounts: List<Mount>,
        timeoutSeconds: Long? = null,
        entry: String = ENTRY_COMMAND,
        pidFile: File? = null,
        reapStale: String? = null,
        /** [reapStale] 生效时跳过的命令行（受保护进程，见 `ProtectedProcessStore`）。 */
        protected: List<String> = emptyList(),
    ): String = buildString {
        val r = shellQuote(manager.rootfsDir.absolutePath)
        // 环境表单独一行：字符串模板里的调用会被「剥注释与字面量」的源码守卫抹掉，
        // 提出来之后 ContainerEnvTest 才能在纯代码里看到通道值的来源（生成的脚本字节不变）。
        val channelEnv = ContainerEnv.pairs().joinToString(" ")
        val chrootLine = "chroot \"\$R\" /usr/bin/env -i $channelEnv /bin/sh $entry"
        appendLine("#!/system/bin/sh")
        appendLine("# Generated by Kair RootCommandRunner; do not edit.")
        appendLine("R=$r")
        appendLine("T='${timeoutSeconds?.takeIf { it > 0 } ?: ""}'")
        // 容器内新建文件默认 666、目录 777。chroot 是真 root，文件属主仍是 root；
        // 若用 022 只会留下 root:644 —— 应用进程（另一个 uid）能读不能写，插件改不了
        // 自己的文件。000 让新建 inode 对 App 可读写，无需事后 chown。
        // umask 只影响此后新建的 inode，不改已有文件与属主；rootfs / 工作区都在
        // App 私有目录内，Android 沙箱仍隔离其他应用。
        appendLine("umask 000")
        // 第一件事就写下自己的 pid（$$ 即执行本脚本的 shell）：mount 失败 / 卡死时宿主仍能收树。
        pidFile?.let { appendLine("echo \$\$ > ${shellQuote(it.absolutePath)}") }
        // 会话启动时先清上一代 App 遗留的容器进程（见 ContainerProcesses.reapCommand）。
        // 必须在 try_mount 之前：那时还没挂载 / chroot，且本脚本不继承 KAIR_CONTAINER。
        reapStale?.let { appendLine(ContainerProcesses.reapCommand(it, protected)) }
        // 挂载幂等、退出不卸：判据只用 /proc/mounts（toybox mountpoint 对同 fs 的 bind 会误报
        // 「未挂」，真机实测每条命令再叠一层）。devpts 是 /dev 的子挂载，普通 bind 带不进容器（D2-0）。
        appendLine("already_mounted() {")
        appendLine("  grep -q \" \$R/\$1 \" /proc/mounts")
        appendLine("}")
        appendLine("try_mount() {")
        appendLine("  already_mounted \"\$1\" && return 0")
        appendLine("  mkdir -p \"\$R/\$1\"")
        appendLine("  if mount -o bind \"\$2\" \"\$R/\$1\"; then :")
        appendLine("  else echo \"kair-chroot: mount \$2 -> \$R/\$1 failed\" >&2; exit 90; fi")
        appendLine("}")
        appendLine("try_mount proc /proc")
        appendLine("try_mount sys /sys")
        appendLine("try_mount dev /dev")
        appendLine("try_mount dev/pts /dev/pts")
        // 可变段：两条通道都是「宿主工作区父目录 → /projects」，项目是它下面的真实子目录。
        mounts.forEach { appendLine("try_mount ${it.relativePoint} ${shellQuote(it.hostPath.absolutePath)}") }
        if (mountSdcard()) appendLine("try_mount ${SdcardMounts.CONTAINER.removePrefix("/")} ${SdcardMounts.HOST}")
        // 回收站：挂载点建在 rootfs 内（$R/trash），所以工作区/仓库里不会出现任何目录。
        trashDir?.let {
            appendLine("try_mount ${TrashMounts.CONTAINER.removePrefix("/")} ${shellQuote(it.absolutePath)}")
        }
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid $chrootLine & GROUP=1")
            appendLine("else")
            appendLine("  $chrootLine & GROUP=0")
            appendLine("fi")
            appendLine("CMD=\$!")
            // 看门狗用 1 秒粒度轮询而非单次 sleep T：命令提前结束时宿主只能 kill
            // 掉看门狗子 shell，杀不到它正在执行的 sleep 孙进程；Magisk/KernelSU
            // 的 su 会等会话内全部后代退出才返回，孤儿 sleep 要活满 T 秒 → 干等。
            // 轮询版里 sleep 最长 1 秒，命令结束后看门狗在 1 秒内发现并自行退出，
            // su 随即收尾。sleep 全部脱离 stdio，孤儿也不持有 su 的输出 fd。
            appendLine("WATCHDOG() {")
            appendLine("  ELAPSED=0")
            appendLine("  while [ \"\$ELAPSED\" -lt \"\$T\" ]; do")
            appendLine("    sleep 1 </dev/null >/dev/null 2>&1 || exit 0")
            appendLine("    ELAPSED=\$((ELAPSED + 1))")
            appendLine("    kill -0 \"\$CMD\" 2>/dev/null || exit 0")
            appendLine("  done")
            appendLine("  echo \"kair-chroot: timed out after \${T}s, killing\" >&2")
            appendLine("  [ \"\$GROUP\" = 1 ] && kill -TERM -- \"-\$CMD\" 2>/dev/null; kill -TERM \"\$CMD\" 2>/dev/null")
            appendLine("  sleep 5 </dev/null >/dev/null 2>&1")
            appendLine("  [ \"\$GROUP\" = 1 ] && kill -KILL -- \"-\$CMD\" 2>/dev/null; kill -KILL \"\$CMD\" 2>/dev/null")
            appendLine("}")
            appendLine("WATCHDOG & WATCH=\$!")
            appendLine("wait \"\$CMD\"")
            appendLine("RC=\$?")
            appendLine("kill \"\$WATCH\" 2>/dev/null; wait \"\$WATCH\" 2>/dev/null; if [ \"\$GROUP\" = 1 ] && kill -0 -- \"-\$CMD\" 2>/dev/null; then echo \"kair-chroot: background processes were left behind; reaped them (use setsid to keep one running)\" >&2; kill -TERM -- \"-\$CMD\" 2>/dev/null; sleep 1 </dev/null >/dev/null 2>&1; kill -KILL -- \"-\$CMD\" 2>/dev/null; fi") // 正常退出也收组：`cmd &` / `nohup` 的后代不是 wait 的直接子进程，主 shell 退出后会被 reparent；只按 setsid 组杀，`setsid` 起的进程会逃逸（agent 的常驻出口）。那行 stderr 是必需的：调用方拿到 RC=0，不打就查不出「服务被谁收掉」
            appendLine("exit \$RC")
        } else {
            // exec 接替宿主脚本 shell：容器入口就是自己的 pgid 组长，Ctrl-C 补刀不会误杀中间层。
            appendLine("exec $chrootLine")
        }
    }

    companion object {
        /** 维护命令的超时（秒）：chown / 挂载这类动作不该无限挂起。 */
        private const val ROOT_COMMAND_TIMEOUT_SECONDS = 15L

        /** 取消时等 root 清理子进程的时长上限（毫秒）；超时后照常走直接 kill。 */
        private const val ROOT_KILL_WAIT_MS = 1_500L

        /** 一次性命令在容器内的落地路径（[run] 写入 rootfs 的 `tmp/.kair-cmd`）。 */
        internal const val ENTRY_COMMAND = "/tmp/.kair-cmd"

        /**
         * 维护脚本在容器内的入口路径（由维护动作写入 rootfs 的同一处）。
         * U78 起会话通道不再经过本类（只有 proot），但 `ContainerProcesses` 仍用它识别
         * 「不是维护脚本」的容器进程。
         */
        internal const val SESSION_ENTRY = "/tmp/.kair-session"

        /** 宿主侧多等的宽限期（秒）：只兜脚本自身卡死，正常结束不必等满。 */
        internal const val SCRIPT_GRACE_SECONDS = 15L

        /** su 候选表：先 PATH，再各管理器常见安装位置。 */
        val DEFAULT_SU_CANDIDATES: List<String> = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/odm/bin/su", "/debug_ramdisk/su",
        )

        internal const val SCRIPT_NAME = ".kair-chroot.sh"

        /**
         * 在候选表里定位第一个可执行的 su。相对名（如 `su`）沿 PATH 展开，
         * 绝对路径直接检查；找不到返回 null（视为未 root，不再执行探测）。
         */
        internal fun locateSu(candidates: List<String>): String? {
            for (candidate in candidates) {
                if (candidate.contains('/')) {
                    if (File(candidate).canExecute()) return candidate
                } else {
                    locateInPath(candidate, System.getenv("PATH"))?.let { return it }
                }
            }
            return null
        }

        /** 在冒号分隔的 PATH 串里找可执行文件；找不到返回 null。 */
        internal fun locateInPath(name: String, pathValue: String?): String? {
            if (pathValue == null) return null
            for (dir in pathValue.split(File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                val f = File(dir, name)
                if (f.canExecute()) return f.absolutePath
            }
            return null
        }

        /**
         * 执行 `su -c id -u`：输出 `0` 即已授权。输出由读线程收集，
         * 等待上限 10s —— 授权弹窗挂着时进程无输出，超时返回 [RootProbeResult.TIMEOUT]
         * （而非无限阻塞或误判失败）。
         */
        internal fun probeRoot(su: String): RootProbeResult {
            val process = try {
                ProcessBuilder(su, "-c", "id -u")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: IOException) {
                return RootProbeResult.NO_SU
            }
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { br ->
                        val buf = CharArray(512)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            synchronized(output) { output.append(buf, 0, n) }
                        }
                    }
                } catch (_: Exception) {
                    // 管道断裂以已收到的输出为准
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(500)
                return RootProbeResult.TIMEOUT
            }
            reader.join(500)
            val uid = synchronized(output) { output.toString().trim() }
            return if (uid == "0") RootProbeResult.GRANTED else RootProbeResult.DENIED
        }

        /** POSIX 单引号转义：`'` → `'\''`，保证路径含任意字符都安全。 */
        internal fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
    }
}
