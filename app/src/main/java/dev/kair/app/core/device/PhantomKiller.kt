package dev.kair.app.core.device

/**
 * 幻影进程查杀（Android 12/13 phantom process killer）的纯协议层：常量、命令拼装、取值解析、版本闸。
 *
 * 与 U86 `core/desktop/RfbProtocol` 同思路——纯函数、零 Android import、零中文字面量，JVM 可测；
 * Android 封装（adb 客户端 / Settings.Global 读写）与 UI 都在 `ui/plugins/builtin`。
 *
 * 取证：真机 `logcat -b events` 的 `am_kill … Trimming phantom processes` 杀掉 proot 整棵进程树
 * （`exit=137`）；`settings put global settings_enable_monitor_phantom_procs false` 后连播 4 分钟+ 零查杀。
 */
object PhantomKiller {
    /** 控制系统是否清理幻影进程的全局设置键；false/0 = 关查杀（保活），true/1 = 开查杀（默认）。 */
    const val SETTING_KEY = "settings_enable_monitor_phantom_procs"

    /** 写 global 设置所需的 development 级权限；安装时不授予，经引导 adb `pm grant` 一次性授予。 */
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    /** 幻影进程查杀自 Android 12（API 31）起存在；以下版本无需此操作。 */
    const val MIN_SDK = 31

    /** 该系统版本是否需要本功能（>= Android 12）。 */
    fun needed(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    /**
     * `settings get global` 的输出 → 查杀是否开启。
     * "false"/"0" → 关查杀；其余（"true"/"1"/null/空/异常）一律按「查杀开」保守处理（系统默认即开）。
     */
    fun killerEnabled(raw: String?): Boolean {
        val v = raw?.trim()?.lowercase() ?: return true
        return v != "false" && v != "0"
    }

    /** 一次性授权命令：把写设置权限授予本 App（shell UID 有权执行 pm grant）。 */
    fun grantCommand(packageName: String): String = "pm grant $packageName $PERMISSION"

    /** 读当前查杀状态。 */
    fun getCommand(): String = "settings get global $SETTING_KEY"

    /** 写查杀状态：killerOn=true 开查杀（1），false 关查杀保活（0）。只针对这一个键；用整数 0/1（Naptime 同款，跨设备最稳）。 */
    fun putCommand(killerOn: Boolean): String =
        "settings put global $SETTING_KEY ${if (killerOn) "1" else "0"}"

    /**
     * 解析用户输入的「IP:端口」→ (host, port)；格式非法返回 null（UI 据此提示，不发起连接）。
     * 端口须为 1..65535 的纯数字；host 非空。容忍首尾空白。
     */
    fun parseAddress(raw: String): Pair<String, Int>? {
        val parts = raw.trim().split(":")
        if (parts.size != 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }
}
