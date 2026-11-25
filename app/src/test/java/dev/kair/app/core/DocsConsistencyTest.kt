package dev.kair.app.core

import dev.kair.app.core.agent.BuiltinPromptSections
import dev.kair.app.core.agent.TurnState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 一致性守卫（评估 A6）：把 `AGENTS.md` §6「文档与代码不一致视为未完成」从人眼换成单测。
 *
 * 为什么要它（`docs/架构评估-2026-10.md` §3.3 的实证）：`addendum` 恒传 `""` 那次，
 * 代码后来修对了、文档里的取证还停在旧版本 —— 「靠人眼守一致性」这条已经失效。
 * 现在没有任何机械约束拦住文档漂移，本类把它变成红灯。
 *
 * 五类扫描：
 * - A6-1 内置提示词段落清单（`BuiltinPromptSections` 是唯一事实源）；
 * - A6-2 会话状态机（`TurnState`；A1 前是 `SessionStatus` 枚举，锚点已随 A1 同步）；
 * - A6-3 `strings.xml` 死条目（定义了但无人引用）；
 * - A6-4 内核中文字面量清单（`AGENTS.md` §4 的例外：`core` 产出的错误 / 会话文本随会话持久化，
 *   不进 `strings.xml`，但**不得**扩散到没登记过的文件）；
 * - A6-5 带占位符的 `strings.xml` 条目里裸露的 `%` 必须写成 `%%`（漏了就是一条运行期崩溃路径）。
 *
 * 扫描范围的取舍（防「永远绿」）：只把 `docs` 与 `docs/adr` 两处的 Markdown 当**现状陈述**；
 * `docs/tasks/`（未来任务）、`docs/review/` 与 `docs/handoff/`（时点记录）里出现的
 * `Stopping` / `Compacting` 是**计划中的状态**，拿它们跟当前枚举比会天天误报。
 * 每条扫描都断言「真的扫到了东西」，扫到 0 个文件 / 0 条陈述直接失败。
 */
class DocsConsistencyTest {

    // --------------------------------------------------------------- A6-1

    @Test
    fun `prompt section list in docs matches the registry`() {
        val registry = BuiltinPromptSections.entries.associate { it.contributor.id to it.contributor.order }
        val claimed = anchoredLines(PROMPT_ANCHOR)
            .flatMap { line -> SECTION_ORDER.findAll(line).map { it.groupValues[1].toInt() } }
            .toSet()
        assertTrue(
            buildString {
                appendLine("文档里的内置段落清单与 `BuiltinPromptSections` 不同步（A6-1）。")
                if (claimed.isEmpty()) {
                    appendLine("  在 ${docsRoot()} 下没找到任何带「$PROMPT_ANCHOR + order 数字」的清单行 ——")
                    append("  扫描落空不等于通过：请把清单补回 docs/架构与内核边界.md，或修本类的锚点。")
                    return@buildString
                }
                val code = registry.values.toSet()
                (code - claimed).forEach { order ->
                    val id = registry.entries.first { it.value == order }.key
                    appendLine("  代码里有 $id（order $order），文档清单里没有")
                }
                (claimed - code).forEach { order ->
                    appendLine("  文档写了 order $order，代码里已经没有这一段了")
                }
                append("改注册表就同步改文档；锚点行取自包含「$PROMPT_ANCHOR」的文档行。")
            },
            claimed.isNotEmpty() && claimed == registry.values.toSet(),
        )
    }

    @Test
    fun `prompt section ids written in docs all exist`() {
        val registered = BuiltinPromptSections.entries.map { it.contributor.id }.toSet()
        val stale = docCorpus()
            .flatMap { (file, text) -> PROMPT_ID.findAll(text).map { file to it.value } }
            .filter { (_, id) -> id !in registered }
        assertTrue(
            buildString {
                appendLine("文档引用了不存在的段落 id（A6-1）：")
                stale.forEach { (file, id) -> appendLine("  $file → $id") }
                append("当前注册表里的 id：${registered.sorted()}")
            },
            stale.isEmpty(),
        )
    }

    // --------------------------------------------------------------- A6-2

    @Test
    fun `session turn state names written in docs match the state machine`() {
        // `TurnState` 是 sealed interface，测试依赖里没有 kotlin-reflect（`sealedSubclasses`
        // 用不了），所以这里**逐个构造**再映射成名字。好处是 `when` 必须穷尽：状态机新增
        // 一个变体，本类会直接编译失败 —— 比手写一份名单更难「忘了改」。
        val code = listOf(TurnState.Idle, TurnState.Busy, TurnState.Running(), TurnState.Stopping)
            .map { it.stateName }
            .toSet()
        // 只扫**现状陈述**的三份文档（读者按它干活的那三份）。显式排除：
        // `docs/adr`（拍板记录）、`docs/review` / `docs/handoff`（时点记录）、
        // `docs/tasks` 与 `docs/架构加固计划.md`（候选形状）—— 它们合法地提到过
        // `Compacting`（A1 已把它收成 Running 的相位），拿来跟实现比会天天误报。
        val claims = TURN_STATE_DOCS.map { path ->
            val file = File(repo, path)
            assertTrue("$path 不存在 —— 一致性扫描落空，不等于通过", file.isFile)
            path to file.readLines(Charsets.UTF_8)
                .filter { it.contains("TurnState") }
                .flatMap { line -> TURN_STATE_NAME.findAll(line).map { it.value } }
                .toSet()
        }
        val stale = claims.flatMap { (path, names) -> (names - code).map { path to it } }
        val documented = claims.flatMap { it.second }.toSet()
        assertTrue(
            buildString {
                appendLine("文档里的会话状态名与 `TurnState` 不同步（A6-2）。")
                stale.forEach { (path, name) ->
                    appendLine("  $path 写了 $name，状态机里没有（改名 / 删除 / 那是候选形状？）")
                }
                (code - documented).forEach { appendLine("  状态机里有 $it，三份现状文档都没写") }
                append("A1 起状态机是 sealed `TurnState`：改状态就同步改 docs/架构与内核边界.md；")
                append("另外两份提到 `TurnState` 时，状态名也必须与实现一致。")
            },
            stale.isEmpty() && documented == code,
        )
    }

    // --------------------------------------------------------------- A6-3

    @Test
    fun `no dead string resources`() {
        val main = mainSourceRoot()
        val stringsFile = File(main, "res/values/strings.xml")
        val defined = Regex("""<string name="([^"]+)"""")
            .findAll(stringsFile.readText(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toList()
        assertTrue("strings.xml 里一条 <string> 都没扫到（$stringsFile）—— 扫描路径写错了，不等于没有死条目", defined.isNotEmpty())

        val corpus = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
        // 必须按**标识符右边界**匹配，不能用 `"R.string.$name" in corpus` 这种子串判定：
        // `R.string.foo` 是 `R.string.foobar` 的子串，父条目只有在子条目还被引用时会被误判成「活」。
        // 本仓 `strings.xml` 里现有 151 对前缀关系（如 proot_tool_install / proot_tool_installed），
        // 这个洞随时会踩到 —— 门卫评审时用 zzz_probe + zzz_probe_child 复现过（旧判据漏掉父条目）。
        val dead = defined.filter { name ->
            !Regex("""(?:R\.string\.|@string/)${Regex.escape(name)}(?![A-Za-z0-9_])""")
                .containsMatchIn(corpus)
        }
        assertTrue(
            buildString {
                appendLine("strings.xml 里有定义了却无人引用的条目（A6-3，${dead.size} 条）：")
                dead.forEach { appendLine("  $it") }
                append("删掉它们，或者把它们接进界面 —— 留着只会让人以为界面还有这个文案。")
            },
            dead.isEmpty(),
        )
    }

    // --------------------------------------------------------------- A6-4

    @Test
    fun `error parser chinese literals are exactly the registered ones`() {
        val file = File(mainSourceRoot(), "java/dev/kair/app/core/ai/ErrorParser.kt")
        val found = chineseLiterals(file.readText(Charsets.UTF_8))
        assertTrue(
            buildString {
                appendLine("ErrorParser.kt 的中文字面量与登记清单不符（A6-4）。")
                (errorParserWhitelist - found.toSet()).forEach { appendLine("  清单里有、文件里没有：「$it」") }
                (found.toSet() - errorParserWhitelist).forEach { appendLine("  文件里新增、清单没登记：「$it」") }
                append("这些文本随会话持久化、且 core 不能依赖 Context，所以按 AGENTS.md §4 由内核直出、")
                append("不进 strings.xml；**新增必须在这里署名**，同时确认它不是该进 strings.xml 的界面文案。")
            },
            found.toSet() == errorParserWhitelist,
        )
    }

    @Test
    fun `kernel chinese text stays within the registered files`() {
        val roots = coreSourceRoots()
        val scanned = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("core 源码里一个 .kt 都没扫到 —— 扫描路径写错了，不等于没有违规", scanned.isNotEmpty())
        val found = scanned.map { relativeToRepo(it) to chineseLiterals(it.readText(Charsets.UTF_8)) }
            .filter { (_, hits) -> hits.isNotEmpty() }
            .map { (rel, _) -> rel }
            .toSet()
        val unregistered = found - kernelChineseTextFiles
        val gone = kernelChineseTextFiles - found
        assertTrue(
            buildString {
                if (unregistered.isNotEmpty()) {
                    appendLine("这些 core 文件新引入了中文字面量，但未登记为「内核会话文本例外」（A6-4）：")
                    unregistered.forEach { appendLine("  $it") }
                    appendLine("  先判它面向谁：面向**用户**的界面文案必须进 res/values/strings.xml（AGENTS.md §4）；")
                    append("只有随会话持久化、且拿不到 Context 的内核文本才算例外，登记进 kernelChineseTextFiles。")
                }
                if (gone.isNotEmpty()) {
                    appendLine("已登记的文件里已经没有中文字面量了（收敛成功，请把白名单一起删掉）：")
                    gone.forEach { appendLine("  $it") }
                    append("白名单只减不增 —— 删干净才算真的减了。")
                }
            },
            unregistered.isEmpty() && gone.isEmpty(),
        )
    }

    // --------------------------------------------------------------- A6-5

    /**
     * 带占位符的字符串里，裸露的 `%` 必须写成 `%%`（A6-5）。
     *
     * 为什么这条值得机械守：`stringResource(R.string.x, n)` 走的是 `String.format`，
     * 文案里写「不是 0%」时那个 `%)` 是**非法转换符**，运行期抛 `UnknownFormatConversionException`
     * —— 面板一打开就崩。而编译期只有一行 aapt warning
     * （`Multiple substitutions specified in non-positional format`），单测看不见、日志不显眼。
     * 实证：`cache_hit_weighted_none` 就是这一条（U30 加的），触发条件是
     * 「本会话每一发都只回了空 usage」—— 那恰好是这条文案本来要说话的那一种情况。
     *
     * 判据只作用在**含位置占位符**（`%1$s` / `%2$d`）的条目上：静态文案里的 `%` 不经
     * `String.format`，是普通字符，不该误报。
     */
    @Test
    fun `formatted string resources escape bare percent signs`() {
        val stringsFile = File(mainSourceRoot(), "res/values/strings.xml")
        val entries = Regex("""<string name="([^"]+)"[^>]*>([\s\S]*?)</string>""")
            .findAll(stringsFile.readText(Charsets.UTF_8))
            .toList()
        assertTrue("strings.xml 里一条 <string> 都没扫到（$stringsFile）—— 扫描路径写错了，不等于全绿", entries.isNotEmpty())
        val positional = Regex("""%[1-9]\$[a-zA-Z]""")
        // 先把**合法**的写法吃掉，再看剩下还有没有 `%`：`%%` 是成对的，直接判「% 后面不是 %」会误报
        // —— `%%` 后面那个汉字看着就像裸 %。第一版就是这么错的：六条正常文案全被判成违规。
        val offenders = entries.filter {
            val body = it.groupValues[2]
            positional.containsMatchIn(body) && positional.replace(body.replace("%%", ""), "").contains('%')
        }.map { it.groupValues[1] to it.groupValues[2].trim() }
        assertTrue(
            buildString {
                appendLine("strings.xml 里有带占位符却没把裸露 % 写成 %% 的条目（A6-5，${offenders.size} 条）：")
                offenders.forEach { (name, body) -> appendLine("  $name = $body") }
                append("那些条目会被 String.format 解析：剩下的 % 后面跟什么就是什么转换符，")
                append("`)`、`，`、空格都是非法的 —— 界面渲染那一刻崩。把裸露的 % 写成 %%。")
            },
            offenders.isEmpty(),
        )
    }

    /**
     * `AGENTS.md` §4 允许的内核中文字面量所在文件（相对**仓库根**）。
     * 全部是**存量**，逐条原因见行尾注释；`docs/团队分工与交付流程.md` §2.3 #1 的 120 处界面硬编码
     * 不在此列（那是 B10 的范围，本批不清理）。
     */
    private val kernelChineseTextFiles: Set<String> = setOf(
        // A5 搬家（批次 4）：「未知错误」兜底随 `streamAssistant` 一起从 AgentSession.kt 搬来，
        // 登记跟着字面量走。**U9（2026-10-28）已修扫描盲区**：`chineseLiterals()` 加强了
        // 模板插值配平，`${e.message ?: "压缩失败"}` 形态不再漏扫 —— AgentSession.kt 的
        // 3 处兜底字面量按预期登记回来（加强扫描的结果，不是回归）。
        "app/src/main/java/dev/kair/app/core/agent/AgentSession.kt", // `${e.message ?: "中文"}` 兜底（压缩失败 / 摘要请求失败随 CompactionRunner 搬走，剩下的登记仍在）
        "app/src/main/java/dev/kair/app/core/agent/CompactionRunner.kt", // 同上两处兜底随 A5 第二步搬到这里（诊断文本，落 Diagnostics 面板）
        "app/src/main/java/dev/kair/app/core/agent/TurnExecutor.kt", // 「未知错误」兜底
        // 2026-11-06 U48 新增：步数用尽提示正文（`stepLimitNoteText`）—— 随会话持久化、
        // core 拿不到 Context 的内核会话文本（AGENTS.md §4 例外，同 `stalledUpstreamMessage` 先例）。
        "app/src/main/java/dev/kair/app/core/agent/TurnRunner.kt",
        "app/src/main/java/dev/kair/app/core/agent/RequestAssembler.kt", // 装配期诊断文本
        "app/src/main/java/dev/kair/app/core/agent/SystemPrompt.kt", // 英文段落里引用的 UI 面板名 + require 断言
        "app/src/main/java/dev/kair/app/core/ai/Ai.kt", // 「服务端卡住」等内核错误文案
        // 2026-11-07 U57：网络前置阶段名（DNS 解析 / 建立连接 / TLS 握手 / 等待响应头）。
        // 它们随「停止超时」诊断给用户看，core 拿不到 Context，按 §4 例外由内核直出。
        "app/src/main/java/dev/kair/app/core/ai/PreConnect.kt",
        // 2026-11-07 U57：强制收口那句话（停在哪一阶段、已卡多久、还能不能发）。
        // 由 `Diagnostics.stopTimeouts` 持久化并直接渲染进「请求诊断」面板 —— 与
        // `AgentSession` 里那条中止说明同族（内核中文，UI 不二次翻译）。
        "app/src/main/java/dev/kair/app/core/agent/StopWatchdog.kt",
        "app/src/main/java/dev/kair/app/core/ai/ErrorParser.kt", // 错误归类说明（清单见下）
        "app/src/main/java/dev/kair/app/core/ai/ModelCatalog.kt", // 供应商展示名（§2.3 #1 存量，B10 收敛）
        "app/src/main/java/dev/kair/app/core/mcp/McpManager.kt", // MCP 连接状态文本
        "app/src/main/java/dev/kair/app/core/mcp/McpProtocol.kt", // MCP 协议错误说明
        "app/src/main/java/dev/kair/app/core/mcp/McpTypes.kt", // 配置校验错误说明
        "app/src/main/java/dev/kair/app/core/plugin/dynamic/DynamicPluginHost.kt", // 插件装载失败条目文本
        "app/src/main/java/dev/kair/app/core/plugin/dynamic/InstallPluginTool.kt", // 安装 / 卸载结果回灌文本
        "app/src/main/java/dev/kair/app/core/plugin/dynamic/Kplug.kt", // 插件包错误说明
        "app/src/main/java/dev/kair/app/core/plugin/dynamic/KplugLoader.kt", // dex 装载失败说明
        "app/src/main/java/dev/kair/app/core/plugin/dynamic/PluginCompatibility.kt", // ABI 不兼容说明
        "app/src/main/java/dev/kair/app/core/proot/ContainerSession.kt", // 常驻会话状态与错误文本
        // 2026-11-14 U89 E：音频接收端失败提示写进会话转录（终端面板直接展示、agent 也要看见），
        // core 拿不到 Context，与 ContainerSession 的内核文本同族。
        "app/src/main/java/dev/kair/app/core/proot/ContainerSessionHost.kt",
        // 2026-11-14 U89 E：`kair-audio-status` 是容器终端里的自查脚本（.asoundrc / nc / 5902），
        // 中文输出面向容器里的用户与 agent；core 无法走 strings.xml（同 DesktopScripts 先例）。
        "app/src/main/java/dev/kair/app/core/proot/SessionAudio.kt",
        "app/src/main/java/dev/kair/app/core/proot/PtySession.kt", // PTY 会话状态与启动失败原因（面板直接展示）
        "app/src/main/java/dev/kair/app/core/proot/ProotCommandRunner.kt", // proot 通道错误说明
        "app/src/main/java/dev/kair/app/core/proot/RootCommandRunner.kt", // root 执行错误说明
        "app/src/main/java/dev/kair/app/core/proot/RootfsManager.kt", // rootfs 状态说明
        // U89 A/B：桌面菜单与快捷方式的中文标签写在容器侧 shell 脚本里（jwm 菜单 / pcmanfm
        // 桌面图标），面向的是容器桌面而不是 Compose；core 拿不到 Context，也无法走 strings.xml。
        "app/src/main/java/dev/kair/app/core/desktop/DesktopScripts.kt",
        // U92：桌面安装失败的完整原因（apt 日志 / 磁盘空间不足）由 core 生成并直接展示在面板，
        // 与 DesktopScripts 同族；core 拿不到 Context，无法走 strings.xml。
        "app/src/main/java/dev/kair/app/core/desktop/DesktopRunner.kt",
        "app/src/main/java/dev/kair/app/core/desktop/DesktopInstallSupport.kt",
        // 2026-10-27 批次 3 新增（A3 技能目录容错）：两处都在**异常 / 空文件**的兜底文本里，
        // 且含 `it.message`（异常原文）与 `$SKILL_FILE` 插值，无法下沉成 code 由 UI 翻译
        // （下沉只能得到 code + 自由文本的双路径）。裁决见 docs/review/2026-10-27-failure-visibility.md §6。
        "app/src/main/java/dev/kair/app/core/skills/Skill.kt", // 「读取失败」与「内容为空」的兜底文本
        "app/src/main/java/dev/kair/app/core/storage/PromptPresetStore.kt", // 预设类型标签
        "app/src/main/java/dev/kair/app/core/storage/Storage.kt", // 会话文件读取失败说明
        "app/src/main/java/dev/kair/app/core/subagent/Subagent.kt", // 「子智能体」标题文本
        "plugin-api/src/main/kotlin/dev/kair/app/core/tools/CommandRunner.kt", // plugin-api：契约默认文本
    )

    /** `ErrorParser.kt` 里被定性的内核中文字面量（AGENTS.md §4 例外，不进 strings.xml）。 */
    private val errorParserWhitelist: Set<String> = setOf(
        "未知错误",
        "…（已截断，完整原文见「请求诊断」的导出）",
    )

    // --------------------------------------------------------------- 扫描辅助

    /** 状态名。`when` 必须穷尽 —— 状态机新增变体时这里会编译失败，逼着同步本类与文档。 */
    private val TurnState.stateName: String
        get() = when (this) {
            TurnState.Idle -> "Idle"
            TurnState.Busy -> "Busy"
            is TurnState.Running -> "Running"
            TurnState.Stopping -> "Stopping"
        }

    private val repo: File by lazy { findRepoRoot() }

    /** 带锚点关键字的文档行（`docs` 与 `docs/adr` 下的 Markdown）。 */
    private fun anchoredLines(anchor: String): List<String> = docCorpus()
        .flatMap { (_, text) -> text.lines().filter { it.contains(anchor) } }

    private fun docCorpus(): List<Pair<String, String>> {
        val docs = docsRoot()
        val files = docs.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .filter { it.parentFile == docs || it.parentFile.name == "adr" }
            .toList()
        assertTrue("docs 目录里没扫到任何现状陈述文档（$docs）—— 一致性扫描落空，不等于通过", files.isNotEmpty())
        return files.map { relativeToRepo(it) to it.readText(Charsets.UTF_8) }
    }

    /**
     * 提取源码里的**字符串字面量**中的中文（`AGENTS.md` §4 判的是模型输入与界面文案，注释不算）。
     *
     * 逐字符扫描，区分行注释 / 块注释 / 普通字符串 / 三引号字符串。
     * **模板插值（U9 加强，2026-10-28）**：`${...}` 整体进字面量文本（含源文），
     * 表达式内嵌套字符串的边界由 [skipTemplateExpression] 配平 —— 旧版在嵌套引号处
     * 按第一个引号收字符串，`${e.message ?: "压缩失败"}` 这类兜底扫不到且让扫描失同步
     * （旧 KDoc「只会多算不会漏算」的说法是错的，U9 证伪）。不分片，一条字面量仍是一条。
     */
    private fun chineseLiterals(source: String): List<String> {
        val hits = mutableListOf<String>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '/' && source.startsWith("//", i)) {
                while (i < n && source[i] != '\n') i++
                continue
            }
            if (c == '/' && source.startsWith("/*", i)) {
                // Kotlin 的块注释**可以嵌套**（`docs/*.md` 这种写法会意外开一层），
                // 所以按深度配平，而不是找第一个 `*/` 就收 —— 否则整份文件会被当成注释吞掉。
                var depth = 1
                i += 2
                while (i + 1 < n && depth > 0) {
                    when {
                        source.startsWith("/*", i) -> { depth++; i += 2 }
                        source.startsWith("*/", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                }
                continue
            }
            if (c == '"' || c == '\'') {
                val triple = c == '"' && source.startsWith("\"\"\"", i)
                val closer = if (triple) "\"\"\"" else c.toString()
                if (triple) i += 3 else i += 1
                val body = StringBuilder()
                while (i < n) {
                    if (source[i] == '\\' && i + 1 < n) {
                        body.append(source[i]).append(source[i + 1]); i += 2; continue
                    }
                    if (!triple && source[i] == '\n') break
                    if (source.startsWith(closer, i)) {
                        i += closer.length
                        break
                    }
                    // 模板插值 `${...}`：整体进 body（含源文）；表达式里嵌套字符串的
                    // 引号 / 花括号由 skipTemplateExpression 配平，不会提前收串（U9）。
                    if (source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                        val end = skipTemplateExpression(source, i + 2)
                        body.append(source, i, end)
                        i = end
                        continue
                    }
                    body.append(source[i]); i++
                }
                val text = body.toString()
                if (CJK.containsMatchIn(text)) hits += text.replace("\n", "\\n").take(120)
                continue
            }
            i++
        }
        return hits
    }

    /** 相对仓库根的路径（白名单里写它，跨工作区可比）。 */
    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    /**
     * 跳过一个字符串字面量（含模板插值的整体边界），返回**结束后的下标**；不记录内容。
     * 与 [skipTemplateExpression] 互相递归：插值表达式里可以有嵌套字符串，嵌套字符串里
     * 又可以有插值。未闭合（普通串遇换行 / 文件尾）时停在断点处。
     */
    private fun skipStringLiteral(source: String, start: Int): Int {
        val n = source.length
        var i = start
        val quote = source[i]
        val triple = quote == '"' && source.startsWith("\"\"\"", i)
        val closer = if (triple) "\"\"\"" else quote.toString()
        i += if (triple) 3 else 1
        while (i < n) {
            when {
                source[i] == '\\' && i + 1 < n -> i += 2
                !triple && source[i] == '\n' -> return i
                source.startsWith(closer, i) -> return i + closer.length
                source[i] == '$' && i + 1 < n && source[i + 1] == '{' ->
                    i = skipTemplateExpression(source, i + 2)
                else -> i++
            }
        }
        return i
    }

    /**
     * 跳过 `${` 之后的表达式到**配平**的 `}`（返回 `}` 之后的下标）。
     * 嵌套字符串整体跳过 —— 其中的引号不终止外层字符串、花括号不参与配平。
     * 已知残余盲区：插值里的**注释**若含花括号会搅乱配平（本仓无此写法）。
     */
    private fun skipTemplateExpression(source: String, start: Int): Int {
        val n = source.length
        var i = start
        var depth = 1
        while (i < n && depth > 0) {
            when (source[i]) {
                '"', '\'' -> i = skipStringLiteral(source, i)
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++ }
                else -> i++
            }
        }
        return i
    }

    private fun docsRoot(): File = requireDir(File(repo, "docs"), "docs 目录")

    private fun mainSourceRoot(): File = requireDir(File(repo, "app/src/main"), "app/src/main")

    /** `core` 的两棵主源码树：app 侧与 plugin-api 侧（`AGENTS.md` §4 的例外覆盖两者）。 */
    private fun coreSourceRoots(): List<File> = listOf(
        requireDir(File(repo, "app/src/main/java/dev/kair/app/core"), "app 侧 core 源码"),
        requireDir(File(repo, "plugin-api/src/main/kotlin/dev/kair/app/core"), "plugin-api 侧 core 契约"),
    )

    private fun requireDir(file: File, what: String): File {
        assertTrue("$what 不存在（$file）—— 扫描落空不等于通过", file.isDirectory)
        return file
    }

    /**
     * 定位仓库根：测试的工作目录通常在模块目录下，但也容忍从仓库根启动，
     * 所以向上逐级找带 `docs/` 与 `app/` 的那一层。找不到直接失败 —— 宁可报错也不要静默跳过检查。
     */
    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），一致性守卫无法运行")
    }

    private companion object {
        const val PROMPT_ANCHOR = "BuiltinPromptSections"

        /** 「开场白（order 0）/ 交付路径（order 2）/ 规则（20）」这类全括号里的序号。 */
        val SECTION_ORDER = Regex("""[（(](?:order\s*)?(\d+)[）)]""")

        /** 文档里写全的段落 id。 */
        val PROMPT_ID = Regex("""dev\.kair\.prompt\.([a-z]+)""")

        /**
         * 状态名候选：`TurnState` 的变体名（大小写混合）。
         * `Compacting` 故意留在候选里 —— 文档若还把它写成并列状态，A6-2 会红。
         */
        val TURN_STATE_NAME = Regex("""(?<![A-Za-z0-9_])(Idle|Busy|Running|Stopping|Compacting)(?![A-Za-z0-9_])""")

        /**
         * A6-2 的现状陈述范围：读者按它干活的三份文档。
         *
         * 白名单之外的都不扫：`docs/adr`（拍板记录）、`docs/review` / `docs/handoff`
         * （时点记录）、`docs/tasks` 与 `docs/架构加固计划.md`（候选形状）。
         */
        // 公开快照：内部流程文档（团队分工）不随公开库分发。它本身没有 TurnState 行，
        // 上面两份已写全四个状态名，守卫覆盖面不变。
        val TURN_STATE_DOCS = listOf(
            "docs/架构与内核边界.md",
            "AGENTS.md",
        )

        val CJK = Regex("""[\u3400-\u9FFF]""")
    }
}
