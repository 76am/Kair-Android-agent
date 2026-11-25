package dev.kair.app.core.tools

import java.io.File
import java.util.Base64

/** 目录项：名字 + 是否目录。 */
data class DirEntry(val name: String, val isDirectory: Boolean)

/**
 * 文件访问后端：把工具的文件 I/O 从「直接 java.io.File」抽象出来，对称于
 * [CommandRunner]（命令执行后端）。
 *
 * - [LocalFileBackend]：宿主/应用视角（默认），行为与改动前的工具完全一致。
 * - [ContainerFileBackend]：容器在场时提供；读/写先走本地快路径，本地失败
 *   （够不到、EACCES）再升级到容器内执行 —— 容器以 root 跑，因此可以改写
 *   bash（root）建的文件，也能读到只有容器里才有的路径（如 `/etc`）。
 *
 * 注意：容器 `/projects/<目录名>` 与宿主工作区是 bind 挂载的**同一批文件**；
 * 差异只在权限（bash root vs 文件工具应用 uid）。所以读走本地既正确又快，
 * 升级路径只用于本地够不到/改不动的情况。
 */
interface FileBackend {
    /** 把用户给的路径解析到本后端的绝对路径。 */
    fun resolve(path: String): String

    suspend fun exists(path: String): Boolean
    suspend fun isDirectory(path: String): Boolean
    suspend fun readText(path: String): String

    /**
     * 文件字节数；无法高效获知时返回 null（调用方退化为读后检查）。
     *
     * 带默认实现是为了二进制兼容：第三方后端不实现也能继续加载。
     */
    suspend fun size(path: String): Long? = null

    /** 写入文本（自动创建父目录），UTF-8，字节精确。 */
    suspend fun writeText(path: String, content: String)

    suspend fun list(path: String): List<DirEntry>

    /** 创建目录（含父目录）；已存在视为成功。供文件管理器「新建文件夹」使用。 */
    suspend fun mkdir(path: String)

    /**
     * 删除文件或目录（目录递归删除）；不存在视为成功。
     *
     * **失败必须抛**（2026-11-13）：删完要复查，条目仍在就抛 [java.io.FileNotFoundException]。
     * 没有这个约定，调用方（文件面板）的「成功」分支必然执行 —— 用户看到「已删除」，
     * 条目却还在列表里（典型：root 属主 `700` 目录，应用连 `ls` 都进不去）。
     * 同族口径：U78 的 `chown` 假成功，都是「把发了命令当成事情成了」。
     */
    suspend fun delete(path: String)

    /** 复制文件或目录到 [to]（目录递归复制、覆盖同名），自动创建父目录。 */
    suspend fun copy(from: String, to: String)

    /** 移动 / 重命名（[from] 与 [to] 同目录即重命名），自动创建父目录。 */
    suspend fun move(from: String, to: String)

    /** 递归列出 [root] 下的普通文件（绝对路径），跳过 [skipDirs] 中的目录。 */
    suspend fun walk(root: String, skipDirs: Set<String>): List<String>

    /** 供展示的相对路径。 */
    fun relative(root: String, path: String): String
}

/**
 * 默认后端：与应用自身权限一致，直接用 `java.io.File`。
 * 行为与引入 FileBackend 之前的工具实现逐字节相同。
 */
class LocalFileBackend(private val cwd: String) : FileBackend {

    override fun resolve(path: String): String = PathUtils.resolveToCwd(path, cwd)

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun isDirectory(path: String): Boolean = File(path).isDirectory

    override suspend fun readText(path: String): String = File(path).readText()

    override suspend fun size(path: String): Long? = File(path).length()

    override suspend fun writeText(path: String, content: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun list(path: String): List<DirEntry> {
        // 用 java.nio.file.DirectoryStream 列目录，而不是 File.listFiles()。
        // File.listFiles() 底层走 JNI 的 NewStringUTF，遇到非法 UTF-8 的文件名（如 f2fs
        // 损坏条目、某些 App 创建的怪文件）会触发 CheckJNI abort 直接杀进程，无法捕获。
        // DirectoryStream 用 Path 对象，不经过 NewStringUTF，能正确处理任意字节的文件名。
        val dir = java.nio.file.Paths.get(path)
        val entries = ArrayList<DirEntry>()
        try {
            java.nio.file.Files.newDirectoryStream(dir).use { stream ->
                for (entry in stream) {
                    val name = entry.fileName.toString()
                    if (name == "." || name == "..") continue
                    entries.add(DirEntry(name, java.nio.file.Files.isDirectory(entry)))
                }
            }
        } catch (_: Exception) {
            // 目录不可读/不存在，降级到旧行为
            return (File(path).listFiles() ?: emptyArray())
                .map { DirEntry(it.name, it.isDirectory) }
        }
        return entries
    }

    override suspend fun mkdir(path: String) {
        File(path).mkdirs()
    }

    override suspend fun delete(path: String) {
        val file = File(path)

        // 「还在吗」不能问 `File.exists()`：它把 `EACCES` 也吞成 false，于是「父目录不可搜索」
        // 被当成「本来就不存在」—— 不删也不抛，直接当成功返回（U84 的静默成功）。
        // 按错误类型分开才是对的：只有 `NoSuchFileException` 算「真没有」；看不到
        // （`AccessDeniedException`）与其它 IO 错误一律当「还在」，宁可报失败也不谎报删除。
        // `NOFOLLOW_LINKS` 顺带保留 U80 的断链符号链接语义：链接本体还在就读得到属性。
        fun stillThere(): Boolean = try {
            java.nio.file.Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            true
        } catch (_: java.nio.file.NoSuchFileException) {
            false
        } catch (_: java.io.IOException) {
            true
        }
        if (!stillThere()) return
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        // 自证：删不掉就抛，不许静默返回 —— 面板的提示语直接来自这里有没有异常。
        if (stillThere()) throw java.io.FileNotFoundException("$path: delete failed")
    }

    override suspend fun copy(from: String, to: String) {
        val src = File(from)
        val dst = File(to)
        dst.parentFile?.mkdirs()
        if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
        else src.copyTo(dst, overwrite = true)
    }

    override suspend fun move(from: String, to: String) {
        val src = File(from)
        val dst = File(to)
        dst.parentFile?.mkdirs()
        // renameTo 覆盖已有目标的行为依平台而异：目标已存在时先删，保证语义一致。
        if (dst.exists()) delete(to)
        if (src.renameTo(dst)) return
        copy(from, to)
        delete(from)
    }

    override suspend fun walk(root: String, skipDirs: Set<String>): List<String> {
        val result = ArrayList<String>()
        fun rec(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in skipDirs) continue
                    rec(child)
                } else {
                    result.add(child.path)
                }
            }
        }
        rec(File(root))
        return result
    }

    override fun relative(root: String, path: String): String =
        try {
            File(root).toURI().relativize(File(path).toURI()).path
        } catch (_: Exception) {
            File(path).name
        }
}

/**
 * 容器后端：工作区在容器内是 `/projects/<目录名>`（工作区父目录挂到 `/projects`，
 * 与 ProotCommandRunner / RootCommandRunner 的 bind 目标一致）。读/写先走
 * [LocalFileBackend]，本地失败才升级到容器内执行 —— 于是 bash（root）建的文件/目录、
 * 以及容器独有的路径都能被文件工具访问。
 *
 * 写入带自愈：本地写失败时先尝试「同目录临时文件 + rename 覆盖」，只要目录可写
 * 就能把 root 属主的文件换成应用自己的，权限恢复正常（下次本地读写无需升级）；
 * 目录也不可写才升级到容器 root，并在落盘后 `chmod 644`，保证应用进程可读。
 *
 * 路径映射：工作区内的路径转成**相对路径**（容器与本地回退两种模式下，命令
 * 的 cwd 都是工作区，相对路径两边都成立）；工作区外保持原样（`/sdcard` 两边
 * 都可用，`/etc` 等只有容器里才有）。
 */
class ContainerFileBackend(
    private val runner: CommandRunner,
    private val workspace: File,
    private val timeoutSeconds: Long? = 60,
) : FileBackend {

    private val local = LocalFileBackend(workspace.path)

    /**
     * 容器视图 → 宿主视图：`/projects[/...]` 是「工作区父目录」的挂载点，项目是它下面的
     * 真实子目录。文件工具统一在宿主侧执行，先把 `/projects/<x>` 换回
     * `<workspace 父目录>/<x>`，read / grep / find / read_image 才看到与 container_shell
     * 同一批文件。`/projects` 只会在容器会话的 `<cwd>` 里出现；无容器时不会走这条分支。
     */
    override fun resolve(path: String): String {
        val expanded = PathUtils.expandPath(path)
        val projectsPath =
            expanded == PathUtils.CONTAINER_PROJECTS ||
                expanded.startsWith("${PathUtils.CONTAINER_PROJECTS}/")
        val base = workspace.parentFile
        if (base != null && projectsPath) {
            val rest = expanded.removePrefix(PathUtils.CONTAINER_PROJECTS).trimStart('/')
            return if (rest.isEmpty()) base.path else File(base, rest).path
        }
        return local.resolve(path)
    }

    /**
     * 本地已看到的路径：是目录与否本地即有定论（容器 `/projects/<目录名>` 是宿主
     * 工作区的 bind 挂载，同一批文件），直接返回，**不再**为一次 `test -d` spawn 一个容器
     * 进程。原实现 `local.isDirectory(path) || testFlag("-d", path)` 会让每个
     * **已存在的普通文件**都补一次容器执行 —— `read` 工具每次读文件都因此多跑一条
     * 容器命令（chroot 模式下即多一次 `su` 调用，面具随之多发一条授权通知）。
     * 同类里 [list] / [walk] 早就是「本地优先」的写法，这里只是对齐。
     *
     * 本地看不到的路径才升级到容器确认（容器可能是唯一能看到它的地方）。
     */
    override suspend fun isDirectory(path: String): Boolean =
        if (local.exists(path)) local.isDirectory(path) else testFlag("-d", path)

    override suspend fun exists(path: String): Boolean =
        local.exists(path) || testFlag("-e", path)

    override suspend fun readText(path: String): String =
        try {
            local.readText(path)
        } catch (e: Exception) {
            containerRead(path)
        }

    override suspend fun size(path: String): Long? {
        // 本地可见（工作区是 bind 挂载，绝大多数情况走这里）直接 stat，零进程开销。
        if (local.exists(path)) return local.size(path)
        // 容器独有的路径：在容器内 stat，避免调用方（read / grep / 文件编辑器）
        // 因为拿不到大小而把超大文件整体读进内存。
        val out = run("stat -c %s ${shellQuote(containerPath(path))}")
        if (out.exitCode != 0) return null
        return out.output.trim().toLongOrNull()
    }

    override suspend fun writeText(path: String, content: String) {
        try {
            local.writeText(path, content)
            return
        } catch (_: Exception) {
            // 本地改不动（root 属主的文件/目录，EACCES）→ 走下面的自愈路径。
        }
        // 自愈：同目录临时文件 + rename 覆盖。替换 root 属主的文件不需要文件本身可写，
        // 只要目录可写即可；产物因此归应用所有、权限正常，下次本地读写都不用再升级。
        // 目录也写不动时才升级到容器 root。
        if (replaceByRename(path, content)) return
        containerWrite(path, content)
    }

    /**
     * 同目录临时文件 + rename 覆盖 [path]，成功返回 true。
     *
     * 只用于本地写失败后的自愈。不复用符号链接：rename 会把链接本身换成普通文件，
     * 改变原有语义，这种情况交给容器升级路径（重定向会跟随链接）。
     */
    private fun replaceByRename(path: String, content: String): Boolean {
        val target = File(path)
        if (target.isDirectory) return false
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) return false
        val dir = target.parentFile ?: return false
        val tmp = File(dir, ".${target.name}.kair-tmp")
        return try {
            tmp.writeText(content)
            if (tmp.renameTo(target)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    override suspend fun list(path: String): List<DirEntry> =
        if (local.exists(path)) local.list(path) else containerList(path)

    // 变更类操作先走本地快路径，本地没做到（root 属主的文件/目录，EACCES）再升级到
    // 容器内以 root 执行 —— 与 read/write 的策略一致。判定方式：本地执行后复查结果。
    override suspend fun mkdir(path: String) {
        local.mkdir(path)
        if (!local.exists(path)) run("mkdir -p ${shellQuote(containerPath(path))}")
    }

    override suspend fun delete(path: String) {
        // 宿主视图「看得见」才是本地快路径的前提。宿主看不见有两种：真不存在，以及
        // 父目录 root 属主 `700`（应用连 `stat` 都 EACCES）—— 后者删不掉也不能报成功，
        // 所以不能拿 `!local.exists` 当「删除成功」的结论（U80 补漏）。
        val visibleLocally = local.exists(path)
        // 本地的「删不掉」不是终点：`LocalFileBackend.delete` 现在会抛（U80），
        // 而容器档要的正是在本地失败后升级到容器 root —— 异常必须就地吞掉，
        // 否则 `root` 属主但目录可写的条目会被误报成删除失败。
        if (visibleLocally) runCatching { local.delete(path) }
        if (visibleLocally && !local.exists(path)) return
        val target = shellQuote(containerPath(path))
        run("rm -rf $target")
        if (local.exists(path)) {
            // 不可变属性（`chattr +i`）连 root 都删不掉，必须先清掉再删；
            // 没有 chattr（精简 rootfs）时 `command -v` 会短路，仍执行 rm。
            run("command -v chattr >/dev/null 2>&1 && chattr -i -R $target 2>/dev/null; rm -rf $target")
        }
        // 自证：本地与容器都没删掉就抛。容器档的 `--root-id` 只是伪造 uid，
        // 文件访问仍按真实身份判定，`700` 属目录它一样删不掉。
        if (local.exists(path)) throw java.io.FileNotFoundException("$path: delete failed")
        // 宿主看不见的路径（父目录不可 stat）本地复查恒为「不存在」，只能问容器；
        // 走到这里说明 `visibleLocally` 为假，容器说还在就是没删掉。
        if (!visibleLocally && testFlag("-e", path)) {
            throw java.io.FileNotFoundException("$path: delete failed")
        }
    }

    override suspend fun copy(from: String, to: String) {
        local.copy(from, to)
        if (!local.exists(to)) {
            run("cp -r ${shellQuote(containerPath(from))} ${shellQuote(containerPath(to))}")
        }
    }

    override suspend fun move(from: String, to: String) {
        if (local.exists(from)) local.move(from, to)
        if (local.exists(from)) {
            val source = shellQuote(containerPath(from))
            // 回收站里的 immutable 文件连 root 都 rename 不动：mv 前先清标志。
            run(
                "command -v chattr >/dev/null 2>&1 && chattr -i -R $source 2>/dev/null; " +
                    "mv $source ${shellQuote(containerPath(to))}",
            )
        }
    }

    // 遍历走本地视图：容器能看到的普通文件与本地是同一批（/workspace 为 bind
    // 挂载）；容器独有目录的递归遍历暂不支持（罕见，且会为每个文件 spawn 一次
    // 容器进程，代价过高）。
    override suspend fun walk(root: String, skipDirs: Set<String>): List<String> =
        local.walk(root, skipDirs)

    override fun relative(root: String, path: String): String = local.relative(root, path)

    // ---- 升级路径（容器内执行） --------------------------------------------

    private suspend fun testFlag(flag: String, path: String): Boolean =
        run("test $flag ${shellQuote(containerPath(path))}").exitCode == 0

    private suspend fun containerRead(path: String): String {
        val out = run("cat -- ${shellQuote(containerPath(path))}")
        if (out.exitCode != 0) {
            throw java.io.FileNotFoundException("$path: ${out.output.trim().ifEmpty { "read failed" }}")
        }
        return out.output
    }

    private suspend fun containerList(path: String): List<DirEntry> {
        val out = run("ls -1ap ${shellQuote(containerPath(path))}")
        if (out.exitCode != 0) return emptyList()
        // `-p` 会给目录名补尾斜杠，于是当前/上级目录显示为 `./`、`../`；
        // 先归一化成带尾斜杠语义的条目，再剔除 . 与 ..。
        return out.output.lineSequence()
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith("/")) DirEntry(it.dropLast(1), true) else DirEntry(it, false) }
            .filter { it.name != "." && it.name != ".." }
            .toList()
    }

    /**
     * 用 base64 走管道落盘，保证与 [content] 字节精确（heredoc 会多补一个
     * 换行，`printf '%s'` 单引号又扛不住任意内容）。按块切分是为了避开
     * 单条 shell 命令的参数长度上限。
     */
    private suspend fun containerWrite(path: String, content: String) {
        val target = shellQuote(containerPath(path))
        val parent = containerPath(path).substringBeforeLast('/', "")
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val chunks = encoded.chunked(MAX_BASE64_CHUNK)

        if (chunks.isEmpty()) {
            run("${mkdirPrefix(parent)}: > $target")
            run("chmod 644 $target")
            return
        }
        chunks.forEachIndexed { index, chunk ->
            val redirect = if (index == 0) ">" else ">>"
            val prefix = if (index == 0) mkdirPrefix(parent) else ""
            run("${prefix}printf '%s' ${shellQuote(chunk)} | base64 -d $redirect $target")
        }
        // 容器内以 root 落盘的文件归 root 所有，默认权限未必让应用进程可读（如 600）。
        // 补一次 chmod：否则依赖 java.io.File 的插件（如记忆插件）读不到，只能反复升级。
        run("chmod 644 $target")
    }

    private fun mkdirPrefix(parent: String): String =
        if (parent.isEmpty()) "" else "mkdir -p ${shellQuote(parent)} && "

    /** 工作区内的路径转相对路径（cwd 即工作区）；工作区外原样返回。 */
    private fun containerPath(path: String): String {
        val ws = workspace.path
        return when {
            path == ws -> "."
            path.startsWith("$ws/") -> path.removePrefix("$ws/")
            else -> path
        }
    }

    private suspend fun run(command: String): CommandOutput =
        runner.run(command, workspace, timeoutSeconds)

    companion object {
        /** base64 分块大小：单条命令里 `printf '%s' '<chunk>'` 的长度上限。 */
        internal const val MAX_BASE64_CHUNK = 12_000
    }
}

/** POSIX 单引号转义：安全地把任意文本嵌进 shell 命令。 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
