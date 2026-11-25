package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SystemNoteCard 的日期本地化调用点守卫（系统提示 wire 批次 P3-1）。
 *
 * `dateNoteDisplayText` 的纯函数用例守不住「界面有没有调用它」：把调用改回 `msg.text`，
 * 那组用例仍全绿，只有真机才看得出英文星期。这里按函数区间扫源码钉住调用点。
 */
class SystemNoteUiGuardTest {

    @Test
    fun `system note card renders the localized date text`() {
        val sourceFile = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/MessageList.kt")
        val source = sourceFile.readText(Charsets.UTF_8)
        val card = KotlinSourceScanner.scan(source).singleOrNull { it.name == "SystemNoteCard" }
            ?: error("MessageList.kt 里找不到 SystemNoteCard —— 守卫扫描落空，不等于通过")

        // 必须扫**剥掉注释**的函数体：留一行含 `dateNoteDisplayText(msg.text)` 的注释、
        // 把真实调用改回 `msg.text`，原文扫描就全绿（同一弱点由 U18 增量复核实测在
        // QueueVisibilityTest 上抓到，见 docs/handoff/维护-后台通知投递时机.md §6.3-P3-3）。
        val body = KotlinSourceScanner.stripped(source).lines()
            .subList(card.startLine - 1, card.endLine)
            .joinToString("\n")
        assertTrue(
            "SystemNoteCard 必须把 dateNoteDisplayText(msg.text) 交给 Text；" +
                "直接用 msg.text 会把 wire 英文日期原样显示给用户。当前函数体：\n$body",
            Regex("""dateNoteDisplayText\(\s*msg\.text\s*\)""").containsMatchIn(body),
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
