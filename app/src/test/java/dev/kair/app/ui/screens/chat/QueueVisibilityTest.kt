package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.agent.QueueKind
import dev.kair.app.core.agent.QueuedMessage
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「引导中」面板只列用户自己的排队消息（U18）：系统通知是 agent 的投递、按步并入历史，
 * 挂在引导列表里只会让人以为通知卡住了（真机上报的原话：「挂了三条在用户引导消息那里」）。
 */
class QueueVisibilityTest {

    private fun queued(text: String, systemNote: Boolean, kind: QueueKind = QueueKind.STEER) =
        QueuedMessage(
            ChatMessage(role = Role.USER, parts = listOf(Part.Text(text)), isSystemNote = systemNote),
            kind,
        )

    @Test
    fun systemNotesNeverAppearInTheGuidingPanel() {
        val user = queued("续问", systemNote = false, kind = QueueKind.FOLLOW_UP)
        val note = queued("[background task t1: done]", systemNote = true)

        assertEquals(listOf(user), visibleQueue(listOf(note, user)))
        assertEquals(emptyList<QueuedMessage>(), visibleQueue(listOf(note)))
    }

    /**
     * 接线守卫（与 `SystemNoteUiGuardTest` 同一手法）：上面那条只证明 `visibleQueue`
     * **自己**对，证不动 `QueueSection` 有没有**用它** —— 把它改回 `queue.forEach`
     * 是全绿的（变异 M3 实测：只有本守卫会红）。按函数体扫源码，不靠渲染。
     */
    @Test
    fun queueSectionRendersTheFilteredQueueNotTheRawOne() {
        val sourceFile = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/Composer.kt")
        val source = sourceFile.readText(Charsets.UTF_8)
        val section = KotlinSourceScanner.scan(source).singleOrNull { it.name == "QueueSection" }
            ?: error("Composer.kt 里找不到 QueueSection —— 守卫扫描落空，不等于通过")

        val body = KotlinSourceScanner.stripped(source).lines()
            .subList(section.startLine - 1, section.endLine)
            .joinToString("\n")
        assertTrue(
            "QueueSection 必须先过 visibleQueue 再渲染 / 计数；直接用 queue 会把 agent 的" +
                "系统通知挂到「引导中」面板上（真机 U18 上报的原样）。当前函数体：\n$body",
            Regex("""visibleQueue\(\s*queue\s*\)""").containsMatchIn(body) &&
                Regex("""stringResource\(R\.string\.chat_queue_title,\s*visible\.size""").containsMatchIn(body) &&
                Regex("""visible\.forEach""").containsMatchIn(body),
        )
        assertTrue(
            "提示文案必须按 q.kind 选（FOLLOW_UP 用 chat_queue_hint_follow_up）—— " +
                "老提示对 FOLLOW_UP 是错的措辞，用户读成「消息发不出去」。当前函数体：\n$body",
            Regex("""q\.kind == QueueKind\.STEER""").containsMatchIn(body) &&
                body.contains("chat_queue_hint_follow_up"),
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
