package dev.kair.app.app

import android.os.Process
import dev.kair.app.core.maintenance.PermissionRepairReport
import dev.kair.app.core.maintenance.WorkspacePermissionRepair
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.proot.RootCommandRunner
import dev.kair.app.core.proot.TrashMounts
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.ContainerFileBackend
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.LocalFileBackend
import java.io.File

/**
 * 工作区文件访问：从 [AppContainer] 搬出的 `filesFor` / `fileBackend` /
 * `repairWorkspacePermissions`（U31 3a）。
 *
 * 三者的共同点是「按插件现状解析真实文件能力」：文件后端随 `commandRunner` 热变更实时解析；
 * 权限修复走**真 `su`**（U78 起不再交给当前容器后端 —— proot 的 `chown` 是假成功）。
 *
 * 依赖只有插件注册表、回收站目录、存储后端与真 root 执行器；不反向持有容器。
 * 真 root 执行器由容器插件在 activate 时 [attachRootRunner] 装入（U78）。
 */
internal class WorkspaceFileAccess(
    private val plugins: PluginRegistry,
    private val trashDir: File,
    private val storage: () -> StorageBackend,
) {

    /** 真 root 执行器（ProotPlugin 装入）；null = 容器插件未激活，权限修复只能走应用侧重建。 */
    @Volatile
    private var rootRunner: RootCommandRunner? = null

    /** U78：容器插件激活时装入真 root 执行器（重新激活覆盖）；不参与请求装配。 */
    fun attachRootRunner(runner: RootCommandRunner) {
        rootRunner = runner
    }

    /** 供 UI 复用文件后端（与工具同源）；同样带「本地优先 + 容器升级」能力。 */
    fun fileBackend(workspace: File): FileBackend = filesFor(workspace)

    /**
     * 工作区权限自检 / 修复：把插件声明的数据路径与各项目 `.kair` 里「应用读写不了」
     * 的文件，交给**真 `su`** 以 root 执行 `chown -R`（U78 起不再经容器后端）。
     *
     * 背景：显式提权（`su -c`）产生的文件归 root（常见 600），应用进程 `java.io.File`
     * 直接读会失败（如记忆插件读 `.kair/memory.md`）。proot 的 `fake_id0` 会把 chown
     * 伪装成成功，所以这条路径必须走真 root，并且用 `stat -c %u` 自证（见 [WorkspacePermissionRepair]）。
     *
     * - 默认只处理**精准范围**（插件声明的数据路径 + `.kair` + 回收站），不遍历整棵工作区；
     * - [fullWorkspace] = true 时只修 [scope] 这一棵（调用方传当前项目的工作区），整棵目录递归（慢，仅手动触发）；
     * - 拿不到真 root 或 chown 失败时，可读文件以应用身份重建（同目录临时文件 + rename，不需要 root）；
     *   不可读的文件与目录仍计失败。
     * [scope] 由调用方显式传入（UI 传 `activeSessionWorkspace()`），本函数不自己读「当前会话」（§10.4）。
     */
    suspend fun repairWorkspacePermissions(
        scope: File?,
        fullWorkspace: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): PermissionRepairReport {
        val root = rootRunner
        val rootRun: (suspend (String) -> CommandOutput?)? =
            root?.let { runner -> { command -> runner.runAsRoot(command) } }
        val workspaces = if (fullWorkspace) {
            // 只修显式传进来的那棵树；null（无活跃会话）就是空，绝不回退到任何全局目录。
            listOfNotNull(scope).filter { it.isDirectory }
        } else {
            storage().listProjects().map { storage().workspaceDir(it) }.filter { it.isDirectory }
        }
        val declared = plugins.installed.flatMap { plugin ->
            plugins.pluginData(plugin.id).map { it.path }
        }
        return WorkspacePermissionRepair(uid = Process.myUid()).repair(
            workspaces = workspaces,
            declaredPaths = { declared },
            rootRun = rootRun,
            fullWorkspace = fullWorkspace,
            onProgress = onProgress,
            // 回收站不在任何工作区里（宿主私有目录），但容器挂到 /trash：
            // 显式提权留下的 600 文件同样会让面板删不掉 / 恢复不了。
            extraMounts = listOf(trashDir to TrashMounts.CONTAINER),
        )
    }

    /**
     * 按当前插件解析文件后端：有插件提供命令后端（容器在场）时用
     * [ContainerFileBackend]（本地快路径 + 失败升级到容器 root），否则用本地后端。
     * 每次请求实时解析，插件热变更即时生效。
     */
    fun filesFor(workspace: File): FileBackend {
        val runner = plugins.commandRunner()
        return if (runner != null) ContainerFileBackend(runner, workspace)
        else LocalFileBackend(workspace.path)
    }
}
