package dev.kair.app.core.proot

import java.io.File

/**
 * 手机共享存储在宿主 / 容器两侧的挂载契约。
 *
 * 宿主侧即 Android 主用户的共享存储根；容器内挂到 [CONTAINER]，与
 * `/projects` / `/trash` 的命名风格一致。授权是否生效以**能否真正读到**为准
 * （比查权限标志更接近事实，兼容 legacy 与 MANAGE 两种授权路径）。
 */
object SdcardMounts {
    /** 宿主侧共享存储根（Android 主用户标准路径）。 */
    const val HOST = "/storage/emulated/0"

    /** 容器内挂载点。 */
    const val CONTAINER = "/sdcard"

    /** 授权是否真实生效：宿主能读到共享存储根。 */
    fun hostAccessible(): Boolean = File(HOST).canRead()
}
