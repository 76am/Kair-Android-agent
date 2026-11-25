package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话草稿的接线守卫（U54）—— **只防「忘了接」，不是行为证据**。
 *
 * 行为判据在 [ChatDraftTest]（Saver 往返 / 目标消息已删 / 丢图真值表）；
 * 「旋转后草稿还在」「选图后旋转会提示」只能真机目视（仓库无 Compose 测试底座）。
 *
 * 扫 [KotlinSourceScanner.stripped]（剥注释后的源码）：注释里提 rememberChatDraft 不算接线，
 * 旧形态写进注释也不会误红 —— 与既有接线守卫同口径。
 */
class ChatDraftWiringTest {

    @Test
    fun `chat screen takes its draft from rememberChatDraft`() {
        val source = strippedSource("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt")

        assertTrue(
            "ChatScreen 必须用 rememberChatDraft() 持有输入区状态，否则 Activity 重建后草稿照旧丢。",
            source.contains("rememberChatDraft()"),
        )
        listOf("var input by remember", "var editing by remember", "var selectingText by remember").forEach { old ->
            assertFalse(
                "旧的「各自 remember」还在（$old）：草稿必须是 ChatDraft 一个实现点。",
                source.contains(old),
            )
        }
        assertTrue(
            "编辑 / 选文必须按消息 id 现算（draft.editingId / draft.selectingId），" +
                "否则恢复后弹窗会挂在一个已经不存在的消息上。",
            source.contains("draft.editingId") && source.contains("draft.selectingId"),
        )
    }

    @Test
    fun `the lost-image notice is wired and its text comes from resources`() {
        val source = strippedSource("app/src/main/java/dev/kair/app/ui/screens/chat/ChatDraft.kt")
        val screen = strippedSource("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt")

        assertTrue(
            "ChatScreen 必须调用 ChatDraftRestoreEffects —— 残留 id 要清、丢图要提示一次，不能静默。",
            screen.contains("ChatDraftRestoreEffects("),
        )
        assertTrue(
            "丢图提示文案必须走 strings.xml（R.string.chat_draft_image_lost），新文件不许带中文字面量。",
            source.contains("R.string.chat_draft_image_lost"),
        )
    }

    private fun strippedSource(path: String): String =
        KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
