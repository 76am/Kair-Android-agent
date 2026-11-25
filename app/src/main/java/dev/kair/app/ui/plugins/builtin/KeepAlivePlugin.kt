package dev.kair.app.ui.plugins.builtin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.kair.app.app.AppContainer
import dev.kair.app.app.KairForegroundService
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.browser.BrowserOverlayPresence
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 后台常驻插件：让应用在切到后台后继续跑，不被系统回收。
 *
 * 正规机制是**前台服务 + 常驻通知**（`KairForegroundService`），**不是无障碍服务**；
 * 面板里还可把 Kair 加入系统省电白名单（忽略电池优化），进一步降低被杀概率。
 *
 * 起停策略：把「常驻开关」与「是否有会话在跑」合成 —— 任一成立即维持前台服务，
 * 二者皆无则停止。这样即便没开常驻，长任务运行期间也能获得前台优先级。
 */
class KeepAlivePlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = "后台常驻"
    override val description = "前台服务 + 常驻通知，避免后台任务被系统回收。"

    override fun activate(ctx: PluginContext) {
        // 首启同步：把落盘的开关灌进进程内镜像（前台服务起停的唯一事实来源）。
        container.setKeepAliveEnabled(ctx.settings.getBoolean(KEY_ALWAYS, false))

        ctx.settingsPanel(
            SettingsPanelSpec(
                id = "settings.keepalive",
                title = "后台常驻",
                icon = PluginGlyph.Battery,
                order = 30,
                fields = listOf(
                    SettingField.Toggle(
                        key = KEY_ALWAYS,
                        label = "始终后台常驻",
                        description = "在通知栏常驻一条 Kair 通知，避免后台任务被系统回收。" +
                            "关闭时，仅在会话运行期间自动常驻。",
                        default = false,
                        onChange = { container.setKeepAliveEnabled(it) },
                    ),
                    SettingField.Action(
                        key = "ignore_battery",
                        label = "忽略电池优化",
                        description = "把 Kair 加入系统省电白名单，进一步降低被冻结 / 杀进程的概率。",
                        buttonLabel = "去设置",
                        onClick = { launchIgnoreBatteryOptimizations(container.appContext) },
                    ),
                    SettingField.Action(
                        key = "notification_settings",
                        label = "通知设置",
                        description = "若通知栏看不到 Kair 的常驻通知，可在这里打开本应用的通知开关。",
                        buttonLabel = "打开通知设置",
                        onClick = { openNotificationSettings(container.appContext) },
                    ),
                ),
            ),
        )

        // 观察「常驻开关 ∪ 是否有会话在跑 ∪ 是否有后台任务 ∪ 悬浮窗可见」：
        // 任一成立就维持前台服务。后台任务不算会话运行（回合已结束），但同样要跑进程，
        // 息屏被冻结就永远收不了尾；悬浮窗可见期间进程被冻结，切到后台的 agent 动作与
        // 截图都会断，所以也并进来（复用同一个服务，不新增通知）。
        container.scope.launch {
            combine(
                container.keepAlive,
                container.runningSessions,
                container.backgroundTasks.tasks,
                BrowserOverlayPresence.visible,
            ) { always, running, tasks, overlay ->
                val background = tasks.count { it.running }
                val overlayCount = if (overlay) 1 else 0
                (always || running.isNotEmpty() || background > 0 || overlay) to
                    (running.size + background + overlayCount)
            }.collect { (active, count) ->
                if (active) startService(count) else KairForegroundService.stop(container.appContext)
            }
        }
    }

    /**
     * 起前台服务。当前 targetSdk 28 不受 Android 12+「后台启动前台服务」限制，但厂商策略与
     * 未来升 targetSdk 都可能把异常抛进协程；服务起不来的后果只是少一条常驻通知
     * （悬浮窗本身仍可用），不该让整个进程崩掉，所以这里降级并留日志。
     */
    private fun startService(count: Int) {
        runCatching { KairForegroundService.start(container.appContext, count) }
            .onFailure { Log.w("KairKeepAlive", "Foreground service start was rejected", it) }
    }

    companion object {
        const val ID = "dev.kair.plugin.keepalive"
        private const val KEY_ALWAYS = "always_on"
    }
}

/** 跳系统「忽略电池优化」授权页；部分 ROM 精简了该 Activity，失败时静默。 */
private fun launchIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** 跳本应用的系统通知设置页。 */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
