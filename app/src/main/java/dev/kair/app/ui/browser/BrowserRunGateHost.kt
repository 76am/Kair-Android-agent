package dev.kair.app.ui.browser

import android.content.Context
import dev.kair.app.core.browser.BrowserMode
import dev.kair.app.core.subagent.BrowserRunGate

/**
 * 进程级的 browser 子智能体闸门：跑的时候页面归 agent 接管，跑完直接关页、销毁小窗。
 *
 * 放在浏览器图层而不是组合根：`AppContainer` 有行数棘轮（只准减），这类宿主适配
 * 不该往里塞。`appContext` 为 null（JVM 单测）时两个回调都是空操作。
 */
internal fun browserRunGate(appContext: Context?): BrowserRunGate = BrowserRunGate(
    onStarted = {
        BrowserHost.setAgentRun(true)
        appContext?.let { BrowserHost.controller(it).setMode(BrowserMode.AGENT) }
    },
    onFinished = {
        BrowserHost.setAgentRun(false)
        appContext?.let { BrowserHost.controller(it).close() }
    },
)
