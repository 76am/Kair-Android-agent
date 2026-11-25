package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.device.PhantomKiller
import java.util.concurrent.TimeUnit

/**
 * U90 可选 root 快路径：真 su 可用时直接写设置，免无线调试。
 * 探测结果进程内缓存（同 DevToolsProbe 思路），避免每次进面板都弹 root 授权。
 * 非 root 设备 [available] 返回 false，UI 走 adb 主路径。
 */
internal object PhantomKillerRoot {
    @Volatile
    private var probed: Boolean? = null

    fun available(): Boolean {
        probed?.let { return it }
        val ok = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroy()
                false
            } else {
                p.exitValue() == 0
            }
        }.getOrDefault(false)
        probed = ok
        return ok
    }

    fun write(killerOn: Boolean): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", PhantomKiller.putCommand(killerOn)))
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroy()
            false
        } else {
            p.exitValue() == 0
        }
    }.getOrDefault(false)
}
