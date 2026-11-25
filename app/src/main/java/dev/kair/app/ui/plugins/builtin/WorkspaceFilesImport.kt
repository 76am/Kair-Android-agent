package dev.kair.app.ui.plugins.builtin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 单次导入上限：100 MB。防手滑选中几 GB 的文件把应用拖死。 */
internal const val MAX_IMPORT_BYTES = 100L * 1024 * 1024

/** 选中项超过 [MAX_IMPORT_BYTES]；[fileName] 用于给出可读提示。 */
internal class ImportTooLarge(val fileName: String) : Exception(fileName)

/** 导入的中间态：缓存临时文件 + 原文件名，落盘后由调用方清理。 */
internal data class StagedImport(val path: String, val name: String)

/**
 * 把系统文件选择器给的 [uri] 拷进缓存，边拷边计数，超过 [maxBytes] 立即中止。
 *
 * 只做暂存，最终位置交给 [FileBackend][dev.kair.app.core.tools.FileBackend.copy]：
 * 目标可能在容器 rootfs（root 属主），只有后端能落盘。
 */
internal fun stageImport(context: Context, uri: Uri, maxBytes: Long): StagedImport {
    val resolver = context.contentResolver
    var declaredSize = -1L
    val name = resolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            declaredSize = if (cursor.isNull(1)) -1L else cursor.getLong(1)
            cursor.getString(0)
        }
        ?.takeIf { it.isNotBlank() }
        ?.substringAfterLast('/')
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "import"
    // 声明大小已知时先快速失败，避免为大文件白读一遍。
    if (declaredSize > maxBytes) throw ImportTooLarge(name)

    val tmp = File.createTempFile("kair-import-", ".tmp", context.cacheDir)
    try {
        val input = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("cannot open $uri")
        input.use { from ->
            tmp.outputStream().use { to -> copyWithLimit(from, to, maxBytes) }
        }
    } catch (error: Throwable) {
        tmp.delete()
        throw if (error is ImportTooLarge) ImportTooLarge(name) else error
    }
    return StagedImport(tmp.path, name)
}

/** 计数拷贝；累计超过 [maxBytes] 时抛 [ImportTooLarge]（文件名由调用方补）。 */
internal fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        total += read
        if (total > maxBytes) throw ImportTooLarge("")
        output.write(buffer, 0, read)
    }
}

/**
 * 当前目录里不覆盖同名文件：`a.png` 已存在时依次尝试 `a (1).png`、`a (2).png`……
 * 返回可直接交给后端的目标绝对路径。
 */
internal fun uniqueImportTarget(dir: String, name: String): String {
    val first = joinPath(dir, name)
    if (!File(first).exists()) return first
    val base = name.substringBeforeLast('.', name)
    val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
    var index = 1
    while (true) {
        val candidate = joinPath(dir, "$base ($index)$ext")
        if (!File(candidate).exists()) return candidate
        index++
    }
}
