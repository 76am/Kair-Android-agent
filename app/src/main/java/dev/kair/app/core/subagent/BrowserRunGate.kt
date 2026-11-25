package dev.kair.app.core.subagent

/**
 * `browser` 子智能体的运行闸门：进 / 出时通知宿主。
 *
 * 浏览器是进程级单例，`browserLock` 保证同一时刻只有一个 browser 子智能体在跑，
 * 所以一个布尔就够（不需要计数，也不需要并发保护）。
 *
 * 宿主拿这两个回调做两件事：跑的时候把页面切到 Agent 接管（用户不能同时点），
 * 跑完自动关掉页面与小窗 —— 否则悬浮窗会一直留在屏上等用户手动关。
 *
 * 单独成文件：`Subagent.kt` 顶着 500 行守卫，这里只放闸门，不掺会话逻辑。
 * 回调异常一律吞掉：宿主收尾失败不该改变委派本身的成败。
 */
class BrowserRunGate(
    private val onStarted: () -> Unit = {},
    private val onFinished: () -> Unit = {},
) {

    private var running = false

    /** 拿到浏览器锁之后调用。 */
    fun enter() {
        running = true
        runCatching(onStarted)
    }

    /** 释放浏览器锁之前调用；没进过闸门时什么都不做。 */
    fun exit() {
        if (!running) return
        running = false
        runCatching(onFinished)
    }
}
