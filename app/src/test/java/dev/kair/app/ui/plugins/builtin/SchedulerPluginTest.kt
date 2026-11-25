package dev.kair.app.ui.plugins.builtin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务的"到点判定"。
 *
 * 单独抽成纯函数是因为这里出错的后果**很贵**：判定写松了会每轮询一次就触发一次
 * （每分钟烧一轮模型调用），写紧了则永远不触发而且用户看不出原因。时间相关逻辑
 * 不进 UI，才能用固定数字测。
 */
class SchedulerPluginTest {

    private val minute = 60_000L

    @Test
    fun `not due before the interval elapses`() {
        assertFalse(SchedulerPlugin.isDue(now = 10 * minute, lastRun = 0, everyMinutes = 30))
    }

    @Test
    fun `due exactly at the interval boundary`() {
        // 边界取"大于等于"：否则 30 分钟的间隔会因为轮询落在 29:59 而永远差一秒。
        assertTrue(SchedulerPlugin.isDue(now = 30 * minute, lastRun = 0, everyMinutes = 30))
    }

    @Test
    fun `never fires twice within the same interval`() {
        // 关键回归：轮询每 30 秒一次，若判定只看"有没有到点"而不推进 lastRun，
        // 就会在到点后的每一次轮询都触发（一分钟内两次）。
        val lastRun = 100 * minute
        val every = 30
        assertFalse(SchedulerPlugin.isDue(lastRun + 29 * minute, lastRun, every))
        assertTrue(SchedulerPlugin.isDue(lastRun + 30 * minute, lastRun, every))
        // 触发后调用方把 lastRun 推到当前时刻，下一次要再等满一个间隔。
        val fired = lastRun + 30 * minute
        assertFalse(SchedulerPlugin.isDue(fired + 1, fired, every))
    }

    @Test
    fun `zero or negative interval is clamped to one minute`() {
        // 用户把间隔填成 0 时不能变成"每次轮询都触发"。
        assertFalse(SchedulerPlugin.isDue(now = 59_000L, lastRun = 0L, everyMinutes = 0))
        assertTrue(SchedulerPlugin.isDue(now = 60_000L, lastRun = 0L, everyMinutes = 0))
        assertTrue(SchedulerPlugin.isDue(now = 60_000L, lastRun = 0L, everyMinutes = -5))
    }

    @Test
    fun `first run after process restart waits for the persisted timestamp`() {
        // 落盘的 lastRun 在重启后继续生效：用户重启应用不该立刻被触发一次。
        val persisted = 1_000L * minute
        assertFalse(SchedulerPlugin.isDue(persisted + 5 * minute, persisted, 30))
    }

    @Test
    fun `default message is model-facing english`() {
        // 面向模型的字符串必须是英文（AGENTS.md §4）——注入的消息会进对话历史。
        val msg = SchedulerPlugin.DEFAULT_MESSAGE
        assertTrue(msg.isNotBlank())
        assertTrue("默认消息不应含中文", msg.none { it.code > 0x2E80 })
    }

    @Test
    fun `permanently invalid target does not retry forever`() {
        // 回归：目标会话被删后，fire() 永远返回 false。若"失败就不推进计时"一刀切，
        // 插件会每 30 秒重试一次、每次都全量扫一遍项目 / 会话（无限空转）。
        assertTrue("投递成功 → 推进", SchedulerPlugin.advanceAfterAttempt(delivered = true, targetExists = true))
        assertTrue("目标已删除 → 也推进（永久失败，重试没意义）", SchedulerPlugin.advanceAfterAttempt(delivered = false, targetExists = false))
        assertFalse("会话还在但限流 / 桥暂不可用 → 不推进，下一轮重试", SchedulerPlugin.advanceAfterAttempt(delivered = false, targetExists = true))
    }
}
