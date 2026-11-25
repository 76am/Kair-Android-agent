package dev.kair.app.app

import dev.kair.app.core.storage.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话作用域的形状与失效语义。
 *
 * `AppContainer` 需要 Android `Context`，JVM 单测起不来，所以判据落在纯函数 [scopeOf] 上 ——
 * 它就是 `activeScope()` 的实现。「作用域随会话关闭而失效」这条语义由它保证。
 */
class SessionScopeTest {

    private val meta = SessionMeta(id = "s-1", projectId = "p-1", name = "会话", model = "m")
    private val workspace = File("/tmp/kair/workspaces/p-1")

    @Test
    fun `no session means no scope`() {
        assertNull("没有打开任何会话时不得给出作用域（更不得回退 scratch）", scopeOf(null, workspace))
    }

    @Test
    fun `closed session means no scope even if a workspace is known`() {
        // 会话关闭 / 被淘汰后注册表里没有它 → openWorkspace 为 null。
        // 只按元信息构造的实现会在这里给出作用域：一个已关闭的会话继续以「当前」的名义派活。
        assertNull("会话已关闭时作用域必须失效", scopeOf(meta, null))
    }

    @Test
    fun `scope carries session, project and the open workspace`() {
        assertEquals(SessionScope("s-1", "p-1", workspace), scopeOf(meta, workspace))
    }

    /**
     * A 段评审 P2-3：作用域必须整体来自**同一次**「当前会话」快照。
     *
     * 旧写法 `scopeOf(activeSessionMeta, activeSessionWorkspace())` 的两个参数各读一次当前会话，
     * 切换会话期间能拼出「A 的 sessionId/projectId + B 的工作区」。
     * 这里把「读当前会话」换成一个**每次调用都返回不同值**的函数：只要实现读两次，
     * 三元组就会混搭、或读取次数变成 2 —— 两种都会红。
     */
    @Test
    fun `scope is a single snapshot even if the active session flips mid-flight`() {
        val a = SessionMeta(id = "s-a", projectId = "p-a", name = "A", model = "m")
        val b = SessionMeta(id = "s-b", projectId = "p-b", name = "B", model = "m")
        val wsA = File("/tmp/kair/workspaces/p-a")
        val wsB = File("/tmp/kair/workspaces/p-b")
        var reads = 0
        val scope = resolveScope(
            meta = { reads++; if (reads == 1) a else b },
            workspaceOf = { id -> if (id == "s-a") wsA else wsB },
        )
        assertEquals("作用域必须整体来自同一次快照（不得拼搭）", SessionScope("s-a", "p-a", wsA), scope)
        assertEquals("当前会话只允许读一次，实际读了 $reads 次", 1, reads)
    }

    // ------------------------------------------------ 插件侧两处不得再读全局工作区

    /**
     * step3 的回归守卫：文件浏览 / devtools 的 cwd 必须来自显式作用域。
     * 回退 `container.activeWorkspaceDir` 时，无会话就等于静默拿 scratch / 第一个项目 ——
     * 正是 P3-3 要消灭的隐式全局。
     *
     * 这两处都是 `@Composable`，JVM 单测渲染不了，故与 `UiTextGuardTest` 同样走源码扫描。
     * **判据必须是「调用」而不是「词」**：早先写的是 `"activeScope" !in source`，
     * 那一行 `import dev.kair.app.app.activeScope` 就满足了，代码改回全局也照样绿。
     * 同理 `container.activeWorkspaceDir` 要带右边界，否则 `activeWorkspaceDirXxx` 也算命中。
     */
    @Test
    fun `plugin panels take the workspace from scope, not from the global`() {
        val dir = findRepoRoot().resolve("app/src/main/java/dev/kair/app/ui/plugins/builtin")
        val callsScope = Regex("""\.activeScope\(\)""")
        val readsGlobal = Regex("""\.activeWorkspaceDir\b""")
        val offenders = listOf("WorkspaceFilesPlugin.kt", "ProotPlugin.kt").filter { name ->
            val source = dir.resolve(name).also { assertTrue("待检文件不存在：$it", it.isFile) }.readText(Charsets.UTF_8)
            readsGlobal.containsMatchIn(source) || !callsScope.containsMatchIn(source)
        }
        assertTrue(
            "这些面板的 cwd 未走显式作用域（须真正调用 .activeScope()，且不得读 .activeWorkspaceDir）：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 顺序守卫：`openSession` 必须**先把会话放进注册表，再发「首个会话已打开」的信号**。
     *
     * 自动启动的常驻容器在 `Dispatchers.Default` 上等这个信号，被唤醒后立刻读 `activeScope()`，
     * 而它查的是注册表（`SessionRegistry.open` = `getOrPut`，**先构造整个 AgentSession 再插入**）。
     * 信号早发一步，容器就读到 null 作用域、把会话置 Error —— 且这个信号只发一次、**不重试**，
     * 表现是「Linux 会话偶发起不来，也没有提示」。
     *
     * 这类时序缺陷用 JVM 用例测不出来（要 `Context`、要真并发），只能钉住顺序：
     * 谁把它改回去，这里就红。判据是源码里的**相对位置**，不是行号（行号会漂）。
     */
    @Test
    fun `open session signals readiness only after the session is registered`() {
        val source = findRepoRoot()
            .resolve("app/src/main/java/dev/kair/app/app/SessionLifecycle.kt")
            .readText(Charsets.UTF_8)
        val body = source.substringAfter("fun openSession(", "").substringBefore("fun sessionFor(", "")
        assertTrue("找不到 openSession 的函数体 —— 函数改名后本守卫失效，请同步更新", body.isNotEmpty())
        val registered = body.indexOf("sessionFor(project, meta)")
        val signaled = body.indexOf("firstSessionOpened.complete")
        assertTrue(
            "openSession 里找不到 sessionFor / firstSessionOpened，守卫需要更新：registered=$registered signaled=$signaled",
            registered >= 0 && signaled >= 0,
        )
        assertTrue(
            "firstSessionOpened 必须在 sessionFor(...) 之后发出：会话还没进注册表时被唤醒的常驻容器" +
                "会读到 null 作用域并永久失败（信号只发一次，不重试）",
            signaled > registered,
        )
    }

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），作用域守卫无法运行")
    }
}
