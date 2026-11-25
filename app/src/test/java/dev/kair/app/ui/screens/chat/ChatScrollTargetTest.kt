package dev.kair.app.ui.screens.chat

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D2 的机械判据：自动跟随的触发键必须在「末条不在可见项内」时仍能发信号。
 *
 * 触发键抽成纯函数（[followTargetOf]）而不是在 Compose 里直接算 —— LazyList 的真实布局
 * 在 JVM 单测里驱动不了，用假快照才能把「哪个字段在变、旧键为什么瞎」钉死。
 */
class ChatScrollTargetTest {

    @Test
    fun `末条不在可见项内时，可见区推进仍要发信号`() {
        // 10 条消息，视口只排到 index 7：末条（index 9）不在可见项里。
        // 旧键取「末条 size」，此刻恒为 null —— 之后布局再怎么推进都发不出信号，
        // 这正是「文字在涨、视口没跟上，停下才对齐」（D2）。
        val before = followTargetOf(
            count = 10,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(6, 600, 100), VisibleItem(7, 800, 100)),
        )
        // 补滚按调用时刻的旧高度钳制后，布局落定、可见区推进（index 7 的底边 900 → 860）。
        val after = followTargetOf(
            count = 10,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(6, 600, 100), VisibleItem(7, 760, 100)),
        )

        assertNotEquals(
            "末条不在可见项内时，末个可见项的底边变化必须换一个触发键（否则 distinctUntilChanged 吞掉信号）",
            before,
            after,
        )
    }

    @Test
    fun `布局完全没变时不发信号`() {
        val first = followTargetOf(
            count = 3,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(0, 0, 300), VisibleItem(1, 300, 300), VisibleItem(2, 600, 300)),
        )
        val again = followTargetOf(
            count = 3,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(0, 0, 300), VisibleItem(1, 300, 300), VisibleItem(2, 600, 300)),
        )

        assertEquals("同一份布局快照必须给出同一个触发键，否则每帧都会补滚", first, again)
    }

    @Test
    fun `补滚后末项底边超出视口才留侦察日志`() {
        val target = followTargetOf(
            count = 10,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(8, 900, 200)),
        )

        // 已贴底（底边落在视口内）：不记日志，并重新武装下一段侦察。
        assertNull(d4ReconLine(target, atBottom = true, actualLastBottom = 1000, actualViewportHeight = 1000))
        // 跟随态却没落到底：记下 Trigger 五元组 + atBottom。
        val line = d4ReconLine(target, atBottom = false, actualLastBottom = 1100, actualViewportHeight = 1000)
        assertNotNull(line)
        assertEquals(
            "D4 follow=true but not at bottom: target=$target atBottom=false " +
                "actualLastBottom=1100 actualViewport=1000",
            line,
        )
    }

    @Test
    fun `末条实例换了就换触发键，即使布局数字完全相同`() {
        // 流式 token 只改内容、不改条数；此时布局产物还没重算，只有实例身份变了。
        // 身份键是布局兜底路径的信号源（同帧贴底由 AutoScrollToLatest 的 SideEffect 负责）。
        val first = ChatMessage(
            id = "m1",
            role = Role.ASSISTANT,
            parts = listOf(Part.Text("a")),
        )
        val grown = first.copy(parts = listOf(Part.Text("ab")))
        val sameContent = first.copy(id = "m2")
        val target = followTargetOf(
            count = 1,
            viewportHeight = 1000,
            imeBottom = 0,
            visibleItems = listOf(VisibleItem(0, 0, 100)),
        )

        assertEquals("同一实例必须相等，否则每帧都会补滚一次", MessageIdentity(first), MessageIdentity(first))
        assertNotEquals(MessageIdentity(first), MessageIdentity(grown))
        assertNotEquals(
            "按引用比较：内容相同的两个实例也不相等，token 改写才不会被去重吞掉",
            MessageIdentity(first),
            MessageIdentity(sameContent),
        )
        assertNotEquals(
            "布局键完全相同，只有末条实例变化时也必须发信号",
            followKey(target, first),
            followKey(target, grown),
        )
        assertEquals("同一实例 + 同一布局必须相等，否则每帧补滚一次", followKey(target, first), followKey(target, first))
    }
}
