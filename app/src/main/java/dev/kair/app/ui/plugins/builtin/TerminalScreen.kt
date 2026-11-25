package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.proot.PtyState
import dev.kair.app.core.proot.SessionState

/**
 * 终端面板该渲染哪一屏。
 *
 * 抽成 [terminalScreen] 纯函数而不是散在 `if` 里，是因为这块判据已经被同一个坑绊过两次：
 * 「PTY 起来之前露出一屏旧画面」。U59 §12.6 处理了启动期，漏了**重启**期 ——
 * [dev.kair.app.core.proot.ContainerSessionHost.restart] 是先停后起，中间那一段里
 * PTY 是 [PtyState.Stopped]、帧协议会话在 [SessionState.Stopping] / `Stopped` / `Starting`
 * 之间来回，按「不在 Running 就回退旧终端」的老判据，点一下「重启会话」就会看到界面
 * 跳回行式终端再跳回来（真机 2026-11-13 复现）。
 */
internal enum class TerminalScreen {
    /** PTY 在跑：Termux 形态占满面板。 */
    Pty,

    /** 过渡态：只显示「正在打开终端…」，**不**渲染任何旧画面。 */
    Starting,

    /**
     * 没有可用的 PTY（起不来 / shell 退出 / 未运行）：只显示原因 + 「重启会话」。
     * U83 起不再回落到第二套终端 —— 行式终端跑的是同一个 proot 会话进程，兜不了底
     * （负责人 2026-11-13 拍板删除）。
     */
    Failed,
}

/**
 * @param restarting 是否处在 `ContainerSessionHost.restart()` 的停-起窗口内（host 置位）。
 *   这个标志**不能**由下面两个状态机推出来：重启中途与「用户主动停掉」在状态上完全同形
 *   （PTY `Stopped` + 会话 `Stopped`），而前者要占位、后者要如实显示「终端未运行」。
 * @param ptyState PTY 状态；PTY 实例不存在时为 null（还没起或已被清除）。
 */
internal fun terminalScreen(
    restarting: Boolean,
    ptyState: PtyState?,
    sessionState: SessionState,
): TerminalScreen = when {
    // 重启的停-起窗口：两条通道都在中间态，此时渲染谁都是上一代的残影。
    // 这一格**推不出来**（见上面 KDoc）：它必须由 host 显式置位。
    restarting -> TerminalScreen.Starting
    ptyState is PtyState.Running -> TerminalScreen.Pty
    ptyState is PtyState.Starting -> TerminalScreen.Starting
    // 会话自己在过渡（启动 / 停止）时同样别下结论：停止期没有可用的终端，启动期还没到。
    sessionState is SessionState.Starting || sessionState is SessionState.Stopping ->
        TerminalScreen.Starting
    else -> TerminalScreen.Failed
}
