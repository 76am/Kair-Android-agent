package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.proot.PtyState
import dev.kair.app.core.proot.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 终端面板渲染判据（U82 / U83）。两个重点：
 *
 * - **重启窗口**：`restart()` 先停后起，中间 PTY 是 `Stopped`、帧协议会话在
 *   `Stopping`/`Stopped`/`Starting` 之间，任何一格若判成 [TerminalScreen.Failed]，
 *   用户就会看到界面跳一下再回来。
 * - **PTY 不可用**：U83 删掉行式终端后只剩 [TerminalScreen.Failed] 一档，它只出原因、
 *   不出第二套终端；这四格必须都落在这里，不许再冒出一个可输入的界面。
 */
class TerminalScreenTest {

    private data class Case(
        val name: String,
        val restarting: Boolean,
        val pty: PtyState?,
        val session: SessionState,
        val expected: TerminalScreen,
    )

    private val cases = listOf(
        // ---- 重启窗口：三种中间态都必须占位 ----
        Case(
            "重启中 · PTY 已停 + 会话 Stopping（点下按钮的第一帧）",
            restarting = true,
            pty = PtyState.Stopped,
            session = SessionState.Stopping,
            expected = TerminalScreen.Starting,
        ),
        Case(
            "重启中 · PTY 已停 + 会话 Stopped（停完还没起的那段，真机上最长的窗口）",
            restarting = true,
            pty = PtyState.Stopped,
            session = SessionState.Stopped,
            expected = TerminalScreen.Starting,
        ),
        Case(
            "重启中 · PTY 已停 + 会话 Starting（帧协议会话先起）",
            restarting = true,
            pty = PtyState.Stopped,
            session = SessionState.Starting,
            expected = TerminalScreen.Starting,
        ),
        Case(
            "重启中 · PTY 还来不及停（按钮点下那一瞬）",
            restarting = true,
            pty = PtyState.Running,
            session = SessionState.Running,
            expected = TerminalScreen.Starting,
        ),
        // ---- 非重启：正常态与「没有终端」 ----
        Case(
            "PTY 在跑",
            restarting = false,
            pty = PtyState.Running,
            session = SessionState.Running,
            expected = TerminalScreen.Pty,
        ),
        Case(
            "PTY 正在起",
            restarting = false,
            pty = PtyState.Starting,
            session = SessionState.Running,
            expected = TerminalScreen.Starting,
        ),
        Case(
            "PTY 实例还没建、会话在起",
            restarting = false,
            pty = null,
            session = SessionState.Starting,
            expected = TerminalScreen.Starting,
        ),
        Case(
            "用户主动停掉：只说原因，不再给行式终端",
            restarting = false,
            pty = PtyState.Stopped,
            session = SessionState.Stopped,
            expected = TerminalScreen.Failed,
        ),
        Case(
            "PTY 起不来：给失败原因",
            restarting = false,
            pty = PtyState.Error("openpty 失败"),
            session = SessionState.Running,
            expected = TerminalScreen.Failed,
        ),
        Case(
            "shell 已退出：给失败原因",
            restarting = false,
            pty = PtyState.Exited(0),
            session = SessionState.Running,
            expected = TerminalScreen.Failed,
        ),
        Case(
            "只有帧协议会话（PTY 从未创建）：没有终端",
            restarting = false,
            pty = null,
            session = SessionState.Running,
            expected = TerminalScreen.Failed,
        ),
    )

    @Test
    fun `每一格状态组合都渲染成预期的屏`() {
        cases.forEach { case ->
            val actual = terminalScreen(case.restarting, case.pty, case.session)
            assertEquals("${case.name}：实际 $actual", case.expected, actual)
        }
    }

    @Test
    fun `重启窗口内没有任何一格会退出占位`() {
        val leaked = cases
            .filter { it.restarting && terminalScreen(it.restarting, it.pty, it.session) != TerminalScreen.Starting }
            .map { it.name }
        assertEquals("重启期间不占位的分支（会闪一下旧画面）：$leaked", emptyList<String>(), leaked)
    }

    @Test
    fun `PTY 不可用的四格都只出失败屏`() {
        val unavailable = cases.filter { !it.restarting && it.expected == TerminalScreen.Failed }
        assertEquals("PTY 不可用的场景数变了，检查判据是否被改窄", 4, unavailable.size)
        unavailable.forEach { case ->
            assertEquals(
                "${case.name}：没有 PTY 时不得渲染可输入的终端",
                TerminalScreen.Failed,
                terminalScreen(case.restarting, case.pty, case.session),
            )
        }
    }
}
