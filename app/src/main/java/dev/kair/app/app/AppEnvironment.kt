package dev.kair.app.app

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Android 侧一次性输入的集合（P4-1 / A2）：构造 `AppContainer` 需要的、派生自 [Context] 的全部数据。
 *
 * 生产侧由 [from] 派生；JVM 测试直接传临时目录 + null [appContext] —— 不需要 Robolectric。
 * [appContext] 只服务平台操作（前台服务 / 跳系统设置页 / contentResolver），构造与装配
 * 一律不许碰它：`AppContainer.appContext` 是 fail-fast 取值器，JVM 里碰即抛，不会静默跑偏。
 */
data class AppEnvironment(
    /** 存储根目录（生产：`<filesDir>/kair`）；测试传临时目录。 */
    val storageRoot: File,
    /** 原生库目录：proot 插件定位 libproot_*.so 用。 */
    val nativeLibraryDir: String,
    /** 主 ABI；null = 未知。 */
    val primaryAbi: String?,
    /** 平台操作用的 Context；JVM 测试为 null（平台操作在 JVM 上本来就不可用）。 */
    val appContext: Context? = null,
) {
    companion object {
        /** 生产入口：从 Android Context 派生一次性输入。 */
        fun from(context: Context): AppEnvironment = AppEnvironment(
            storageRoot = File(context.filesDir, "kair").apply { mkdirs() },
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
            appContext = context.applicationContext,
        )
    }
}
