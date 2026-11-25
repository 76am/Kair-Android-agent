package dev.kair.app.core.proot

import dev.kair.app.core.tools.PathUtils
import java.io.File

/**
 * 容器内全部项目的父目录：宿主「工作区父目录」整棵 bind 到这里，项目是 `/projects/<目录名>`。
 *
 * 只挂父目录（不是每个项目一条挂载）之后，新建 / 改名 / 删除项目都不再需要重新挂载：
 * 目录本身就是宿主文件的真实子目录，proot 的 `-b` 每次启动按它翻译。代价是 agent
 * 能看见并修改其它项目 —— 这是负责人 2026-10-30 明确选择的取舍（不再限制跨项目访问）。
 *
 * 目录名由存储层按项目名生成（`core/storage/Storage.kt` 的 `projectDirName`），
 * agent 的 cwd / 系统提示里的 `<cwd>` 都取 [containerWorkspacePath]。
 *
 * 与 [PathUtils.CONTAINER_PROJECTS] 共用同一个字面量：文件工具的 `/projects/...` →
 * 宿主路径映射依赖这两处一致。
 */
const val PROJECTS_ROOT = PathUtils.CONTAINER_PROJECTS

/**
 * 一条 bind 挂载：容器内 [sessionPath] ← 宿主目录 [hostPath]。
 *
 * proot 用它生成 `-b host:session` 参数；[RootCommandRunner.buildScript] 的维护挂载脚本
 * 也消费同一份清单（U78 起会话通道不再经过后者，见 ADR 0007）。
 *
 * [sessionPath] 必须以 `/` 开头；[relativePoint] 供维护脚本拼 `$R/<相对点>`。
 * **不得含空白**：维护脚本用 `for m in <清单>` 按空白分词。现在唯一的可变挂载点是
 * [PROJECTS_ROOT]，固定值满足这条。
 */
data class Mount(val sessionPath: String, val hostPath: File) {
    /** rootfs 内的相对挂载点（`/projects` → `projects`）：维护脚本 mkdir / mount 都用它。 */
    val relativePoint: String get() = sessionPath.removePrefix("/")
}

/** 宿主工作区父亲目录 → 容器 [PROJECTS_ROOT]；一次会话 / 一条命令都只挂这一条。 */
internal fun projectsMount(base: File): List<Mount> = listOf(Mount(PROJECTS_ROOT, base))

/**
 * 某个工作区在容器内的路径：`/projects/<宿主目录名>`。
 *
 * 目录名与宿主侧一致（工作区是父目录的真实子目录），所以这一句同时是「宿主的谁」与
 * 「容器里的哪」的映射，不需要 slug 表。
 */
internal fun containerWorkspacePath(workspace: File): String = "$PROJECTS_ROOT/${workspace.name}"

/**
 * 单工作区清单：宿主「工作区父目录」→ 容器 `/projects`。
 *
 * 一次性命令与常驻会话用同一份挂载，区别只在 cwd / 入口脚本：命令都从
 * [containerWorkspacePath] 开始，所以 `container_shell` 与终端看到的是同一棵目录树。
 */
internal fun workspaceMounts(workspace: File): List<Mount> =
    projectsMount(workspace.parentFile ?: workspace)
