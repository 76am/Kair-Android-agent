package dev.kair.app.core.proot

import dev.kair.app.core.ai.Json
import dev.kair.app.core.model.newId
import dev.kair.app.core.storage.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 一个**受保护的容器进程**：Kair 死后由收尸人一并收掉，下次会话就绪时若不在则被重新拉起。
 *
 * [command] 同时是两件事，这是本设计的核心取舍：
 * - **启动清扫时的跳过判据**（命令行完全相等才跳过，不做子串匹配 —— 否则
 *   `python3 -m http.server 8080` 会顺带保护 `python3 -m http.server 80801`）；
 * - **重启后的启动命令**（当时怎么跑的，就怎么再跑一次）。
 *
 * 不按 PID 记：PID 在重启后必然变，记了等于没记。
 */
@Serializable
data class ProtectedProcess(
    val id: String,
    /** 完整命令行（参数之间单空格、无尾随空格），由 `ContainerProcesses.parse` 的同一形态给出。 */
    val command: String,
    val addedAt: Long,
)

/**
 * 受保护进程的持久化登记（跨 App 进程）。
 *
 * 归属：整个应用（文件在 `storageRoot` 下，与 `projects.json` 同级），不按项目分 ——
 * 容器进程本来就活在 `/projects` 这一层，不归属单个项目。
 *
 * 与清扫的关系见 [ContainerProcesses.reapCommand]：**启动**清扫跳过这里的每一条，
 * 死亡清扫（[ContainerProcesses.deathSweepScript]）不跳过；
 * 与重启恢复的关系见 [ProtectedProcesses.restoreCommand]。
 */
class ProtectedProcessStore(root: File) {

    private val file = File(root, "protected-processes.json")
    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ProtectedProcess>> = _entries.asStateFlow()

    /** 当前全部受保护命令行；`ContainerSession` 启动时据此生成「清扫时跳过」的判据。 */
    fun commands(): List<String> = _entries.value.map { it.command }

    /**
     * 把 [command] 登记为受保护。
     *
     * @return 新条目；同一条命令行已经登记过则返回 null（不重复、不报错 —— 面板上重复点
     *   「保护」是最常见的误操作，没有理由让它失败）。
     */
    fun add(command: String): ProtectedProcess? = synchronized(lock) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return null
        val current = _entries.value
        if (current.any { it.command == cmd }) return null
        val entry = ProtectedProcess(id = "pp-" + newId().replace("-", "").take(8), command = cmd, addedAt = System.currentTimeMillis())
        persist(current + entry)
        entry
    }

    fun remove(id: String) = synchronized(lock) {
        val current = _entries.value
        if (current.none { it.id == id }) return
        persist(current.filterNot { it.id == id })
    }

    /** 外部（如测试 / 卸载 rootfs）重建文件后重新读一次。 */
    fun reload() = synchronized(lock) { _entries.value = load() }

    private fun persist(list: List<ProtectedProcess>) {
        writeAtomically(file, Json.encodeToString(list))
        _entries.value = list
    }

    private fun load(): List<ProtectedProcess> {
        if (!file.exists()) return emptyList()
        return runCatching { Json.decodeFromString<List<ProtectedProcess>>(file.readText()) }.getOrElse {
            // 坏文件先留副本：否则下一次保存会把唯一一份登记永久覆盖成空。
            runCatching {
                file.copyTo(
                    File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"),
                    overwrite = true,
                )
            }
            emptyList()
        }
    }
}

/**
 * 重启恢复：受保护进程若已不在，按登记的命令行重新拉起。
 *
 * 由 `ProotPlugin` 在会话进入 `Running` 之后通过 `exec` 执行（脚本在容器内跑，`/proc`
 * 与命令执行的 cwd 都已是容器视角）。
 */
object ProtectedProcesses {

    /**
     * 恢复脚本在容器内的路径。**必须写成文件再执行**：会话入口是逐行 `read` + `eval`，
     * 直接把 [restoreCommand] 的多行脚本文本交给 `session.exec` 会按行拆开 —— `for` / `if`
     * 全部变成语法错误，而且第一行的退出帧会让 `exec` 提前返回、与后续命令的输出串帧。
     */
    const val RESTORE_SCRIPT = "/tmp/.kair-restore.sh"

    /** 恢复日志：容器内 `/tmp`（rootfs 内），App 侧可通过文件后端读。 */
    fun logPath(id: String): String = "/tmp/kair-protected-$id.log"

    /**
     * 把 [entries] 的恢复脚本落到 rootfs 的 [RESTORE_SCRIPT]，返回容器内路径供调用方
     * `exec("sh <path>")`；没有登记项返回 null（不白占一次终端转录）。
     *
     * 写文件失败按 best-effort 处理：返回 null，下次会话启动再试 —— 受保护进程本身不受影响
     * （若还活着则由清扫跳过，若已死则只是这次没被拉回来）。
     */
    fun installScript(rootfsDir: File, entries: List<ProtectedProcess>): String? = runCatching {
        restoreCommand(entries)?.let { script ->
            writeAtomically(File(rootfsDir, RESTORE_SCRIPT.removePrefix("/")), script)
            RESTORE_SCRIPT
        }
    }.getOrNull()

    /**
     * 生成恢复脚本；没有登记项时返回 null（[installScript] 据此不写文件、不占终端转录）。
     *
     * 每一项都是**先判在不在跑、再决定起不起**：
     * - 在跑（命令行完全相等 + 带容器标记）→ 打一行「already running」，**不重复启动**。
     *   这是本功能最容易踩的坑：受保护进程在清扫时被跳过，所以 App 重启后它通常还活着，
     *   无条件再起一个就是两份抢同一端口。
     * - 不在跑 → `setsid` 起来，stdout / stderr 进 `/tmp/kair-protected-<id>.log`。
     *   没有 `setsid` 时退化为直接后台运行（会话 stop 时会被 `killTree` 收掉，属平台降级）。
     */
    fun restoreCommand(entries: List<ProtectedProcess>): String? {
        if (entries.isEmpty()) return null
        return buildString {
            entries.forEach { entry ->
                // 命令用 `sh -c` 执行，不做词分割：`$pcmd` 原样交给 shell 解析。
                // 直接 `setsid $pcmd` 会把 `sh -c "a b"` 这类带引号的命令拆坏，
                // 而 `sh -c` 对 `python3 -m http.server 8080` 这种简单命令完全等价。
                appendLine("pcmd=${RootCommandRunner.shellQuote(entry.command)}")
                appendLine("plog=${RootCommandRunner.shellQuote(logPath(entry.id))}")
                appendLine("running=0")
                appendLine("for d in /proc/[0-9]*; do")
                appendLine("  [ -r \"\$d/environ\" ] || continue")
                appendLine("  tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null | grep -qx '${ContainerEnv.MARKER}' || continue")
                appendLine("  c=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null | sed 's/ *\$//')")
                appendLine("  [ \"\$c\" = \"\$pcmd\" ] && { running=1; break; }")
                appendLine("done")
                appendLine("if [ \"\$running\" = 1 ]; then")
                appendLine("  echo \"kair: protected process already running: \$pcmd\"")
                appendLine("else")
                appendLine("  if command -v setsid >/dev/null 2>&1; then setsid sh -c \"\$pcmd\" > \"\$plog\" 2>&1 </dev/null &")
                appendLine("  else nohup sh -c \"\$pcmd\" > \"\$plog\" 2>&1 </dev/null & fi")
                appendLine("  echo \"kair: restored protected process: \$pcmd\"")
                appendLine("fi")
            }
        }
    }
}
