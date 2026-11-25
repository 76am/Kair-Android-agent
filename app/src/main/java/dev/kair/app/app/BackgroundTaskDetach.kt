package dev.kair.app.app

import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.RootCommandRunner
import dev.kair.app.core.proot.SessionState
import dev.kair.app.core.tools.TailTextBuffer
import dev.kair.app.core.tools.killTree
import dev.kair.app.core.tools.resolvePid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 后台任务的**脱离执行器**（U60）：宿主侧直接起一个不等待的容器进程。
 *
 * 为什么不再经会话 `exec`：会话 shell 的命令 ↔ 退出帧是严格配对的，一条能占死帧的命令
 * （`bash -i` / `tmux`）会让 `exec()` 永等，而终端面板与 `background_task` 共用同一把
 * `execLock` —— 两方一起永久挂起。宿主侧持有进程、不等待，就完全不占那把锁。
 *
 * U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）：
 * `ProcessBuilder(proot ... /bin/sh -c <runner>)`，proot 活着 = 任务活着；
 * `--kill-on-exit` 在 proot 退出时收掉 tracee。收尾走 `process.killTree()`。
 *
 * **前台跑，不带 `&`**：`... &` 是「送进会话后立刻返回」的形状；宿主侧照抄会让被包的
 * `sh` / `proot` 立即退出，proot 的 `--kill-on-exit` 随即把刚起来的任务一起收掉。
 */
internal data class DetachSpec(
    val id: String,
    val hostWorkspace: File,
    /** 宿主侧任务目录（脚本 / 日志 / rc / pid 都在这里）。 */
    val hostDir: File,
    /** 容器内任务脚本路径。 */
    val containerScript: String,
    /** 容器内 runner 脚本路径（把任务输出重定向到日志）。 */
    val containerRunner: String,
    /** 宿主侧 rc 文件（watcher 读的就是它）。 */
    val hostRc: File,
    /** 宿主侧 pid 文件（脱离脚本自己写）。 */
    val hostPid: File,
)

/** 宿主侧已拉起的脱离任务进程。 */
internal class DetachedProcess(
    /** 宿主侧进程 pid（proot 本体）。 */
    val pid: Long,
    private val graceful: suspend () -> Unit,
    private val force: suspend () -> Unit,
    /**
     * 宿主进程是否仍存活（U49）。任务以 `.rc` 为完成判据，而 `trap rc EXIT` 在 SIGKILL 下不执行 ——
     * 没有这一路，死掉的任务会永久停在 RUNNING。
     *
     * 语义保证：proot 是 tracee 的父，`--kill-on-exit` 保证两端同步退出。
     * **收尾之后可能仍为 true**（信号还没生效），不要拿它判「停成功」。
     */
    val alive: () -> Boolean,
) {
    /** [force] = false 先礼后兵；true 立即收树。 */
    suspend fun stop(force: Boolean) = if (force) force() else graceful()
}

internal sealed interface DetachResult {
    data class Started(val process: DetachedProcess) : DetachResult
    data class Failed(val message: String) : DetachResult
}

internal suspend fun launchDetachedTask(session: ContainerSession, spec: DetachSpec): DetachResult =
    withContext(Dispatchers.IO) {
        if (session.state.value !is SessionState.Running) {
            return@withContext DetachResult.Failed(TASK_ERR_NO_SESSION)
        }
        launchProot(session, spec)
    }

// ---- proot ------------------------------------------------------------------

private suspend fun launchProot(session: ContainerSession, spec: DetachSpec): DetachResult {
    val process = session.prootRunner.spawnProcess(
        "exec /bin/sh ${RootCommandRunner.shellQuote(spec.containerRunner)}",
        spec.hostWorkspace,
    ) ?: return DetachResult.Failed("proot is unavailable: cannot start a detached background task.")
    try {
        val output = drain(process)
        val finished = runCatching {
            runInterruptible { process.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS) }
        }.getOrDefault(false)
        if (finished && !spec.hostRc.isFile) {
            val reason = output.tail().ifBlank { "exit ${runCatching { process.exitValue() }.getOrDefault(-1)}" }
            process.killTree()
            return DetachResult.Failed(reason)
        }
        return DetachResult.Started(
            DetachedProcess(
                pid = process.resolvePid(),
                // proot 的事件循环把终止信号设成 SIG_IGN（只有 SIGQUIT / SIGILL / SIGABRT /
                // SIGFPE / SIGSEGV 会触发收尾），所以 `destroy()` 的 SIGTERM 什么也不会做。
                // 首次停止就枚举整棵 tracee 树并强杀：proot 还活着时 client / Gradle daemon /
                // Kotlin daemon 都还在 `/proc` 的 PPID 链上，这正是唯一收得干净的时刻。
                graceful = { process.killTree() },
                force = { process.killTree() },
                // proot 是 tracee 的父，`--kill-on-exit` 让它在 tracee 消失后自行退出。
                alive = { process.isAlive },
            ),
        )
    } catch (e: Throwable) {
        process.killTree()
        throw e
    }
}

// ---- 辅助 -------------------------------------------------------------------

/** 读宿主进程输出（proot 的失败原因）；任务本身的输出已由 runner 重定向到日志。 */
private class OutputDrain(process: Process) {
    private val buffer = TailTextBuffer(DRAIN_KEEP_CHARS)

    init {
        // 两条流都得读：proot 档不合流，它自己的 ptrace 警告写满 stderr 管道
        // （约 64KB）就会把常驻任务堵死。缓冲自带 @Synchronized，两线程共用一份尾部即可。
        listOf(process.inputStream, process.errorStream).forEach { stream ->
            Thread {
                runCatching {
                    stream.bufferedReader().use { br ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            buffer.append(buf, n)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }
    }

    fun tail(): String = buffer.toString().trim()
}

private fun drain(process: Process): OutputDrain = OutputDrain(process)

private const val STARTUP_GRACE_MS = 500L
private const val DRAIN_KEEP_CHARS = 2_000
