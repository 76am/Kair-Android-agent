package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 定时任务插件：到点往指定会话投一条消息，让 agent 自己干活。
 *
 * 三个设计约束（都是踩过的地方）：
 * 1. **任务活在 `ctx.scope` 里** —— 卸载 / 删除插件时宿主取消它，定时器不会在插件消失后
 *    继续往会话里发消息（没有作用域时这是真会发生的）。
 * 2. **注入的消息是 system note** —— 走 `agents.send`，只追加、不绕过审批；界面渲染成
 *    提示卡片，模型也能区分"这不是用户说的"。
 * 3. **不和自己的事件形成环路** —— 靠宿主侧速率上限（6 条/分钟/会话）兜底，插件这边只在
 *    到点时发一条，不看自己的回执。
 *
 * 关于可靠性：**仅在应用进程存活期间生效**。系统杀掉进程后定时器消失；真要可靠定时需要
 * WorkManager 或前台服务，那属于另一个决定（见 docs/archive/插件接口扩展提案.md §3.3），
 * 这一版选择"诚实地说清楚"而不是假装可靠。
 */
class SchedulerPlugin(private val container: AppContainer) : Plugin {

    override val id = ID
    override val name = "定时任务"
    override val description = "按固定间隔向指定会话发送一条消息，让 agent 定时干活。"

    override fun activate(ctx: PluginContext) {
        // 桥只在 activate 期间能从 ctx 取到，存下来给后台循环与按钮回调使用。
        agents = ctx.agents

        // 上次触发时间落插件私有目录：用户可见设置里不放运行状态。
        val stateFile = ctx.dataDir?.let { File(it, "last-run.txt") }
        // 先恢复落盘值再注册 onDeactivate：后台循环启动前插件就被卸载时，
        // 也不会把初始的 0 写回去（那会让下次启动立刻触发一次）。
        lastRunMs = readLastRun(stateFile)

        // 一次「已触发」记账：推进计时并落盘。只有真正投递成功才调用它。
        fun markFired() {
            lastRunMs = System.currentTimeMillis()
            writeLastRun(stateFile, lastRunMs)
        }

        ctx.settingsPanel(
            SettingsPanelSpec(
                id = "settings.scheduler",
                title = "定时任务",
                icon = PluginGlyph.Clock,
                order = 40,
                fields = listOf(
                    SettingField.Toggle(
                        key = KEY_ENABLED,
                        label = "启用定时任务",
                        description = "关闭后不再触发。仅在应用存活期间生效：进程被系统回收后定时器会消失。",
                        default = false,
                    ),
                    SettingField.Number(
                        key = KEY_EVERY_MINUTES,
                        label = "触发间隔（分钟）",
                        description = "两次触发之间的最小间隔。",
                        default = 30,
                        min = 1,
                        max = 1440,
                    ),
                    SettingField.Choice(
                        key = KEY_TARGET,
                        label = "目标会话",
                        description = "到点时向这个会话投一条消息（以提示形式追加，不是用户发言）。",
                        // 会话列表随用户操作变化，插件拿不到静态列表 → 由宿主渲染时解析。
                        source = "sessions",
                    ),
                    SettingField.Text(
                        key = KEY_MESSAGE,
                        label = "发送内容",
                        description = "到点时作为一条消息发给该会话的 agent。留空则发默认的巡检提示。",
                        default = "",
                    ),
                    SettingField.Action(
                        key = KEY_FIRE_NOW,
                        label = "立即触发一次",
                        description = "用来验证配置是否正确，不需要等到下个间隔。",
                        buttonLabel = "立即触发",
                        onClick = { store ->
                            // onClick 不是 suspend：真正发送丢到插件作用域里（卸载即取消）。
                            val target = store.getString(KEY_TARGET, "")
                            val message = store.getString(KEY_MESSAGE, "")
                            ctx.scope.launch {
                                // 手动触发也计入计时：否则恰好处于到点窗口时，
                                // 轮询会在 30 秒内再自动发一条（重复）。
                                if (fire(target, message)) markFired()
                            }
                        },
                    ),
                ),
            ),
        )

        ctx.onDeactivate {
            // 作用域会在下一步被宿主取消；这里只负责把状态落盘（下次启动不从 0 重新计时）。
            writeLastRun(stateFile, lastRunMs)
        }

        ctx.scope.launch {
            while (isActive) {
                // 用 30 秒粒度轮询：区间由用户配到"分钟"级，轮询再细没有意义，还会耗电。
                delay(POLL_INTERVAL_MS)
                if (!ctx.settings.getBoolean(KEY_ENABLED, false)) continue
                val everyMinutes = ctx.settings.getInt(KEY_EVERY_MINUTES, 30).coerceAtLeast(1)
                if (!isDue(System.currentTimeMillis(), lastRunMs, everyMinutes)) continue
                val target = ctx.settings.getString(KEY_TARGET, "")
                if (target.isBlank()) continue
                // 只有投递成功才推进计时；但「目标会话已被删除」是永久失败 —— 再重试也不会成功，
                // 继续按 30 秒一轮重试只会换来无限次全量扫项目 / 会话（见 advanceAfterAttempt）。
                val delivered = fire(target, ctx.settings.getString(KEY_MESSAGE, ""))
                val exists = delivered || sessionExists(target)
                if (advanceAfterAttempt(delivered, exists)) markFired()
            }
        }
    }

    /**
     * 触发一次：真正的发送交给宿主桥（限速、system note、只追加都在宿主侧保证）。
     * 返回是否真的投递成功。
     *
     * 桥会读存储、按 id 打开会话，可能抛异常。这里必须兜住：异常冒出去会杀掉
     * 上面那个唯一的轮询协程，插件看起来仍"已启用"，但此后永远不会再触发。
     */
    private suspend fun fire(target: String, message: String): Boolean {
        val bridge = agents ?: return false
        if (target.isBlank()) return false
        return try {
            bridge.send(target, message.ifBlank { DEFAULT_MESSAGE })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 宿主桥（activate 期间取出）。为 null 表示宿主不支持，定时任务静默不做事。 */
    private var agents: dev.kair.app.core.plugin.AgentBridge? = null

    /**
     * 目标会话是否还在。
     *
     * 用来把「会话被删」这类**永久失败**从限流 / 桥暂不可用这类**临时失败**里分出来：
     * 前者重试没有意义，后者才值得等下一轮。
     */
    private fun sessionExists(sessionId: String): Boolean =
        agents?.sessions()?.any { it.id == sessionId } == true

    @Volatile
    private var lastRunMs: Long = 0L

    private fun readLastRun(file: File?): Long =
        runCatching { file?.takeIf { it.isFile }?.readText()?.trim()?.toLong() ?: 0L }.getOrDefault(0L)

    private fun writeLastRun(file: File?, value: Long) {
        runCatching { file?.writeText(value.toString()) }
    }

    companion object {
        const val ID = "dev.kair.plugin.scheduler"
        const val KEY_ENABLED = "scheduler.enabled"
        const val KEY_EVERY_MINUTES = "scheduler.every_minutes"
        const val KEY_TARGET = "scheduler.target_session"
        const val KEY_MESSAGE = "scheduler.message"
        const val KEY_FIRE_NOW = "scheduler.fire_now"

        private const val POLL_INTERVAL_MS = 30_000L

        /** 用户没填内容时的默认提示：让 agent 自己判断该做什么，而不是要求它一定干活。 */
        const val DEFAULT_MESSAGE =
            "[scheduled task] Check the current state of this project and report anything that needs attention. " +
                "If nothing is pending, reply that no action is needed."

        /** 供测试使用：把"是否到点"的判定抽成纯函数。 */
        fun isDue(now: Long, lastRun: Long, everyMinutes: Int): Boolean =
            now - lastRun >= everyMinutes.coerceAtLeast(1) * 60_000L

        /**
         * 一次投递尝试后是否推进计时（纯函数，供测试）。
         *
         * 成功 → 推进。失败且会话已不存在 → 也推进（永久失败，重试只会空转）。
         * 失败但会话还在（限流 / 桥暂不可用）→ 不推进，下一个轮询周期重试，消息不丢。
         */
        fun advanceAfterAttempt(delivered: Boolean, targetExists: Boolean): Boolean =
            delivered || !targetExists
    }
}
