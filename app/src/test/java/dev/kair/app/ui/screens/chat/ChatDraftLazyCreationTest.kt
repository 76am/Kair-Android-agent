package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「草稿不落库」的接线守卫（U110）。
 *
 * 行为本身要真机点（空会话 → 发首条消息 → 抽屉出现一条会话）；这里只钉住两条
 * 结构判据，防止退回旧路径：
 * 1. 建档只能发生在草稿页的发送动作里，ChatScreen（真会话页）不许再顺手建档；
 * 2. 删除当前会话的落点是空会话界面，不再挑「同项目最近一条」。
 */
class ChatDraftLazyCreationTest {

    @Test
    fun `only the draft screen creates a session`() {
        val chat = strippedSource("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt")
        assertFalse(
            "ChatScreen 不能再调用 container.newSession —— 草稿进页即建档会让抽屉凭空出现「新会话」。",
            chat.contains("container.newSession("),
        )

        val draft = strippedSource("app/src/main/java/dev/kair/app/ui/screens/chat/ChatDraftScreen.kt")
        assertTrue(
            "草稿页必须在发送动作里建档（container.newSession）。",
            draft.contains("container.newSession("),
        )
        assertTrue(
            "建档后必须把真 id 交给宿主换路由，否则页面停在草稿上、删除判定又会错位。",
            draft.contains("onSessionCreated(meta.id)"),
        )
    }

    @Test
    fun `deleting the current session lands on an empty draft`() {
        val nav = strippedSource("app/src/main/java/dev/kair/app/ui/KairNav.kt")
        val body = nav
            .substringAfter("private fun leaveDeletedSession")
            .substringBefore("\n}")

        assertTrue(
            "删除当前会话后必须换回草稿路由（空会话界面）。",
            body.contains("Routes.chatDraft(project.id)"),
        )
        assertFalse(
            "不要在选择落点时再读会话列表挑「最近一条」—— 删除即清场，落到空会话。",
            body.contains("listSessions"),
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
