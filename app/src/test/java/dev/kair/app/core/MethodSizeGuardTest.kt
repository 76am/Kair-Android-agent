package dev.kair.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方法长度守卫（函数级）：非 `@Composable` > 100 行 / `@Composable` > 200 行即红，
 * 存量 13 个进白名单且**只准减不准增**。
 *
 * 这是 `SourceSizeGuardTest`（文件级，B8）的函数级兄弟，模式一致：白名单 + 棘轮 + 防假绿的
 * 防御断言。分两档的依据是实测分布（2026-10-29，1201 个函数）：非 `@Composable` P99 = 92、
 * `@Composable` P95 = 194，两类几乎不重叠（P50 是 5 vs 32）。一刀切 150 的后果是
 * 非 Composable 上只抓 4 个、Composable 上把中等复杂度的屏幕也判成问题 —— 详见
 * `docs/tasks/P5-2-方法长度守卫.md` §1。
 *
 * 口径是**物理行**：`fun` 所在行到函数体最后一行，空行与注释都计入
 * （与 `SourceSizeGuardTest` 的 `readLines().size` 同一口径）。所以「为过守卫删 KDoc」
 * 属于作弊，评审直接打回（`AGENTS.md` §7）。
 *
 * 范围只含**主源码**（`app/src/main` + `plugin-api/src/main`）：测试文件的长度反映场景数量，
 * 把它们套进同一阈值只会诱导人拆测试。
 */
class MethodSizeGuardTest {

    /**
     * 白名单分两张表：普通方法（阈值 100）与 `@Composable`（阈值 200）。
     * 拆成两张而不是在条目里加一个 bool，是为了让「分类」本身也成为匹配条件：
     * 若解析器把 Composable 判成普通方法（或反过来），条目会「找不到」→ 判据 3 直接红。
     * 没有这一步，把分类写死成 false 时守卫会全绿 —— 那正是变异 D 要排除的情形。
     *
     * 每张表的结构都是「相对仓库根的路径 → `方法名 → 允许达到的行数`」，
     * 不是「文件 → 行数」：`AnthropicProvider.kt` 有两个方法同时超限
     * （`stream` / `addAnthropicMessages`）。
     *
     * **白名单键为「文件 + 方法名」，一个键覆盖 1 个超限方法**：同名重载若同时超限，
     * 第 2 个起不豁免，必须在 PR 里显式拆分或另立条目。这条规则的唯一说明书就是这里 ——
     * 判据 1 按名字豁免、判据 2 取同名最大值，两处合起来会让「新增同名重载」
     * 从缝里静默溜过去（P3-2 的原始漏洞）；`isWhitelisted` 里的计数就是堵这个缝的。
     */
    private val plainOversized: Map<String, Map<String, Int>> = mapOf(
        // 单轮流式装配：请求拼装 / 工具循环 / 增量落库三段同居。**优先拆这个**（P2-2 靶子之一）。
        "app/src/main/java/dev/kair/app/core/agent/TurnExecutor.kt" to mapOf(
            // 2026-11-02 U30 由 279 上调到 282（+3）：取装配产物而不是只取 settings
            // （`val built = host.assembly()` + `val settings = built.settings`，1 行变 2 行）
            // 加 2 行「为什么读数必须成对带来」的注释。这条本来可以只 +1（把注释并到上一行），
            // 多出来的那两行注释是给下一位的护栏 —— 第一轮 B 审 P1 就是这么被踩出来的。
            // 2026-11-06 U48 由 282 上调到 287（+5）：空回合不再无痕 —— 写诊断计数与
            // 「溢出救援不吃亏」的说明，返回值改带 emptyResponse、丢弃分支收尾注释。
            // 2026-11-07 U61 由 287 上调到 **293**（+6）：诊断要能区分「HTTP 200 之后流失败」——
            // 记本次尝试起点（防本地异常误标上一发）+ 在 `StreamEvent.Failure` 回填 `streamFailure`。
            // 真实功能增长；拆分仍按上面那条 P2-2 计划走，不在本批动。
            // 2026-11-12 U76 由 293 上调到 **298**（+5，实测）：思考回传的来源键要在请求侧与
            // 落库侧各带一份 —— 装配处 `providerId` / `providerHost` 两参、`snapshot()` 两参，
            // 加 1 行「消息侧取自本发请求」的说明。**先把能省的省掉了**：初版另立
            // `val providerHost = hostOf(...)` 加 3 行护栏注释（+9，实测 302），改成落库直接读
            // `request.providerHost` 后降到 +5，来源也只剩一个实现点（AGENTS.md §10.4）；
            // 那 3 行护栏搬进 `RequestAssembler.assemble` 的参数 KDoc，没有删掉信息。
            // 2026-11-24 由 298 上调到 **300**（+2）：只有思考的干净收尾改判空回合
            // （qwen 网关推理后直接 EOS）—— 判定从 `hasContent` 改为 `hasAnswer` 加兜底。
            "streamAssistant" to 300,
        ),
        // `= flow { ... }` 表达式体，四家传输层同形。拆分要先定共同骨架，暂无单独计划。
        "app/src/main/java/dev/kair/app/core/ai/AnthropicProvider.kt" to mapOf(
            "stream" to 208,
            // 消息 → Anthropic messages 转换。无拆分计划。
            "addAnthropicMessages" to 107,
        ),
        // 与 Anthropic 的 `stream` 同形（`= flow { ... }`）。无拆分计划。
        "app/src/main/java/dev/kair/app/core/ai/OpenAiProvider.kt" to mapOf(
            "stream" to 207,
        ),
        // 会话主循环本体。2026-10-31 A5 第二步把它从 `AgentSession` 搬到 `TurnRunner`
        // （纯位移，198 → 202 行：`host.` 前缀与两处参数展开）。仍是**单一职责**的一个循环，
        // 拆成碎片只会把「中止 / 截断 / 溢出」三条语义切散 —— 见 `TurnRunnerTest` 的定点用例。
        // 2026-11-06 U48 由 202 上调到 215（+13）：四条静默出口改可枚举原因 —— `termination`
        // 显式携带（+4）、`!isActive` 早退区分关闭 / 中止（+3）、步数上限出口（+3）、
        // 空回合 / 正常收尾取回合自带 stopReason 的说明（+3）。逻辑还是那一个循环，
        // 多的是每个出口「为什么是这个原因」；砍注释过守卫属于作弊（AGENTS.md §7）。
        "app/src/main/java/dev/kair/app/core/agent/TurnRunner.kt" to mapOf(
            // 2026-11-24 空回复重试：策略包在 `streamWithEmptyRetry`（新文件）里，
            // 循环本体不加逻辑，仍为 215。
            "runLoop" to 215,
        ),
        // 审批流全部分支（本次 / 本会话 / 永久）在一个函数里。无拆分计划。
        "app/src/main/java/dev/kair/app/ui/plugins/builtin/ApprovalPlugin.kt" to mapOf(
            "activate" to 130,
        ),
        // 消息 → 原生 contents 转换。无拆分计划。
        "app/src/main/java/dev/kair/app/core/ai/GeminiProvider.kt" to mapOf(
            "buildContents" to 108,
        ),
    )

    private val composableOversized: Map<String, Map<String, Int>> = mapOf(
        // 整屏一个函数：顶栏 / 消息列表 / 输入区 / 浮层全在 `ChatScreen` 里。无拆分计划。
        // 2026-10-31 C1 由 549 上调到 557（+8）：退后台清焦点 + 点空白清焦点。
        // 2026-11-01 U22 由 557 收到 496（-61）：输入区整段（图片预览 / 输入框 / 提交键 /
        // 轻点回底部的交互源）搬进 `Composer.kt` 的 `ComposerInputBar`，这里只留调用点。
        // **只跟着实测往下走。**
        // 2026-11-01 U22 B 审 §B4.4 P2 由 496 调到 501（+5，撤回一帧竞态守卫，理由见
        // SourceSizeGuardTest 同文件条目）。
        // 2026-11-01 U22 B 审 §B4.4 P2 由 496 调到 501（+5，撤回一帧竞态守卫，理由见
        // SourceSizeGuardTest 同文件条目）。
        // 2026-11-01 插话挪进排队区由 501 收到 **498**（-3）：`submit` 的 mode 分支删掉、
        // 改道实现收进 `Composer.promoteToSteer`，`ChatScreen` 这边只剩一行调用点。
        // 2026-11-05 由 498 收到 **491**（-7）：↓ 按钮连同悬浮配色 / 阴影抽到
        // `AutoScroll.kt` 的 `JumpToBottomButton`，同文件条目见 SourceSizeGuardTest。
        // 2026-11-06 U52 由 491 上调到 **507**（+16）：会话解析 / 打开从三处 `remember`
        // 改成一个 `produceState` + 加载态分支（Loading / Missing / Ready）。新增的是
        // 「加载中不能闪未找到」的分支与说明，逻辑仍是单一屏幕；无拆分计划。
        // 2026-11-09 U66 由 507 上调到 **509**（+2，实测）：瞬态提示条从 Scaffold 的屏幕底部
        // 宿主挪到消息区顶部，否则弹出时压住「停止 / 发送」那一排。规矩本身见
        // docs/界面规范.md §3。仍无拆分计划。
        // 2026-11-24 由 509 上调到 **517**（+8，实测）：删除语义改为「从这条到末尾」——
        // 撤回点状态 + 一处 `DeleteTailHost` 接线；确认框 / 撤销窗本体已拆到 `DeleteTail.kt`。
        // 2026-11-25 由 517 上调到 **525**（+8，实测）：展开体行级虚拟化的接线 —— 展开态
        // `rememberFoldExpansion()`、`buildRows` 与 `FeedRowItem` 各一处；行模型 / 行渲染 /
        // 分段描边全在新文件 `FeedRows.kt`（340 行，不进白名单），本屏只是装配。
        "app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt" to mapOf(
            "ChatScreen" to 525,
        ),
        // 插件页：筛选 / 详情 / 卸载确认同屏。无拆分计划。
        "app/src/main/java/dev/kair/app/ui/screens/PluginsScreen.kt" to mapOf(
            "PluginsScreen" to 495,
        ),
        // 模型编辑器：表单 + 校验 + 对话框。无拆分计划。
        "app/src/main/java/dev/kair/app/ui/screens/settings/ModelEditorScreen.kt" to mapOf(
            "ModelEditorScreen" to 325,
        ),
        // 「工作区文件」面板的文件列表项渲染 + 菜单。无拆分计划。
        // 2026-11-05 U38 随文件搬家改登记路径：`FileList` 与对话框、行组件一起从
        // `WorkspaceFilesPlugin.kt` 抽到 `WorkspaceFilesUi.kt`（回收站面板共用同一份），
        // 摘掉那颗「跳转回收站」按钮后实测 262 行（原登记 264）。条目数不变，只换路径。
        "app/src/main/java/dev/kair/app/ui/plugins/builtin/WorkspaceFilesUi.kt" to mapOf(
            "FileList" to 262,
        ),
        // 单条消息的多类型渲染分支。无拆分计划。
        // 2026-10-31 U1 由 244 收到 243（-1）：「⋯」移出 Column 改角标叠加，
        // 省掉的 3 行结构换回 2 行注释。**登记值只跟着实测往下走。**
        // 2026-11-01 U24 追加需求由 243 收到 **182**（-61）：用户气泡整段抽成 `UserBubbleCard`
        // （83 行，低于 Composable 阈值不进白名单），`MessageCard` 只剩分支派发。
        "app/src/main/java/dev/kair/app/ui/screens/chat/MessageList.kt" to mapOf(
            "MessageCard" to 182,
        ),
    )

    @Test
    fun `no new method exceeds the limit`() {
        // 判据 1：新的超限方法必须被看见（所属分类的白名单里没有它）。
        val offenders = functions.filter { it.function.lineCount > limitFor(it.function) && !isWhitelisted(it) }
        assertTrue(
            buildString {
                appendLine("以下方法超过长度阈值且未登记在白名单里（P5-2）：")
                offenders.forEach { (path, f) ->
                    appendLine("  $path:${f.startLine}  ${f.name} = ${f.lineCount} 行（阈值 ${limitFor(f)}）")
                }
                appendLine()
                appendLine("正确做法是**拆**，不是加白名单；确实要豁免时在 MethodSizeGuardTest 的")
                append("plainOversized / composableOversized 写清「为什么超阈值 / 何时拆」，并在 PR 里单列一条。")
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `whitelisted methods do not grow`() {
        // 判据 2：棘轮只准往一个方向转 —— 登记值只能被改小。
        val grown = oversizedTables().flatMap { (isComposable, table) ->
            table.flatMap { (path, methods) ->
                methods.mapNotNull { (name, allowed) ->
                    val actual = functionsWith(path, name, isComposable).maxOfOrNull { it.function.lineCount }
                        ?: return@mapNotNull null // 找不到由判据 3 负责
                    if (actual > allowed) MethodGrowth(path, name, allowed, actual) else null
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("白名单不是「超更多的豁免」（P5-2 棘轮）：")
                grown.forEach { (path, name, allowed, actual) ->
                    appendLine("  $path:$name 实际 $actual 行，比登记的 $allowed 行多了 ${actual - allowed} 行")
                }
                appendLine()
                append("往下走请顺手把登记的数字改小 —— 棘轮只准往回转，不然下一个人可以免费加回来。")
            },
            grown.isEmpty(),
        )
    }

    @Test
    fun `whitelist entries still exist`() {
        // 判据 3：防「改名 / 删方法后白名单静默失效」—— 登记了却找不到同样要红。
        // **分类不符也算找不到**：@Composable 判定一旦写错，这里会立刻炸出来（变异 D）。
        val missing = oversizedTables().flatMap { (isComposable, table) ->
            table.flatMap { (path, methods) ->
                methods.keys
                    .filter { name -> functionsWith(path, name, isComposable).isEmpty() }
                    .map { path to it }
            }
        }
        assertTrue(
            buildString {
                appendLine("白名单里有登记、源码里却找不到对应方法（P5-2）：")
                missing.forEach { (path, name) -> appendLine("  $path:$name") }
                appendLine()
                appendLine("匹配条件是「文件 + 方法名 + 是否 @Composable」三项全等：")
                append("若是方法改名 / 拆分后已达标，请把条目删掉；若是分类变了，请核对注解后调整条目；若是扫描路径写错，先修扫描。")
            },
            missing.isEmpty(),
        )
    }

    @Test
    fun `scanned enough functions`() {
        // 判据 4：防「扫描路径写错、一个都没扫到，于是全绿」。
        // 门卫口径的基数是 1201（裸 token 数，含无体的接口 / 抽象声明）；本扫描器只数**有体**的函数，
        // 实测 1127。下限取 1000 仍留 127 余量，足以在扫描失效（归零）时炸，又扛得住正常的增删。
        assertTrue(
            "只扫到 ${functions.size} 个函数（预期至少 $MIN_FUNCTIONS）——扫描路径或解析器坏了，" +
                "不等于主源码没有超限方法。",
            functions.size >= MIN_FUNCTIONS,
        )
    }

    @Test
    fun `whitelist count matches docs`() {
        // 判据 5：有人加豁免或整类文件消失都要被看见；改这里要同步改 团队分工 §2.3 第 2 行。
        val entries = plainOversized.values.sumOf { it.size } + composableOversized.values.sumOf { it.size }
        assertTrue(
            "白名单当前登记 $entries 个方法（普通 ${plainOversized.values.sumOf { it.size }} + Composable " +
                "${composableOversized.values.sumOf { it.size }}），docs/团队分工与交付流程.md §2.3 记的是" +
                "$EXPECTED_WHITELIST 个；改完这一处请同步改那一处。",
            entries == EXPECTED_WHITELIST,
        )
    }

    // ------------------------------------------------------------------ 扫描与工具

    private data class LocatedFunction(val path: String, val function: ScannedFunction)

    private data class MethodGrowth(val path: String, val name: String, val allowed: Int, val actual: Int)

    private fun oversizedTables(): List<Pair<Boolean, Map<String, Map<String, Int>>>> = listOf(
        false to plainOversized,
        true to composableOversized,
    )

    private fun functionsWith(path: String, name: String, isComposable: Boolean): List<LocatedFunction> =
        functions.filter {
            it.path == path && it.function.name == name && it.function.isComposable == isComposable
        }

    /** 懒加载：五条判据共用一次扫描结果，避免各扫一遍造成口径漂移。 */
    private val functions: List<LocatedFunction> by lazy {
        mainRoots().flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.absolutePath }
                .flatMap { file ->
                    val path = repoRelative(file)
                    KotlinSourceScanner.scan(file).map { LocatedFunction(path, it) }
                }
                .toList()
        }
    }

    private fun limitFor(function: ScannedFunction): Int =
        if (function.isComposable) COMPOSABLE_LIMIT else LIMIT

    private fun isWhitelisted(located: LocatedFunction): Boolean {
        val table = if (located.function.isComposable) composableOversized else plainOversized
        if (table[located.path]?.containsKey(located.function.name) != true) return false
        // 白名单键是「文件 + 方法名」，一个键只允许覆盖 1 个超限方法。
        // 同名重载若也超限，第 2 个起不豁免 —— 否则它会被这个键静默带走（判据 2 取 max 也拦不住）。
        return functionsWith(located.path, located.function.name, located.function.isComposable)
            .count { it.function.lineCount > limitFor(it.function) } <= 1
    }

    private fun mainRoots(): List<File> = listOf(
        requireDir(File(repo, "app/src/main"), "app 主源码"),
        requireDir(File(repo, "plugin-api/src/main"), "plugin-api 主源码"),
    )

    private fun requireDir(file: File, what: String): File {
        assertTrue("$what 不存在（$file）—— 方法长度守卫无法运行", file.isDirectory)
        return file
    }

    private fun repoRelative(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    private val repo: File by lazy { findRepoRoot() }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），方法长度守卫无法运行")
    }

    private companion object {
        const val LIMIT = 100
        const val COMPOSABLE_LIMIT = 200
        const val MIN_FUNCTIONS = 1000
        const val EXPECTED_WHITELIST = 12
    }
}
