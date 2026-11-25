package dev.kair.app.ui.plugins.builtin

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.kair.app.core.device.PhantomKiller

/**
 * U90 C 层：幻影查杀开关的本地读写（授权后离线可用）+ 授权态判定。
 *
 * 安全收敛：本对象只读写 [PhantomKiller.SETTING_KEY] 这一个键（PhantomKillerScopeGuardTest 钉死）。
 * 写用整数 0/1（Naptime 同款）；读用 getString + 容错解析，兼容 0/1/true/false 各种历史取值。
 */
internal object PhantomKillerStore {
    /** 是否已持有写设置权限（经引导 adb 一次性 pm grant）。 */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PhantomKiller.PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 读当前查杀状态；未授权（SecurityException）返回 null，由 UI 显示「未授权」。 */
    fun readKillerEnabled(context: Context): Boolean? = try {
        PhantomKiller.killerEnabled(Settings.Global.getString(context.contentResolver, PhantomKiller.SETTING_KEY))
    } catch (_: SecurityException) {
        null
    }

    /** 写查杀状态（killerOn=true 开查杀 1，false 关查杀保活 0）；成功 true，未授权 false。 */
    fun writeKillerEnabled(context: Context, killerOn: Boolean): Boolean = try {
        Settings.Global.putInt(context.contentResolver, PhantomKiller.SETTING_KEY, if (killerOn) 1 else 0)
    } catch (_: SecurityException) {
        false
    }
}
