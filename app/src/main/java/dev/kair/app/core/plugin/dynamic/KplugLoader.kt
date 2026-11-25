package dev.kair.app.core.plugin.dynamic

import dalvik.system.DexClassLoader
import java.io.File
import dev.kair.plugin.api.Plugin as ApiPlugin

/**
 * `.kplug`（dex 插件）的类装载器。
 *
 * 用 [DexClassLoader] 从插件私有目录加载 `classes.dex`——dex 是当**数据** mmap
 * 进虚拟机的，不触发 Android 29+ 对可写目录执行 native 代码的 W^X 限制，因此合法。
 *
 * 父委派：契约类（`dev.kair.app.core.*` / `dev.kair.plugin.api.*` / kotlinx）一律
 * 由宿主类加载器提供；插件 dex 只应包含自己的逻辑类，重复打包契约会触发
 * ClassCastException（打包规则见 `docs/插件接口契约.md`）。
 *
 * [factory] 可注入，JVM 单测里用「从测试类路径直接 loadClass」的假加载器，
 * 覆盖除真实 dex 装载之外的整条链路。
 */
class KplugLoader(
    /** dex 优化的输出目录（应用私有缓存目录）。 */
    private val optDir: File,
    /** 父类加载器：传宿主的 PathClassLoader，保证契约类全局唯一。 */
    private val parent: ClassLoader,
    /** dex 文件 → ClassLoader；生产用 [DexClassLoader]，测试可注入。 */
    private val factory: (dexFile: File) -> ClassLoader = { dexFile ->
        DexClassLoader(dexFile.path, optDir.path, null, parent)
    },
) {
    /** 装载并实例化入口类；任何失败都收敛为 Result，宿主据此拒绝安装。 */
    fun load(dir: File, entryClass: String): Result<ApiPlugin> = runCatching {
        val dexFile = File(dir, DEX_FILE)
        require(dexFile.isFile) { "缺少 $DEX_FILE：${dexFile.path}" }
        val loader = factory(dexFile)
        val cls = loader.loadClass(entryClass)
        val instance = cls.getDeclaredConstructor().newInstance()
        instance as? ApiPlugin
            ?: error("入口类未实现 dev.kair.plugin.api.Plugin：$entryClass")
    }

    companion object {
        const val DEX_FILE = "classes.dex"
    }
}
