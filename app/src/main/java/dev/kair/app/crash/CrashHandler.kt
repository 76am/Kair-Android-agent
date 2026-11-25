package dev.kair.app.crash

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import dev.kair.app.core.storage.Delivery
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局崩溃报告：接管未捕获异常，写崩溃日志并拉起**独立进程**的崩溃界面。
 *
 * 为什么用独立进程：未捕获异常会让主线程的 `Looper.loop()` 退出，此时在主进程里
 * 再启动 Activity 也收不到生命周期回调。[CrashActivity] 声明在 `:crash` 进程，
 * 主进程崩溃后由系统在该进程里展示错误信息；随后结束已崩溃的主进程，避免继续以
 * 不一致状态运行。
 */
object CrashHandler {

    /** 崩溃日志（应用私有）所在子目录。 */
    private const val CRASH_DIR = "crash"

    @Volatile
    private var installed = false

    /**
     * 安装全局未捕获异常处理器（幂等）。应在进程启动时尽早调用，覆盖 UI 之前
     * 也覆盖任意后台线程。
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(app, thread, throwable)
        }
    }

    /**
     * 处理一次崩溃：拼报告 → 写应用私有日志（必定成功）→ 尽力导出到下载目录 →
     * 拉起崩溃界面 → 结束主进程。
     */
    fun report(app: Application, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("Kair crash report")
            appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("app: ${app.packageName} (${appVersion(app)})")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("thread: ${thread.name} (id ${thread.id})")
            appendLine()
            appendLine(Log.getStackTraceString(throwable))
        }

        // 私有目录写入不依赖任何权限，先保证报告一定落盘。
        val internal = File(File(app.filesDir, CRASH_DIR).apply { mkdirs() }, "crash-$stamp.log")
        runCatching { internal.writeText(text) }
        // 共享交付目录（[Delivery.PATH]）需要存储权限；失败则忽略，崩溃界面里可重试。
        val exported = exportToDownload(text)

        val intent = Intent(app, CrashActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            putExtra(CrashActivity.EXTRA_LOG_PATH, internal.absolutePath)
            putExtra(CrashActivity.EXTRA_SUMMARY, "${throwable.javaClass.name}: ${throwable.message}")
            putExtra(CrashActivity.EXTRA_EXPORTED_PATH, exported?.absolutePath)
        }
        runCatching { app.startActivity(intent) }

        // 结束已崩溃的主进程：主线程已死，继续运行只会在不一致状态下苟活。
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    /**
     * 把报告写到共享交付目录 [Delivery.PATH] 下的 `kair-crash-<时间>.log`；需要存储
     * 权限（WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE）。失败返回 null。
     */
    fun exportToDownload(report: String): File? = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(Environment.getExternalStorageDirectory(), Delivery.RELATIVE)
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "kair-crash-$stamp.log")
        out.writeText(report)
        out
    }.getOrNull()

    private fun appVersion(app: Application): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
