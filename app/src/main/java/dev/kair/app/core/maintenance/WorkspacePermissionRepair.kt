package dev.kair.app.core.maintenance

import dev.kair.app.core.tools.CommandOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 一次工作区权限自检的结果。 */
data class PermissionRepairReport(
    /** 实际检查过的路径数（无需修复的不计）。 */
    val checked: Int = 0,
    val repaired: Int = 0,
    val failed: Int = 0,
) {
    val hasChanges: Boolean get() = repaired > 0
}

/**
 * 工作区权限自检与修复。
 *
 * 背景：显式提权（`su -c`）产生的文件归 root 所有（常见 600）。应用进程用 `java.io.File`
 * 直接读会失败（典型：记忆插件读 `.kair/memory.md`）。
 *
 * 修复只做**精准范围**，不遍历整个工作区：每个项目工作区的 `.kair`，以及已安装
 * 插件通过 `declareData` 声明的路径。整棵工作区递归由调用方显式选择
 * （[fullWorkspace]），避免大仓库 / `node_modules` 在启动时被全量 `chown`。
 *
 * 修复命令交给**真 `su`**（U78 起不再交给容器后端）：proot 的 `fake_id0` 会把 `chown`
 * 伪装成成功，界面报「已修复」而 `stat -c %u` 仍是 0。命令末尾用 `stat -c %u` 自证 ——
 * 只有属主真的变成 [uid] 才算 repaired；否则降级 [adoptWithoutRoot]（应用侧重建可读文件），
 * 仍不行就如实计 failed。
 */
class WorkspacePermissionRepair(
    /** 修复后文件要归的目标 uid（应用进程 uid，由宿主注入，core 不依赖 Android）。 */
    private val uid: Int,
) {

    suspend fun repair(
        workspaces: List<File>,
        /** 某项目工作区里、插件声明的数据路径（相对或绝对）。 */
        declaredPaths: (File) -> List<String>,
        /**
         * 真 root 执行（`su -c <command>`）：返回 null = 本机拿不到 su。
         * 只有它的输出能证明 chown 真的生效 —— 容器后端的 chown 不算（U78 的假成功）。
         */
        rootRun: (suspend (String) -> CommandOutput?)?,
        fullWorkspace: Boolean = false,
        onProgress: (String) -> Unit = {},
        /** 是否认为该路径需要修复；缺省按应用进程的真实读写能力判断（测试可注入）。 */
        shouldRepair: (File) -> Boolean = ::needsRepair,
        /**
         * 无 root 时的降级修复：把可读文件以应用身份重建。缺省 [adoptWithoutRoot]；
         * 测试可注入以模拟「不可读 / 目录」等无法降级的场景。
         */
        adopt: suspend (File) -> Boolean = ::adoptWithoutRoot,
        /**
         * 工作区之外、但容器里另有挂载点的宿主目录 → 容器内绝对路径。
         * 典型是回收站（`<storageRoot>/trash` → `/trash`）：它不在任何工作区里，
         * 却同样会被 root `container_shell` 留下 600 文件。同样只修真正坏掉的条目。
         */
        extraMounts: List<Pair<File, String>> = emptyList(),
    ): PermissionRepairReport {
        var checked = 0
        var repaired = 0
        var failed = 0

        // 容器命令的 cwd：任意一个真实工作区即可（回收站是绝对路径，不依赖 cwd）。
        val cwd = workspaces.firstOrNull { it.isDirectory }
        val scopes = ArrayList<Scope>()
        for (workspace in workspaces) {
            if (!workspace.isDirectory) continue
            if (fullWorkspace) {
                scopes += Scope(workspace, workspace, ".", workspace, force = true)
            } else {
                repairTargets(workspace, declaredPaths(workspace)).forEach { target ->
                    scopes += Scope(target, workspace, ".", workspace, force = false)
                }
            }
        }
        extraMounts.forEach { (dir, mountPoint) ->
            if (dir.exists()) {
                scopes += Scope(dir, dir, mountPoint.trimEnd('/'), cwd ?: dir, force = false)
            }
        }

        val seen = HashSet<String>()
        for (scope in scopes) {
            // 全量模式以「整棵目录都归应用」为目标；精准模式要下钻找坏条目
            // （父目录健康不代表里面的文件健康 —— root 只替换了其中一个文件）。
            val targets = if (scope.force) listOf(scope.scanRoot) else brokenEntries(scope.scanRoot, shouldRepair)
            for (file in targets) {
                if (!seen.add(file.absolutePath)) continue
                val relative = containerPath(scope, file) ?: continue
                checked++
                onProgress(relative)
                val chowned = if (rootRun == null) {
                    false
                } else {
                    // immutable（chattr +i）会直接让 chown 失败，必须先清标志再改属主；
                    // 没有 chattr 的精简 rootfs 由 `command -v` 短路，仍继续 chown。
                    // 末尾的 stat 是**自证**：不看它的输出，proot 的假 root 也能把 chown 报成 0。
                    val target = shellQuote(file.absolutePath)
                    val result = rootRun(
                        "command -v chattr >/dev/null 2>&1 && chattr -i -R $target 2>/dev/null; " +
                            "chown -R $uid:$uid $target && stat -c %u $target",
                    )
                    val owner = result?.output?.lineSequence()
                        ?.map { it.trim() }
                        ?.lastOrNull { it.isNotEmpty() }
                        ?.toIntOrNull()
                    result != null && !result.timedOut && result.exitCode == 0 && owner == uid
                }
                if (chowned || adopt(file)) repaired++ else failed++
            }
        }
        return PermissionRepairReport(checked, repaired, failed)
    }

    /** `.kair` + 插件声明路径，去重后转成容器内相对路径（工作区外的跳过）。 */
    private fun repairTargets(workspace: File, declared: List<String>): List<File> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<File>()

        fun add(path: String) {
            val file = resolveTarget(workspace, path)
            val relative = containerRelative(workspace, file) ?: return
            if (!seen.add(relative)) return
            out.add(file)
        }

        add(AGENT_DATA_DIR)
        declared.forEach(::add)
        return out
    }

    private companion object {
        /** 智能体自己的元数据目录：记忆文件、附件等（与 `AttachmentStore` 同源）。 */
        const val AGENT_DATA_DIR = ".kair"
    }
}

/**
 * 精准模式单次最多检查的条目数。父目录健康时也要下钻（坏文件可能只有一个），
 * 但回收站 / 附件目录可能很大，必须给启动路径一个上限。
 */
private const val MAX_SCAN_ENTRIES = 4000

/** 一个待修范围：扫描根 + 映射到容器路径的宿主根 + 容器路径前缀 + 命令 cwd + 是否强制整棵。 */
private data class Scope(
    val scanRoot: File,
    val pathRoot: File,
    val containerBase: String,
    val cwd: File,
    val force: Boolean,
)

/**
 * 在 [root] 下找出应用碰不了的条目（含 root 自身）。目录自身坏掉时不再下钻
 * （读不了子项）；最多扫 [MAX_SCAN_ENTRIES] 个条目。
 */
private fun brokenEntries(root: File, shouldRepair: (File) -> Boolean): List<File> {
    if (!root.exists()) return emptyList()
    if (shouldRepair(root)) return listOf(root)
    if (!root.isDirectory) return emptyList()

    val out = ArrayList<File>()
    val queue = ArrayDeque<File>()
    queue.add(root)
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_SCAN_ENTRIES) {
        val dir = queue.removeFirst()
        val children = dir.listFiles() ?: continue
        for (child in children) {
            if (++visited > MAX_SCAN_ENTRIES) break
            if (shouldRepair(child)) {
                out.add(child)
            } else if (child.isDirectory) {
                queue.add(child)
            }
        }
    }
    return out
}

/** 把 [file] 映射到 [scope] 的容器路径；不在该范围内返回 null。 */
private fun containerPath(scope: Scope, file: File): String? {
    val root = scope.pathRoot.absoluteFile.path.trimEnd('/')
    val target = file.absoluteFile.path
    val relative = when {
        target == root -> ""
        target.startsWith("$root/") -> target.removePrefix("$root/")
        else -> return null
    }
    return when {
        scope.containerBase == "." -> if (relative.isEmpty()) "." else "./$relative"
        relative.isEmpty() -> scope.containerBase
        else -> "${scope.containerBase}/$relative"
    }
}

/**
 * 应用进程是否真的「碰不了」这个路径：文件要可读可写（写不了时本地工具会走
 * 升级路径），目录还要可进入。不存在视为正常——没有文件就没有权限问题。
 */
internal fun needsRepair(file: File): Boolean {
    if (!file.exists()) return false
    return if (file.isDirectory) {
        !(file.canRead() && file.canWrite() && file.canExecute())
    } else {
        !(file.canRead() && file.canWrite())
    }
}

/** 降级重建的体积上限：超过它的可读文件不动，避免启动时搬大数据。 */
private const val MAX_ADOPT_BYTES = 4L * 1024 * 1024

/**
 * 无 root 时把**可读**文件转成应用所有：读出内容 → 同目录临时文件 → rename 覆盖。
 *
 * 替换 root 属主的文件只需要父目录可写（rename 语义），所以不需要 `CAP_CHOWN`。
 * 不可读（如 600）、目录、符号链接、超过 [MAX_ADOPT_BYTES] 的一律返回 false ——
 * 这些只能靠 root 修，如实计入失败。
 */
internal suspend fun adoptWithoutRoot(file: File): Boolean = withContext(Dispatchers.IO) {
    if (!file.isFile || !file.canRead()) return@withContext false
    if (Files.isSymbolicLink(file.toPath())) return@withContext false
    val parent = file.parentFile ?: return@withContext false
    if (!parent.canWrite()) return@withContext false
    if (file.length() > MAX_ADOPT_BYTES) return@withContext false
    try {
        val tmp = File.createTempFile(".kair-adopt-", ".tmp", parent)
        try {
            file.copyTo(tmp, overwrite = true)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        !needsRepair(file)
    } catch (_: Exception) {
        false
    }
}

/** 插件数据路径可能是工作区相对路径，也可能是绝对路径。 */
internal fun resolveTarget(workspace: File, path: String): File =
    if (path.startsWith("/")) File(path) else File(workspace, path)

/**
 * 映射到容器内路径：工作区在容器 `/projects/<目录名>`（bind 挂载），命令的 cwd 就是它，
 * 因此工作区内的路径一律取相对路径。工作区外返回 null —— 容器看不到，改不了。
 */
internal fun containerRelative(workspace: File, file: File): String? {
    val root = workspace.absoluteFile.path.trimEnd('/')
    val target = file.absoluteFile.path
    if (target == root) return "."
    val prefix = "$root/"
    if (!target.startsWith(prefix)) return null
    return "./" + target.removePrefix(prefix)
}

/** POSIX 单引号转义（内部工具用，不依赖 plugin-api 的同名实现）。 */
private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
