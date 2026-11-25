package dev.kair.app.core.storage

import dev.kair.app.core.ai.Json
import dev.kair.app.core.model.ChatMessage
import kotlinx.serialization.encodeToString
import java.io.File

// Project / SessionMeta 契约位于 `:plugin-api` 模块的 StorageBackend.kt（同包）。

/**
 * 原子写：先写同目录临时文件，再改名覆盖。
 *
 * 直接 `writeText` 覆盖索引时，进程在写一半被杀 / 掉电会留下半截 JSON；下次启动解析失败
 * 会被当成「没有数据」，随后任意一次保存都会把空列表写回，索引从此永久丢失。
 */
internal fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    if (tmp.renameTo(file)) return
    // 某些文件系统不允许 rename 覆盖已存在文件：删掉旧文件后再试一次。
    file.delete()
    if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}

/**
 * 读索引；解析失败时把损坏文件另存为 `.corrupt-<时间戳>` 再返回空列表。
 * 保留副本是为了让「后续保存空列表」不至于把唯一一份数据彻底覆盖掉。
 */
private fun <T> readIndex(file: File, decode: (String) -> List<T>): List<T> {
    if (!file.exists()) return emptyList()
    return runCatching { decode(file.readText()) }.getOrElse {
        runCatching {
            file.copyTo(
                File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"),
                overwrite = true,
            )
        }
        emptyList()
    }
}

/**
 * 项目名 → 工作区目录名。
 *
 * 目录名按项目名生成（用户看得懂），但必须能安全落在所有平台上：
 * `/` `\` 与 Windows 保留字符换成 `-`；首尾空白 / 点去掉；过长的名字截断。
 * 清洗后为空时退回 `project`。展示名本身不受影响。
 */
internal fun projectDirName(name: String): String =
    name.trim()
        .map { if (it in "/\\:*?\"<>|" || it.code < 0x20 || it.code == 0x7F) '-' else it }
        .joinToString("")
        .trim('.', ' ')
        .take(60)
        .ifBlank { "project" }

/**
 * Persists projects and their workspace directories under [root].
 *
 * [warn] 只用于「该删而没删掉」这类**必须留痕但不足以让调用失败**的情况：core 不依赖 Android
 * （本包无一处 `android.util.Log`），所以日志由装配层注入，见 `AppContainer` 的 `fallbackStorage`。
 */
class ProjectStore(private val root: File, private val warn: (String) -> Unit = {}) {
    private val indexFile = File(root, "projects.json")
    private val dirsFile = File(root, "project-dirs.json")
    private val lock = Any()

    /** 项目 id → 工作区目录名。改名时跟着搬目录；映射丢失时按名字重新认领。 */
    @Volatile
    private var dirNames: Map<String, String> = emptyMap()

    init {
        dirNames = loadDirNames()
        migrate()
    }

    fun list(): List<Project> =
        readIndex(indexFile) { Json.decodeFromString<List<Project>>(it) }

    private fun save(projects: List<Project>) {
        root.mkdirs()
        writeAtomically(indexFile, Json.encodeToString(projects))
    }

    private fun saveDirNames(names: Map<String, String>) {
        root.mkdirs()
        writeAtomically(dirsFile, Json.encodeToString(names))
    }

    private fun loadDirNames(): Map<String, String> {
        if (!dirsFile.exists()) return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, String>>(dirsFile.readText()) }.getOrElse {
            runCatching {
                dirsFile.copyTo(
                    File(root, "${dirsFile.name}.corrupt-${System.currentTimeMillis()}"),
                    overwrite = true,
                )
            }
            emptyMap()
        }
    }

    fun create(name: String): Project = synchronized(lock) {
        val projects = list()
        val trimmed = validName(projects, name)
        val dirName = uniqueDirName(projectDirName(trimmed), takenDirs())
        workspaceBase().mkdirs()
        File(workspaceBase(), dirName).mkdirs()
        val project = Project(name = trimmed)
        save(projects + project)
        dirNames = dirNames + (project.id to dirName)
        saveDirNames(dirNames)
        project
    }

    fun delete(id: String) {
        synchronized(lock) {
            val projects = list()
            projects.firstOrNull { it.id == id }?.let { project ->
                val dir = workspaceDir(project)
                // 返回值过去被丢掉：删不掉（EBUSY —— 目录还挂在终端会话里 / 权限）时静默往下走，
                // 于是「项目没了、目录还在、名字还被占着」。这里至少把它留痕，
                // 残留的空目录由 [migrate] 在下次启动时收掉。
                if (!dir.deleteRecursively()) warn("无法删除项目工作区：${dir.absolutePath}")
            }
            // **先摘映射、再摘索引**：反过来的话，两步之间被系统强杀就会留下
            // 「索引里没有、映射里还有」的孤儿，它把目录名永久占住（`takenDirs()` 会算上它）。
            // 这个顺序崩溃只会让项目「复活」成空目录（下次启动按名认领），不丢名字。
            if (dirNames.containsKey(id)) {
                dirNames = dirNames - id
                saveDirNames(dirNames)
            }
            save(projects.filterNot { it.id == id })
        }
    }

    fun rename(id: String, name: String) {
        synchronized(lock) {
            val projects = list()
            val project = projects.firstOrNull { it.id == id } ?: return
            val trimmed = validName(projects, name, exceptId = id)
            val oldDirName = dirNames[id] ?: id
            val oldDir = File(workspaceBase(), oldDirName)
            val newDirName = uniqueDirName(projectDirName(trimmed), takenDirs() - oldDirName)
            if (newDirName == oldDirName) {
                save(projects.map { if (it.id == id) it.copy(name = trimmed) else it })
                return
            }
            workspaceBase().mkdirs()
            val newDir = File(workspaceBase(), newDirName)
            val moved = if (oldDir.isDirectory) oldDir.renameTo(newDir) else newDir.mkdirs()
            require(moved) { "Cannot move the project workspace: $oldDirName -> $newDirName" }
            try {
                // 先写映射、再写展示名：两份文件都落盘之前崩溃时，映射仍指向**已经搬好**的目录，
                // 下次启动能按名认领到数据。反过来（先写展示名）会留下「映射指旧目录、旧目录已搬空」，
                // 启动时重建一个空目录，用户数据变成没人认领的孤儿。
                val updated = dirNames + (id to newDirName)
                saveDirNames(updated)
                save(projects.map { if (it.id == id) it.copy(name = trimmed) else it })
                dirNames = updated
            } catch (e: Exception) {
                // 任一写入失败：目录搬回旧名、映射退回旧值，内存与磁盘保持一致；
                // 否则 workspaceDir 会指向不存在的目录，agent 在新目录里空跑。
                runCatching { newDir.renameTo(oldDir) }
                runCatching { saveDirNames(dirNames) }
                throw e
            }
        }
    }

    fun workspaceDir(project: Project): File = synchronized(lock) {
        File(workspaceBase(), dirNames[project.id] ?: ensureDirName(project))
    }

    fun workspaceBase(): File = File(root, "workspaces").apply { mkdirs() }

    /**
     * 初始化 / 映射丢失时的收口：给每个项目认领一个目录名。
     *
     * 优先认领磁盘上已经叫这个名字的目录（改名后再启动、或映射文件损坏都能接上）；
     * 否则把旧版的 UUID 目录搬成名字目录；都没有就建空目录。冲突按 `-2`、`-3` 去重。
     */
    private fun migrate() = synchronized(lock) {
        val projects = list()
        val names = loadDirNames().toMutableMap()
        var changed = false
        // 反向裁剪：映射里指向**已删项目**的条目直接扔掉（幂等）。
        // 下面那个只处理「项目在、映射缺」的正向认领永远碰不到这类条目，于是它们永久占名。
        // **必须排在「索引为空就返回」之前**：删掉最后一个项目恰恰是最容易留下孤儿的时刻。
        val live = projects.mapTo(HashSet()) { it.id }
        for (stale in names.keys - live) {
            names.remove(stale)
            changed = true
        }
        val mappedDirs = names.values.toMutableSet()
        val onDisk = workspaceBase().listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
            .toMutableSet()
        projects.forEach { project ->
            val known = names[project.id]
            if (known != null) {
                val dir = File(workspaceBase(), known)
                if (!dir.isDirectory) {
                    val legacy = File(workspaceBase(), project.id)
                    if (legacy.isDirectory && legacy.renameTo(dir)) return@forEach
                    dir.mkdirs()
                }
                return@forEach
            }
            val base = projectDirName(project.name)
            val existing = pickExistingDir(base, onDisk - mappedDirs)
            val name = if (existing != null) {
                existing
            } else {
                val legacy = File(workspaceBase(), project.id)
                val fresh = uniqueDirName(base, onDisk + mappedDirs)
                if (legacy.isDirectory) {
                    if (legacy.renameTo(File(workspaceBase(), fresh))) fresh else project.id
                } else {
                    File(workspaceBase(), fresh).mkdirs()
                    fresh
                }
            }
            names[project.id] = name
            mappedDirs += name
            onDisk += name
            changed = true
        }
        // 残留目录：**没人引用且为空**才删。空目录没有数据，删掉最多下次重建；
        // 非空的一律保留 —— 那可能是映射丢失的项目数据，会被上面的认领逻辑接回去，
        // 删了就是丢用户数据（这条是安全边界，`ProjectWorkspaceNamingTest` 有用例锁着）。
        val referenced = names.values.toSet()
        File(root, "workspaces").takeIf { it.isDirectory }?.listFiles()?.forEach { dir ->
            val reclaimable = dir.isDirectory && dir.name !in referenced && dir.listFiles()?.isEmpty() == true
            if (reclaimable && dir.delete()) changed = true
        }
        if (changed) saveDirNames(names)
        dirNames = names
    }

    /** 启动 / 映射丢失时，认领一个已经按名字存在的目录：精确名优先，其次最小的 `-N`。 */
    private fun pickExistingDir(base: String, available: Set<String>): String? {
        if (base in available) return base
        val prefix = "$base-"
        return available
            .mapNotNull { name ->
                val suffix = name.removePrefix(prefix).toIntOrNull()
                if (name.startsWith(prefix) && suffix != null) name to suffix else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun ensureDirName(project: Project): String = synchronized(lock) {
        dirNames[project.id]?.let { return@synchronized it }
        // 与 migrate 同一套认领规则：先认磁盘上已叫这个名字的目录（不是别的项目的），
        // 再退到唯一的 base-N —— 否则「映射刚丢、还没重启」时会凭空建一个 Name-2。
        val mapped = dirNames.values.toSet()
        val onDisk = workspaceBase().listFiles()?.map { it.name }.orEmpty()
        val base = projectDirName(project.name)
        val name = pickExistingDir(base, (onDisk - mapped).toSet())
            ?: uniqueDirName(base, onDisk.toSet() + mapped)
        File(workspaceBase(), name).mkdirs()
        dirNames = dirNames + (project.id to name)
        saveDirNames(dirNames)
        name
    }

    private fun takenDirs(): Set<String> =
        dirNames.values.toSet() + workspaceBase().listFiles()?.map { it.name }.orEmpty()

    private fun uniqueDirName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    private fun validName(projects: List<Project>, name: String, exceptId: String? = null): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Project name must not be blank" }
        require(projects.none { it.id != exceptId && it.name.equals(trimmed, ignoreCase = true) }) {
            "Project name already exists: $trimmed"
        }
        return trimmed
    }
}

/** Persists sessions and their message transcripts as JSON Lines. */
class SessionStore(private val root: File) {
    private val indexFile = File(root, "sessions.json")
    private val sessionsDir = File(root, "sessions").apply { mkdirs() }
    private val lock = Any()

    fun list(projectId: String? = null): List<SessionMeta> {
        val all = readIndex(indexFile) { Json.decodeFromString<List<SessionMeta>>(it) }
        val filtered = if (projectId == null) all else all.filter { it.projectId == projectId }
        return filtered.sortedByDescending { it.updatedAt }
    }

    private fun saveIndex(sessions: List<SessionMeta>) {
        root.mkdirs()
        writeAtomically(indexFile, Json.encodeToString(sessions))
    }

    fun create(projectId: String, name: String, model: String): SessionMeta = synchronized(lock) {
        val meta = SessionMeta(projectId = projectId, name = name, model = model)
        saveIndex(list() + meta)
        File(sessionsDir, "${meta.id}.jsonl").writeText("")
        meta
    }

    fun rename(id: String, name: String) {
        synchronized(lock) {
            saveIndex(
                list().map {
                    if (it.id == id) it.copy(name = name, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
    }

    fun touch(id: String) {
        synchronized(lock) {
            saveIndex(list().map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it })
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            saveIndex(list().filterNot { it.id == id })
            File(sessionsDir, "$id.jsonl").delete()
        }
    }

    fun append(sessionId: String, message: ChatMessage) {
        synchronized(lock) {
            val file = File(sessionsDir, "$sessionId.jsonl")
            // 文件不存在有两种可能：新会话首次写入，或**会话已被删除**后迟到的收尾回调。
            // 后者若继续 append，会把已删除的 .jsonl 重新创建出来（mkdirs + appendText），
            // 让「删除」静默失效。以索引为准判定，删除后一律丢弃。
            if (!file.exists() && list().none { it.id == sessionId }) return
            sessionsDir.mkdirs()
            file.appendText(Json.encodeToString(message) + "\n")
            touch(sessionId)
        }
    }

    /**
     * Overwrite the transcript after a compaction or an in-place edit/retract. The JSONL
     * file is rewritten wholesale so on-disk state matches the in-memory history.
     */
    fun replace(sessionId: String, messages: List<ChatMessage>) {
        synchronized(lock) {
            val file = File(sessionsDir, "$sessionId.jsonl")
            // 同 [append]：会话已删除时丢弃迟到的整段重写。
            if (!file.exists() && list().none { it.id == sessionId }) return
            sessionsDir.mkdirs()
            val text = buildString { messages.forEach { append(Json.encodeToString(it)).append('\n') } }
            writeAtomically(file, text)
            touch(sessionId)
        }
    }

    fun load(sessionId: String): List<ChatMessage> {
        val file = File(sessionsDir, "$sessionId.jsonl")
        // 只清自己这一条：其它会话的失败原因是它们各自的事实，不能因为本次读成功就抹掉。
        synchronized(lock) { loadErrors.remove(sessionId) }
        if (!file.exists()) return emptyList()
        // 逐行读而不是 readLines()：后者会先把整份日志（含内联图片时可达数十 MB）
        // 复制成字符串列表，低内存机型在打开长会话 / 附件回收时会被这一步顶到 OOM。
        val messages = ArrayList<ChatMessage>()
        // 打不开不能崩：外部工具把文件放进 App 私有目录时常常带上别的 uid / SELinux
        // 上下文，open 会抛 EACCES。此时返回空历史让界面进得去，而不是在组合期直接
        // 抛异常（Compose 里抛 = 整屏崩溃）。损坏的行同样逐行跳过。
        val failure = runCatching {
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        runCatching { Json.decodeFromString<ChatMessage>(line) }.getOrNull()?.let(messages::add)
                    }
                }
            }
        }.exceptionOrNull()
        if (failure != null) {
            // 不能静默：空会话和「本来就没历史」看起来一模一样，用户会以为数据被删了。
            // 记下路径与原因，由宿主按会话取走提示（见 [LocalStorage.takeLoadError]）。
            synchronized(lock) {
                loadErrors[sessionId] =
                    "会话文件读取失败：${file.path}\n${failure.message ?: failure.javaClass.simpleName}"
            }
        }
        return messages
    }

    /**
     * 各会话最近一次 [load] 的失败说明，键是 sessionId。
     *
     * 按会话分槽而不是单槽：附件回收 / 记忆摘要会遍历**别的**会话调 [load]，
     * 读成功会把单槽里的原因抹掉，导致用户回到出问题的会话时看不到任何提示。
     */
    private val loadErrors = mutableMapOf<String, String>()

    /** 取走 [sessionId] 在 [load] 中记下的失败说明（取走后清空，避免重复提示）。 */
    fun takeLoadError(sessionId: String): String? = synchronized(lock) { loadErrors.remove(sessionId) }
}

/**
 * 内置存储后端：本地文件实现（`projects.json` / `sessions.json` / `<id>.jsonl`）。
 * 由内置插件 `dev.kair.plugin.storage` 注册，同时作为内核的保底实现。
 */
class LocalStorage(root: File, warn: (String) -> Unit = {}) : StorageBackend {
    private val projectStore = ProjectStore(File(root, "projects"), warn)
    private val sessionStore = SessionStore(File(root, "sessions"))

    /**
     * 取走 [sessionId] 的读取失败原因（权限 / IO）。界面拿它弹提示，让用户知道
     * 会话是「读不出来」而不是「没有历史」，并据此自己修文件属主。
     */
    fun takeLoadError(sessionId: String): String? = sessionStore.takeLoadError(sessionId)

    override fun listProjects(): List<Project> = projectStore.list()

    override fun createProject(name: String): Project = projectStore.create(name)

    override fun deleteProject(id: String) {
        // 会话是项目的数据：项目删除后，它的会话索引项与落盘日志也必须一起清掉。
        // 只删项目会让 UI 里项目消失、而 sessions/ 下的 .jsonl 永远留着（文件管理器能看到），
        // 长期使用会积成大量孤儿文件。
        sessionStore.list(id).forEach { sessionStore.delete(it.id) }
        projectStore.delete(id)
    }

    override fun renameProject(id: String, name: String) = projectStore.rename(id, name)

    override fun workspaceDir(project: Project): File = projectStore.workspaceDir(project)

    /** 空项目时也返回稳定目录：常驻终端先起来、后建项目也能直接看到（容器挂的是父目录）。 */
    override fun workspaceBase(): File = projectStore.workspaceBase()

    override fun listSessions(projectId: String?): List<SessionMeta> = sessionStore.list(projectId)

    override fun createSession(projectId: String, name: String, model: String): SessionMeta =
        sessionStore.create(projectId, name, model)

    override fun renameSession(id: String, name: String) = sessionStore.rename(id, name)

    override fun touchSession(id: String) = sessionStore.touch(id)

    override fun deleteSession(id: String) = sessionStore.delete(id)

    override fun appendMessage(sessionId: String, message: ChatMessage) =
        sessionStore.append(sessionId, message)

    override fun replaceMessages(sessionId: String, messages: List<ChatMessage>) =
        sessionStore.replace(sessionId, messages)

    override fun loadMessages(sessionId: String): List<ChatMessage> = sessionStore.load(sessionId)
}
