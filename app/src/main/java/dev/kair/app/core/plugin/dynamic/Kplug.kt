package dev.kair.app.core.plugin.dynamic

import java.io.File
import java.util.zip.ZipInputStream

/**
 * `.kplug` 包（zip）的解包工具。
 *
 * 包内结构：`manifest.json` + `classes.dex`（可选脚本 / 技能等资产）。
 * 防路径穿越（zip slip）：拒绝任何逃出 [target] 的条目。
 */
object Kplug {

    /** 把 zip 解到 [target]；成功返回 true。target 已存在时先清空。 */
    fun extract(zip: File, target: File): Result<Unit> = runCatching {
        require(zip.isFile) { "插件包不存在：${zip.path}" }
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        val canonicalTarget = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name)
                val canonical = out.canonicalPath
                // zip slip 防护：条目必须落在 target 内（目录条目允许等于 target 本身）。
                require(canonical == target.canonicalPath || canonical.startsWith(canonicalTarget)) {
                    "插件包含非法路径条目：${entry.name}"
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }

    /** `.kplug` 包的文件扩展名。 */
    const val EXTENSION = ".kplug"
}
