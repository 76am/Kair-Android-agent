package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 进程面板的纯逻辑：/proc 输出解析、容器标记、结束前校验。 */
class ContainerProcessesTest {

    @Test
    fun `marker is part of the clean container environment`() {
        assertTrue(ContainerEnv.pairs().contains(ContainerEnv.MARKER))
    }

    @Test
    fun `probe filters foreign processes and its own probe tree`() {
        val command = ContainerProcesses.PROBE_COMMAND

        // 只保留带容器标记的进程；探测自身与探测命令的子孙用 KAIR_PROBE_PID 排除。
        assertTrue(command.contains(ContainerEnv.MARKER))
        assertTrue(command.contains("KAIR_PROBE_PID"))
        assertTrue(command.contains("${'$'}d/environ"))
        assertTrue(command.contains("/proc/[0-9]*"))
        // 跳过祖先链，避免把本次 chroot / su / .kair-cmd 入口列出来。
        assertTrue(command.contains("skip=\"${'$'}skip ${'$'}p\""))
        assertTrue(command.contains("in *\" ${'$'}pid \"*"))
        // Kotlin 的 `$` 转义不能漏：这些是 shell 变量 / 命令替换，不是 Kotlin 模板。
        assertTrue(command.contains("self=${'$'}${'$'}"))
        assertTrue(command.contains("p=${'$'}(awk '/^PPid:/{print ${'$'}2}' \"/proc/${'$'}p/status\" 2>/dev/null)"))
        assertTrue(command.contains("pid=${'$'}{d#/proc/}"))
        assertTrue(command.contains("echo \"${'$'}pid|${'$'}ppid|${'$'}{rss:-0}|${'$'}cmd\""))
    }

    @Test
    fun `probe checks the container marker before the probe pid`() {
        val command = ContainerProcesses.PROBE_COMMAND
        val loop = command.indexOf("for d in /proc")
        // 标记判据一次滤掉绝大部分宿主进程；探测自身子孙的判据只在命中标记后才跑。
        // 真机进程表上千条，先按标记过滤与先按 PID 过滤的 fork 次数差别很实在。
        val marker = command.indexOf("^" + ContainerEnv.MARKER + "\$", loop)
        val probePid = command.indexOf("^KAIR_PROBE_PID=", loop)
        assertTrue("探测循环里找不到标记判据", marker > loop)
        assertTrue("探测循环里找不到 KAIR_PROBE_PID 判据", probePid > loop)
        assertTrue("标记判据必须排在 KAIR_PROBE_PID 之前（先省 fork，再排自身子孙）", marker < probePid)
    }

    @Test
    fun `kill command validates marker and protects the session shell`() {
        val command = ContainerProcesses.killCommand(20887)

        assertTrue(command.contains("pid=20887"))
        assertTrue(command.contains(ContainerEnv.MARKER))
        assertTrue(command.contains("\"${RootCommandRunner.SESSION_ENTRY}\""))
        assertTrue(command.contains("kill -TERM \"${'$'}pid\""))
        assertTrue(command.contains("kill -KILL \"${'$'}pid\""))
        assertTrue(command.contains("kill -0 \"${'$'}pid\""))
    }

    @Test
    fun `parse keeps command pipes and sorts by pid`() {
        val output = """
            34|12|1024|sh -c a|b
            not a line
            12|1|2048|python3 -m http.server 8080
            99|x|3|broken ppid
            0|1|1|pid 0 is not a process
        """.trimIndent()

        val parsed = ContainerProcesses.parse(output)

        assertEquals(listOf(12, 34), parsed.map { it.pid })
        assertEquals(1, parsed[0].ppid)
        assertEquals(2048L, parsed[0].rssKb)
        assertEquals("python3 -m http.server 8080", parsed[0].command)
        assertEquals("sh -c a|b", parsed[1].command)
    }

    @Test
    fun `session shell is marked and ordinary service is not`() {
        val session = ContainerProcess(1, 1, 0, "/bin/sh ${RootCommandRunner.SESSION_ENTRY}")
        val service = ContainerProcess(2, 1, 1024, "python3 -m http.server 8080")

        assertTrue(session.isSessionShell)
        assertFalse(service.isSessionShell)
    }

    // ---- 上一代清扫（R1） ----------------------------------------------------

    @Test
    fun `reap command skips the current generation but keeps a missing one`() {
        val command = ContainerProcesses.reapCommand("i0123456789ab")

        // 代际值来自调用方（会话启动时传 ContainerEnv.instance），不是脚本里现算的。
        assertTrue("代际值必须来自调用方", command.contains("cur='i0123456789ab'"))
        // 判据只能是「等于当前才 continue」（`&&`）。两头都是语义，缺一不可：
        // - 删掉整行 → 把本代正在跑的进程一起清掉；
        // - 写成 `|| continue`（必须有 instance 才跳过）→ KAIR_INSTANCE 缺失的历史遗留
        //   永远清不掉，而负责人 2026-10-31 报的正是那批。
        assertTrue(
            "缺少「等于本代就跳过、缺失按上一代处理」的判据",
            command.contains("grep -qx \"${ContainerEnv.INSTANCE}=\$cur\" && continue"),
        )
        assertTrue("victims 收集必须在跳过判据之后", command.indexOf("victims=\"\$victims") > command.indexOf("&& continue"))
    }

    @Test
    fun `reap command terminates then kills`() {
        val command = ContainerProcesses.reapCommand("i0123456789ab")
        val term = command.indexOf("kill -TERM \$victims")
        val grace = command.indexOf("sleep 1 </dev/null >/dev/null 2>&1")
        val kill = command.indexOf("kill -KILL \$victims")

        assertTrue("先 TERM", term > 0)
        assertTrue("TERM 后要留 1 秒收尾窗口", grace > term)
        assertTrue("睡眠之后才 KILL", kill > grace)
        assertTrue("只对非空 victims 执行", command.contains("if [ -n \"\$victims\" ]"))
    }

    @Test
    fun `reap command only touches container processes`() {
        val command = ContainerProcesses.reapCommand("i0123456789ab")
        val loop = command.indexOf("for d in /proc")
        val marker = command.indexOf("grep -qx '${ContainerEnv.MARKER}'")
        val instance = command.indexOf("${ContainerEnv.INSTANCE}=\$cur")

        assertTrue("必须先判容器标记", marker > loop)
        assertTrue("代际判据排在标记判据之后", instance > marker)
        // 整行匹配（-x）而不是子串：`KAIR_CONTAINER=10` 不能命中 `KAIR_CONTAINER=1`。
        assertTrue(command.contains("grep -qx"))
        assertFalse("不能用子串匹配标记", command.contains("grep -q '${ContainerEnv.MARKER}'"))
    }

    @Test
    fun `kill all protects the session shell and its own probe tree`() {
        val command = ContainerProcesses.killAllCommand("i0123456789ab")

        // 会话 shell 由「Linux 工作区」的停止按钮负责；从这里杀掉终端会失效但面板仍显示运行中。
        assertTrue(
            "缺少会话 shell 保护（会从面板把终端本体杀掉）",
            command.contains("case \"\$cmd\" in *\"${RootCommandRunner.SESSION_ENTRY}\"*) continue;; esac"),
        )
        // 这条命令自己在容器内执行、带当前 instance，必须靠 KAIR_PROBE_PID 跳过自己与子孙，
        // 否则会把自己打断在半路（只杀了一部分进程）。
        assertTrue(command.contains("export KAIR_PROBE_PID=\"\$self\""))
        assertTrue(command.contains("grep -q '^KAIR_PROBE_PID=' && continue"))
        assertTrue("只清本代", command.contains("grep -qx \"${ContainerEnv.INSTANCE}=\$cur\" || continue"))
    }

    // ---- 受保护进程（R1 续） --------------------------------------------------

    @Test
    fun `reap command skips registered protected commands verbatim`() {
        val command = ContainerProcesses.reapCommand(
            "i0123456789ab",
            protected = listOf("python3 -m http.server 8080", "node server.js"),
        )

        // 只有**受保护判据落在代际判据之后**才安全：先认本代、再认保护，两者都不命中的才被杀。
        val instance = command.indexOf("${ContainerEnv.INSTANCE}=\$cur")
        val protect = command.indexOf("case \"\$cmd\" in 'python3 -m http.server 8080'|'node server.js') continue;; esac")
        val victim = command.indexOf("victims=\"\$victims \$pid\"")
        assertTrue("缺受保护判据", protect > 0)
        assertTrue("受保护判据必须在代际判据之后", protect > instance)
        assertTrue("受保护判据必须在收 victims 之前", protect < victim)
        // 完全相等（case 的整串模式），不是子串 —— 否则保护 ...:8080 会顺带保护 ...:80801。
        assertTrue(command.contains("cmd=\$(tr '\\0' ' ' < \"\$d/cmdline\" 2>/dev/null | sed 's/ *\$//')"))
    }

    @Test
    fun `reap command omits the protected clause when nothing is registered`() {
        val command = ContainerProcesses.reapCommand("i0123456789ab")

        // 没有登记项时不生成空 case（空模式在 shell 里是语法错误）。
        assertFalse("不该出现命令行词表", command.contains("case \"\$cmd\" in"))
    }

    @Test
    fun `kill all skips protected commands`() {
        val command = ContainerProcesses.killAllCommand(
            "i0123456789ab",
            protected = listOf("python3 -m http.server 8080"),
        )

        val shell = command.indexOf("${RootCommandRunner.SESSION_ENTRY}")
        val protect = command.indexOf("case \"\$cmd\" in 'python3 -m http.server 8080') continue;; esac")
        assertTrue("缺受保护判据（点一次「全部结束」就会把刚保护的常驻服务杀掉）", protect > 0)
        assertTrue("受保护判据排在会话 shell 判据之后即可", protect > shell)
    }
}
