package dev.kair.app.core.proot

/**
 * 容器进程管理：枚举与结束容器内进程的纯逻辑。
 *
 * 容器没有 PID namespace（见 `docs/架构与内核边界.md` §9.8），容器里的 `/proc`
 * 就是宿主进程表。因此不能直接拿 `ps` 全量展示 —— 会混进 Android 系统 / 其他 App 的
 * 进程，误杀代价很大。[ContainerEnv.MARKER] 由所有容器命令继承，探测脚本只保留
 * 带标记的进程，再跳过探测命令自身的祖先 / 子进程。
 *
 * 本类只生成 shell 命令与解析文本，不依赖 Android；UI 与单测都走同一条路径。
 */
object ContainerProcesses {

    /** 探测超时：只读 /proc，但 proot 档仍要起一次容器进程，留给一次性命令的时间。 */
    const val PROBE_TIMEOUT_SECONDS = 20L

    /** 结束进程超时：TERM 后最多等 5 秒再 KILL。 */
    const val KILL_TIMEOUT_SECONDS = 15L

    /**
     * 扫描 `/proc`，输出 `pid|ppid|rssKb|cmdline`。
     *
     * 跳过（按代码里的判定顺序）：
     * - 探测 shell 自己的 PID 与祖先链（一次性入口脚本）；
     * - 不带 [ContainerEnv.MARKER] 的宿主进程（**先判这条**：一次滤掉绝大部分，
     *   后面的判据才对少数进程跑，省掉每个进程一对 tr|grep 的 fork）；
     * - 探测 shell 的子孙（`tr` / `grep` / `awk` 自己会带 `KAIR_PROBE_PID`）。
     *
     * 这样结果里只剩容器里的持久进程 shell、服务与并发的一次性命令。
     */
    val PROBE_COMMAND: String = buildString {
        appendLine("self=\$\$")
        appendLine("export KAIR_PROBE_PID=\"\$self\"")
        appendLine("skip=\"\$self\"")
        appendLine("p=\$self")
        appendLine("while [ \"\$p\" -gt 1 ]; do")
        appendLine("  p=\$(awk '/^PPid:/{print \$2}' \"/proc/\$p/status\" 2>/dev/null)")
        appendLine("  [ -n \"\$p\" ] || break")
        appendLine("  skip=\"\$skip \$p\"")
        appendLine("done")
        appendLine("for d in /proc/[0-9]*; do")
        appendLine("  pid=\${d#/proc/}")
        appendLine("  case \" \$skip \" in *\" \$pid \"*) continue;; esac")
        appendLine("  [ -r \"\$d/environ\" ] || continue")
        // 标记判据放前面：它一次就能滤掉 99% 的宿主进程，探测自身子孙的判据只在
        // 命中标记后才跑。反过来的话，每个宿主进程都要多 fork 一对 tr|grep。
        // 真机进程表上千条，先按标记过滤与先按 PID 过滤的 fork 次数差别很实在。
        appendLine("  if ! tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null | grep -q '^${ContainerEnv.MARKER}\$'; then continue; fi")
        appendLine("  if tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null | grep -q '^KAIR_PROBE_PID='; then continue; fi")
        appendLine("  ppid=0; rss=0; while read -r k v _; do case \"\$k\" in PPid:) ppid=\$v;; VmRSS:) rss=\$v;; esac; done < \"\$d/status\" 2>/dev/null")
        appendLine("  cmd=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null)")
        appendLine("  echo \"\$pid|\$ppid|\${rss:-0}|\$cmd\"")
        appendLine("done")
        appendLine("exit 0")
    }

    /**
     * 结束 [pid] 的校验 + 信号脚本：
     * - 目标必须仍带容器标记（列表可能已过期，PID 可能被复用）；
     * - 拒绝结束 `/tmp/.kair-session` 持久进程 shell（由工作区的停止按钮负责）；
     * - TERM 后最多等 5 秒，仍未退出则 KILL。
     *
     * 只结束单个 PID，不按进程组 —— 持久进程里的后台服务与 shell 可能同组，
     * 按组杀会连终端 shell 一起杀掉。
     */
    fun killCommand(pid: Int): String = buildString {
        appendLine("pid=$pid")
        appendLine("[ -r \"/proc/\$pid/environ\" ] || { echo 'process not found'; exit 3; }")
        appendLine("if ! tr '\\0' '\\n' < \"/proc/\$pid/environ\" 2>/dev/null | grep -q '^${ContainerEnv.MARKER}\$'; then echo 'not a container process'; exit 4; fi")
        appendLine("cmd=\$(tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null)")
        appendLine("case \"\$cmd\" in *\"${RootCommandRunner.SESSION_ENTRY}\"*) echo 'refusing to kill the session shell'; exit 5;; esac")
        appendLine("kill -TERM \"\$pid\" 2>/dev/null || { echo 'kill failed'; exit 6; }")
        appendLine("i=0")
        appendLine("while [ \$i -lt 5 ]; do")
        appendLine("  kill -0 \"\$pid\" 2>/dev/null || exit 0")
        appendLine("  sleep 1")
        appendLine("  i=\$((i+1))")
        appendLine("done")
        appendLine("kill -KILL \"\$pid\" 2>/dev/null || exit 0")
    }

    /**
     * 清扫**上一代 App** 留下的容器进程；插在维护挂载脚本里、进入 rootfs 之前执行。
     *
     * 为什么必须在那之前：此时脚本仍是 root 但尚未 chroot，`/proc` 是宿主视角的进程表，
     * 而且**本脚本不继承 `KAIR_CONTAINER`**（它由 `su` 从 App 的环境起，App 侧没有这个变量），
     * 所以不会把自己或自己的 `tr` / `grep` 算进 victims。**把这段挪到 `chroot` 之后就会杀自己。**
     * （U78 起会话通道不再产生这段脚本，见 ADR 0007；维护动作仍可复用。）
     *
     * 判据刻意保留「`KAIR_INSTANCE` 缺失也算上一代」：本批上线前拉起的进程没有这个变量，
     * 靠这一条才能在第一次启动时把存量遗留一起清掉（负责人 2026-10-31 报告的正是这批）。
     *
     * 为什么要先 `case` 粗筛再 `grep`：宿主进程表通常上千条，`case` 是内建、零 fork；
     * 只有粗筛命中的少数进程才 fork `grep` 做整行判定。
     *
     * [protected] 是用户在进程面板登记过的命令行：**完全相等才跳过**（不做子串匹配，
     * 否则保护 `...:8080` 会顺带保护 `...:80801`）。**这条跳过只属于启动侧**：Kair 死后
     * 由新一代的启动清扫统一处理（U56 单模式；U78 起 chroot 收尸人已删除）。
     */
    fun reapCommand(
        currentInstance: String = ContainerEnv.instance,
        protected: List<String> = emptyList(),
    ): String = buildString {
        appendLine("cur='$currentInstance'")
        appendLine("victims=''")
        appendLine("for d in /proc/[0-9]*; do")
        appendLine("  pid=\${d#/proc/}")
        appendLine("  [ -r \"\$d/environ\" ] || continue")
        appendLine("  env=\$(tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null) || continue")
        appendLine("  case \"\$env\" in *${ContainerEnv.MARKER}*) ;; *) continue;; esac")
        appendLine("  printf '%s\\n' \"\$env\" | grep -qx '${ContainerEnv.MARKER}' || continue")
        // 本代的跳过；没有 KAIR_INSTANCE 的落到下一行，按上一代处理。
        appendLine("  printf '%s\\n' \"\$env\" | grep -qx \"${ContainerEnv.INSTANCE}=\$cur\" && continue")
        // 命令行提前算一次：受保护判据与日志都要用（否则每条进程多 fork 一次 tr）。
        appendLine("  cmd=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null | sed 's/ *\$//')")
        if (protected.isNotEmpty()) {
            appendLine("  case \"\$cmd\" in ${protected.joinToString("|") { RootCommandRunner.shellQuote(it) }}) continue;; esac")
        }
        appendLine("  echo \"kair: reaping stale container process \$pid: \$cmd\"")
        appendLine("  victims=\"\$victims \$pid\"")
        appendLine("done")
        appendLine("if [ -n \"\$victims\" ]; then")
        appendLine("  kill -TERM \$victims 2>/dev/null")
        appendLine("  sleep 1 </dev/null >/dev/null 2>&1")
        appendLine("  kill -KILL \$victims 2>/dev/null")
        appendLine("  echo \"kair: reaped \$(echo \$victims | wc -w) stale container process(es)\"")
        appendLine("fi")
    }

    /**
     * 运行期「全部结束」：清掉**本代**容器进程，保留持久进程 shell。
     *
     * 与 [reapCommand] 的两处差别，都是因为这条命令**在容器内执行**：
     * - 自己带 `KAIR_CONTAINER` + `KAIR_INSTANCE=当前` → 必须靠 `KAIR_PROBE_PID` 跳过自己与子孙
     *   （`export` 后 `tr` / `grep` / `wc` 都继承它）；
     * - 只清本代（启动清扫已经处理完上一代），避免把「本来就不该活着」和「用户刚起的」混为一谈。
     *
     * `case` 跳过 `/tmp/.kair-session`：会话 shell 由「Linux 工作区」的停止按钮负责，
     * 从这里杀掉终端会立刻失效但面板还显示运行中。
     *
     * [protected] 与清扫共用同一份登记：受保护进程不参与「全部结束」，否则用户点一次
     * 「全部结束」就把自己刚保护的常驻服务杀了，而它下次启动才会被拉回来。
     */
    fun killAllCommand(
        currentInstance: String = ContainerEnv.instance,
        protected: List<String> = emptyList(),
    ): String = buildString {
        appendLine("self=\$\$")
        appendLine("export KAIR_PROBE_PID=\"\$self\"")
        appendLine("cur='$currentInstance'")
        appendLine("victims=''")
        appendLine("for d in /proc/[0-9]*; do")
        appendLine("  pid=\${d#/proc/}")
        appendLine("  [ -r \"\$d/environ\" ] || continue")
        appendLine("  env=\$(tr '\\0' '\\n' < \"\$d/environ\" 2>/dev/null) || continue")
        appendLine("  case \"\$env\" in *${ContainerEnv.MARKER}*) ;; *) continue;; esac")
        appendLine("  printf '%s\\n' \"\$env\" | grep -qx '${ContainerEnv.MARKER}' || continue")
        appendLine("  printf '%s\\n' \"\$env\" | grep -qx \"${ContainerEnv.INSTANCE}=\$cur\" || continue")
        appendLine("  printf '%s\\n' \"\$env\" | grep -q '^KAIR_PROBE_PID=' && continue")
        appendLine("  cmd=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null | sed 's/ *\$//')")
        appendLine("  case \"\$cmd\" in *\"${RootCommandRunner.SESSION_ENTRY}\"*) continue;; esac")
        if (protected.isNotEmpty()) {
            appendLine("  case \"\$cmd\" in ${protected.joinToString("|") { RootCommandRunner.shellQuote(it) }}) continue;; esac")
        }
        appendLine("  victims=\"\$victims \$pid\"")
        appendLine("done")
        appendLine("if [ -z \"\$victims\" ]; then echo 'no container processes to stop'; exit 0; fi")
        appendLine("kill -TERM \$victims 2>/dev/null")
        appendLine("sleep 1 </dev/null >/dev/null 2>&1")
        appendLine("kill -KILL \$victims 2>/dev/null")
        appendLine("echo \"kair: stopped \$(echo \$victims | wc -w) container process(es)\"")
    }

    /** 解析 [PROBE_COMMAND] 的每一行；坏行 / 非法 PID 一律丢弃，不猜测。 */
    fun parse(output: String): List<ContainerProcess> = output.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size != 4) return@mapNotNull null
            val pid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val ppid = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            val rssKb = parts[2].trim().toLongOrNull() ?: 0L
            if (pid <= 0) return@mapNotNull null
            ContainerProcess(pid = pid, ppid = ppid, rssKb = rssKb, command = parts[3].trim())
        }
        .sortedBy { it.pid }
        .toList()
}

/** 容器内一个进程的快照；[isSessionShell] 为真时 UI 禁止结束。 */
data class ContainerProcess(
    val pid: Int,
    val ppid: Int,
    val rssKb: Long,
    val command: String,
) {
    val isSessionShell: Boolean get() = command.contains(RootCommandRunner.SESSION_ENTRY)
}
