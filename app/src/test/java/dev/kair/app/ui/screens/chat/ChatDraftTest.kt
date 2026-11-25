package dev.kair.app.ui.screens.chat

import androidx.compose.runtime.saveable.SaverScope
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话草稿（U54）的纯逻辑判据。
 *
 * 仓库没有 Compose 测试底座，「旋转后还在」没法用组合测试证明，所以把能 JVM 直测的部分
 * 全放在这里：Saver 真往返、按 id 现算、丢图判定。「ChatScreen 接上了 rememberChatDraft」
 * 由源码守卫负责，旋转 / 分屏只能真机目视（交接里如实登记）。
 */
class ChatDraftTest {

    @Test
    fun `the saver round-trips every draft slot`() {
        val original = ChatDraft().apply {
            input = "hello"
            editingId = "msg-1"
            selectingId = "msg-2"
            hadImage = true
        }

        val restored = roundTrip(original)

        assertEquals("hello", restored.input)
        assertEquals("msg-1", restored.editingId)
        assertEquals("msg-2", restored.selectingId)
        assertTrue(restored.hadImage)
    }

    @Test
    fun `a draft target that no longer exists resolves to null`() {
        val kept = ChatMessage(role = Role.USER, parts = listOf(Part.Text("hi")))

        assertNull(resolveDraftTarget(listOf(kept), "deleted-id"))
        assertSame(kept, resolveDraftTarget(listOf(kept), kept.id))
        assertNull(resolveDraftTarget(listOf(kept), null))
    }

    @Test
    fun `the lost-image prompt only fires for a restored draft that had an image`() {
        val restoredWithImage = roundTrip(ChatDraft().apply { hadImage = true })
        val restoredWithoutImage = roundTrip(ChatDraft())

        assertTrue(imageDraftLost(restoredWithImage.hadImage, null))
        assertFalse(imageDraftLost(restoredWithoutImage.hadImage, null))
        assertFalse(imageDraftLost(restoredWithImage.hadImage, Part.Image(base64 = "img")))
    }

    private fun roundTrip(draft: ChatDraft): ChatDraft {
        val saved = with(ChatDraftSaver) { SaverScope { true }.save(draft) }
            ?: error("Saver 拒绝保存草稿 —— 往返判据失效，不等于通过")
        return ChatDraftSaver.restore(saved) ?: error("Saver 无法恢复草稿 —— 往返判据失效，不等于通过")
    }
}
