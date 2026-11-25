package dev.kair.app.core.tools

import dev.kair.app.core.model.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 内置工具清单。
 *
 * `read_image` 常驻注册表，但**是否发给模型由宿主按会话过滤**（见
 * `SessionAssembly.toolEnabled`，容器侧只留同名委托）：只有当前模型勾选「图像模型」时它的 schema 才会
 * 进入请求与系统提示，未勾选则完全不可见 —— 等价于「未勾选不注册」，且不浪费 token。
 *
 * @param bashReady `container_shell` 是否在列：容器（proot rootfs）就绪才有完整 Linux 用户态，
 *   未就绪时宿主不注册它，由始终在场的 [LocalShellTool] 覆盖基础命令。
 *   两者不互斥 —— 就绪时 `container_shell` 跑容器、`android_shell` 跑设备系统目录。
 */
fun builtinTools(bashReady: Boolean = true): List<Tool> = buildList {
    add(ReadTool())
    add(WriteTool())
    add(EditTool())
    if (bashReady) add(BashTool())
    add(LocalShellTool())
    add(LsTool())
    add(GrepTool())
    add(FindTool())
    add(ReadImageTool())
}

/** `read_image` 的工具名，供宿主按「是否图像模型」过滤时引用。 */
const val ReadImageToolName = "read_image"

/**
 * 单文件读取上限：超过直接拒绝，而不是先读进内存再截断。
 * 100MB 的日志 / 二进制被 `readText` 整体载入会直接把堆撑爆；需要局部内容时用 grep。
 */
private const val MAX_READ_BYTES = 32L * 1024 * 1024

/** 单个文件参与 grep 的上限：更大的文件跳过，避免遍历目录时把内存打满。 */
private const val MAX_GREP_FILE_BYTES = 8L * 1024 * 1024

// ---------------------------------------------------------------------------
// read
// ---------------------------------------------------------------------------

/**
 * 给正文每行加绝对行号前缀（`NNN→内容`），对齐主流编码代理的 read 输出：文件正文本身
 * 不含行号，模型据此才能精确引用「第几行」，否则只能自行计数、容易漂移出错。
 * [firstLine] 是首行在文件中的绝对行号（从 1 起），行号按最大行宽右对齐。
 */
private fun numberLines(content: String, firstLine: Int): String {
    if (content.isEmpty()) return content
    val lines = content.split("\n")
    val width = (firstLine + lines.size - 1).toString().length
    return lines.mapIndexed { i, line ->
        (firstLine + i).toString().padStart(width) + "→" + line
    }.joinToString("\n")
}

class ReadTool : Tool {
    override val name = "read"
    override val category = ToolCategory.READ
    override val promptSnippet = "Read file contents"
    override val promptGuidelines = listOf("Use read to examine files instead of cat or sed.")
    override val isParallelSafe = true
    override val readOnly = true
    override val description =
        "Read the contents of a file. For text files, output is truncated to " +
            "${Truncation.DEFAULT_MAX_LINES} lines or ${Truncation.DEFAULT_MAX_BYTES / 1024}KB " +
            "(whichever is hit first). Use offset/limit for large files. When you need the full " +
            "file, continue with offset until complete."
    override val parameters = schemaOf(
        props(
            "path" to strProp("Path to the file to read (relative or absolute)"),
            "offset" to numProp("Line number to start reading from (1-indexed)"),
            "limit" to numProp("Maximum number of lines to read"),
        ),
        "path",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val absolute = ctx.files.resolve(path)
        if (!ctx.files.exists(absolute)) throw ToolException("File not found: $path")
        if (ctx.files.isDirectory(absolute)) throw ToolException("Path is a directory, not a file: $path")
        // 先看大小再读：readText 会整体载入内存，超大文件会直接 OOM。
        ctx.files.size(absolute)?.let { bytes ->
            if (bytes > MAX_READ_BYTES) {
                val sizeText = Truncation.formatSize(bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                throw ToolException(
                    "File is too large to read ($sizeText). Use grep to search it instead.",
                )
            }
        }

        val offset = args.int("offset")
        val limit = args.int("limit")
        val text = ctx.files.readText(absolute)
        val allLines = text.split("\n")
        val totalFileLines = allLines.size
        val startLine = if (offset != null) maxOf(0, offset - 1) else 0
        val startLineDisplay = startLine + 1
        if (startLine >= allLines.size) {
            throw ToolException("Offset $offset is beyond end of file (${allLines.size} lines total)")
        }
        var userLimitedLines: Int? = null
        val selected: String
        if (limit != null) {
            val endLine = minOf(startLine + limit, allLines.size)
            selected = allLines.subList(startLine, endLine).joinToString("\n")
            userLimitedLines = endLine - startLine
        } else {
            selected = allLines.subList(startLine, allLines.size).joinToString("\n")
        }
        val truncation = truncateHead(selected)
        // 每行加绝对行号前缀（见 numberLines）：模型可精确引用行号，也能用 offset 继续读完。
        val numbered = numberLines(truncation.content, startLineDisplay)
        val outputText: String = when {
            truncation.firstLineExceedsLimit -> {
                val firstLineSize = Truncation.formatSize(allLines[startLine].toByteArray(Charsets.UTF_8).size)
                "[Line $startLineDisplay is $firstLineSize, exceeds ${Truncation.formatSize(Truncation.DEFAULT_MAX_BYTES)} limit.]"
            }
            truncation.truncated -> {
                val endLineDisplay = startLineDisplay + truncation.outputLines - 1
                val nextOffset = endLineDisplay + 1
                if (truncation.truncatedBy == "lines") {
                    "$numbered\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines. Use offset=$nextOffset to continue.]"
                } else {
                    "$numbered\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines (${Truncation.formatSize(Truncation.DEFAULT_MAX_BYTES)} limit). Use offset=$nextOffset to continue.]"
                }
            }
            userLimitedLines != null && startLine + userLimitedLines < allLines.size -> {
                val remaining = allLines.size - (startLine + userLimitedLines)
                val nextOffset = startLine + userLimitedLines + 1
                "$numbered\n\n[$remaining more lines in file. Use offset=$nextOffset to continue.]"
            }
            else -> numbered
        }
        ToolResult(outputText)
    }
}

// ---------------------------------------------------------------------------
// write
// ---------------------------------------------------------------------------

class WriteTool : Tool {
    override val name = "write"
    override val category = ToolCategory.WRITE
    override val promptSnippet = "Create or overwrite files"
    override val promptGuidelines = listOf("Use write only for new files or complete rewrites.")
    override val description =
        "Write content to a file. Creates the file if it does not exist, overwrites it if it does. " +
            "Creates parent directories as needed."
    override val parameters = schemaOf(
        props(
            "path" to strProp("Path to the file to write (relative or absolute)"),
            "content" to strProp("Content to write to the file"),
        ),
        "path", "content",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val content = args.reqStr("content")
        val absolute = ctx.files.resolve(path)
        ctx.files.writeText(absolute, content)
        ToolResult("Successfully wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path")
    }
}

// ---------------------------------------------------------------------------
// edit
// ---------------------------------------------------------------------------

class EditTool : Tool {
    override val name = "edit"
    override val category = ToolCategory.WRITE
    override val promptSnippet =
        "Make precise file edits by exact text replacement (multiple disjoint edits in one call)"
    override val promptGuidelines = listOf(
        "Use edit for precise changes (edits[].oldText must match exactly).",
        "All edits[] match the **original** file, not the result of earlier edits — put separate " +
            "locations in one call, combine adjacent changes into one entry, keep oldText minimal but " +
            "unique, and never emit overlapping or nested edits.",
    )
    override val description =
        "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, " +
            "non-overlapping region of the original file. If two changes affect the same block or nearby " +
            "lines, merge them into one edit instead of emitting overlapping edits."
    override val parameters = schemaOf(
        props(
            "path" to strProp("Path to the file to edit (relative or absolute)"),
            "edits" to arrayProp(
                schemaOf(
                    props(
                        "oldText" to strProp("Exact text for one targeted replacement. Must be unique in the original file."),
                        "newText" to strProp("Replacement text for this targeted edit."),
                    ),
                    "oldText", "newText",
                ),
                "One or more targeted replacements, matched against the original file.",
            ),
        ),
        "path", "edits",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val edits = parseEdits(args)
        if (edits.isEmpty()) throw ToolException("Edit tool input is invalid. edits must contain at least one replacement.")
        val absolute = ctx.files.resolve(path)
        if (!ctx.files.exists(absolute)) throw ToolException("Could not edit file: $path. File does not exist.")
        val raw = ctx.files.readText(absolute)

        val bom = raw.startsWith("\uFEFF")
        val withoutBom = if (bom) raw.substring(1) else raw
        val crlf = withoutBom.contains("\r\n")
        val normalized = withoutBom.replace("\r\n", "\n")
        val applied = applyEdits(normalized, edits, path)
        val restored = if (crlf) applied.content.replace("\n", "\r\n") else applied.content
        ctx.files.writeText(absolute, (if (bom) "\uFEFF" else "") + restored)
        val summary = "Successfully replaced ${edits.size} block(s) in $path."
        if (applied.content == normalized) return@withContext ToolResult(summary)
        // diff 一并回灌给模型：模型不必再读一遍文件就能确认改动是否如预期。
        val hunks = applied.edits.map {
            EditHunk(startLine = lineIndexOf(normalized, it.start), oldText = it.oldText, newText = it.newText)
        }
        ToolResult("$summary\n\n" + unifiedDiff(path, normalized, hunks))
    }

    private fun parseEdits(args: JsonObject): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val editsEl = args["edits"]
        if (editsEl is JsonArray) {
            editsEl.forEach { e ->
                val o = e.jsonObject
                result.add(o.str("oldText")!! to (o.str("newText") ?: ""))
            }
        }
        // Legacy single-edit forms.
        val legacyOld = args.str("oldText")
        val legacyNew = args.str("newText")
        if (legacyOld != null && legacyNew != null) result.add(legacyOld to legacyNew)
        return result
    }

    /** 一次 [EditTool.execute] 的产物：改后的正文 + 各编辑块在原文中的定位。 */
    private data class AppliedEdits(val content: String, val edits: List<LocatedEdit>)

    private data class LocatedEdit(val start: Int, val oldText: String, val newText: String)

    private fun applyEdits(original: String, edits: List<Pair<String, String>>, path: String): AppliedEdits {
        data class Range(val start: Int, val end: Int, val newText: String)

        val ranges = ArrayList<Range>()
        for ((oldText, newText) in edits) {
            if (oldText.isEmpty()) throw ToolException("edits[].oldText must not be empty")
            val idx = original.indexOf(oldText)
            if (idx < 0) {
                throw ToolException("Could not find the exact text to replace in $path. edits[].oldText must match the original file exactly.")
            }
            if (original.indexOf(oldText, idx + 1) >= 0) {
                throw ToolException("Found multiple matches for edits[].oldText in $path. It must be unique; include more surrounding context.")
            }
            ranges.add(Range(idx, idx + oldText.length, newText))
        }
        ranges.sortBy { it.start }
        for (i in 1 until ranges.size) {
            if (ranges[i].start < ranges[i - 1].end) {
                throw ToolException("Edits overlap or are nested; merge them into a single edit.")
            }
        }
        val sb = StringBuilder()
        val located = ArrayList<LocatedEdit>()
        var cursor = 0
        for (r in ranges) {
            sb.append(original, cursor, r.start)
            sb.append(r.newText)
            located.add(LocatedEdit(r.start, original.substring(r.start, r.end), r.newText))
            cursor = r.end
        }
        sb.append(original, cursor, original.length)
        return AppliedEdits(sb.toString(), located)
    }
}

// ---------------------------------------------------------------------------
// container_shell  (delegates to the plugin-provided CommandRunner; see CommandRunner.kt)
// ---------------------------------------------------------------------------

class BashTool : Tool {
    override val name = "container_shell"
    override val category = ToolCategory.EXECUTE
    override val promptSnippet = "Execute shell commands in the Ubuntu container (ls, cat, apt, etc.)"
    override val promptGuidelines = listOf(
        "Deletion is gated: `rm` / `rmdir` / `unlink` / `shred` / `find -delete` — and any script " +
            "containing them — requires user approval; never try to bypass it. For files **inside the " +
            "workspace** prefer the shared trash over deleting: `T=\"\$KAIR_TRASH/\$(date +%Y%m%d-%H%M%S)\"; " +
            "mkdir -p \"\$T\" && mv <paths> \"\$T\"/` — the user can restore from it. Never create a trash " +
            "directory inside the workspace. Files outside the workspace: delete outright when asked (still gated).",
        "The container shell is POSIX sh (dash), not GNU bash — no [[ ]], arrays, process " +
            "substitution or {a,b} expansion.",
        "Interactive/TTY tools (expect, sshpass, password prompts) can fail without a pty — prefer " +
            "non-interactive forms: key-based SSH, or SSH_ASKPASS with SSH_ASKPASS_REQUIRE=force.",
        "Network and package-manager commands can hang indefinitely; set an explicit timeout (e.g. 30-60). " +
            "Default is $DEFAULT_TIMEOUT_SECONDS; pass a larger value for long builds, 0 to disable.",
        "A command's `cmd &` / `nohup` children are reaped when it exits; an unset `\$KAIR_CHANNEL` " +
            "means it fell back to the device shell, where nothing reaps them. In the container " +
            "nothing escapes the one-shot process tree, `setsid` included. For a service that must stay up, " +
            "**use background_task with `timeout: 0`** — the one escape the host supervises and reports back on. " +
            "Mounts persist across commands, so a survivor still sees /projects; stop it with `kill` " +
            "or the process panel.",
        "Multiple container_shell calls in one turn run **one at a time** (android_shell and remote MCP tools " +
            "are serial too) — among built-in tools only read / ls / grep / find / read_image / " +
            "subagent / ask_user and mcp_list run in parallel. For concurrent shell work, combine " +
            "it into one command with `&` + `wait`, or issue several subagent calls.",
    )

    // 描述进入系统提示的缓存稳定前缀：本工具仅在 rootfs 就绪时注册（见 AppContainer.bashAvailable），
    // 因此下面都是「容器内必然成立」的事实；不写 Ubuntu 版本号（升级会变）、不写权限（按通道变，见上面守则）。
    override val description =
        "Execute a shell command in the current working directory, inside the Ubuntu Linux workspace " +
            "container (glibc; NOT the Android device shell). The package manager is apt and the base " +
            "image ships only coreutils + apt, so git / python / node / openssh must be installed first " +
            "(e.g. `apt-get install git`). /dev, /proc and /sys are the host's; there is no init/systemd. " +
            "Returns stdout and stderr, truncated to last " +
            "${Truncation.DEFAULT_MAX_LINES} lines or ${Truncation.DEFAULT_MAX_BYTES / 1024}KB " +
            "(whichever is hit first). Only /projects, /sdcard and /trash are mount points — other host " +
            "paths resolve inside the rootfs, so use ls / read / write for those."
    override val parameters = schemaOf(
        props(
            "command" to strProp("Shell command to execute"),
            "timeout" to numProp("Timeout in seconds (default $DEFAULT_TIMEOUT_SECONDS; 0 disables)"),
        ),
        "command",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = args.reqStr("command")
        // 宿主工作区绝对路径在容器内不存在（只有 /projects /sdcard /trash 挂载）。放过去只会
        // 得到 No such file，然后诱发 agent 用 `find /` 全盘搜 —— 吃满整个超时。执行前拦截，
        // 把 300s 的黑洞变成一条指路的报错。android_shell 是宿主 shell，宿主路径在那里合法，不拦。
        val hostWorkspace = ctx.workspace.absolutePath
        if (hostWorkspace.length > 1 && command.contains(hostWorkspace)) {
            throw ToolException(
                "Command references the host workspace path (`$hostWorkspace`), which does not exist " +
                    "inside the container — only /projects, /sdcard and /trash are mounted. Replace it " +
                    "with the same path under your <cwd> (strip the <workspace> prefix and re-attach it " +
                    "there), or use the read / write / edit / grep / find tools, which do take host paths.",
            )
        }
        runShell(ctx.commandRunner, args, ctx)
    }

    companion object {
        /**
         * 默认超时：不传 timeout 时的兜底。没有它，一条挂起的网络命令
         * （如连不通的 curl）会让 agent 永久卡死——工具是串行执行的。
         */
        const val DEFAULT_TIMEOUT_SECONDS = 300
    }
}

/**
 * `container_shell` / `android_shell` 共用的执行体：跑命令 → 截断尾部 → 组装结果。
 * 超时与退出码语义两个工具必须一致，模型对它们的心智模型才不会分裂。
 */
internal suspend fun runShell(
    runner: CommandRunner,
    args: JsonObject,
    ctx: ToolContext,
): ToolResult {
    val command = args.reqStr("command")
    val timeoutSec = args.int("timeout") ?: BashTool.DEFAULT_TIMEOUT_SECONDS
    val workDir = ctx.workspace
    if (!workDir.exists()) {
        throw ToolException("Working directory does not exist: ${workDir.path}\nCannot execute commands.")
    }
    val result = runner.run(command, workDir, timeoutSec.takeIf { it > 0 }?.toLong())
    val snapshot = truncateTail(result.output)
    val text = snapshot.content.ifEmpty { "(no output)" }

    if (result.timedOut) {
        throw ToolException("$text\n\nCommand timed out after $timeoutSec seconds")
    }
    val withStatus = if (result.exitCode != 0) "$text\n\nCommand exited with code ${result.exitCode}" else text
    return ToolResult(withStatus, isError = result.exitCode != 0)
}

// ---------------------------------------------------------------------------
// android_shell（Android 系统 shell；始终注册）
// ---------------------------------------------------------------------------

/**
 * 直接跑 Android 的 `/system/bin/sh`（toybox），**不经过容器**。
 *
 * `container_shell` 只有在 proot rootfs 就绪时才注册（见 `AppContainer.bashAvailable`），本工具保证
 * 任何时刻都有一条可用的命令通道：它落在工作区目录、但视野是整台设备（`/system`、`/data`、
 * `/sdcard`…），正好补上「容器里看不到宿主系统」的空档。反过来它没有 git / python / 包管理器，
 * 需要完整用户态时用 `container_shell`；设备已 root 时可用 `su -c '<command>'` 跑单条 root 命令（见 [LocalCommandRunner]）。
 */
class LocalShellTool(private val runner: CommandRunner = LocalCommandRunner()) : Tool {
    override val name = "android_shell"
    override val category = ToolCategory.EXECUTE
    override val promptSnippet = "Run Android device shell commands outside the Linux workspace"
    override val promptGuidelines = listOf(
        "Destructive deletes are gated exactly like in container_shell — never bypass; prefer the shared trash " +
            "for workspace files.",
        "Use android_shell for device/system paths (/system, /data, /sdcard) or when container_shell is unavailable. It is plain toybox: no git, python or package managers — use container_shell for those.",
    )

    // 描述进入系统提示的缓存稳定前缀，只写稳定事实（不写当前是否装了容器）。
    override val description =
        "Execute a shell command with the Android system shell (/system/bin/sh, toybox) in the current " +
            "working directory, outside the Ubuntu Linux workspace. This is the host Android shell; for the " +
            "Ubuntu container use container_shell instead. Gives access to device and " +
            "system paths that are not mounted into the workspace. On a rooted device `su -c '<command>'` runs " +
            "one command as root in the device shell, resolved through PATH (no container mounts). Returns " +
            "stdout and stderr, truncated to last " +
            "${Truncation.DEFAULT_MAX_LINES} lines or ${Truncation.DEFAULT_MAX_BYTES / 1024}KB (whichever is " +
            "hit first). Optionally provide a timeout in seconds " +
            "(default ${BashTool.DEFAULT_TIMEOUT_SECONDS}; 0 disables the timeout)."
    override val parameters = schemaOf(
        props(
            "command" to strProp("Shell command to execute"),
            "timeout" to numProp("Timeout in seconds (default ${BashTool.DEFAULT_TIMEOUT_SECONDS}; 0 disables)"),
        ),
        "command",
    )

    /** 独立的后端实例：本工具永远不碰 ctx.commandRunner（那是容器的通道）。 */
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        runShell(runner, args, ctx)
    }
}

// ---------------------------------------------------------------------------
// ls
// ---------------------------------------------------------------------------

class LsTool : Tool {
    override val name = "ls"
    override val category = ToolCategory.READ
    override val promptSnippet = "List directory contents"
    override val isParallelSafe = true
    override val readOnly = true
    override val description =
        "List directory contents. Returns entries sorted alphabetically, with '/' suffix for " +
            "directories. Includes dotfiles. Output is truncated to 500 entries or " +
            "${Truncation.DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first)."
    override val parameters = schemaOf(
        props(
            "path" to strProp("Directory to list (default: current directory)"),
            "limit" to numProp("Maximum number of entries to return (default: 500)"),
        ),
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val dir = ctx.files.resolve(args.str("path") ?: ".")
        if (!ctx.files.exists(dir)) throw ToolException("Directory not found: ${args.str("path") ?: "."}")
        if (!ctx.files.isDirectory(dir)) throw ToolException("Path is not a directory: ${args.str("path") ?: "."}")
        val limit = args.int("limit") ?: 500
        val entries = ctx.files.list(dir)
            .sortedBy { it.name.lowercase() }
            .take(limit)
            .map { if (it.isDirectory) it.name + "/" else it.name }
        ToolResult(if (entries.isEmpty()) "(empty directory)" else entries.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------
// grep
// ---------------------------------------------------------------------------

class GrepTool : Tool {
    override val name = "grep"
    override val category = ToolCategory.READ
    override val promptSnippet = "Search file contents for patterns"
    override val isParallelSafe = true
    override val readOnly = true
    override val description =
        "Search file contents for a pattern. Returns matching lines with file paths and line numbers. " +
            "Output is truncated to 100 matches or ${Truncation.DEFAULT_MAX_BYTES / 1024}KB (whichever is " +
            "hit first). Long lines are truncated to ${Truncation.GREP_MAX_LINE_LENGTH} chars."
    override val parameters = schemaOf(
        props(
            "pattern" to strProp("Search pattern (regex or literal string)"),
            "path" to strProp("Directory or file to search (default: current directory)"),
            "glob" to strProp("Filter files by glob pattern, e.g. '*.ts'"),
            "ignoreCase" to boolProp("Case-insensitive search (default: false)"),
            "literal" to boolProp("Treat pattern as literal string instead of regex (default: false)"),
            "context" to numProp("Number of lines to show before and after each match (default: 0)"),
            "limit" to numProp("Maximum number of matches to return (default: 100)"),
        ),
        "pattern",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.reqStr("pattern")
        val literal = args.bool("literal") ?: false
        val ignoreCase = args.bool("ignoreCase") ?: false
        val context = args.int("context") ?: 0
        val limit = args.int("limit") ?: 100
        var regex = if (literal) Regex(Regex.escape(pattern)) else Regex(pattern)
        if (ignoreCase) regex = Regex(regex.pattern, RegexOption.IGNORE_CASE)
        val globRegex = args.str("glob")?.let { globToRegex(it) }

        val target = ctx.files.resolve(args.str("path") ?: ".")
        if (!ctx.files.exists(target)) throw ToolException("Path not found: ${args.str("path") ?: "."}")

        val files = if (ctx.files.isDirectory(target)) ctx.files.walk(target, SKIP_DIRS) else listOf(target)
        val out = ArrayList<String>()
        var matches = 0
        var bytes = 0
        outer@ for (file in files) {
            val rel = ctx.files.relative(target, file)
            val name = file.substringAfterLast('/')
            if (globRegex != null && !globRegex.matches(name) && !globRegex.matches(rel)) continue
            // 超大文件直接跳过：grep 是整文件读入，不设上限时扫目录很容易把内存打满。
            val size = ctx.files.size(file)
            if (size != null && size > MAX_GREP_FILE_BYTES) continue
            val lines = try { ctx.files.readText(file).lines() } catch (_: Exception) { continue }
            for ((i, line) in lines.withIndex()) {
                if (!regex.containsMatchIn(line)) continue
                val (truncatedLine, _) = truncateLine(line)
                val loc = "$rel:${i + 1}: $truncatedLine"
                if (context > 0) {
                    for (c in maxOf(0, i - context)..i) out.add("$rel:${c + 1}: ${lines[c]}")
                    out.add(loc)
                    for (c in i + 1..minOf(lines.size - 1, i + context)) out.add("$rel:${c + 1}: ${lines[c]}")
                } else {
                    out.add(loc)
                }
                matches++
                bytes += loc.toByteArray(Charsets.UTF_8).size
                if (matches >= limit || bytes > Truncation.DEFAULT_MAX_BYTES) break@outer
            }
        }
        if (out.isEmpty()) ToolResult("No matches found for pattern: $pattern")
        else ToolResult(out.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------
// find
// ---------------------------------------------------------------------------

class FindTool : Tool {
    override val name = "find"
    override val category = ToolCategory.READ
    override val promptSnippet = "Find files by glob pattern"
    override val isParallelSafe = true
    override val readOnly = true
    override val description =
        "Search for files by glob pattern. Returns matching file paths relative to the search directory. " +
            "Output is truncated to 1000 results or ${Truncation.DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first)."
    override val parameters = schemaOf(
        props(
            "pattern" to strProp("Glob pattern to match files, e.g. '*.ts' or 'src/**/*.spec.ts'"),
            "path" to strProp("Directory to search in (default: current directory)"),
            "limit" to numProp("Maximum number of results (default: 1000)"),
        ),
        "pattern",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args.reqStr("pattern")
        val limit = args.int("limit") ?: 1000
        val regex = globToRegex(pattern)
        val hasSlash = pattern.contains("/")
        val base = ctx.files.resolve(args.str("path") ?: ".")
        if (!ctx.files.exists(base)) throw ToolException("Path not found: ${args.str("path") ?: "."}")
        val results = ArrayList<String>()
        for (file in ctx.files.walk(base, SKIP_DIRS)) {
            val rel = ctx.files.relative(base, file)
            val matched = if (hasSlash) regex.matches(rel) else regex.matches(file.substringAfterLast('/'))
            if (matched) {
                results.add(rel)
                if (results.size >= limit) break
            }
        }
        results.sort()
        ToolResult(if (results.isEmpty()) "No files found matching pattern: $pattern" else results.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------
// read_image（多模态）
// ---------------------------------------------------------------------------

/**
 * 把工作区里的一张图片读进来交给模型「看」：文本内容回灌到 tool 消息，图片本体
 * 作为 [ToolResult.images] 由内核另起一条 user 回合追加，vision 模型才能真正看到。
 */
class ReadImageTool : Tool {
    override val name = ReadImageToolName
    override val category = ToolCategory.READ
    override val promptSnippet = "Load an image file so you can see it"
    override val isParallelSafe = true
    override val readOnly = true
    override val promptGuidelines = listOf(
        "Use read_image to view screenshots, diagrams, or photos instead of guessing their content.",
    )
    override val description =
        "Load an image (png/jpg/jpeg/gif/webp) from the workspace so a vision model can see it. " +
            "Relative paths resolve from the workspace root; in container sessions /projects/... maps " +
            "to the same files. Images over 8MB are rejected."

    override val parameters = schemaOf(
        props(
            "path" to strProp(
                "Image path. Relative paths resolve from the workspace root (e.g. .kair/shots/123.png); " +
                    "container sessions also accept /projects/<dir>/... .",
            ),
        ),
        "path",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.reqStr("path")
        val absolute = ctx.files.resolve(path)
        val file = java.io.File(absolute)
        if (!file.exists()) throw ToolException("Image not found: $path")
        if (!file.isFile) throw ToolException("Not a file: $path")
        if (file.length() > MAX_IMAGE_BYTES) {
            throw ToolException("Image too large (>${MAX_IMAGE_BYTES / 1024 / 1024}MB): $path")
        }
        val mediaType = mediaTypeFor(file.name)
        val base64 = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        ToolResult(
            content = "Loaded image ${ctx.files.relative(ctx.workspace.path, absolute)} " +
                "($mediaType, ${file.length()} bytes). The image is attached for you to view.",
            images = listOf(Part.Image(base64 = base64, mediaType = mediaType)),
        )
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8L * 1024 * 1024
    }
}

private fun mediaTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "image/png"
}

// ---------------------------------------------------------------------------
// shared helpers
// ---------------------------------------------------------------------------

private val SKIP_DIRS = setOf("node_modules", ".git", "build", ".gradle")

/** 把 glob 模式转成支持 `*` / `**` / `?` 的正则。 */
internal fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when (c) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    i++
                    if (i + 1 < glob.length && glob[i + 1] == '/') {
                        sb.append("(?:.*/)?")
                        i++
                    } else {
                        sb.append(".*")
                    }
                } else {
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString())
}
