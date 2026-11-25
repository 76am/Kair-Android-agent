package dev.kair.app.core.agent

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「缩放只剩一个实现点 + 内核不碰位图 + 宿主确实接上线」的机械信号（U23 / AGENTS §10.4 与 §2）。
 *
 * 三条各守一面：
 * 1. 会真正改变像素的 Android 图形 API **只允许出现在装配层的一个文件里**
 *    （`app/app/BitmapImageScaler.kt`）；`core` 侧出现即红 —— 内核不引 Android 图形类型。
 * 2. 内核入口 `ImageDownscaler.attach(` **只允许 `HistoryWrites.kt` 一个调用点**（U77 把会话的
 *    写入面搬出 `AgentSession` 后的位置），且必须走在「无内联图片就早退」之后
 *    （纯文字路径逐字节不变，缓存前缀契约）。
 * 3. 装配层每个 `AgentSession(` 都必须注入 `imageScaler = BitmapImageScaler` ——
 *    漏接的后果是「不缩放」，功能静默退回 400，所以这条由守卫钉，不靠默认值。
 *
 * 全部扫**剥注释后的源码**（`KotlinSourceScanner.stripped`）：写进注释的 API 名不该把守卫带绿，
 * 也不该把它带红。
 */
class ImageScalingGuardTest {

    /** 会真正改变像素的 API；`BitmapFactory.decodeByteArray` 只用于读尺寸，但只在缩放实现文件里出现。 */
    private val scalingApis = listOf(
        "createScaledBitmap",
        "inSampleSize",
        "Bitmap.CompressFormat",
        "ImageDecoder",
    )

    private val scalerFile = "app/src/main/java/dev/kair/app/app/BitmapImageScaler.kt"
    private val kernelFile = "app/src/main/java/dev/kair/app/core/agent/ImageDownscaler.kt"

    /** 写入面（U77 起 `ImageDownscaler.attach` 的唯一调用点就在这里，不再是 `AgentSession`）。 */
    private val writesFile = "app/src/main/java/dev/kair/app/core/agent/HistoryWrites.kt"
    private val containerFile = "app/src/main/java/dev/kair/app/app/SessionAssembly.kt"

    @Test
    fun `bitmap scaling lives in exactly one non-core file`() {
        val mainRoot = repoFile("app/src/main/java")
        val files = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val scalingFiles = files.filter { file ->
            scalingApis.any {
                KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8)).contains(it)
            }
        }.map { it.name }

        assertEquals(
            "位图缩放只允许一处实现（BitmapImageScaler.kt）；发现：$scalingFiles",
            listOf("BitmapImageScaler.kt"),
            scalingFiles,
        )

        // 内核侧不得引 Android 图形类型（ArchitectureGuard 之外的第二道：这条链路专门盯缩放）。
        val kernelCode = KotlinSourceScanner.stripped(repoFile(kernelFile).readText(Charsets.UTF_8))
        assertTrue(
            "core/agent/ImageDownscaler.kt 不该出现 android 导入 —— 位图能力由宿主注入",
            !kernelCode.contains("import android."),
        )
    }

    @Test
    fun `kernel entry has a single call site and keeps the text-only path first`() {
        val kernelCode = KotlinSourceScanner.stripped(repoFile(kernelFile).readText(Charsets.UTF_8))
        val attach = KotlinSourceScanner.scan(kernelCode).singleOrNull { it.name == "attach" }
            ?: error("ImageDownscaler.kt 里找不到 attach —— 守卫扫描落空，不等于通过")
        val attachBody = kernelCode.lines().subList(attach.startLine - 1, attach.endLine).joinToString("\n")
        val earlyReturn = attachBody.indexOf("message.images.none")
        val storeCall = attachBody.indexOf("store(")
        assertTrue(
            "attach 必须先判「无内联图片」再落盘，纯文字路径字节不变；当前函数体：\n$attachBody",
            earlyReturn >= 0 && storeCall > earlyReturn,
        )

        // store 必须调注入的缩放器、再落盘，且降级只在这一处兜。
        val store = KotlinSourceScanner.scan(kernelCode).singleOrNull { it.name == "store" }
            ?: error("ImageDownscaler.kt 里找不到 store —— 守卫扫描落空，不等于通过")
        val storeBody = kernelCode.lines().subList(store.startLine - 1, store.endLine).joinToString("\n")
        assertTrue(
            "store 必须调用注入的缩放器并落盘；当前函数体：\n$storeBody",
            storeBody.contains("scaler.scale(") && storeBody.contains("attachments.put("),
        )

        val mainRoot = repoFile("app/src/main/java")
        val files = mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val attachCallers = files.filter {
            KotlinSourceScanner.stripped(it.readText(Charsets.UTF_8)).contains("ImageDownscaler.attach(")
        }.map { it.name }
        assertEquals(
            "内核入库入口只允许一个调用点（HistoryWrites.kt）；发现：$attachCallers",
            listOf("HistoryWrites.kt"),
            attachCallers,
        )
    }

    @Test
    fun `host wires the scaler into every session it builds`() {
        val container = KotlinSourceScanner.stripped(repoFile(containerFile).readText(Charsets.UTF_8))
        val built = Regex("""(?<![\w.])AgentSession\(""").findAll(container).count()
        val wired = Regex("""imageScaler\s*=\s*BitmapImageScaler""").findAll(container).count()
        assertTrue(
            "SessionAssembly 里建了 $built 个 AgentSession，却只有 $wired 处注入 imageScaler —— " +
                "漏接会静默退回「不缩放」（超限大图继续 400）。每个构造点都要显式接上 BitmapImageScaler。",
            built > 0 && built == wired,
        )

        // 决策日志同一条规矩：漏接的后果是「设备上一条证据都没有」，U23 就是这么查不动的。
        val logged = Regex("""imageLog\s*=\s*::logImageScale""").findAll(container).count()
        assertTrue(
            "建了 $built 个 AgentSession，却只有 $logged 处接上 imageLog —— 缩放降级在真机上" +
                "重新变成不可观测，每个构造点都要接到 ::logImageScale。",
            built == logged,
        )
    }

    /** 入库调用点必须把日志一起传进内核，否则上面两处接线只到会话构造就断了。 */
    @Test
    fun `the session passes the log sink down to the kernel entry`() {
        val writes = KotlinSourceScanner.stripped(repoFile(writesFile).readText(Charsets.UTF_8))
        val call = Regex("""ImageDownscaler\.attach\(([^)]*)\)""").find(writes)
            ?: error("HistoryWrites.kt 里找不到 ImageDownscaler.attach( —— 守卫扫描落空，不等于通过")
        assertTrue(
            "attach 必须把 imageScaler 与 imageLog 一起交给内核；当前调用：${call.value}",
            call.groupValues[1].contains("imageScaler") && call.groupValues[1].contains("imageLog"),
        )
    }

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
