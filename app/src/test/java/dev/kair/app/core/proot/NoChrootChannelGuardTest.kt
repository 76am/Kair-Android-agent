package dev.kair.app.core.proot

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U78 守卫：chroot 会话通道已下线，主源码里不得再出现「通道」形态的它。
 *
 * 为什么按源码守：`SessionChannel.CHROOT` 与 `MODE_CHROOT` 是两个用于路由的**标识符**，
 * 删掉分支后若有人（或一次合并）把它们带回来，所有行为用例都可能照样绿 —— 因为
 * `ContainerRunner` 只剩 proot 档时，多出来的枚举值 / 常量没有任何调用点。
 * 本守卫按 [KotlinSourceScanner.stripped] 剥注释后扫描，历史注释里的 chroot 不算违规。
 *
 * 只禁这两个**路由标识符**，不禁 `RootCommandRunner` 内部的 chroot 维护脚本：
 * ADR 0007 明确保留它做挂载 bind / 权限迁移 / 显式提权（不经会话通道）。
 */
class NoChrootChannelGuardTest {

    @Test
    fun `main sources do not resurrect the chroot session channel`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
            for (token in FORBIDDEN) {
                if (code.contains(token)) offenders += "${repoRelative(file)}: $token"
            }
        }
        assertTrue(
            buildString {
                appendLine("chroot 会话通道已由 U78 删除（ADR 0007），主源码里不得再出现这些标识符：")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                append("要恢复通道请先改 ADR 与 docs/架构与内核边界.md §9.6，再删本守卫 —— 不许悄悄带回来。")
            },
            offenders.isEmpty(),
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
        error("找不到仓库根（须含 docs/ 与 app/），chroot 通道守卫无法运行")
    }

    /**
     * U78 实测漏网的一条（2026-11-12，负责人上机发现）：路由与字符串都删干净了，
     * 但 `SessionCardBody` 在 Running 状态下仍渲染一行「执行通道：proot」。
     *
     * 这类残留负向断言抓不到 —— 它不含任何路由标识符，只是把 `activeChannel` 显示出来。
     * 而 ADR 0007 要求「面板无通道选择器」：显示一个恒为 proot 的只读标签，用户会以为
     * 还能切。所以连**展示层的引用面**一起钉住。
     */
    @Test
    fun `session panel does not render a channel label`() {
        val offenders = mutableListOf<String>()
        for (file in mainSources()) {
            val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
            for (token in FORBIDDEN_UI) {
                if (code.contains(token)) offenders += "${repoRelative(file)}: $token"
            }
        }
        assertTrue(
            buildString {
                appendLine("U78 起会话只有 proot 一档，面板不得再显示「执行通道」（ADR 0007：面板无通道选择器）。")
                appendLine("显示一个恒为 proot 的只读标签会让用户以为还能切换。命中的引用：")
                offenders.forEach { appendLine("  $it") }
            },
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** 路由标识符：带回来就等于通道复活。 */
        val FORBIDDEN = listOf("MODE_CHROOT", "SessionChannel.CHROOT")

        /** 展示层引用面：路由删了它也可能漏网（U78 实测就这么漏的）。 */
        val FORBIDDEN_UI = listOf("session_channel")
    }
}
