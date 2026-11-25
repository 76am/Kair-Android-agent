package dev.kair.app.core.storage

/**
 * 交付目录：agent 产出、用户需要拿走的文件统一落到这里
 * （`/sdcard/Download/KairDownloads`）。收在一个子目录里而不是散在 Download 顶层，
 * 免得和其它 App 下载的东西混成一团。
 *
 * 为什么用共享存储：用户拿系统文件管理器就能看到、拿走、往里放。应用私有工作区
 * 对外部 App 完全不可见，取文件只能靠面板「导出」。
 *
 * [PATH] 用 `/sdcard`（容器挂载点，也是 Android 侧的软链），同一串字面量在
 * **模型提示词、文件管理器、容器内命令**三处都成立；宿主侧建目录用 [RELATIVE] +
 * 共享存储根，不依赖软链。
 */
object Delivery {
    /** 共享存储根（`/sdcard`）下的相对路径。 */
    const val RELATIVE = "Download/KairDownloads"

    /** 宿主与容器两侧等价的绝对路径；模型与文件管理器都用它。 */
    const val PATH = "/sdcard/Download/KairDownloads"
}
