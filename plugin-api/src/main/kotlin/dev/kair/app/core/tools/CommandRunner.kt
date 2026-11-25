package dev.kair.app.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 命令执行后端的原始结果。不做截断、不做文案包装——那是宿主 `bash` 工具的职责。
 * 后端只负责把命令跑起来并把合并后的输出与退出码交回来。
 */
data class CommandOutput(
    /** stdout 与 stderr 合并后的原始输出。 */
    val output: String,
    val exitCode: Int,
    /** true 表示因超时被强制终止，[exitCode] 此时无意义。 */
    val timedOut: Boolean = false,
)

/**
 * 命令执行后端契约。内置的 `container_shell` 工具只认这个接口，具体实现由插件提供：
 *
 * - 内置的 [LocalCommandRunner]：跑 Android 的 `/system/bin/sh`（toybox 用户态）。
 * - proot 后端（内置插件）：跑容器内完整的 Linux 用户态（git / python / 包管理器）。
 *
 * 约定：命令在 [run] 的 `workspace` 目录下执行；该目录是宿主机上的真实路径，
 * proot 实现应把它 bind 到容器内的固定路径，而不是复制。
 */
interface CommandRunner {
    /** 稳定 id，用于展示与诊断，例如 `toybox` / `proot-alpine`。 */
    val id: String

    /** 人类可读名称。 */
    val displayName: String

    /** 简介：说明这个后端能跑什么、有什么限制。 */
    val description: String

    /**
     * 命令**实际在容器里执行**时，容器内与非空标记；否则返回 null。
     *
     * 它表示「这条通道真的进了容器」，**不一定**是某个工作区的 cwd（内置容器返回的是
     * 工作区父目录的挂载点 `/projects`）。要 cwd 请用 [containerWorkspacePathFor]。
     * 本地 shell 后端（含容器未就绪时的回退）一律返回 null。
     */
    val containerWorkspacePath: String? get() = null

    /**
     * [workspace] 在容器内的**真实路径**（`bash` 的 cwd）；进不去容器时返回 null。
     *
     * 装配层据此决定系统提示里的 `<cwd>`，让模型拿到的 cwd 与工具实际执行位置一致。
     * 默认返回 [containerWorkspacePath] —— 旧实现把工作区直接挂到固定点；内置容器把
     * **工作区父目录**挂到 `/projects`，因此覆盖为 `/projects/<目录名>`。
     */
    fun containerWorkspacePathFor(workspace: File): String? = containerWorkspacePath

    /**
     * 在 [workspace] 目录下执行一条 shell 命令。
     *
     * @param timeoutSeconds 超时秒数；null 或非正数表示不限时。
     */
    suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput
}

/**
 * 取 [Process] 的 PID；取不到返回 0。
 *
 * **不能直接调 `pid()`**：它是 Java 9 API，Android 运行时（core-oj）里根本没有这个方法。
 * plugin-api 是纯 JVM 模块（按 JDK 编译），所以编译期完全看不出来 —— 只有真机运行时
 * 才抛 `NoSuchMethodError`，而它落在取消路径的 finally 里，结果就是点「停止」直接崩溃。
 * JVM 单测同样发现不了（测试进程有 `pid()`），这是本模块的结构性盲区。
 *
 * **Android 兜底（U67）**：`pid()` 反射失败时解析 `java.lang.UNIXProcess.toString()` 的
 * `Process[pid=1234, hasExited=false]`。这段形状在 Android SDK 源码 API 26–35 的
 * `java/lang/UNIXProcess.java` 里逐版一致；它不是公开契约，解析不出来就按 0 处理
 * （退回「只杀本体」的旧行为，绝不猜测）。
 */
fun Process.resolvePid(): Long {
    val raw = runCatching { Process::class.java.getMethod("pid").invoke(this) }.getOrNull()
    val direct = (raw as? Number)?.toLong() ?: 0L
    if (direct > 0L) return direct
    // toString() 同样不是契约：自定义 Process 可能抛错。本函数是 killTree 的默认参数，
    // 在 killTree 函数体的 runCatching 之外求值 —— 抛出去就是取消路径崩溃，必须兜住。
    return runCatching { parseProcessPid(toString()) }.getOrDefault(0L)
}

/** `UNIXProcess.toString()` 的 PID 解析；不匹配返回 0。 */
internal fun parseProcessPid(description: String): Long =
    runCatching {
        PROCESS_PID.find(description)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

/** 锚定前缀，避免把自定义 `toString()` 里的普通数字误认成 PID。 */
private val PROCESS_PID = Regex("""^\s*Process\[pid=(\d+)""")

/**
 * 杀掉进程及其全部后代。`destroyForcibly()` 只对单个 PID 生效：`sh -c` / proot 派生的
 * 子进程（编译、下载、sleep 等）会被留成孤儿，继续占住工作区与挂载点。这里先按**逆序**收
 * 后代、最后杀本体 —— 提前杀掉本体的话，后代会被 reparent 到 init，之后就找不到了。
 *
 * **Android 差异**：Android 运行时没有 `java.lang.ProcessHandle`（`android.jar` 里也没有
 * 这个类），`toHandle()` 会抛错；`pid()` 同样不存在 —— [resolvePid] 会退回解析
 * `UNIXProcess.toString()`，所以本函数在 Android 上同样能枚举后代。root 通道另有
 * 更强的路径：脚本开头把自己的 pid 写进 pidfile，取消时按那个 pid 让 su 递归收树
 * （见 `RootCommandRunner`）。
 *
 * **本体已退出时直接返回**：PID 可能已被复用，后代也已经 reparent 到 init（`/proc` 里
 * 找不到）—— 这时再按解析出的 pid 发信号只会误伤无关进程。
 *
 * **本函数绝不抛错**：它只从取消路径的 finally 里被调用，抛出即变成应用崩溃
 * （真机上已经因为 `pid()` 的 `NoSuchMethodError` 崩过一次）。清理失败是可接受的，
 * 崩溃不是。
 *
 * @param rootPid 调用方已知的进程树根 PID（如脚本自己写入的 pidfile）；未知传 0。
 */
fun Process.killTree(rootPid: Long = resolvePid()) {
    if (!runCatching { isAlive }.getOrDefault(false)) return
    runCatching {
        // android-gap-ok: Java 9 API，Android 上没有；包在 runCatching 里，取不到就走 /proc 分支。
        val handles = runCatching { toHandle().descendants().toList() }.getOrNull()
        if (handles != null) {
            handles.asReversed().forEach { runCatching { it.destroyForcibly() } }
            runCatching { destroyForcibly() }
            return@runCatching
        }
        // Android：**先**枚举后代再 SIGTERM —— 本体一退出，子进程会被 reparent 到 init，
        // 那时再扫描就找不到了（下载中的 curl 会继续跑）。枚举后才发 TERM 给挂载清理 /
        // proot 的 --kill-on-exit 一个机会，最后逐个强杀。
        val pids = if (rootPid > 0L) runCatching { descendantPids(rootPid) }.getOrDefault(emptyList()) else emptyList()
        runCatching { destroy() }
        pids.asReversed().forEach(::killPid)
        killPid(rootPid)
    }
}

/**
 * 从 `/proc/<pid>/stat` 收集 [rootPid] 的全部后代（近似「子先于父」的顺序）。
 *
 * 平台无关、不依赖第三方库；没有 `/proc`（如 Windows 单测）时返回空。
 * 只读取，不校验 uid —— 调用方必须只对**自己派生的进程树**使用。
 */
fun descendantPids(rootPid: Long): List<Long> {
    if (rootPid <= 0L) return emptyList()
    val childrenByParent = HashMap<Long, MutableList<Long>>()
    runCatching {
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toLongOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val parent = parseProcStatPpid(stat) ?: return@forEach
            childrenByParent.getOrPut(parent) { mutableListOf() } += pid
        }
    }
    val result = ArrayList<Long>()
    val queue = ArrayDeque<Long>()
    queue += rootPid
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        childrenByParent[current]?.forEach { child ->
            result += child
            queue += child
        }
    }
    return result
}

/** 解析 `/proc/<pid>/stat` 的 PPID 字段（第 4 列；`comm` 可能含空格与括号）。 */
internal fun parseProcStatPpid(stat: String): Long? {
    val close = stat.lastIndexOf(')')
    if (close < 0 || close + 1 >= stat.length) return null
    val fields = stat.substring(close + 1).trim().split(' ')
    // fields[0] = state, fields[1] = ppid
    return fields.getOrNull(1)?.toLongOrNull()
}

/**
 * 按 PID 发送 SIGKILL。Android 用反射调 `android.os.Process.killProcess`（plugin-api
 * 不能编译期依赖 android.*）；JVM（单测 / 桌面）退回 `ProcessHandle`。
 */
private fun killPid(pid: Long) {
    if (pid <= 0L) return
    val androidKilled = runCatching {
        Class.forName("android.os.Process")
            .getMethod("killProcess", Int::class.javaPrimitiveType)
            .invoke(null, pid.toInt())
        true
    }.getOrDefault(false)
    if (androidKilled) return
    // android-gap-ok: JVM 专用；Android 上此法不存在，runCatching 吃掉 NoSuchMethodError。
    runCatching { ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
}

/**
 * 有界输出缓冲：只保留最近 [maxChars] 个字符，超出后从头部丢弃。
 *
 * 命令输出必须在**读取阶段**就限流：`truncateTail` 只在进程结束后执行，
 * 若读线程无上限地 append，`yes` / `cat 大文件` 会先把堆撑爆。保留尾部是因为
 * 工具最终展示的也是尾部（错误信息通常出现在最后）。
 */
class TailTextBuffer(private val maxChars: Int = DEFAULT_MAX_CHARS) {
    private val builder = StringBuilder()

    @Synchronized
    fun append(chars: CharArray, length: Int) {
        builder.append(chars, 0, length)
        val overflow = builder.length - maxChars
        if (overflow > 0) builder.delete(0, overflow)
    }

    @Synchronized
    override fun toString(): String = builder.toString()

    companion object {
        /** 256K 字符：远大于工具最终保留的 50KB，既够定位错误又不会撑爆内存。 */
        const val DEFAULT_MAX_CHARS = 256 * 1024
    }
}

/**
 * 内置回退实现：Android 的 `/system/bin/sh`（toybox）。没有插件提供后端时内核用它，
 * 保证 `container_shell` 工具始终可用，只是没有 git / python / 包管理器。
 * 已 root 的设备上，命令里可直接写 `su -c '<command>'`（本类把 su 的常见目录补进 PATH）。
 *
 * [shell] 可覆盖，便于在 JVM 单测里指向 `/bin/sh`。
 */
class LocalCommandRunner(private val shell: String = DEFAULT_SHELL) : CommandRunner {
    override val id = "toybox"
    override val displayName = "Android Shell"
    override val description = "Android 自带的 /system/bin/sh（toybox），无 git / python / 包管理器。"

    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput =
        withContext(Dispatchers.IO) {
            val builder = ProcessBuilder(shell, "-c", command)
                .directory(workspace)
                .redirectErrorStream(true)
            // 设备已 root 时，`su` 常被 Root 管理器装在默认 PATH 之外（/system/xbin、/debug_ramdisk…），
            // 而 app 进程的 PATH 只含系统目录 —— 补上这些目录，`su -c '<command>'` 才找得到。
            builder.environment()["PATH"] = (builder.environment()["PATH"].orEmpty().split(':').filter { it.isNotBlank() } +
                SU_DIRS).distinct().joinToString(":")
            val process = builder.start()

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
                }
            }.apply { isDaemon = true; start() }

            // 等待进程结束：包在 runInterruptible 里，使调用协程被取消（用户点停止 /
            // abort）时能中断阻塞的 waitFor，从而让下面的 finally 真正杀掉进程 ——
            // 否则「停止按钮只停流式输出、bash 命令继续跑」。
            var finished = false
            try {
                finished = runInterruptible {
                    if (timeoutSeconds != null && timeoutSeconds > 0) {
                        process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                    } else {
                        process.waitFor(); true
                    }
                }
            } finally {
                // 取消（未正常结束）时强杀整棵进程树：命令被中断即终止，子进程也不留。
                // shortcut: 本地后端在 Android 上拿不到 pid（无 Process.pid()），只能杀外层 sh，
                // 孙进程（sh -c 里的 sleep / 下载）会变成孤儿。升级条件：本地后端也需要
                // 「停止即连孙进程一起停」时，照 chroot 通道的做法让命令先把自己的 pid 写进文件。
                if (!finished) process.killTree()
            }
            reader.join(1000)

            val raw = output.toString()
            val exit = try { process.exitValue() } catch (_: Exception) { -1 }
            CommandOutput(raw, exit, timedOut = !finished)
        }

    companion object {
        /** Android 自带的 shell；JVM 单测可传 `/bin/sh`。 */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /**
         * `su` 的常见安装目录（Root 管理器：Magisk / KernelSU / SuperSU）。
         *
         * 与 `RootCommandRunner.DEFAULT_SU_CANDIDATES` 的绝对路径同源，但这里只补 PATH ——
         * `android_shell` 只负责把 `su` 暴露给 `/system/bin/sh -c`，提权与授权判定仍由 `su` 自己完成。
         */
        internal val SU_DIRS = listOf(
            "/system/xbin",
            "/system/sbin",
            "/sbin",
            "/vendor/bin",
            "/odm/bin",
            "/debug_ramdisk",
        )
    }
}
