package dev.kair.app.ui.screens.chat

import dev.kair.app.core.plugin.PanelChrome
import dev.kair.app.core.plugin.PanelContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板入口的筛选逻辑（原 `DockPanelRowTest` 的纯函数部分，随面板坞撤销迁入 JVM 单测）。
 *
 * U100 拍板：沉浸面板也进列表、带全屏标记；顺序 = 注册表声明序，**不按频率、不按字母**
 * —— 重排会让用户每次重新扫一遍，省下的点击被寻找的时间吃回去。
 * 渲染侧（快捷入口 sheet）是 `List<DockPanelEntry> -> 像素`，不在这里测。
 */
class DockPanelsTest {

    private fun panel(id: String, title: String, chrome: PanelChrome = PanelChrome.Standard) =
        PanelContribution(id = id, title = title, chrome = chrome, content = {})

    @Test
    fun order_follows_registry_declaration_not_frequency_or_alphabet() {
        val panels = listOf(panel("p.z", "Zed"), panel("p.a", "Alpha"), panel("p.m", "Mike"))
        assertEquals(listOf("p.z", "p.a", "p.m"), dockPanels(panels).map { it.id })
    }

    @Test
    fun immersive_panels_are_included_and_marked_fullscreen() {
        val panels = listOf(panel("panels.session", "终端"), panel("panels.desktop", "桌面", PanelChrome.Immersive))

        val kept = dockPanels(panels)

        assertEquals(listOf("panels.session", "panels.desktop"), kept.map { it.id })
        assertFalse(isFullscreenPanel(kept[0]))
        assertTrue(isFullscreenPanel(kept[1]))
    }
}
