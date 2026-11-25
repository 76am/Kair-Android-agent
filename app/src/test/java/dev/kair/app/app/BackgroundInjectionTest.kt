package dev.kair.app.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台注入不劫持「当前项目 / 当前会话」。
 *
 * 定时任务给另一个项目的会话发一条消息时，走 `openSession` 会把用户正在看的工作区、
 * 当前会话（缓存面板据此过滤）与 stdio MCP 连接一起切走 —— 用户什么都没做，界面却被
 * 后台改到了另一个项目。`sessionFor` 只建实例，副作用只留在目标会话自己身上。
 *
 * 唯一允许沿用 `openSession` 的情形是「宿主还没有活动会话」：那时没有可劫持的 UI 状态，
 * 且容器 / MCP 需要按目标工作区首次启动。
 */
class BackgroundInjectionTest {

    @Test
    fun `injection keeps the current session once the user has one`() {
        assertFalse("已有活动会话时不得切换全局", mayAdoptActiveSession("session-a"))
    }

    @Test
    fun `injection may start the app state when nothing is open yet`() {
        assertTrue("没有任何活动会话时沿用正常路径，让容器 / MCP 首次启动", mayAdoptActiveSession(null))
    }
}
