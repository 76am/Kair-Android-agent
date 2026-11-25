package dev.kair.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推屏抽屉的几何与松手判据（U115，含返工）。
 *
 * 只测纯函数：真机手感（跟手、吸附）仍需 C 层目视；这里守住几个最容易写错的数 ——
 * 位移要乘 65%、拖动换算按卡片实际行程、左右两侧的松手阈值、以及整屏起手后的
 * 起拖裁决（`drawerDragClaim`：16dp 行程 + 位移或速度任一更横 + 方向符合状态）。
 */
class PushDrawerTest {

    @Test
    fun `展开满时卡片右移 65 屏宽`() {
        // 返工点：调用方传的是 0..1 的进度，65% 必须由这里乘上。
        // 之前漏乘会让卡片整整右移一屏（真机「直接百分百右滑」）。
        assertEquals(650f, pushDrawerTranslationX(1000f, 1f), 0.01f)
        assertEquals(325f, pushDrawerTranslationX(1000f, 0.5f), 0.01f)
        assertEquals(0f, pushDrawerTranslationX(1000f, 0f), 0.01f)
        // 动画过冲 / 越界夹住，不能把卡片推出屏外。
        assertEquals(650f, pushDrawerTranslationX(1000f, 1.4f), 0.01f)
    }

    @Test
    fun `拖动换算按卡片实际行程 65 屏宽`() {
        // 手指拖 65% 屏宽 = 卡片走完全程（进度 1），这样卡片才跟手。
        assertEquals(1f, pushDrawerDragDelta(650f, 1000f), 0.01f)
        assertEquals(0.5f, pushDrawerDragDelta(325f, 1000f), 0.01f)
        assertEquals(0f, pushDrawerDragDelta(120f, 0f), 0.01f)
    }

    @Test
    fun `边缘拉开过阈值或向右甩都展开`() {
        assertTrue(shouldOpenPushDrawer(progress = 0.4f, velocityPxPerSec = 0f))
        assertTrue(shouldOpenPushDrawer(progress = 0.2f, velocityPxPerSec = 800f))
        assertFalse(shouldOpenPushDrawer(progress = 0.2f, velocityPxPerSec = -100f))
    }

    @Test
    fun `展开后几乎没动才回弹`() {
        // 0.8 起算：往回拨约 13% 屏宽就收起；只有基本没动、且向右甩才回弹。
        assertFalse(shouldClosePushDrawer(progress = 0.9f, velocityPxPerSec = 1200f))
        assertTrue(shouldClosePushDrawer(progress = 0.75f, velocityPxPerSec = 0f))
    }

    @Test
    fun `展开后不到一半或向左甩都关闭`() {
        assertTrue(shouldClosePushDrawer(progress = 0.4f, velocityPxPerSec = 0f))
        assertTrue(shouldClosePushDrawer(progress = 0.8f, velocityPxPerSec = -50f))
    }

    /**
     * 松手落点只看画面进度与速度，不看宿主状态（真机：无项目页右滑拉开后收不回）。
     * 起手侧由 `progress >= 0.5` 决定，状态脱节也能靠下一次左滑真的合上。
     */
    @Test
    fun `松手落点按视觉进度自愈状态脱节`() {
        // 从收起侧起手：过阈值 / 右甩 → 展开。
        assertTrue(drawerSettle(progress = 0.4f, velocityPxPerSec = 0f, startedOpen = false))
        assertTrue(drawerSettle(progress = 0.2f, velocityPxPerSec = 800f, startedOpen = false))
        assertFalse(drawerSettle(progress = 0.2f, velocityPxPerSec = -100f, startedOpen = false))
        // 从展开侧起手：不过半 / 左甩 → 收起（宿主状态若已脱节成「关」，也会真的合上）。
        assertFalse(drawerSettle(progress = 0.4f, velocityPxPerSec = 0f, startedOpen = true))
        assertFalse(drawerSettle(progress = 0.8f, velocityPxPerSec = -50f, startedOpen = true))
        assertTrue(drawerSettle(progress = 0.9f, velocityPxPerSec = 1200f, startedOpen = true))
    }

    /**
     * 起拖裁决（U115 返工第三轮）：行程过 16dp 一次定生死，横向看**位移或速度任一**，
     * 再加方向符合状态。
     */
    @Test
    fun `行程过死区一次定生死，位移或速度任一更横就算横滑`() {
        val slop = 16f
        // 行程不够：打字时随手横扫那点位移，连裁决都不进。
        assertEquals(DrawerDragClaim.Pending, drawerDragClaim(10f, 0f, 900f, 0f, slop, true))
        // 收起态整屏右滑。
        assertEquals(DrawerDragClaim.Claimed, drawerDragClaim(30f, 4f, 800f, 100f, slop, true))
        // **弧形拖**：累计纵向分量已经反超横向（拇指拖到一半必然这样），
        // 但速度还是横的 —— 靠速度救回来。旧口径在这里判给列表，就是「识别成上下滑」。
        assertEquals(DrawerDragClaim.Claimed, drawerDragClaim(60f, 90f, 1200f, 200f, slop, true))
        // 真上下滚：位移和速度都纵，判给列表，本条手势不再碰抽屉。
        assertEquals(DrawerDragClaim.Rejected, drawerDragClaim(20f, 200f, 50f, 1500f, slop, true))
        assertEquals(DrawerDragClaim.Rejected, drawerDragClaim(0f, 200f, 0f, 1500f, slop, true))
        // 收起态向左：输入板下沿 chip 行往左滚仍然归它自己。
        assertEquals(DrawerDragClaim.Rejected, drawerDragClaim(-80f, 0f, -900f, 0f, slop, true))
        // 展开态反向：左滑收起、右滑不接（抽屉里的列表要能点能滚）。
        assertEquals(DrawerDragClaim.Claimed, drawerDragClaim(-30f, -4f, -800f, -100f, slop, false))
        assertEquals(DrawerDragClaim.Rejected, drawerDragClaim(30f, 4f, 800f, 100f, slop, false))
        // 横向累计为零但横向甩得猛（起手就快甩）：方向由速度定。
        assertEquals(DrawerDragClaim.Claimed, drawerDragClaim(0f, 20f, 1500f, 100f, slop, true))
    }

    /**
     * 横向滚动子区域（Markdown 表格）按下即登记：表格右滚与抽屉右滑方向相同，
     * 方向判据分不出来，登记优先。
     */
    @Test
    fun `子区域接管横向手势时抽屉整条放弃`() {
        assertEquals(
            DrawerDragClaim.Rejected,
            drawerDragClaim(30f, 4f, 800f, 100f, 16f, opening = true, childOwnsHorizontal = true),
        )
        // 登记必须在方向判据之前生效，否则收起态右滑会先归抽屉。
        assertEquals(
            DrawerDragClaim.Rejected,
            drawerDragClaim(0f, 0f, 900f, 0f, 16f, opening = true, childOwnsHorizontal = true),
        )
    }

    @Test
    fun `多个滚动面嵌套登记时全部释放才归还抽屉`() {
        val exclusion = HorizontalPanExclusion()
        assertFalse(exclusion.active)
        exclusion.acquire()
        exclusion.acquire()
        assertTrue(exclusion.active)
        exclusion.release()
        assertTrue("还有一层没释放，仍归子区域", exclusion.active)
        exclusion.release()
        assertFalse(exclusion.active)
        // 多余的 release 不能把计数打成负数。
        exclusion.release()
        assertFalse(exclusion.active)
    }

    /** 展开态轻点只收「落在会话卡上」的那一下，抽屉内部的控件不能把抽屉弄没。 */
    @Test
    fun `展开态只有落在会话卡的轻点收抽屉`() {
        // 满开时卡片左缘在 65% 屏宽处。
        assertTrue(shouldCloseOnTap(xPx = 800f, widthPx = 1000f, progress = 1f, open = true))
        // 抽屉内部：会话条目 / 分段 / 项目行 / ⋮ / 列底新建 / 长按都不收。
        assertFalse(shouldCloseOnTap(xPx = 300f, widthPx = 1000f, progress = 1f, open = true))
        // 半开时按**当前**卡片左缘算（此例 = 325px）：左缘左边归抽屉，右边归会话卡。
        assertFalse(shouldCloseOnTap(xPx = 300f, widthPx = 1000f, progress = 0.5f, open = true))
        assertTrue(shouldCloseOnTap(xPx = 400f, widthPx = 1000f, progress = 0.5f, open = true))
        // 收起态没有「点空白关闭」这回事。
        assertFalse(shouldCloseOnTap(xPx = 800f, widthPx = 1000f, progress = 0f, open = false))
    }
}
