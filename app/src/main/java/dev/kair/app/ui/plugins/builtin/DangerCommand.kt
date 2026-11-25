package dev.kair.app.ui.plugins.builtin

/**
 * 「危险命令」识别：默认拦截删除类命令（`rm` / `rmdir` / `unlink` / `shred` / `find -delete`）。
 *
 * 纯文本识别（纯函数，可单测），分两层判定：
 *
 * - [match]：**保守词元匹配** —— 文本里只要出现独立的删除命令词元就命中，连 `grep -r rm .`
 *   这种「只是提到 rm」也算。用于读脚本文件内容（脚本运行期 cwd 未知，只能保守），
 *   也是 [commandReason] 解析不出目标时的兜底。
 * - [commandReason]：命令行专用，带**明显临时目标豁免** —— 逐条语句解析出删除命令的操作数，
 *   并顺带跟踪 `cd` 之后的 cwd；只有当目标**不全**是明显临时 / 构建产物时才拦。于是
 *   `rm -rf build/classes`、`cd /tmp && rm -rf apicheck` 这类清理不再弹确认，而
 *   `rm -rf src`、`rm -rf /`、`rm -rf ~/data` 依旧拦。解析不出目标（`xargs rm`、
 *   `sh -c "rm ..."`、变量 / 通配）时一律回退为拦 —— 宁可误报，不漏真实删除。
 *   命令位本身是变量 / 命令替换（`cmd=rm; $cmd -rf src`）时文本里没有可命中的删除词元，
 *   因此在 [match] 之前就按「命令位不确定」拦（理由词是 [UNCERTAIN_COMMAND_REASON]，
 *   不谎称「命令里有删除命令」）；`~/bin/tool` 这类家目录缩写命令是**已知**命令，不在此列。
 *   命令位判定会**先穿透包装命令**（[COMMAND_WRAPPERS]：`sudo` / `env` / `timeout` / `chroot` …）：
 *   `sudo $cmd -rf src` 的变量藏在 `sudo` 的参数位，不穿透就整条漏掉；`sudo vim notes.md`
 *   这类已知命令词不受影响，不新增弹窗。`su` / `runuser` 靠 `-c` 带命令，穿透规则不同，
 *   仍列在下面的「已知覆盖不到」里。
 *   豁免判定前会先折叠 `.` / `..`，避免 `build/../../src` 凭裸 `build` 段漏过。
 *   heredoc 的**正文不参与命令行判定**（[stripHeredocBodies]）：`git commit -F - <<'EOF'`
 *   的提交说明是数据，正文里的反引号 / `$` / 中文会被当成「一条语句的命令位」，
 *   于是每次带 heredoc 的提交都弹一次窗（真机复现的误报）。**但接收方是解释器时不剥** ——
 *   `sh <<'EOF'` + 正文 `rm -rf …` 就是「用 heredoc 现写一个删除脚本再执行」，照旧拦。
 *
 * 「先写个删除脚本再执行」这类绕过的兜底，由调用方再读一遍被解释器引用的脚本文件来做
 * （见 [ApprovalPlugin]）。**已知覆盖不到的绕过**：写 python / node 代码直接调库删除、
 * 用 `busybox rm` 之外的同名二进制、把命令编码后再解码执行、把删除塞进**接收方不是
 * 解释器**的 heredoc 正文再另起一条命令执行（`cat > clean.sh <<EOF` 写脚本 + 之后 `sh clean.sh`
 * 仍会被脚本内容扫描兜住；`docker exec … sh <<EOF` 这类隔了一层的接收方认不出来）。
 */
internal object DangerCommand {

    /** 命令片段起点：行首、空白、shell 分隔符，或路径里的 `/`（覆盖 `/bin/rm`）。 */
    private const val SEGMENT = """(?:^|[;&|()\s/])"""

    private val DELETE_PATTERNS: List<Pair<Regex, String>> = listOf(
        // rm / rmdir / unlink / shred 作为独立命令词出现，后面必须是空白或行尾；
        // 前置分隔符保证 `charm`、`firmware` 这类词不会误判。
        Regex("""$SEGMENT(rm|rmdir|unlink|shred)(\s|$)""") to DELETE_REASON,
        Regex("""(?:^|\s)-delete(?:\s|$)""") to "find -delete",
    )

    /**
     * **应用级删除**：`pm uninstall` 删的是应用与其数据，`pm clear` 清的是应用数据，
     * 破坏性与删文件同级，用户点下去无法从回收站恢复。
     *
     * 为什么理由复用 [DELETE_REASON] 而不是新写一句：弹窗文案是「命令里有…」，说「删除命令」
     * 对这两条本来就不算错；而新建理由短语要动 `UiTextGuardTest` 的冻结基线（只降不增），
     * 为一个更精确的措辞不值。
     *
     * **`am force-stop` 刻意不拦**：它只是停进程，重启即恢复，且模型操作 UI 时常要靠它重启应用，
     * 拦了会让正常流程每次弹窗。
     *
     * 目标解析（`scanDeleteUses`）对这两条不适用——操作数是包名不是路径，所以命中的命令
     * 走 `uses.isEmpty()` 那条保守分支直接拦，不受临时目录豁免影响。
     */
    private val PACKAGE_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""$SEGMENT(pm|cmd\s+package)\s+(uninstall|clear)(\s|$)""") to DELETE_REASON,
    )

    /** 删除类命中的理由短语（面向用户）。**唯一**出处，别让第三份字面量漂在各分支里。 */
    private const val DELETE_REASON = "删除命令"

    /**
     * 命令位是变量 / 命令替换时的理由短语。这类命令里**未必**有删除词元（`~/` 脚本、
     * `$cmd --version`），所以不能借用 [DELETE_REASON] —— 弹窗会说「命令里有删除命令」。
     */
    private const val UNCERTAIN_COMMAND_REASON = "不确定的命令位"

    /** 会去执行脚本文件的解释器。 */
    private const val INTERPRETERS = "sh|bash|dash|ash|zsh|ksh|python3|python|node|perl|ruby|lua"

    private val INTERPRETER_CALL = Regex("""$SEGMENT(?:$INTERPRETERS)\s+([A-Za-z0-9_./-]+)""")

    private val SOURCE_CALL = Regex("""$SEGMENT(?:source\s+|\.\s+|\./)([A-Za-z0-9_./-]+)""")

    /** 会被当作「删除命令」的命令词。 */
    private val DELETE_BINARIES = setOf("rm", "rmdir", "unlink", "shred")

    /** 路径中出现任意一段即视为「明显临时 / 构建产物」，删它无风险。 */
    private val TRANSIENT_SEGMENTS = setOf(
        "tmp", "temp", ".tmp", ".temp",
        "build", "dist", "out", "target",
        "node_modules", ".gradle", ".cache", ".kotlin", ".dart_tool", ".next",
    )

    /** 明显临时的绝对根目录（自身或以 `/` 开头）。 */
    private val TRANSIENT_ROOTS = listOf("/tmp", "/var/tmp", "/dev/shm", "/private/tmp")

    /** 目标里出现这些字符说明是变量 / 通配 / 家目录缩写 —— 目标不确定，不豁免。 */
    private const val UNCERTAIN_CHARS = "\$*?~{`"

    /**
     * **命令位**的不确定字符：只认变量与命令替换（外加通配 —— 展开成什么是未知的）。
     * 故意不含 `~` 与 `{`：`~/bin/tool` 的命令是**已知**的，只是路径在家目录下，
     * 把它当「目标不确定」会让每次调用都弹一次窗。
     */
    private const val UNCERTAIN_COMMAND_CHARS = "\$*?`"

    /**
     * 包装命令 → 它在命令词之前**额外吞掉的非选项操作数**个数。
     *
     * 判定命令位之前要先穿透这些包装词：`sudo $cmd -rf src` 里第一个命令词是 `sudo`
     * （已知二进制），变量被推进了参数位 —— 不穿透就等于「命令位没变量」，整条不弹审批。
     * 值取 0 的只需跳过它自己的选项 / 数值（`-E`、`timeout 30` 的 `30` 由数值跳过兜住）；
     * `chroot` 取 1，因为 jail 路径既不是选项也不是命令词。
     * 故意**不含** `su` / `runuser` / `nsenter`：它们要靠 `-c` 带命令，穿透规则不同，
     * 留作已知未覆盖（见类注释），别在没有用例的情况下假装管到了。
     */
    private val COMMAND_WRAPPERS: Map<String, Int> = mapOf(
        "sudo" to 0, "nohup" to 0, "setsid" to 0, "exec" to 0, "time" to 0,
        "nice" to 0, "ionice" to 0, "stdbuf" to 0, "command" to 0, "taskset" to 0,
        "busybox" to 0, "env" to 0, "timeout" to 0, "chroot" to 1,
    )

    /** 命中则返回原因短语（面向用户，中文）；未命中返回 null。 */
    fun match(text: String): String? =
        // 原文与归一化后的文本都试一遍：`\rm`、`'rm'`、`r''m` 在 shell 里等价于 `rm`，
        // 但正则的前导分隔符匹配不到引号 / 反斜杠。宁可误报，不可漏掉真实删除。
        listOf(text, normalize(text)).firstNotNullOfOrNull { candidate ->
            (DELETE_PATTERNS + PACKAGE_PATTERNS).firstOrNull { it.first.containsMatchIn(candidate) }?.second
        }

    /**
     * shell 层面的等价归一化：去掉转义反斜杠与成对/不成对的引号。
     * 只用于**识别**，不用于实际执行。
     */
    private fun normalize(text: String): String =
        text.replace(ESCAPED_CHAR, "$1").replace(QUOTES, "")

    private val ESCAPED_CHAR = Regex("""\\([A-Za-z0-9_./-])""")

    /** 引号归一化；反引号保留，因为它是命令替换、属于「目标不确定」。 */
    private val QUOTES = Regex("""['"]""")

    /**
     * 命令行判定（带「明显临时目标豁免」）：删除目标不全是明显临时目录时返回原因短语，
     * 否则返回 null。[workspace] 是解析相对操作数用的基准目录（通常即工作区 cwd），可为 null。
     */
    fun commandReason(command: String, workspace: String?): String? {
        // 先把「不会被执行的」heredoc 正文剥掉，再判定：正文是数据（提交说明、配置文件内容），
        // 不是命令行。不剥的话，`git commit -F - <<'EOF'` 的中文说明里一行
        // 「- `65c187f` 外部素材…」会被当成一条语句，命令位是反引号包住的 sha，
        // 于是判成「不确定的命令位」—— 每次带 heredoc 的提交都弹一次审批（真机复现的误报）。
        // 只剥命令行判定这一条路径；[match] 供调用方读**脚本文件内容**时仍按保守语义全文扫。
        val text = stripHeredocBodies(command)
        val base = match(text)
        // 用归一化文本解析目标：否则 `\rm -rf src` 能命中删除词却解析不出目标，落进
        // 「uses.isEmpty() 保守拦」分支——虽然仍会拦，但与 `'rm' /tmp/x` 的临时目录豁免不一致。
        val normalized = normalize(text)
        // 命令位出现变量 / 命令替换：真实命令未知，按「目标不确定」保守拦。这条必须在
        // match 之外判 —— `cmd=rm; $cmd -rf src` 的文本里根本没有可命中的删除词元。
        if (hasUncertainCommandPosition(normalized)) return base ?: UNCERTAIN_COMMAND_REASON
        if (base == null) return null
        val uses = scanDeleteUses(normalized, workspace)
        // 解析不出删除调用（如 `grep -r rm .`、`sh -c "rm ..."`）：保守拦。
        if (uses.isEmpty()) return base
        val allTransient = uses.all { use ->
            use.targets.isNotEmpty() && use.targets.all { isTransient(it, use.cwd) }
        }
        return if (allTransient) null else base
    }

    // ---------------------------------------------------------------------
    // heredoc 正文剥除（[commandReason] 用）
    // ---------------------------------------------------------------------

    /**
     * heredoc 重定向：`<<EOF` / `<<-EOF` / `<<'EOF'` / `<<"EOF"`（`1<<EOF` 的 fd 写法同样命中）。
     * `<<<`（here-string）匹配不到 —— 它只有「紧跟的那一项」，没有正文行，不需要剥。
     */
    private val HEREDOC_REDIRECT = Regex("""<<(-?)[ \t]*(['"]?)([A-Za-z0-9_]+)\2""")

    /** 会执行 heredoc 正文的命令词 —— 与 [INTERPRETERS] 同源，别维护两份。 */
    private val INTERPRETER_BINARIES: Set<String> = INTERPRETERS.split('|').toSet()

    /**
     * 结束行**允许**跟在分隔符后面的字符：命令替换写法（`git commit -m "$(cat <<'EOF' … EOF)"`）
     * 里，结束行带着收尾标点。只接受标点与空白，出现字母数字就说明这行还有真实命令，不能当结束行剥掉。
     */
    private const val CLOSING_CHARS = ")]}'\"|&;"

    /**
     * 剥掉**不会被执行**的 heredoc 正文，返回只剩命令行的文本；没有 heredoc 时原样返回。
     *
     * 判据是「谁接收这份 stdin」：正文交给解释器（[INTERPRETER_BINARIES]，含穿透包装命令与
     * 管道末端）时**不剥** —— `sh <<'EOF'` + 正文 `rm -rf …` 就是要现写一个删除脚本执行，
     * 剥了等于放行。其余（`git commit -F -` / `cat > file`）正文是数据，剥。
     *
     * 两处「宁可不剥」的保守口径，都只会多弹一次窗、不会吞掉真实命令：
     * 找不到结束行（`<<` 其实在引号里，并非 heredoc）时整段不动；结束行只认
     * 「分隔符 + 收尾标点」，后面跟了别的内容（`EOF && rm -rf src`）就不算结束行。
     * 一行上写多个重定向时只处理第一个，其余正文按命令行判定 —— 同样是偏严的一侧。
     */
    private fun stripHeredocBodies(command: String): String {
        if (!command.contains("<<")) return command
        val lines = command.split('\n')
        val kept = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            kept.add(line)
            i++
            val body = strippableBody(line) ?: continue
            val delimiter = body.first
            val tabbed = body.second
            val end = indexOfTerminator(lines, i, delimiter, tabbed)
            // 找不到结束行 = 这不是合法 heredoc（`<<` 其实在引号里）：整段不剥。
            if (end < 0) continue
            i = end + 1
        }
        return kept.joinToString("\n")
    }

    /** 这一行的 heredoc 正文该不该剥：该剥时给（结束标记, 是否 `<<-`），正文会被执行时给 null。 */
    private fun strippableBody(line: String): Pair<String, Boolean>? {
        if (runsAsScript(line)) return null
        val redirect = HEREDOC_REDIRECT.find(line) ?: return null
        return redirect.groupValues[3] to (redirect.groupValues[1] == "-")
    }

    /**
     * 正文会不会被**当作命令执行**：这一行里（按 shell 分隔符拆出的任一段）真正执行的命令词
     * 是解释器就算。管道末端同样算 —— `echo ok | sh <<'EOF'` 的正文确实进了 `sh`。
     */
    private fun runsAsScript(line: String): Boolean =
        splitStatements(line).any { piece ->
            val word = commandWord(tokenize(piece))
            word != null && basename(word) in INTERPRETER_BINARIES
        }

    /** 从 [from] 起找结束行；没有（不是合法 heredoc）返回 -1。 */
    private fun indexOfTerminator(lines: List<String>, from: Int, delimiter: String, tabbed: Boolean): Int {
        for (j in from until lines.size) {
            if (isTerminator(lines[j], delimiter, tabbed)) return j
        }
        return -1
    }

    /**
     * 这行是不是结束行。shell 要求结束行独占一行，但命令替换写法（`… <<'EOF' … EOF)"`）会让它
     * 带上一串收尾标点，所以**只**接受「分隔符 + 标点 / 空白」；后面还有字母数字的（`EOF && rm -rf src`）
     * 不当它是结束行 —— 于是整段剥不掉，跟着的真实命令继续参与判定。
     * `<<-` 的结束行按 shell 语义只允许 tab 缩进。
     */
    private fun isTerminator(line: String, delimiter: String, tabbed: Boolean): Boolean {
        val text = (if (tabbed) line.trimStart('\t') else line).trimEnd()
        if (!text.startsWith(delimiter)) return false
        return text.substring(delimiter.length).all { it in CLOSING_CHARS || it.isWhitespace() }
    }

    /**
     * 命令引用到的脚本文件操作数（`sh x` / `python3 x` / `source x` / `./x`），去重保序。
     * 以 `-` 开头的操作数（如 `sh -c`）按选项处理，不算脚本文件。
     */
    fun scriptPaths(command: String): List<String> =
        (INTERPRETER_CALL.findAll(command).map { it.groupValues[1] } +
            SOURCE_CALL.findAll(command).map { it.groupValues[1] })
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-") && it != "." && it != ".." }
            .distinct()
            .toList()

    // ---------------------------------------------------------------------
    // 命令行目标解析（[commandReason] 用）
    // ---------------------------------------------------------------------

    /** 一条删除调用的解析结果：目标操作数 + 该语句执行时的 cwd。 */
    private data class DeleteUse(val reason: String, val targets: List<String>, val cwd: String?)

    private fun scanDeleteUses(command: String, workspace: String?): List<DeleteUse> {
        val uses = ArrayList<DeleteUse>()
        var cwd = workspace
        for (raw in splitStatements(command)) {
            val tokens = tokenize(raw)
            if (tokens.isEmpty()) continue
            // 语句以 cd 开头：更新 cwd（先按当前基准解析成绝对路径）。
            if (basename(tokens[0]) == "cd") {
                val arg = tokens.getOrNull(1)
                if (arg != null && arg != "-" && arg.none { it in UNCERTAIN_CHARS }) {
                    cwd = resolve(arg, cwd)
                }
                continue
            }
            // rm / rmdir / unlink / shred：其后的非选项词即操作数。
            val delIdx = tokens.indexOfFirst { basename(it) in DELETE_BINARIES }
            if (delIdx >= 0) {
                val targets = tokens.drop(delIdx + 1).filter { !it.startsWith("-") }
                uses.add(DeleteUse(DELETE_REASON, targets, cwd))
                continue
            }
            // find -delete：find 之后的非选项词是搜索根（即删除范围）。
            val findIdx = tokens.indexOfFirst { basename(it) == "find" }
            if (findIdx >= 0 && tokens.any { it == "-delete" }) {
                val roots = tokens.drop(findIdx + 1).takeWhile { !it.startsWith("-") }
                uses.add(DeleteUse("find -delete", roots, cwd))
            }
        }
        return uses
    }

    /**
     * 目标是否为「明显临时 / 构建产物」。[cwd] 为 null 且操作数是相对路径时，先折叠 `.` / `..`
     * 再看路径段（如 `build/classes` 仍可凭 `build` 一段豁免，`build/../../src` 不行）。
     */
    private fun isTransient(operand: String, cwd: String?): Boolean {
        val op = operand.trim()
        if (op.isEmpty()) return false
        if (op.any { it in UNCERTAIN_CHARS }) return false
        resolve(op, cwd)?.let { return isTransientAbs(it) }
        // cwd 未知的相对路径：先折叠 `.` / `..` 再按路径段判豁免；否则
        // `build/../../src` 会凭裸 `build` 段被误判为临时目录。
        return normalizeSegments(op).any { it.isNotEmpty() && it in TRANSIENT_SEGMENTS }
    }

    private fun isTransientAbs(abs: String): Boolean {
        for (root in TRANSIENT_ROOTS) {
            if (abs == root || abs.startsWith("$root/")) return true
        }
        return abs.split('/').any { it.isNotEmpty() && it in TRANSIENT_SEGMENTS }
    }

    /** 按**未被引号包裹**的 `; & |` 与换行把命令切成语句（引号内的分隔符不切）。 */
    private fun splitStatements(command: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = ' '
        var escaped = false
        for (c in command) {
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }
            when {
                c == '\\' && quote != '\'' -> {
                    sb.append(c)
                    escaped = true
                }
                quote == ' ' && (c == '\'' || c == '"') -> {
                    quote = c
                    sb.append(c)
                }
                quote != ' ' && c == quote -> {
                    quote = ' '
                    sb.append(c)
                }
                quote == ' ' && (c == ';' || c == '&' || c == '|' || c == '\n') -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /** 语句切分为词：按未被引号包裹的空白切；引号与反斜杠转义被去掉。 */
    private fun tokenize(statement: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = ' '
        var escaped = false
        var started = false
        for (c in statement) {
            if (escaped) {
                sb.append(c)
                escaped = false
                started = true
                continue
            }
            when {
                c == '\\' && quote != '\'' -> escaped = true
                quote == ' ' && (c == '\'' || c == '"') -> {
                    quote = c
                    started = true
                }
                quote != ' ' && c == quote -> quote = ' '
                quote != ' ' -> {
                    sb.append(c)
                    started = true
                }
                c.isWhitespace() -> if (started) {
                    out.add(sb.toString())
                    sb.clear()
                    started = false
                }
                else -> {
                    sb.append(c)
                    started = true
                }
            }
        }
        if (started) out.add(sb.toString())
        return out
    }

    private fun basename(token: String): String = token.substringAfterLast('/')

    /**
     * 命令位是不是变量 / 命令替换 / 通配（`$VAR`、`${VAR}`、反引号、`*.sh`）。
     * 判定对象是**穿透包装命令之后**的真正命令词（见 [commandWord]）——
     * `sudo $cmd -rf src` 的 `$cmd` 藏在 `sudo` 的参数位，只看第一个词会整条漏掉。
     * 用 [UNCERTAIN_COMMAND_CHARS]，不是目标侧的 [UNCERTAIN_CHARS]：家目录缩写 `~/`
     * 的命令是已知的，不该按「未知命令」拦。
     */
    private fun hasUncertainCommandPosition(command: String): Boolean =
        splitStatements(command).any { raw ->
            val word = commandWord(tokenize(raw))
            word != null && word.any { it in UNCERTAIN_COMMAND_CHARS }
        }

    /**
     * 一条语句里**真正被执行的那个词**：跳过环境变量前缀、shell 控制记号、选项与纯数值，
     * 遇到 [COMMAND_WRAPPERS] 就按它声明的操作数个数吞掉前置项再继续。
     * 找不到返回 null（空语句 / 全是前缀赋值）。
     */
    private fun commandWord(tokens: List<String>): String? {
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val isPrefix = token.startsWith("-") ||
                token.all { it.isDigit() } ||
                ENV_ASSIGNMENT.matches(token) ||
                token in SHELL_CONTROL_TOKENS
            if (isPrefix) {
                i++
                continue
            }
            val operands = COMMAND_WRAPPERS[basename(token)] ?: return token
            i++
            var consumed = 0
            while (i < tokens.size && consumed < operands) {
                if (!tokens[i].startsWith("-")) consumed++
                i++
            }
        }
        return null
    }

    private val ENV_ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")

    /** 不是命令词本身、但会出现在命令位前的 shell 控制记号（子壳 / 取反 / 分组）。 */
    private val SHELL_CONTROL_TOKENS = setOf("(", ")", "!", "{", "}")

    /** 把 [path] 解析成绝对路径（相对则拼到 [base] 下），并规范化 `.` / `..`；无法解析返回 null。 */
    private fun resolve(path: String, base: String?): String? {
        val raw = if (path.startsWith("/")) path else {
            val b = base ?: return null
            "$b/$path"
        }
        return "/" + normalizeSegments(raw).joinToString("/")
    }

    /** 按 `..` 折叠路径段；路径未知绝对基准时用于豁免判定前的规范化。 */
    private fun normalizeSegments(path: String): List<String> {
        val segments = ArrayList<String>()
        for (s in path.split('/')) {
            when (s) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty() && segments.last() != "..") {
                    segments.removeAt(segments.size - 1)
                } else {
                    segments.add("..")
                }
                else -> segments.add(s)
            }
        }
        return segments
    }
}
