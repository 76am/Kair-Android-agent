package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.PluginSettingsStore
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 记忆文件的解析 / 渲染预算 / 过期过滤 / 整理取材 / 备份（纯 JVM，无 Android 依赖）。
 *
 * 这一层是 §6 B 层全部九条的落点：给定同样的文件与设置，[render] 必须产出同样的字节
 * （段落缓存契约）；截断优先丢「近期最旧」；过期只影响注入、不删文件；整理结果必须更短
 * 才允许留下，否则按原始字节回滚。
 *
 * 文件格式（两段式，段标题是唯一的类型声明）：
 * ```
 * ## 稳定
 * - 2026-10-31: 用户要求所有提交信息用中文
 *
 * ## 近期
 * - 2026-11-08: main 在 2ae7938
 * ```
 * 没有段标题的旧文件整份按「稳定」解析（宁可保守保留，也不误删用户偏好）。
 */
object MemoryStore {

    /** 工作区相对路径；内置版沿用外部插件的数据位置，已有记忆直接继承。 */
    const val FILE_PATH = ".kair/memory.md"

    /** 备份目录（与文件同级，位于 `.kair/` 下）；只留最近 [BACKUP_KEEP] 份。 */
    const val BACKUP_DIR_NAME = "memory-backups"
    const val BACKUP_KEEP = 3

    /** 单行超过这个字数就点名（§4.6：不拒写、不自动改写）。 */
    const val MAX_LINE_CHARS = 200

    /** 整理取材的总字符预算（不是 maxChars —— 那是每条文本的截断上限）。 */
    const val DIGEST_CHARS = 6_000

    /** 单个回合文本进整理 prompt 前的截断上限（防止一条超长消息吃满预算）。 */
    const val DIGEST_TEXT_CHARS = 1_200

    /** 设置默认值（负责人 2026-11-08 拍板 1500；2026-11-19 按用户要求提到 2600，`maxLines` 取 40）。 */
    const val DEFAULT_MAX_CHARS = 2_600
    const val DEFAULT_RETENTION_DAYS = 30
    const val DEFAULT_MAX_LINES = 40
    const val DEFAULT_EVERY = 5
    const val DEFAULT_AUTO = true

    /** 记忆相关设置的只读快照；渲染路径只认它，不直接读存储。 */
    data class MemorySettings(
        val maxChars: Int = DEFAULT_MAX_CHARS,
        val retentionDays: Int = DEFAULT_RETENTION_DAYS,
        val maxLines: Int = DEFAULT_MAX_LINES,
        val every: Int = DEFAULT_EVERY,
        val auto: Boolean = DEFAULT_AUTO,
    )

    /** 一条记忆条目。[date] 为 null 表示缺日期前缀（格式不合法，但内容仍然注入）。 */
    data class Entry(val date: LocalDate?, val text: String, val lineNo: Int) {
        /** 统一渲染成 `- YYYY-MM-DD: 正文`；无日期的旧行原样保留（不丢内容）。 */
        fun render(): String = if (date != null) "- $date: $text" else text
    }

    data class Doc(val stable: List<Entry>, val recent: List<Entry>) {
        val invalidCount: Int get() = (stable + recent).count { it.date == null }

        /** 超过 200 字的行号（按文件行号，稳定段在前、近期段在后）。 */
        val overlongLines: List<Int>
            get() = (stable + recent).filter { weightedChars(it.render()) > MAX_LINE_CHARS }.map { it.lineNo }
    }

    /** 一次渲染的结果；[dropped] / [expired] 都是计数，不参与正文。 */
    data class Rendered(
        val text: String,
        val dropped: Int,
        val expired: Int,
        val invalid: Int,
        val overlongLines: List<Int>,
    )

    private val DATED = Regex("""^-\s*(\d{4}-\d{2}-\d{2})\s*:\s*(.*)$""")

    /** 从工作区解析记忆文件路径；不存在时也返回路径（写入方负责建目录）。 */
    fun fileOf(workspace: String): File = File(workspace, FILE_PATH)

    /** 读文件；文件不存在返回空串。IO / 权限错误按原样抛出，由装配层记成段落失败。 */
    fun readFile(file: File): String = if (file.isFile) file.readText() else ""

    /**
     * 确保记忆文件存在（新建项目时调用）：只写两段标题的空模板，已有内容一律不动。
     *
     * 返回是否真的新建了文件；失败（权限 / 磁盘）静默返回 false —— 建不出来不该
     * 拦住项目创建，agent 仍可按维护规则里的路径自己写。
     */
    fun ensureTemplate(workspace: String): Boolean {
        val file = fileOf(workspace)
        if (file.isFile) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(MemoryPrompts.TEMPLATE)
            true
        }.getOrDefault(false)
    }

    /**
     * 解析文件成两段。规则：
     * - 段标题之前的内容按「稳定」解析（旧文件迁移口径）；
     * - 每个非空行都保留（空行丢弃、CRLF 归一）；
     * - `- YYYY-MM-DD: ` 匹配且日期合法 → 带日期条目；否则原文保留、计入格式不合法；
     * - 重复行不去重（解析不丢内容，交给整理合并）。
     */
    fun parse(text: String): Doc {
        val stable = ArrayList<Entry>()
        val recent = ArrayList<Entry>()
        var target = stable
        var lineNo = 0
        text.lineSequence().forEach { raw ->
            lineNo++
            val line = raw.trimEnd('\r', ' ', '\t')
            if (line.isBlank()) return@forEach
            when (line.trim()) {
                MemoryPrompts.STABLE_HEADER -> {
                    target = stable
                    return@forEach
                }
                MemoryPrompts.RECENT_HEADER -> {
                    target = recent
                    return@forEach
                }
            }
            val match = DATED.matchEntire(line.trim())
            val date = match?.groupValues?.get(1)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            target += if (match != null && date != null) {
                Entry(date, match.groupValues[2].trim(), lineNo)
            } else {
                Entry(null, line.trim(), lineNo)
            }
        }
        return Doc(stable, recent)
    }

    /**
     * 渲染注入段：稳定段（文件原序）→ 近期段（按日期正序，最旧在前）。
     *
     * 预算按加权字计（中文 1 字、ASCII 0.25 字）。超出就从**近期最旧**开始丢，
     * 丢完还不够再丢**稳定最旧**；[Rendered.dropped] 与段尾「另有 N 条未注入」严格一致。
     * 过期近期条目不注入、不删除，只计入 [Rendered.expired]（交给诊断面）。
     */
    fun render(
        doc: Doc,
        now: LocalDate,
        settings: MemorySettings,
        tailNotes: List<String> = emptyList(),
    ): Rendered {
        val cutoff = now.minusDays(settings.retentionDays.coerceAtLeast(1).toLong())
        val visibleRecent = doc.recent.filter { it.date == null || !it.date.isBefore(cutoff) }
        val expired = doc.recent.size - visibleRecent.size
        // 日期正序；同日期按文件原序；无日期的放段尾（文件原序）——全部确定性。
        val recentSorted = visibleRecent.sortedWith(
            compareBy<Entry> { it.date ?: LocalDate.MAX }.thenBy { it.lineNo },
        )
        val stableKept = doc.stable.toMutableList()
        val recentKept = recentSorted.toMutableList()
        var dropped = 0
        val invalid = doc.invalidCount
        val overlong = doc.overlongLines
        var text = ""
        while (true) {
            text = compose(stableKept, recentKept, settings, dropped, invalid, overlong, tailNotes)
            if (weightedChars(text) <= settings.maxChars) break
            when {
                recentKept.isNotEmpty() -> {
                    recentKept.removeAt(0) // 近期最旧
                    dropped++
                }
                stableKept.isNotEmpty() -> {
                    stableKept.removeAt(0) // 稳定最旧
                    dropped++
                }
                else -> break // 只剩固定文案；maxChars 下限 400，正常到不了这里
            }
        }
        return Rendered(text, dropped, expired, invalid, overlong)
    }

    private fun compose(
        stable: List<Entry>,
        recent: List<Entry>,
        settings: MemorySettings,
        dropped: Int,
        invalid: Int,
        overlong: List<Int>,
        tailNotes: List<String>,
    ): String {
        val lines = ArrayList<String>()
        lines += MemoryPrompts.SECTION_TITLE
        if (stable.isEmpty() && recent.isEmpty() && dropped == 0) {
            lines += MemoryPrompts.EMPTY_NOTE
        } else {
            if (stable.isNotEmpty()) {
                lines += ""
                lines += MemoryPrompts.STABLE_HEADER
                stable.forEach { lines += it.render() }
            }
            if (recent.isNotEmpty()) {
                lines += ""
                lines += MemoryPrompts.RECENT_HEADER
                recent.forEach { lines += it.render() }
            }
        }
        lines += ""
        lines += MemoryPrompts.maintenance(settings.maxChars)
        if (dropped > 0) lines += MemoryPrompts.omitted(dropped)
        if (invalid > 0) lines += MemoryPrompts.invalid(invalid)
        if (overlong.isNotEmpty()) lines += MemoryPrompts.overlong(overlong)
        tailNotes.forEach { lines += it }
        return lines.joinToString("\n")
    }

    /** 中文按 1 字、ASCII 按 0.25 字折算（预算口径的唯一实现点）。 */
    fun weightedChars(value: String): Double {
        var sum = 0.0
        value.forEach { c -> sum += if (c.code < 0x80) 0.25 else 1.0 }
        return sum
    }

    /** 从插件设置读一份快照；键名沿用外部插件的 `memory.*`（用户改过的值不丢）。 */
    fun settingsOf(store: PluginSettingsStore): MemorySettings = MemorySettings(
        maxChars = store.getInt(MemoryPlugin.KEY_MAX_CHARS, DEFAULT_MAX_CHARS),
        retentionDays = store.getInt(MemoryPlugin.KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS),
        maxLines = store.getInt(MemoryPlugin.KEY_MAX_LINES, DEFAULT_MAX_LINES),
        every = store.getInt(MemoryPlugin.KEY_EVERY, DEFAULT_EVERY),
        auto = store.getBoolean(MemoryPlugin.KEY_AUTO, DEFAULT_AUTO),
    )

    /** 文件非空行数（`maxLines` 软阈值：只用于触发整理，不再当作注入上界）。 */
    fun lineCount(text: String): Int = text.lineSequence().count { it.isNotBlank() }

    // ---- 整理取材 ---------------------------------------------------------

    /** 会话存档里的「用户回合」序号：跳过 system note 与压缩摘要（两套刻度的边界）。 */
    fun eligibleTurnCount(messages: List<ChatMessage>): Int =
        messages.count { it.role == Role.USER && !it.isSystemNote && !it.isCompaction }

    private class DigestTurn(val index: Int, val user: String) {
        val assistants = ArrayList<String>()
    }

    /**
     * 取 [afterTurn]（**会话存档里的用户回合序号**，不是插件的 `total`）之后的增量。
     *
     * 过滤规则：`isSystemNote` 与 `isCompaction = true` 的消息都不是用户发言 ——
     * 压缩摘要是 `Role.USER` 且没有 system-note 标记，漏掉它会把整份摘要塞进整理 prompt。
     *
     * 预算：总字符 [DIGEST_CHARS]，超出时保留**最近**的回合（记忆关心当前状态），
     * 被略去的更早回合在开头注明条数（只影响 prompt，不进系统提示）。
     */
    fun digest(messages: List<ChatMessage>, afterTurn: Int, maxChars: Int = DIGEST_CHARS): String {
        val turns = ArrayList<DigestTurn>()
        var current: DigestTurn? = null
        var index = 0
        for (message in messages) {
            when (message.role) {
                Role.USER -> {
                    if (message.isSystemNote || message.isCompaction) continue
                    index++
                    val turn = DigestTurn(index, message.text.trim().take(DIGEST_TEXT_CHARS))
                    turns += turn
                    current = turn
                }
                Role.ASSISTANT -> {
                    // 与 USER 分支同口径：注入的提示 / 压缩摘要不是这一轮的助手输出，
                    // 挂到 current 上会让整理模型把它们当成「发生过的事实」。
                    if (!message.isSystemNote && !message.isCompaction) {
                        val text = message.text.trim()
                        if (text.isNotEmpty()) current?.assistants?.add(text.take(DIGEST_TEXT_CHARS))
                    }
                }
                else -> Unit
            }
        }
        val selected = turns.filter { it.index > afterTurn }
        if (selected.isEmpty()) return ""
        val blocks = ArrayList<String>()
        var used = 0.0
        for (turn in selected.asReversed()) {
            val block = buildString {
                append("User: ").append(turn.user.ifBlank { "(empty)" })
                val answer = turn.assistants.filter { it.isNotBlank() }.joinToString("\n")
                if (answer.isNotBlank()) append("\nAssistant: ").append(answer)
            }
            val weight = weightedChars(block)
            if (used + weight > maxChars) break
            used += weight
            blocks += block
        }
        if (blocks.isEmpty()) return ""
        blocks.reverse()
        val omitted = selected.size - blocks.size
        val header = if (omitted > 0) "（更早的 $omitted 个回合因长度预算未列出）\n" else ""
        return header + blocks.joinToString("\n\n")
    }

    // ---- 备份 / 回滚 -----------------------------------------------------

    /** 改写前备份；返回备份文件（没有原文件 / 建目录失败时为 null）。只保留最近几份。 */
    fun backup(file: File): File? {
        if (!file.isFile) return null
        val dir = File(file.parentFile ?: return null, BACKUP_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val target = File(dir, "memory-$stamp.md")
        val copied = runCatching {
            if (target.exists()) target.delete()
            file.copyTo(target, overwrite = true)
        }.isSuccess
        prune(dir, BACKUP_KEEP)
        return target.takeIf { copied }
    }

    /** 只留最近 [keep] 份（按修改时间；名字带时间戳只是给人看）。 */
    fun prune(dir: File, keep: Int = BACKUP_KEEP): Int {
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("memory-") && it.name.endsWith(".md") }
            .sortedByDescending { it.lastModified() }
        var removed = 0
        files.drop(keep).forEach { if (runCatching { it.delete() }.getOrDefault(false)) removed++ }
        return removed
    }

    /**
     * 整理是否被接受（唯一硬判据：**字符数下降**）。
     *
     * 空文件播种是唯一例外（0 → N 是新增，不是「整理后变短」）。除此之外必须严格更短，
     * 否则调用方按原始字节回滚。
     */
    fun acceptConsolidation(before: String, after: String): Boolean {
        if (after.isBlank()) return false
        if (before.isBlank()) return true
        return weightedChars(after) < weightedChars(before)
    }

    /** 回滚到原始字节；原本就没有文件时把新文件删掉。 */
    fun restore(file: File, original: String, existed: Boolean) {
        if (!existed) {
            runCatching { file.delete() }
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(original)
    }
}
