package dev.kair.app.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.kair.app.MainActivity
import dev.kair.app.R

/**
 * 后台常驻前台服务。
 *
 * 在通知栏挂一条**常驻通知**，把进程提升为「前台服务」优先级，系统在内存回收时
 * 会优先保留它，从而让长任务在切到后台后继续跑。
 *
 * 说明：这**不是无障碍服务**（无障碍需用户手动开启、面向辅助操作）；让应用稳定
 * 驻留后台的正规机制就是前台服务 + 常驻通知，配合「忽略电池优化」白名单。
 *
 * 由 `dev.kair.plugin.keepalive` 插件按「常驻开关 ∪ 是否有会话在跑 ∪ 是否有后台任务在跑」
 * 实时起停；通知里的数字是活跃任务数（运行中的会话 + 后台任务），不只是会话。
 */
class KairForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val running = intent?.getIntExtra(EXTRA_RUNNING, 0) ?: 0
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(running))
        // 有任务在跑时持一把 partial wake lock：熄屏后 CPU 不休眠，任务不被拖慢/挂起。
        if (running > 0) acquireWakeLock() else releaseWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keepalive_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(running: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            // API 31+ 要求显式指定可变性；本 PendingIntent 不再被系统改写，用 IMMUTABLE。
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (running > 0) {
            getString(R.string.keepalive_running, running)
        } else {
            getString(R.string.keepalive_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kair_notification)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "kair_background"
        private const val NOTIFICATION_ID = 0x4B414952 // "KAIR"
        private const val EXTRA_RUNNING = "running"
        private const val WAKELOCK_TAG = "kair:agent"

        /** 启动（或更新）前台服务。[running] 为活跃任务数（运行中的会话 + 后台任务），用于通知文案与唤醒锁。 */
        fun start(context: Context, running: Int) {
            val intent = Intent(context, KairForegroundService::class.java)
                .putExtra(EXTRA_RUNNING, running)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止前台服务（撤回常驻通知）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, KairForegroundService::class.java))
        }
    }
}
