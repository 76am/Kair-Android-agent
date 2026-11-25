package dev.kair.app.app

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话「模型取法」的形状守卫（U31 ③-b 终审补）。
 *
 * 守的是**时机**不是结果：宿主会话要「每回合现取」（用户中途换模型，下一发请求就该换端点），
 * 子智能体要「创建时解析一次并固定」（短命会话，中途改设置不该把它的鉴权与窗口半路换掉）。
 * 这条差别是本批把两处 `AgentSession` 收成一处 `agentSession(spec)` 之后**唯一剩下的语义差异**，
 * 全部由 [SessionSpec] 的显式字段承载，没有类型判断 —— 所以它必须由守卫钉住，不能靠注释自觉。
 *
 * **为什么先按文本守**（初版这里写的是「行为侧没有那条路」，独立复核指出这话说过头了，改成下面这样）：
 * `AgentSession.configProvider` / `assemblyProvider` 确实是 private，没有出口；但
 * `AgentSession.contextWindow()` 是公开的，理论上能走「JVM 里建容器 → `openSession` → 改设置 →
 * 读 `contextWindow()`」这条路（`AppContainerWiringTest` 证明容器能在 JVM 建起来）。
 * 那条行为探针更贵也更脆（要摆出两个 contextWindow 不同的模型、要控住 lazy 初始化时序），
 * 本批先用形状守卫把「两处不同形」钉死，行为探针作为后续一笔（登记在交接 §6.3 的账里）。
 *
 * 实测（终审记录）：把宿主的 `modelProvider` 改成「先在 lambda 外解一次、再把结果传进去」，
 * 补守卫**之前**全量 133 类 / 1019 条一条都没红（XML `14:23:37.441Z`）；补守卫之后同一变异只剩这里红
 * （XML `14:30:41.644Z`，红在旧版 `:37`）。 tightening 之后的两轮变异（宿主改成 `sessionModel(null)`、
 * 子智能体改成每次现取）与本轮时戳、红行都记在交接 §6.3 —— 别在这里写没跑过的数。
 * 扫的是 `KotlinSourceScanner.stripped`：写进注释的形态既不能把守卫带绿，也不能把它带红。
 */
class SessionProviderShapeTest {

    /** 编排对象：两处 `SessionSpec` 的家。 */
    private val lifecycleFile = "app/src/main/java/dev/kair/app/app/SessionLifecycle.kt"

    @Test
    fun `host session resolves the model per turn while subagent freezes it at creation`() {
        val code = strippedRepo(lifecycleFile)

        // 按**函数体**取段（不数全文件）：终审初版用全文件计数，独立复核指出「把两行的实参互换
        // 仍然 1/1/2 全绿」，所以每条断言只看自己那个成员。切片方式与 SessionScopeTest:104 同形。
        val host = body(code, "fun sessionFor(", "fun releaseSession(")
        val subagent = body(code, "fun createSubAgentSession(", "fun activeSessionWorkspace(")

        // 1. 宿主：现取，而且必须**按会话 id** 现取（`sessionModel(null)` 时机对、作用域错 —— 会丢掉会话级模型覆盖）。
        assertTrue(
            "宿主会话的 modelProvider 必须是「每次调用按本会话 id 现取」" +
                "（`modelProvider = { assembly.sessionModel(meta.id) }`）—— sessionFor 里没匹配到。" +
                "改成先在 lambda 外解一次再把结果传进去，就等于让宿主也「创建时固定」：用户中途换模型，" +
                "当前会话的下一发请求仍打旧端点（鉴权与窗口跟着旧模型），而这条差别当时没有任何用例守着" +
                "（终审实测：全量 1019 条 0 红）。改成 sessionModel(null) / sessionModel(project.id) 也不接受：" +
                "那会静默丢掉会话级模型覆盖。解析函数或参数改名时请同步本守卫。",
            Regex("""modelProvider\s*=\s*\{\s*assembly\.sessionModel\(\s*meta\.id\s*\)""").containsMatchIn(host),
        )

        // 2. 子智能体：固定 —— 用的是创建时解好的那个值。
        assertTrue(
            "子智能体的 modelProvider 必须是「创建时解析一次并固定」（`modelProvider = { model }`）—— " +
                "createSubAgentSession 里没匹配到。改成每次现取，就是让一个正在跑的子智能体中途换端点" +
                "（同一个会话前后两发请求打到不同 provider）。改名时请同步本守卫。",
            Regex("""modelProvider\s*=\s*\{\s*model\s*\}""").containsMatchIn(subagent),
        )

        // 3. 两处不许同形：宿主段里不该出现 `{ model }`，子智能体段里不该出现现取调用。
        assertTrue(
            "子智能体那段不该出现 sessionModel( —— 它必须用创建时算好的 model（出现即说明两端同形了）",
            !Regex("""sessionModel\(""").containsMatchIn(subagent),
        )
        assertTrue(
            "宿主那段不该出现「创建时算好的 model」—— 它必须每回合现取",
            !Regex("""modelProvider\s*=\s*\{\s*model\s*\}""").containsMatchIn(host),
        )
    }

    @Test
    fun `request tool face has no second filter and subagent filter stays frozen at creation`() {
        val code = strippedRepo(lifecycleFile)
        val subagent = body(code, "fun createSubAgentSession(", "fun activeSessionWorkspace(")

        // U42 删掉了「创建时固定」的请求侧过滤器：请求 schema 与系统提示共用装配产出的同一份
        // 工具列表。「两次请求切模型」的行为判据在 RequestInputSnapshotTest（V1/V2），这里只钉形状：
        // 旧字段不得复活，子智能体的过滤器仍是创建时固定的那份。
        assertTrue(
            "requestToolFilter 已随 U42 删除 —— 复活它就是「提示现算 / schema 创建时固定」的错位回潮",
            !code.contains("requestToolFilter"),
        )
        val assembly = strippedRepo("app/src/main/java/dev/kair/app/app/SessionAssembly.kt")
        assertTrue(
            "SessionSpec 不得再长出请求侧的独立过滤器字段（schema 工具面只从装配产物派生）",
            !assembly.contains("requestToolFilter"),
        )
        assertTrue(
            "子智能体的 assemblyToolFilterProvider 必须是创建时固定的那份（`{ _ -> toolFilter }`）—— " +
                "改成现算就是让一个跑着的子智能体中途换工具面",
            Regex("""assemblyToolFilterProvider\s*=\s*\{\s*_\s*->\s*toolFilter\s*\}""").containsMatchIn(subagent),
        )
    }

    /**
     * 「一处装配」这条收口**全仓**核一次。`ImageScalingGuardTest:98` 之后只扫 `SessionAssembly.kt`，
     * 所以在别的文件新起一处 `AgentSession(` 构造不会惊动它 —— 由这条补上（独立复核指出该盲区）。
     * 负向断言用 `(?<![\w.])` 排除 `createSubAgentSession(` 这种同后缀的函数名，再减掉类声明本身。
     */
    @Test
    fun `exactly one AgentSession construction site remains in main sources`() {
        val root = findRepoRoot().resolve("app/src/main/java")
        val ctor = Regex("""(?<![\w.])AgentSession\(""")
        val decl = Regex("""class\s+AgentSession\(""")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val built = files.sumOf { file ->
            val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
            ctor.findAll(code).count() - decl.findAll(code).count()
        }
        assertEquals(
            "生产代码里 `AgentSession(` 构造点必须只剩一处（`SessionAssembly.agentSession`）—— 实际 $built 处。" +
                "多一处就多一套鉴权 / 窗口 / 过滤器的接线形状（③-b 收口的正是这件事）；" +
                "确实要新增装配点，先交代它跟 spec 的差异靠哪个字段承载，别加 if (子智能体)。",
            1,
            built,
        )
    }

    /**
     * 「单发单解析」这一格没有行为探针可走（B 审实测，见下面两句失败消息）：一次解析之内的第二次
     * 解模型、装配快照之后的第二次 `host.inputs()`，都发生在请求发出之前 —— 四条
     * `RequestInputSnapshotTest`（V1 / V2 / V4 / V6）对此全绿。所以这一格只能按文本钉。
     * 扫的是 `stripped()`：把重复调用写进注释既骗不过去，也带不红。
     */
    @Test
    fun `the request input is parsed exactly once per request`() {
        val assembly = strippedRepo("app/src/main/java/dev/kair/app/app/SessionAssembly.kt")
        val entry = body(assembly, "internal fun agentSession(", "internal fun compactionSettings(")
        val parsed = Regex("""spec\.modelProvider\(\)""").findAll(entry).count()
        assertEquals(
            "`agentSession` 的 inputsProvider 里 `spec.modelProvider()` 必须恰好出现一次（实际 $parsed 次）—— " +
                "U42 的「单发单解析」就落在这一个调用点上：鉴权 / 工具面 / 窗口 / 采样全取它的结果。" +
                "出现第二次就是让同一发请求的五个输入各自解模型（U31 ③-a 记的「提示用 A、鉴权走 B、窗口按 C」）。" +
                "解析入口改名时请同步本守卫。",
            1,
            parsed,
        )

        val executor = strippedRepo("app/src/main/java/dev/kair/app/core/agent/TurnExecutor.kt")
        val stream = body(executor, "suspend fun streamAssistant(", "private fun hydrateAttachments(")
        val asked = Regex("""host\.inputs\(\)""").findAll(stream).count()
        assertEquals(
            "`streamAssistant` 里 `host.inputs()` 必须恰好调用一次（实际 $asked 次）—— 快照的消费契约是" +
                "「解析一次、之后只认这一份」。多调一次就多一条「请求装配期间又回读设置」的路径，" +
                "而它是行为用例看不见的：那一发请求早就装配完了。执行器改名时请同步本守卫。",
            1,
            asked,
        )
    }

    /** 取成员体的一段：从 [from] 到下一个声明 [until] 之间（与 SessionScopeTest:104 同形）。 */
    private fun body(code: String, from: String, until: String): String {
        val start = code.indexOf(from)
        assertTrue("找不到 $from —— 函数改名后本守卫失效，请同步更新", start >= 0)
        val rest = code.substring(start)
        val end = rest.indexOf(until, from.length)
        assertTrue("找不到 $until —— 成员顺序变了，本守卫的切片需要跟着改", end > 0)
        return rest.substring(0, end)
    }

    private fun strippedRepo(relativePath: String): String =
        KotlinSourceScanner.stripped(repoFile(relativePath).readText(Charsets.UTF_8))

    private fun repoFile(relativePath: String): File = findRepoRoot().resolve(relativePath)

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），形状守卫无法运行")
    }
}
