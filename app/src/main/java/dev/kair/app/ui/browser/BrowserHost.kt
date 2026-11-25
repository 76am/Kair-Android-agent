package dev.kair.app.ui.browser

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 整个应用唯一的浏览器控制器（AGENTS.md §10.5 的四项交代见任务文档 §3.1）。
 *
 * 为什么是单例：WebView 必须在面板关闭后继续执行 Agent 动作；而面板、插件工具、
 * 全屏路由三个入口必须看到**同一个页面**。按入口各建一个 WebView 会得到三份 DOM。
 *
 * 归属：整个应用；生命周期：首次访问创建、进程死亡回收；清除：插件卸载时 [release]
 * （主线程销毁 WebView 并清空状态）；并发：控制器内部把所有 WebView 操作串行到主线程。
 */
internal object BrowserHost {

    @Volatile
    private var instance: AndroidBrowserController? = null

    /**
     * browser 子智能体是否在跑。跑的时候地址栏只读：用户改导航会和 agent 的操作打架。
     * 没跑的时候用户可以自己输网址打开页面（打开即用户接管）。
     */
    private val _agentRun = MutableStateFlow(false)
    val agentRun: StateFlow<Boolean> = _agentRun

    fun setAgentRun(running: Boolean) {
        _agentRun.value = running
    }

    fun controller(context: Context): AndroidBrowserController =
        instance ?: synchronized(this) {
            instance ?: AndroidBrowserController(context.applicationContext).also { instance = it }
        }

    fun release() {
        val old = synchronized(this) {
            instance.also { instance = null }
        }
        old?.release()
    }
}
