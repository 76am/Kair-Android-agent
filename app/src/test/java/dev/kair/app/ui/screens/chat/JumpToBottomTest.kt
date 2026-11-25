package dev.kair.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1 的机械判据：↓ 按钮的可见性必须由「是否跟随」决定，而不是「这一帧在不在底部」。
 *
 * 后者在流式输出期间每个 token 翻一次（内容长高 → 立刻可滚 → 补滚落位 → 又到底），
 * 按钮以推送频率（约 15Hz）频闪 —— 这就是负责人报的「下拉按键一起闪」。
 *
 * 判据抽成纯函数是因为 LazyList 的真实布局在 JVM 单测里驱动不了；[distanceToBottomOf]
 * 收的是与生产同一份 [VisibleItem] 快照类型，不是测试自己另造的结构。
 */
class JumpToBottomTest {

    @Test
    fun `自动跟随中不显示按钮`() {
        // 变异靶子：把这条判据改回 `!atBottom`（即忽略 following），本用例必红。
        assertFalse(
            "跟随中的一帧间隙不能让按钮出现 —— 流式期间它每帧都会出现一次",
            jumpToBottomVisible(following = true, messageCount = 10),
        )
        assertFalse(jumpToBottomVisible(following = true, messageCount = 1))
    }

    @Test
    fun `手动上翻且有条目才显示`() {
        assertTrue(jumpToBottomVisible(following = false, messageCount = 10))
        // 空列表没有「底部」可回，按钮不给。
        assertFalse(jumpToBottomVisible(following = false, messageCount = 0))
    }

    @Test
    fun `离底不足 24dp 不显示`() {
        assertFalse("贴底差 1px 不算上翻", isFarEnoughToShow(distancePx = 3, thresholdPx = 24))
        assertFalse(isFarEnoughToShow(distancePx = 23, thresholdPx = 24))
        assertTrue(isFarEnoughToShow(distancePx = 24, thresholdPx = 24))
        assertTrue(isFarEnoughToShow(distancePx = 25, thresholdPx = 24))
    }

    @Test
    fun `末条在视口外时按最远处理`() {
        // 用户翻了很远，末条根本不在可见项里：不必算精确距离，一定是「远」。
        val distance = distanceToBottomOf(
            totalItems = 10,
            visibleItems = listOf(VisibleItem(6, 600, 100), VisibleItem(7, 800, 100)),
            viewportHeight = 1000,
        )

        assertEquals(Int.MAX_VALUE, distance)
        assertTrue(isFarEnoughToShow(distance, thresholdPx = 24))
    }

    @Test
    fun `贴底距离为零，内容超出为正值`() {
        // 末条正好铺满视口：距离 0（迟滞会压掉这点抖动）。
        assertEquals(
            0,
            distanceToBottomOf(
                totalItems = 3,
                visibleItems = listOf(VisibleItem(1, 0, 500), VisibleItem(2, 900, 100)),
                viewportHeight = 1000,
            ),
        )
        // 末条底边超出视口 50px（刚长高、补滚还没落位）：距离 50。
        assertEquals(
            50,
            distanceToBottomOf(
                totalItems = 3,
                visibleItems = listOf(VisibleItem(2, 950, 100)),
                viewportHeight = 1000,
            ),
        )
    }

    @Test
    fun `空列表不算离底`() {
        assertEquals(0, distanceToBottomOf(totalItems = 0, visibleItems = emptyList(), viewportHeight = 1000))
    }
}
