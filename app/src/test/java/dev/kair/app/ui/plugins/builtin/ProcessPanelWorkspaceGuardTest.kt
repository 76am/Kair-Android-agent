package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 进程面板「工作区只取显式作用域、拿不到就不操作」的源码守卫（B 审 P2）。
 *
 * 为什么是源码守卫而不是行为用例：这条不变量的全部内容就是「工作区从哪来、缺了走不走
 * fail-closed」——把 `liveWorkspace` 改回 `File(appContext.cacheDir, "process-probe")`
 * 兜底，单测仍全绿（面板用例不起真容器），而真机上这条一次性命令会把 App 私有目录 bind
 * 到容器 `/projects`，**覆盖整棵项目树**（2026-11-02 真机取证，本分支修的就是它）。
 * 变异实证：恢复 cacheDir 回退后目标类测试 0 红 —— 这条不变量当时只靠注释钉着。
 *
 * 结构类断言扫 `KotlinSourceScanner.stripped()`（剥注释后的源码）：`cacheDir` 允许留在
 * 注释里当历史教训，不许出现在代码里。**守卫扫描落空，不等于通过。**
 */
class ProcessPanelWorkspaceGuardTest {

    private val panelFile = "app/src/main/java/dev/kair/app/ui/plugins/builtin/ProcessPanel.kt"

    private fun panelSource(): String = repoFile(panelFile).readText(Charsets.UTF_8)

    private fun strippedPanel(): String = KotlinSourceScanner.stripped(panelSource())

    private fun funBody(name: String): String {
        val fn = KotlinSourceScanner.scan(panelSource()).singleOrNull { it.name == name }
            ?: error("$panelFile 里找不到 $name —— 守卫扫描落空，不等于通过")
        return KotlinSourceScanner.stripped(panelSource()).lines()
            .subList(fn.startLine - 1, fn.endLine)
            .joinToString("\n")
    }

    /** 面板代码里不得再出现 App 私有目录当工作区（注释里的历史教训不算）。 */
    @Test
    fun `the panel never falls back to the app private dir as the command workspace`() {
        val body = strippedPanel()
        assertTrue(
            "ProcessPanel 代码里不得再出现 cacheDir：一次性命令会把工作区父目录 bind 到 " +
                "容器 /projects，私有目录当工作区等于覆盖整棵项目树",
            !body.contains("cacheDir"),
        )
        assertTrue(
            "不得再构造 process-probe 临时目录",
            !body.contains("process-probe"),
        )
    }

    /** 工作区的唯一来源是 `activeScope()`；`liveWorkspace` 缺失时回显提示并返回 null。 */
    @Test
    fun `the workspace comes only from the explicit active scope`() {
        assertTrue(
            "组合函数里的工作区必须来自 activeScope：\n${strippedPanel()}",
            strippedPanel().contains("container.activeScope()?.workspace"),
        )
        val live = funBody("liveWorkspace")
        assertTrue(
            "liveWorkspace 必须只取 activeScope，缺失时调 onMissing 并返回 null（fail-closed）：\n$live",
            live.contains("container.activeScope()?.workspace") &&
                live.contains("onMissing()") &&
                Regex("""\?:\s*run\s*\{""").containsMatchIn(live),
        )
    }

    /** 三个动作点（刷新 / 结束单进程 / 全部结束）都在点击时重取，取不到直接返回。 */
    @Test
    fun `every action re-resolves the workspace at click time and fails closed`() {
        val body = strippedPanel()
        val callCount = Regex("""liveWorkspace\(container\)[^\n]*\?:\s*return@launch""").findAll(body).count()
        assertTrue(
            "refresh / kill / killAll 三处都必须点击时重取工作区、拿不到就 return，实际 $callCount 处",
            callCount == 3,
        )
        // UI 侧同样门控：无会话显示提示，按钮禁用判据里带着 workspace != null。
        assertTrue("无会话必须走 NoSessionHint 分支：\n$body", body.contains("NoSessionHint("))
        assertTrue(
            "操作按钮必须按 workspace != null 门控（至少刷新与全部结束两处）：\n$body",
            Regex("""workspace\s*!=\s*null""").findAll(body).count() >= 3,
        )
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
