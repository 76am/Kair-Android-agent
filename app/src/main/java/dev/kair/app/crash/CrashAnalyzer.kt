package dev.kair.app.crash

import dev.kair.app.core.ai.Json
import dev.kair.app.core.plugin.dynamic.DynamicPluginHost
import dev.kair.app.core.plugin.dynamic.PluginManifest
import java.io.File

/**
 * 崩溃来源识别：解析崩溃堆栈，判定是内核 / 内置代码出错，还是第三方插件导致；
 * 若来自插件，尽量定位到具体是哪个插件。
 *
 * 崩溃页（[CrashActivity]）运行在独立 `:crash` 进程，无法访问主进程的注册表，
 * 因此这里直接扫描 `plugins/` 目录读清单做匹配（见 [scanInstalledPlugins]）。
 */
sealed interface CrashOrigin {
    /** 内核 / 内置代码出错。 */
    data object Kernel : CrashOrigin

    /** 第三方插件导致，且已定位到具体插件。 */
    data class Plugin(val id: String, val name: String) : CrashOrigin

    /** 第三方插件导致，但无法定位到具体插件（如未安装 / 已删除）。 */
    data object UnknownPlugin : CrashOrigin
}

/** 已安装插件的最小信息，供堆栈匹配。 */
data class InstalledPluginRef(val id: String, val name: String, val entryClass: String)

object CrashAnalyzer {

    /** 插件代码的命名空间前缀（第三方插件按约定使用 `dev.kair.plugin.*`）。 */
    private const val PLUGIN_NS = "dev.kair.plugin."

    /** SDK 自身的命名空间：属宿主提供代码，不算第三方插件。 */
    private const val SDK_NS = "dev.kair.plugin.api."

    private const val MANIFEST_FILE = DynamicPluginHost.MANIFEST_FILE

    /**
     * 判定崩溃来源。优先用已装插件的 [InstalledPluginRef.entryClass] 包名前缀匹配堆栈
     * （定位到具体插件）；匹配不到时，只要堆栈含非 SDK 的 `dev.kair.plugin.` 帧就判定为
     * 第三方插件（未知具体插件）；否则视为内核错误。
     */
    fun origin(report: String, plugins: List<InstalledPluginRef>): CrashOrigin {
        val matched = plugins.firstOrNull { plugin ->
            val prefix = packageOf(plugin.entryClass)
            prefix.isNotBlank() && report.contains("$prefix.")
        }
        if (matched != null) return CrashOrigin.Plugin(matched.id, matched.name)
        if (hasThirdPartyFrame(report)) return CrashOrigin.UnknownPlugin
        return CrashOrigin.Kernel
    }

    /** 报告里是否含第三方插件命名空间的类帧：`dev.kair.plugin.` 但排除 SDK。 */
    fun hasThirdPartyFrame(report: String): Boolean {
        var idx = report.indexOf(PLUGIN_NS)
        while (idx >= 0) {
            if (!report.startsWith(SDK_NS, idx)) return true
            idx = report.indexOf(PLUGIN_NS, idx + PLUGIN_NS.length)
        }
        return false
    }

    /** 扫描插件根目录（`plugins/`）下已安装插件的清单，取 id / name / entryClass。 */
    fun scanInstalledPlugins(root: File): List<InstalledPluginRef> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, MANIFEST_FILE).isFile }
            .mapNotNull { dir ->
                runCatching {
                    Json.decodeFromString<PluginManifest>(File(dir, MANIFEST_FILE).readText())
                }.getOrNull()?.let { InstalledPluginRef(it.id, it.name, it.entryClass.orEmpty()) }
            }
    }

    /** 类全名的包名（去掉最后一段）；无包名时返回空串。 */
    private fun packageOf(className: String): String = className.substringBeforeLast('.', "")
}
