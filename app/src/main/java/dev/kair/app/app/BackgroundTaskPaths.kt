package dev.kair.app.app

import dev.kair.app.core.proot.Mount
import java.io.File

/**
 * 工作区的路径语义：宿主目录 → 容器路径、工具参数 → 工作区内相对路径。
 *
 * 单独成文件：管理器守住 500 行体量守卫（B8）；这里全是纯函数，测试同包直接调用。
 */

/**
 * 宿主工作区 → 持久进程里的容器路径；找不到覆盖它的挂载说明该工作区没挂进容器。
 *
 * 挂载点是工作区的**父目录**（`<base> → /projects`），所以按前缀映射：
 * `<base>` 本身是 `/projects`，`<base>/<项目目录>` 是 `/projects/<项目目录>`。
 * 精确挂载（旧实现的工作区直挂固定点、第三方后端）同样走这一条 —— 相对路径为空。
 */
internal fun containerPathOf(workspace: File, mounts: List<Mount>): String? {
    val target = runCatching { workspace.canonicalFile }.getOrNull() ?: return null
    for (mount in mounts) {
        val host = runCatching { mount.hostPath.canonicalFile }.getOrNull() ?: continue
        val relative = runCatching { target.toRelativeString(host) }.getOrNull() ?: continue
        val normalized = relative.replace(File.separatorChar, '/')
        // 相等时不同平台分别给 ""（Android/Linux）或 "."（部分 Windows JDK）。
        if (normalized.isEmpty() || normalized == ".") return mount.sessionPath
        if (normalized.startsWith("../") || normalized == ".." || File(relative).isAbsolute) continue
        return "${mount.sessionPath}/$normalized"
    }
    return null
}

/** 工具参数里的 cwd：只接受工作区内相对路径；绝对路径 / `..` / 反斜杠一律拒绝。 */
internal fun normalizeRelativeCwd(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value == "." || value == "./") return ""
    if (value.startsWith("/") || value.startsWith("~") || value.contains('\\')) return null
    val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) return null
    return parts.joinToString("/")
}
