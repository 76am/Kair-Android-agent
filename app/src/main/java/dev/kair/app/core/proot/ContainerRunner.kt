package dev.kair.app.core.proot

import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import java.io.File

/**
 * 容器执行通道路由：U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）。
 *
 * 保留本类是因为它仍是一个**具名的容器后端**：`WorkspaceFileAccess` / 组装层据此判断
 * 「容器后端是否在场」，而不是去认 `ProotCommandRunner` 这个具体实现。
 * 路由不再是「按设置挑选」，所以本类**不探测 root**、不读设置、不持有 `su`。
 */
class ContainerRunner(
    private val proot: CommandRunner,
) : CommandRunner {

    override val id: String get() = proot.id
    override val displayName: String get() = proot.displayName
    override val description: String get() = proot.description
    override val containerWorkspacePath: String? get() = proot.containerWorkspacePath
    override fun containerWorkspacePathFor(workspace: File): String? = proot.containerWorkspacePathFor(workspace)

    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?): CommandOutput =
        proot.run(command, workspace, timeoutSeconds)

    companion object {
        const val MODE_PROOT = "proot"
    }
}
