package dev.kair.app.app

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U60 的源码守卫（UI 无 JVM 底座，结构类判据只能扫源码）：
 *
 * 1. `background_task` 不得再经会话 `exec`（否则会话 shell 一卡，任务一起死）；
 * 2. 脱离进程必须**前台跑**，不许出现 `setsid` / `startCommand`（那是「送进会话」的形状）；
 * 3. 终端面板必须有「重启会话」按钮；U59 起调 `host.restart()`（host 内部先停后起两条通道）；
 * 4. `exec()` 必须**等自己的退出帧再取输出**（`ContainerSession` 要真 su / proot，无 JVM 底座）；
 * 5. `cancelAll` 必须**先摘进程句柄再取消 watcher**（竞态窗口小，行为用例实测抓不住）；
 * 6. 终端只有**一条**渲染路径（U83 删掉行式终端，U59 验收点 V2 随之撤销）：
 *    `terminalScreen()` 判据还在、面板确实调它、旧那套 UI 不得回来、失败原因不得静默。
 *    `ContainerSession.exec()` 仍不得删 —— 那是 agent `container_shell` 的通道。
 *
 * 一律扫 `KotlinSourceScanner.stripped()`（剥注释后的源码）：注释里提 `exec` 不算。
 * **扫描落空不等于通过**（找不到文件即 error）。
 */
class BackgroundTaskDetachGuardTest {

    private val managerPath = "app/src/main/java/dev/kair/app/app/BackgroundTasks.kt"
    private val detachPath = "app/src/main/java/dev/kair/app/app/BackgroundTaskDetach.kt"
    private val terminalPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/TerminalPlugin.kt"
    private val hostPath = "app/src/main/java/dev/kair/app/core/proot/ContainerSessionHost.kt"
    private val screenPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/TerminalScreen.kt"
    private val sessionPath = "app/src/main/java/dev/kair/app/core/proot/ContainerSession.kt"

    private fun stripped(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    @Test
    fun `background tasks never go through the session exec lock`() {
        listOf(managerPath, detachPath).forEach { path ->
            val code = stripped(path)
            assertFalse(
                "$path 不得出现会话 exec（否则会话 shell 卡死会连带后台任务）；" +
                    "重新接回去 = 退回 U60 修的故障",
                Regex("""\.exec\s*\(""").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `detached launch runs foreground on the proot channel`() {
        val code = stripped(detachPath)
        // 前台跑：不能出现 `setsid` / 旧的 startCommand；U78 起只有 proot 一个入口。
        assertFalse("脱离进程不得 setsid：那是「送进会话后立刻返回」的形状", code.contains("setsid"))
        assertFalse("不得复用 startCommand（它带后台化 `&`）", code.contains("startCommand"))
        assertTrue("proot 通道必须起独立进程", code.contains("spawnProcess("))
        assertTrue("收尾必须走进程树", code.contains("killTree("))
    }

    @Test
    fun `terminal panel has a restart button in every stable state`() {
        val code = stripped(terminalPath)
        val function = KotlinSourceScanner.scan(repoFile(terminalPath).readText(Charsets.UTF_8))
            .singleOrNull { it.name == "TerminalBody" }
            ?: error("$terminalPath 里找不到 TerminalBody —— 守卫扫描落空，不等于通过")
        val body = stripped(terminalPath).lines()
            .subList(function.startLine - 1, function.endLine)
            .joinToString("\n")

        assertTrue("重启按钮文案必须走 strings.xml", body.contains("terminal_restart_session"))
        // U59：按钮语义扩成「两条通道一起重启」，统一收口在 host.restart() 里先停后起；
        // 面板只负责调用，不在 UI 里各写一遍 stop/start（AGENTS §10.4）。
        assertTrue("按钮必须调 host.restart()", body.contains("host.restart()"))
        // 三态可点：enabled 只能排除过渡态，不能排除 Running / Error / Stopped。
        assertTrue(
            "按钮不得按 SessionState.Error 分支隐藏（那正是要修的症状）：\n$body",
            body.contains("state !is SessionState.Starting") && body.contains("state !is SessionState.Stopping"),
        )

        val restart = KotlinSourceScanner.scan(repoFile(hostPath).readText(Charsets.UTF_8))
            .singleOrNull { it.name == "restart" }
            ?: error("$hostPath 里找不到 restart —— 守卫扫描落空，不等于通过")
        val hostBody = stripped(hostPath).lines()
            .subList(restart.startLine - 1, restart.endLine)
            .joinToString("\n")
        assertTrue(
            "host.restart() 必须真的先停后起两条通道：\n$hostBody",
            hostBody.contains("pty?.stop()") &&
                hostBody.contains("session?.stop()") &&
                hostBody.contains("session?.start()") &&
                hostBody.contains("pty?.start()"),
        )
    }

    @Test
    fun `terminal keeps a single rendering path`() {
        // U59 验收点 V2（PTY 不可用时回退行式终端）已由 U83 显式撤销：行式终端跑的是同一个
        // proot 会话进程，容器不在它一样跑不了；容器在而 PTY 起不来属 PTY 自身缺陷，修缺陷
        // 而不是养第二套终端（负责人 2026-11-13 拍板）。这里钉的是撤销之后必须成立的形状：
        // **判据函数还在、面板确实调它、旧那套 UI 没有偷偷回来、失败原因没有静默**。
        val screen = stripped(screenPath)
        assertTrue(
            "PTY Running 必须判成网格面板：\n$screen",
            screen.contains("ptyState is PtyState.Running -> TerminalScreen.Pty"),
        )
        assertTrue(
            "没有可用的 PTY 时必须落到失败屏（不是第二套终端）：\n$screen",
            screen.contains("else -> TerminalScreen.Failed"),
        )
        val code = stripped(terminalPath)
        assertTrue(
            "面板必须真的调用判据函数，写了不接等于没修：\n$code",
            code.contains("terminalScreen(restarting, ptyState, state)"),
        )
        assertTrue(
            "Pty 分支必须渲染网格面板：\n$code",
            code.contains("TerminalScreen.Pty") && code.contains("PtyTerminalBody("),
        )
        // U83：行式终端的组件与它独用的文案都不许回来（这一屏正是负责人以为「老 UI 没删掉」的那个）。
        listOf(
            "LineTerminalBody",
            "terminal_no_tty_hint",
            "terminal_projects_hint",
            "terminal_input_hint",
            "terminal_panel_title",
        ).forEach { banned ->
            assertFalse("U83 已删除行式终端降级路径，$banned 不得重新出现：\n$code", code.contains(banned))
        }
        assertTrue(
            "PTY 失败时不许静默：必须把 PtyState.Error 的 message 交给界面：\n$code",
            code.contains("is PtyState.Error -> ptyState.message"),
        )
        assertTrue(
            "ContainerSession.exec() 不得删（agent 通道仍走帧协议，PTY 自己也会卡）",
            stripped(sessionPath).contains("suspend fun exec("),
        )
    }

    @Test
    fun `exec takes the output snapshot only after its own exit frame arrived`() {
        // outputOf 返回的是 StringBuilder 的快照，而 Kotlin 构造参数从左到右求值：
        // 写成 SessionResult(outputOf(entry), entry.done.await()) 会在命令刚下发时就取缓冲，
        // 终端每条命令都回空输出（退出码却是对的，所以症状很隐蔽）。B 审 P1 就是这个形状。
        val function = KotlinSourceScanner.scan(repoFile(sessionPath).readText(Charsets.UTF_8))
            .singleOrNull { it.name == "exec" }
            ?: error("$sessionPath 里找不到 exec —— 守卫扫描落空，不等于通过")
        val body = stripped(sessionPath).lines()
            .subList(function.startLine - 1, function.endLine)
            .joinToString("\n")
        val await = body.indexOf("entry.done.await()")
        val snapshot = body.indexOf("execQueue.outputOf(entry)")
        assertTrue("exec 里必须同时有等帧与取输出；找不到就是形状变了\n$body", await >= 0 && snapshot >= 0)
        assertTrue(
            "取输出必须在 entry.done.await() 之后，否则快照恒为空（终端没有输出）\n$body",
            await < snapshot,
        )
    }

    @Test
    fun `cancelAll takes the process handle before cancelling the watcher`() {
        // watcher 的 finally 摘的是同一把句柄，而且它一看到终态就自行 return → 走 finally。
        // 先 cancel 再摘，句柄会被它抢先摘走，cancelAll 扑空 → killAsync 不执行 → 进程成孤儿。
        // 为什么按源码钉而不是按行为钉：B 审实测把顺序改回去，BackgroundTasksTest 34 条全绿
        // （竞态窗口太小，行为用例抓不住），所以这条判据只能落在语句顺序上。
        val function = KotlinSourceScanner.scan(repoFile(managerPath).readText(Charsets.UTF_8))
            .singleOrNull { it.name == "cancelAll" }
            ?: error("$managerPath 里找不到 cancelAll —— 守卫扫描落空，不等于通过")
        val body = stripped(managerPath).lines()
            .subList(function.startLine - 1, function.endLine)
            .joinToString("\n")
        val take = body.indexOf("processes.remove(task.id)")
        val cancel = body.indexOf("?.cancel()")
        assertTrue("cancelAll 里必须既摘句柄又取消 watcher；找不到就是形状变了\n$body", take >= 0 && cancel >= 0)
        assertTrue(
            "必须先摘句柄再取消 watcher，否则它的 finally 抢先摘掉、这里漏杀进程\n$body",
            take < cancel,
        )
    }

    @Test
    fun `pty panel keeps the termux extra keys row and no separate input box`() {
        // U59 改版（用户对照 Termux 截图）：PTY 主路径 = 网格 + 底部两行扩展按键，
        // 不再放独立输入框（输入框只允许留在行式回退里）。
        val ptyPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/TerminalPtyPanel.kt"
        val code = stripped(ptyPath)
        assertTrue(
            "PTY 面板底部必须有扩展按键行（Termux 形态）：\n$code",
            code.contains("TerminalExtraKeys("),
        )
        assertFalse(
            "PTY 主路径不得再放独立输入框（那是行式回退的形态）：\n$code",
            code.contains("OutlinedTextField"),
        )
    }

    private fun repoFile(relativePath: String): File = findRepoRoot().resolve(relativePath)

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），形状守卫无法运行")
    }
}
