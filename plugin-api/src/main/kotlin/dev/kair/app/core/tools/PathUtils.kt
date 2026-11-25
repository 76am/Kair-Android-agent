package dev.kair.app.core.tools

import java.io.File

/**
 * Path resolution for tools, ported from pi's `path-utils.ts`.
 *
 * pi resolves relative paths against the working directory and allows absolute paths.
 * Android already sandboxes the app's own storage, so no extra confinement is applied;
 * this mirrors pi's behavior for porting fidelity.
 *
 * 容器路径桥接：`/workspace`（历史挂载点）与 `/sdcard` 在这里统一映射；容器**当前**的
 * 项目挂载点 `/projects/<目录名>` 需要「工作区父目录」才能换算，放在
 * [ContainerFileBackend.resolve] 里做 —— 只有它同时知道宿主工作区与容器视图。
 */
object PathUtils {
    private const val NARROW_NO_BREAK_SPACE = "\u202F"

    /** 容器内工作区挂载点（历史值；与 ProotCommandRunner / RootCommandRunner 的旧挂载一致）。 */
    const val CONTAINER_WORKSPACE = "/workspace"

    /**
     * 容器内项目根：宿主「工作区父目录」整棵 bind 到这里，项目是 `/projects/<目录名>`。
     *
     * 这里镜像 `Mount.kt` 的 `PROJECTS_ROOT`（plugin-api 不依赖 app，不能反向 import）。
     */
    const val CONTAINER_PROJECTS = "/projects"

    /** 容器内共享存储挂载点；宿主侧真实路径（镜像 SdcardMounts，避免跨包耦合）。 */
    const val CONTAINER_SDCARD = "/sdcard"
    const val HOST_SDCARD = "/storage/emulated/0"

    /** Normalize unicode spaces and strip a leading "@" (agent mentions files as @path). */
    fun expandPath(filePath: String): String =
        filePath.replace(NARROW_NO_BREAK_SPACE, " ").removePrefix("@").trim()

    /** Resolve a path relative to [cwd]. Absolute paths are kept. */
    fun resolveToCwd(filePath: String, cwd: String): String {
        val expanded = expandPath(filePath)
        // 容器视图 → 宿主视图：/workspace[/...] → 真实工作区；/sdcard[/...] →
        // 共享存储根。注意 "/workspaceX"、"/sdcardX" 不是挂载点，不映射。
        // /projects 需要工作区父目录，见 ContainerFileBackend.resolve。
        val normalized = when {
            expanded == CONTAINER_WORKSPACE -> cwd
            expanded.startsWith("$CONTAINER_WORKSPACE/") -> cwd + expanded.removePrefix(CONTAINER_WORKSPACE)
            expanded == CONTAINER_SDCARD -> HOST_SDCARD
            expanded.startsWith("$CONTAINER_SDCARD/") -> HOST_SDCARD + expanded.removePrefix(CONTAINER_SDCARD)
            else -> expanded
        }
        val file = File(normalized)
        return if (file.isAbsolute) file.path else File(cwd, normalized).path
    }

    fun resolveReadPath(filePath: String, cwd: String): String = resolveToCwd(filePath, cwd)
}
