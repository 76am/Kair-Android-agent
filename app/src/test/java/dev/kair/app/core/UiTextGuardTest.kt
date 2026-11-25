package dev.kair.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 文案守卫（B10 / P5-1）：把 `AGENTS.md` §4「面向用户的文案收敛到 `strings.xml`」
 * 从人眼换成单测。目标**不是**清空存量（那是 2–3 天的活、且有回归风险），而是让
 * 「UI 里的硬编码中文变多」变成一次红灯 —— 存量按文件登记上限、只降不增（棘轮）。
 *
 * core 侧的中文由 [DocsConsistencyTest] 的 A6-4 守（内核会话文本例外）；本类只守 `ui/`，
 * **两套登记表互不影响、不得合并**（P5-1 四闸）。
 *
 * 判定口径（唯一口径，照 [DocsConsistencyTest.chineseLiterals] 抄，别另起一套）：
 * 1. 范围：`app/src/main/java/dev/kair/app/ui/` 下的全部 `.kt`；
 * 2. **先剥注释**（`//`、可嵌套的块注释、KDoc）—— §4 要求注释是中文，剥不掉就全仓判红；
 * 3. 再抽字符串字面量（单引号串与三引号串）；
 * 4. 字面量含 CJK 即计 1 处（**一个字面量算一处**，不看里面几个汉字）；
 * 5. `Log.*` / `Timber.*` / `println` / `print` 开头的行不计（当前实测 0 处，规则留着防以后误伤日志）。
 *
 * 剥注释必须字符级状态机（要区分字符串里的 `//`），正则一把梭会把 54 个文件的中文注释全误伤。
 */
class UiTextGuardTest {

    // --------------------------------------------------------------- R1 · 新文件零容忍

    @Test
    fun `unregistered ui file must not contain chinese literals`() {
        val actual = actualCounts()
        val offenders = actual.filter { (rel, n) -> rel !in UI_CHINESE_BUDGET && n > 0 }
        assertTrue(
            buildString {
                appendLine("以下 ui/ 文件不在登记表里却有中文字面量（R1 · 新文件零容忍）：")
                offenders.forEach { (rel, n) ->
                    appendLine("  新文件 $rel 有 $n 处中文字面量，应走 res/values/strings.xml（AGENTS.md §4）")
                }
                append("面向用户的文案不得硬编码；确需新增存量，先在本类 UI_CHINESE_BUDGET 登记（并同步下调别的项）。")
            },
            offenders.isEmpty(),
        )
    }

    // --------------------------------------------------------------- R2 · 存量按文件登记上限

    @Test
    fun `registered files stay within their per-file cap`() {
        val actual = actualCounts()
        val over = mutableListOf<String>()
        val gone = mutableListOf<String>()
        UI_CHINESE_BUDGET.forEach { (rel, cap) ->
            val n = actual[rel]
            if (n == null) {
                gone += rel // 登记的文件已不在 ui/ 下（改名 / 删除）
            } else if (n > cap) {
                over += "  $rel：实际 $n 处，登记 $cap 处（超 ${n - cap}）"
            }
            // 实际 < 登记：不红（留余量，避免「改了文案忘改登记」就红）；
            // 但收敛成功后必须在同一次提交里把登记值改小到实际值 —— 这条靠评审人核，不靠守卫。
        }
        assertTrue(
            buildString {
                if (over.isNotEmpty()) {
                    appendLine("这些 ui/ 文件的中文字面量超过了登记上限（R2）：")
                    over.forEach { appendLine(it) }
                    appendLine("面向用户的文案请下沉到 strings.xml；确属存量新增，先上调登记（并同步 TOTAL_BASELINE）。")
                }
                if (gone.isNotEmpty()) {
                    appendLine("登记表里的这些文件已不在 ui/ 下（改名 / 删除，请把条目一起删掉）：")
                    gone.forEach { appendLine("  $it") }
                }
            },
            over.isEmpty() && gone.isEmpty(),
        )
    }

    // --------------------------------------------------------------- R3 · 总和棘轮，只降不增

    @Test
    fun `total registered budget equals the frozen baseline`() {
        val sum = UI_CHINESE_BUDGET.values.sum()
        // 用 ==（不是 ≤）把「登记总和」与「冻结基线」死绑：只调大 TOTAL_BASELINE 而不动登记 → 红。
        // 这正是变异③要验的：把 102 改成 103，本断言立刻红，堵住「反正只是改个数字」的悄悄放宽。
        assertTrue(
            "登记上限总和（$sum）必须与 TOTAL_BASELINE（$TOTAL_BASELINE）相等（R3 · 总和棘轮）。" +
                "两者要一起动：收敛一处就同时下调登记与基线；单独调大基线 = 悄悄放宽棘轮。",
            sum == TOTAL_BASELINE,
        )

        val actualTotal = actualCounts().values.sum()
        assertTrue(
            "ui/ 实测中文字面量总数（$actualTotal）不得超过冻结基线（$TOTAL_BASELINE）—— 只降不增。",
            actualTotal <= TOTAL_BASELINE,
        )
    }

    // --------------------------------------------------------------- R4 · 文件数棘轮

    @Test
    fun `registry does not grow beyond the frozen file-count ceiling`() {
        assertTrue(
            "登记表条目数（${UI_CHINESE_BUDGET.size}）不得超过 FILE_COUNT_CEILING（$FILE_COUNT_CEILING）（R4 · 文件数棘轮）。" +
                "想让一个新文件带中文存量 = 把守卫变成登记簿，批次 2 已裁决过 —— 新文件一律走 strings.xml（R1）。",
            UI_CHINESE_BUDGET.size <= FILE_COUNT_CEILING,
        )
    }

    // --------------------------------------------------------------- R5 · 界面文案不用 emoji

    /**
     * `AGENTS.md` §4 的界面文案一律**用字，不用 emoji**（负责人 2026-11-17：「ui 不要用 emoji 了」）。
     *
     * 为什么要有机械守卫：emoji 在 Android 上按系统字体渲染，同一句提示在 MIUI / 类原生上
     * 是两种字形、两种宽度、两种基线 —— 它与「配色 / 字距 / 行高都由我们定」是对立的，
     * 而且警告三角那类符号还会在某些字体里被渲染成彩色方块，把一行告警变成一块补丁。
     * 存量清干净（2026-11-17 U96：警告三角 × 3、图片记号 × 1、思考记号 × 2）之后**直接零容忍**，
     * 不设白名单 —— 这个门槛没有「历史包袱」，不需要棘轮。
     *
     * **U97 补的一个洞**：R5 原来只在字面量里找 emoji **字符**，于是写成转义序列的
     * `"\uD83E\uDDE0"` 从头到尾是 ASCII，扫不到 —— 25 个插件图标（`icon = "\uD83D\uDD0C"`）
     * 就是从这个洞里活下来的，用户看到坞里一排彩色脑子 / 插头才发现。现在每个字面量先按
     * `\uXXXX` 解码再判（`\\` 转义出来的反斜杠不算转义），孤立代理项也判红 ——
     * 半截的 `"\uD83D"` 在设备上只会显示成豆腐块。
     *
     * 范围比 R1–R4 宽：`app/src/main` 下**全部** `.kt` 的字符串字面量 + `res/values*` 的
     * `strings.xml` 文本节点。警告三角与思考记号当时就有一半长在 `core/`（`RequestAssembler` 的
     * 可读渲染、`DynamicPluginHost` 的安装回执），只扫 `ui/` 会漏掉一半。
     *
     * 口径：框「表情 / 符号 / 装饰符号」三段，**不碰** `→` `←` `·` `✓` `✕` —— 这些是排版记号，
     * 由系统字体当文字画（单色、等宽、与汉字同基线），`PhantomKillerPanel` 的完成徽章
     * 就靠它们。`FE0F`（变体选择符）一并禁：它唯一的作用就是把文字记号**强行转成 emoji 呈现**。
     */
    @Test
    fun `ui text must not contain emoji`() {
        val offenders = mutableListOf<String>()
        var scanned = 0
        var literals = 0

        val sources = File(repo, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        sources.forEach { f ->
            scanned++
            forEachLiteral(f.readText(Charsets.UTF_8)) { body, _ ->
                literals++
                emojisIn(decodeUnicodeEscapes(body)).forEach { cp ->
                    offenders += "${relativeToRepo(f)}：U+${codePointHex(cp)}（$body）"
                }
            }
        }

        val xmlFiles = File(repo, "app/src/main/res").walkTopDown()
            .filter {
                it.isFile &&
                    it.name.startsWith("strings") &&
                    it.parentFile?.name?.startsWith("values") == true
            }
            .toList()
        xmlFiles.forEach { f ->
            scanned++
            xmlTextOf(f.readText(Charsets.UTF_8)).forEach { text ->
                // 资源里的 `\u26a0` 由 aapt 解码，绕开源码扫描的同时照样显示成 emoji。
                emojisIn(decodeUnicodeEscapes(text)).forEach { cp ->
                    offenders += "${relativeToRepo(f)}：U+${codePointHex(cp)}（$text）"
                }
            }
        }

        assertTrue("app/src/main 下没扫到源码 / 文案 —— 扫描落空不等于通过", scanned > 0 && literals > 0)
        assertTrue(
            buildString {
                appendLine("界面文案里出现了 emoji（R5）：")
                offenders.forEach { appendLine("  $it") }
                append("改成文字或线稿图标。状态靠颜色 + 措辞表达（朱红 = 你 / 审批 / 危险、青碧 = agent），不靠表情符号；")
                append("图标走 `ui/icons/PluginGlyph` 的 SVG 路径。")
            },
            offenders.isEmpty(),
        )
    }

    // --------------------------------------------------------------- 登记表

    /**
     * 存量按**文件**登记上限（不是逐条登记 102 个字面量 —— 那等于把守卫变成登记簿）。
     * key 是相对 `ui/` 的路径。初值来自 P5-1 的门卫实测（基线 f902b04），
     * 副程用本类的状态机在 e6d326d 上复核：`SkillsPlugin.kt` 实测为 **4**（门卫记的 3 漏算了
     * `PLUGIN_AUTHORING.body` 那个含中文的三引号字面量；该文件自 f902b04 起未改动，纯属扫描口径差异，
     * 已按 P5-1 书面口径 rule 3「含 `"""..."""`」裁定计 4）。因此总和是 **103 → 收敛 ModelEditorScreen 后 102**。
     *
     * `DangerCommand.kt` **维持 2**（2026-11-01，U26 修复 + §B1.4 改码后）：A 侧曾把三处重复的
     * `"删除命令"` 收敛成 `DELETE_REASON`（3 → 1），但 §B1.4 第 2 条要求给「命令位不确定」
     * 一个独立理由词（不谎称「命令里有删除命令」），新增 `UNCERTAIN_COMMAND_REASON = "不确定的命令位"`
     * → 实测回到 2。登记 2、`TOTAL_BASELINE` 保持 103 —— 棘轮**停在原地**，不是放宽。
     * （rebase 收分叉时本文件不报冲突但会合出红：登记值只有跑测试才看得见。）
     *
     * `MessageTime.kt` 的 2 处**保留登记、暂不收敛**：它是无 Context 的纯函数，
     * 那 2 处是 `SimpleDateFormat` 的日期格式串（`月/日/年`），不是界面文案；
     * 强行入 strings.xml 需要 Context，会破 `MessageTimeTest` 的纯 JVM 断言（四闸「已有测试语义不变」）。
     * `ModelEditorScreen.kt` 已从本表移除：那 1 处（`"Gemini 原生"`）已下沉到
     * `R.string.settings_transport_gemini_native`，棘轮据此从 103 转到 102。
     *
     * `ProotPlugin.kt` **5 → 6**（2026-10-30，PR #31 与 #32 合并时）：两批各自给面板入口加了
     * 一个中文标题（`"Linux 进程"` / `"后台任务"`），**单独都是 5 处、都没越线**，合并后才 6 处。
     * 没下沉到 strings.xml 是有原因的，不是图省事：`PanelContribution.title` 是 `String`
     * （没有 `titleRes` 通道），而这段注册在 `Plugin.activate()` 里、属装配阶段 ——
     * `AppEnvironment` 明文规定「构造与装配一律不许碰 `appContext`」（JVM 里碰即抛），
     * 所以取不到 `getString`。同位置的存量 `"Linux 端口"` 也是这么登记的。
     * 真要收敛，得先给 `PanelContribution` 加 `titleRes` 通道，那是另一件事。
     *
     * **2026-11-05 更正（U37）**：那条「另一件事」已经做完 —— `PanelContribution.titleRes` 走构造器、
     * `SettingsPanelSpec.titleRes` / `SettingField.Action.buttonLabelRes` / `ChoiceOption.labelRes`
     * 走类体，`Plugin.nameRes` / `descriptionRes` 是带默认实现的接口成员，宿主解析只在
     * `ui/components/PluginDisplayText.kt` 一处。上面那段解释的是**登记当时的理由**，保留原文不改写；
     * 现在的含义变成「通道已有，这 6 处属存量未收敛」—— 收敛要单独一笔（见
     * `docs/tasks/2026-11-05-插件显示文本走资源通道.md` §6 第 3 条）。
     *
     * **2026-11-05 U38：`WorkspaceFilesPlugin.kt` 条目已删，基线 103 → 100、条目上限 22 → 21。**
     * 那 3 处（`name` / `description` / 面板 `title`）全部下沉 `strings.xml`，源码里改留空字面量 +
     * `nameRes` / `descriptionRes` / `titleRes`；同批拆出的 `WorkspaceFilesUi.kt` 与新加的
     * `TrashPlugin.kt` 实测 0 处中文字面量（内置插件的显示文案一律走资源，第三方插件仍只给字面量）。
     * 这就是上面那条挂账「那是另一件事」的兑现，也是本仓第一次真的把棘轮往回转一处文件。
     *
     * **2026-11-08 U64：记忆插件内置化新增 4 个文件进表（14 + 3 + 1 + 1 = 19；基线 100 → 119、
     * 条目上限 21 → 25）。** 这 19 处**不是用户界面文案**，全部是模型输入契约或诊断文本：
     * `MemoryPrompts.kt`（两段式标题、段尾维护规则、两张整理 prompt）、`MemoryContributor.kt`
     * （诊断面提示）、`MemoryPlugin.kt`（槽位按钮，`SlotElement` 没有资源通道）、`MemoryStore.kt`
     * （摘录省略说明）。记忆正文与维护规则按任务文档 §4.3 用中文（记忆文件本身就是中文），
     * 用户可见的设置 / 面板 / 按钮文案仍全部在 `strings.xml`。登记依据：负责人 2026-11-08
     * 派活原文「中文文案登记 UiTextGuardTest 的『文件 → 条数』白名单」。
     *
     * **2026-11-09 U51 加单：`MemoryPlugin.kt` 1 → 2，基线 119 → 120。** 新增 1 处是
     * TOP 槽位 `SlotElement.Panel` 的 label「记忆」—— `SlotElement` 没有资源通道
     * （与 U64 登记的「整理记忆」同类：装配阶段注册、取不到 `getString`）。技术负责人
     * 2026-11-09 设计委派，随批交接落档；面板 / 提示文案本身全走 `strings.xml`
     * （新文件 `MemoryPanel.kt` 零中文字面量，R1 不动）。
     *
     * **2026-11-17 U101 撤回：`MemoryPlugin.kt` 2 → 1，基线 120 → 119。** TOP 槽位停用
     * （负责人拍板去掉顶栏插件按钮），label「记忆」随 `memory.top` 声明一起退场 ——
     * 棘轮第一次真正往回转一格条目。
     *
     * **2026-11-19 记忆提示词补全：`MemoryPrompts.kt` 14 → 17，基线 119 → 122。**
     * 维护规则补上记忆文件路径（`.kair/memory.md`）、两段标题与读写工具说明 —— 这些是
     * 模型输入契约，不是用户界面文案；用户侧的设置 / 面板 / 按钮文案仍全部在 `strings.xml`。
     *
     * **2026-11-23 删除「Linux 端口」面板：`ProotPlugin.kt` 6 → 5，基线 122 → 121。**
     * 面板标题「Linux 端口」随 `panels.session` 注册一起删除，其余中文仍为面板入口标题。
     */
    private val UI_CHINESE_BUDGET: Map<String, Int> = mapOf(
        "plugins/builtin/ApprovalPlugin.kt" to 14,
        "plugins/builtin/SchedulerPlugin.kt" to 14,
        "plugins/builtin/MemoryPrompts.kt" to 17,
        "plugins/builtin/KeepAlivePlugin.kt" to 12,
        "plugins/builtin/DevToolsPlugin.kt" to 11,
        "plugins/builtin/McpPlugin.kt" to 6,
        "plugins/builtin/ProotPlugin.kt" to 5,
        "plugins/builtin/SkillsPlugin.kt" to 4,
        "plugins/builtin/MemoryContributor.kt" to 3,
        "plugins/builtin/CachePlugin.kt" to 3,
        "plugins/builtin/ContextPlugin.kt" to 3,
        "plugins/builtin/InfoPlugin.kt" to 3,
        "plugins/builtin/RequestLogPanel.kt" to 3,
        "plugins/builtin/SubagentsPlugin.kt" to 3,
        "plugins/builtin/AskUserPlugin.kt" to 2,
        "plugins/builtin/DangerCommand.kt" to 2,
        "plugins/builtin/PromptPresetsPlugin.kt" to 2,
        "plugins/builtin/ProviderTransportPlugin.kt" to 2,
        "plugins/builtin/ShellPlugin.kt" to 2,
        "plugins/builtin/StoragePlugin.kt" to 2,
        "plugins/builtin/SystemPromptPlugin.kt" to 2,
        "plugins/builtin/TerminalPlugin.kt" to 2,
        "plugins/builtin/MemoryPlugin.kt" to 1,
        "plugins/builtin/MemoryStore.kt" to 1,
        "screens/chat/MessageTime.kt" to 2,
    )

    private companion object {
        /** 登记上限总和的冻结基线。收敛一处就同时下调它与 [UI_CHINESE_BUDGET] 里对应条目（R3 用 == 死绑）。 */
        const val TOTAL_BASELINE = 121

        /** 登记表条目数的冻结上限（只减不增）。 */
        const val FILE_COUNT_CEILING = 25

        const val UI_PREFIX = "app/src/main/java/dev/kair/app/ui/"
    }

    // --------------------------------------------------------------- 扫描

    private val repo: File by lazy { findRepoRoot() }

    /** 每个 ui/ 文件的中文字面量数（key 相对 `ui/`），只返回 > 0 的与登记相关的。 */
    private fun actualCounts(): Map<String, Int> {
        val root = requireDir(File(repo, UI_PREFIX), "ui 源码目录")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("ui/ 下一个 .kt 都没扫到 —— 扫描落空不等于通过", files.isNotEmpty())
        return files.associate { f ->
            relativeToRepo(f).removePrefix(UI_PREFIX) to chineseLiteralCount(f.readText(Charsets.UTF_8))
        }
    }

    private val CJK = Regex("""[\u3400-\u9FFF]""")
    private val LOG_LINE = Regex("""^(?:Log|Timber|println|print)\b""")

    /**
     * 数一个源文件里「含 CJK 的字符串字面量」个数（R1–R4 的口径）。
     * 遍历与剥注释交给 [forEachLiteral]，本函数只负责「含中文字面量算 1 处」。
     */
    private fun chineseLiteralCount(source: String): Int {
        var count = 0
        forEachLiteral(source) { body, onLogLine ->
            if (CJK.containsMatchIn(body) && !onLogLine) count++
        }
        return count
    }

    /**
     * 遍历源文件里的全部**字符串字面量**（普通串 / 三引号串），逐字符状态机：
     * 区分行注释 / 可嵌套块注释 / 字面量，并配平模板插值 `${...}`。照
     * [DocsConsistencyTest] 的扫描器抄（口径已在那里对齐过，别另起一套）。
     *
     * `action` 收到字面量正文与「这个字面量是否在日志行上」（R1–R4 要排除日志行，
     * R5 不需要 —— emoji 长在日志里也不该留）。
     */
    private fun forEachLiteral(source: String, action: (body: String, onLogLine: Boolean) -> Unit) {
        var i = 0
        var lineStart = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '\n') { i++; lineStart = i; continue }
            if (c == '/' && source.startsWith("//", i)) {
                while (i < n && source[i] != '\n') i++
                continue
            }
            if (c == '/' && source.startsWith("/*", i)) {
                var depth = 1
                i += 2
                while (i + 1 < n && depth > 0) {
                    when {
                        source.startsWith("/*", i) -> { depth++; i += 2 }
                        source.startsWith("*/", i) -> { depth--; i += 2 }
                        else -> { if (source[i] == '\n') lineStart = i + 1; i++ }
                    }
                }
                continue
            }
            if (c == '"' || c == '\'') {
                val triple = c == '"' && source.startsWith("\"\"\"", i)
                val closer = if (triple) "\"\"\"" else c.toString()
                val litStart = i
                if (triple) i += 3 else i += 1
                val body = StringBuilder()
                while (i < n) {
                    if (source[i] == '\\' && i + 1 < n) {
                        body.append(source[i]).append(source[i + 1]); i += 2; continue
                    }
                    if (!triple && source[i] == '\n') break
                    if (source.startsWith(closer, i)) { i += closer.length; break }
                    // 模板插值 `${...}`：整体进 body（含源文）；嵌套字符串的引号 / 花括号
                    // 由 skipTemplateExpression 配平，不会提前收串（U9，与 DocsConsistencyTest 同步）。
                    if (source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                        val end = skipTemplateExpression(source, i + 2)
                        body.append(source, i, end)
                        i = end
                        continue
                    }
                    body.append(source[i]); i++
                }
                val onLogLine = LOG_LINE.containsMatchIn(source.substring(lineStart, litStart).trimStart())
                action(body.toString(), onLogLine)
                continue
            }
            i++
        }
    }

    /** 按**码点**判 emoji 区（不能用 `Char`：表情都在增补平面，代理对逐个看会判错）。 */
    private fun emojisIn(text: String): List<Int> =
        text.codePoints().toArray().filter { it.isEmoji() }

    private fun Int.isEmoji(): Boolean {
        // 表情 / 交通 / 物件 / 象形符号（1F000–1FAFF：图片、思考、火箭、灯泡都在这一段）
        if (this in 0x1F000..0x1FAFF) return true
        // 杂项符号（2600–26FF：警告三角、太阳、星、电话、回收标志）
        if (this in 0x2600..0x26FF) return true
        // 装饰符号（2700–27BF：剪刀、信封、铅笔、对勾框）。`✓ ✔ ✕ ✖ ✗` 除外 ——
        // 它们由系统字体当**单色文字**画，是与汉字同基线的排版记号（R5 口径见函数上方 KDoc）。
        if (this in 0x2700..0x27BF) return this !in 0x2713..0x2717
        // 星 / 方块 / 三角（2B00–2BFF）
        if (this in 0x2B00..0x2BFF) return true
        // 孤立代理项：`"\uD83D"` 这种半截转义在设备上只会显示成豆腐块
        if (this in 0xD800..0xDFFF) return true
        // 变体选择符：唯一作用是让前面的文字记号**按 emoji 呈现**
        return this == 0xFE0F
    }

    /**
     * 把字面量里的 `\uXXXX` 还原成真字符，其余部分原样保留。
     *
     * 为什么要还原：Kotlin 源码里写 `"\uD83D\uDD0C"` 与写 `"🔌"` 编译出来是同一个字符串，
     * 但**前者在磁盘上是纯 ASCII** —— 只按字符扫的守卫一个都看不见。U96 的 R5 就是这么
     * 放过了 25 个插件图标。`\\`（转义出来的反斜杠）不参与解码：`"\\uD83D"` 是一段
     * 描述转义的文本，不是转义本身，不能误判。
     */
    private fun decodeUnicodeEscapes(text: String): String {
        if (!text.contains("\\u")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '\\') {
                    out.append('\\')
                    i += 2
                    continue
                }
                if (next == 'u' && i + 6 <= text.length) {
                    val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                out.append(c).append(next)
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePointHex(cp: Int): String = Integer.toHexString(cp).uppercase()

    /**
     * 取 XML 的**文本节点**与属性值（注释整段剥掉）。不用 DOM：只需要「这一串是不是文案」，
     * 而 `strings.xml` 的坏味道只会出现在 `>…<` 之间与被引号包住的属性里。
     */
    private fun xmlTextOf(source: String): List<String> {
        val noComment = source.replace(Regex("""<!--[\s\S]*?-->"""), "")
        val textNodes = Regex(""">([^<]+)<""").findAll(noComment).map { it.groupValues[1] }
        val attrs = Regex("=\"([^\"]*)\"").findAll(noComment).map { it.groupValues[1] }
        return (textNodes + attrs).toList()
    }

    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    /**
     * 跳过一个字符串字面量（含模板插值的整体边界），返回**结束后的下标**；不记录内容。
     * 与 [skipTemplateExpression] 互相递归（口径照 `DocsConsistencyTest` 的同名实现，别另起一套）。
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

    /** 跳过 `${` 之后的表达式到配平的 `}`；嵌套字符串整体跳过（U9，与 `DocsConsistencyTest` 同步）。 */
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

    private fun requireDir(file: File, what: String): File {
        assertTrue("$what 不存在（$file）—— 扫描落空不等于通过", file.isDirectory)
        return file
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），UI 文案守卫无法运行")
    }
}
