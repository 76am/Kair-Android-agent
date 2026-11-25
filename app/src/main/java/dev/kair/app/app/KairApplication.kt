package dev.kair.app.app

import android.app.Application
import android.os.Build
import android.webkit.WebView
import dev.kair.app.core.browser.BROWSER_DATA_DIR_SUFFIX
import dev.kair.app.core.proot.RootAccess
import dev.kair.app.crash.CrashHandler
import kotlinx.coroutines.launch
import java.io.File

class KairApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃界面（CrashActivity）跑在独立的 :crash 进程；那里不装配内核容器，
        // 也不必再安装崩溃处理器（避免重入）。
        if (!isMainProcess()) return
        // 全局崩溃报告：任何线程的未捕获异常都会写日志并拉起独立进程的崩溃界面。
        CrashHandler.install(this)
        // Agent Dev Browser 的 WebView 数据目录：必须在进程里创建任何 WebView 之前设置。
        // API 26/27 没有这个 API，退化为共享默认目录（P1 只在 28+ 上承诺隔离）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(BROWSER_DATA_DIR_SUFFIX)
        }
        container = AppContainer(this)
        // 设备权限（root）冷启动自动探测一次：设置页状态行与会话 `[root]` note 都依赖它。
        // 异步执行不阻塞启动：未 root 的机器 locateSu 找不到 su 二进制、秒回无感；已授权的
        // 机器静默通过；首次未授权会弹 Root 管理器授权框（负责人 2026-11-22 确认接受）。
        container.scope.launch { RootAccess.probe() }
    }

    /** 当前进程是否是主进程（`packageName`）；`:crash` 等子进程返回 false。 */
    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrNull()
        }
        return name.isNullOrBlank() || name == packageName
    }
}
