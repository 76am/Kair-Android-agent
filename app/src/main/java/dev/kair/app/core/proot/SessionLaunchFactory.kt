package dev.kair.app.core.proot

import java.io.File

/**
 * 容器会话的启动三元组构造（任务文档 §3 D2-6）：**帧协议会话与 PTY 共用同一套 argv / env**。
 *
 * U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）：直接复用 [ProotCommandRunner]
 * 的 argv / env（**三个 `PROOT_*` 变量**，U69 起无 `PROOT_L2S_DIR`）。
 *
 * 放在独立文件里是为了守住 `ContainerSession` 的体量线；本类零状态、零 Android 依赖。
 */
internal class SessionLaunchFactory(
    private val proot: ProotCommandRunner,
    private val scriptDir: () -> File,
) {

    /**
     * proot 档：`ProcessBuilder` 老路是 clean + putAll([ProotCommandRunner.buildEnv])；
     * PTY 走 native 时同样只传这三个变量（D2-6，不许继承宿主 environ）。
     */
    fun proot(mounts: List<Mount>, pty: Boolean = false): Result<SessionLaunch> = runCatching {
        scriptDir().mkdirs()
        val argv = if (pty) proot.buildPtyArgs(mounts) else proot.buildSessionArgs(mounts)
        SessionLaunch(path = argv.first(), argv = argv, env = proot.buildEnv())
    }
}
