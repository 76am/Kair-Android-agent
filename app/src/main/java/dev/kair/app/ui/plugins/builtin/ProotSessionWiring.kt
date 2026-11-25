package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.app.projectMounts
import dev.kair.app.core.proot.ContainerSession
import dev.kair.app.core.proot.ProtectedProcesses
import dev.kair.app.core.proot.PtySession
import dev.kair.app.core.proot.RootfsManager
import dev.kair.app.core.proot.SessionChannel
import dev.kair.app.core.proot.SessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 容器会话的装配与启动（从 `ProotPlugin` 抽出，守住它的体量线；零 UI 依赖）。
 *
 * 帧协议会话与用户终端 PTY 共享同一个 `ContainerSessionHost`（任务文档 §3 D3），
 * 启停顺序由 host 串行；PTY 起不来只置自己的 Error，不阻塞帧协议会话。
 * U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）。
 */

/** 用帧协议会话的启动三元组构造器给 PTY 复用（D2-6：argv/env 只有一套）。 */
internal fun createPtySession(
    container: AppContainer,
    manager: RootfsManager,
    session: ContainerSession,
    resolveChannel: suspend () -> SessionChannel,
): PtySession = PtySession(
    manager = manager,
    mounts = { container.projectMounts() },
    session = session,
    resolveChannel = resolveChannel,
)

/** 常驻会话启动 + 受保护进程恢复；起之前先判会话是否已被替换。 */
internal fun startContainerSession(
    container: AppContainer,
    manager: RootfsManager,
    session: ContainerSession,
    pty: PtySession,
) {
    container.scope.launch {
        container.containerSessionHost.replace(session, pty)
        manager.state.first { manager.isReady }
        // 重激活可能已替换会话：别把已被淘汰的实例拉起来。
        if (container.containerSessionHost.session.value !== session) return@launch
        session.start()
        // 会话就绪后恢复受保护进程（启动清扫刚跳过它们）。脚本先写进 rootfs 再
        // `exec("sh <file>")`：会话逐行读命令，多行脚本直接下发会被拆坏（见 ProtectedProcesses）。
        if (session.state.value is SessionState.Running) {
            ProtectedProcesses.installScript(manager.rootfsDir, container.protectedProcesses.entries.value)
                ?.let { session.exec("sh $it") }
        }
        // PTY 通道独立启动：起不来只会把自己的状态置 Error（终端面板显示原因，不阻塞帧协议会话）。
        pty.start()
    }
}
