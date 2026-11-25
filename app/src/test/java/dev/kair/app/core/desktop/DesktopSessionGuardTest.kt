package dev.kair.app.core.desktop

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.proot.ContainerSessionHost
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 判据同源守卫（真机 2026-11-14 反馈的「桌面启动失败：container session is not running」）。
 *
 * 根因是**同一个前置条件被两处各判一套**：面板只判 `session == null`，
 * `DesktopRunner` 判 `session.state !is Running`。会话对象存在、但停在 `Stopped`
 * 时 → 面板照常摆出可点的「启动桌面」→ 点下去才被 runner 拒 → 英文原文泄到界面。
 *
 * 修法是把真值收成一处 `ContainerSessionHost.sessionReady`，面板可点性与 runner
 * 守卫都读它。本守卫钉三件事：
 * 1. 那两句英文不得作为字面量留在主源码里（错误文案必须走资源通道，U37）；
 * 2. 桌面面板只读 `sessionReady`，不得回退到只判 `session` 是否为空；
 * 3. 真值本身存在且是 `StateFlow`（反射钉形状，防止被换成一次性布尔）。
 *
 * 为什么不只写行为用例：`ContainerSession` 要真 proot/frame 通道才构造得出来，
 * JVM 上跑不起；而这条 bug 的形态是**引用面**问题，源码级判据反而精确。
 */
class DesktopSessionGuardTest {

    @Test
    fun `英文 not-running 文案不得出现在主源码里`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
            if (code.contains(LEGACY_MESSAGE)) {
                offenders += "${repoRelative(file)}: $LEGACY_MESSAGE"
            }
        }
        assertTrue(
            buildString {
                appendLine("容器会话未就绪时不得把英文技术串渲给用户（U37：显示文本走资源通道）。")
                appendLine("正确做法是置 DesktopState.NoSession，由面板显示 R.string.desktop_no_session。")
                appendLine("命中：")
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `桌面面板的可点性读 sessionReady 而不是只判 session 是否为空`() {
        val file = File(repo, "app/src/main/java/dev/kair/app/ui/plugins/builtin/DesktopPanel.kt")
        val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))

        assertTrue(
            "DesktopPanel 必须用 container.containerSessionHost.sessionReady 判「能否启动」：" +
                "只判 session 是否为空会在会话 Stopped 时照样画出可点的「启动桌面」。",
            code.contains("containerSessionHost.sessionReady"),
        )
        assertTrue(
            "DesktopPanel 不得回退到 collectAsState 整个 session 再判 null —— 那就是这条 bug 的原形。",
            !code.contains("containerSessionHost.session.collectAsState"),
        )
    }

    @Test
    fun `sessionReady 是 ContainerSessionHost 上的 StateFlow 真值`() {
        val field = ContainerSessionHost::class.java.declaredFields.firstOrNull { it.name == "sessionReady" }
        assertTrue(
            "ContainerSessionHost 必须有 sessionReady 字段（面板与 runner 共用的唯一真值），" +
                "实际字段：${ContainerSessionHost::class.java.declaredFields.map { it.name }}",
            field != null,
        )
        assertTrue(
            "sessionReady 必须是 StateFlow（要跟着会话状态变化重组面板），实际类型：${field!!.type.name}",
            field.type.name.endsWith("StateFlow"),
        )
    }

    private fun mainSources(): List<File> =
        File(repo, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repoRelative(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    private val repo: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），会话判据守卫无法运行")
    }

    private companion object {
        /** 只有 `DesktopRunner` 曾经硬编码过的那两句；出现在别处同样是问题。 */
        const val LEGACY_MESSAGE = "container session is not running"
    }
}
