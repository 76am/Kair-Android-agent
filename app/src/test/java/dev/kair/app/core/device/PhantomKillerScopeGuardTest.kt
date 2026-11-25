package dev.kair.app.core.device

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * U90 安全收敛守卫（任务书 §5.2）。本功能持有 WRITE_SECURE_SETTINGS，必须钉死它只碰一个设置键：
 * 1. 设置键字面量在主源码里**只有一个实现点**（PhantomKiller.kt），别处不得另写一份（§10.4）；
 * 2. PhantomKillerStore 的每个 Settings.Global.put 调用都用 PhantomKiller.SETTING_KEY，不得硬编码别的键；
 * 3. PhantomKiller.kt 是 core 纯层：剥注释后无中文字面量、无 android import。
 *
 * 源码级判据而非行为用例：Settings.Global 要 Android 框架才跑得起，JVM 上不可达；
 * 而「只写一个键」是引用面约束，源码扫描精确（同 DesktopSessionGuardTest 思路）。
 */
class PhantomKillerScopeGuardTest {

    @Test
    fun `设置键字面量只有一个实现点`() {
        // 键是字符串字面量，stripped 会把它抹掉，故搜原文（grep 确认主源码仅 PhantomKiller.kt 含它）。
        val hits = mainSources().filter { it.readText(Charsets.UTF_8).contains(PhantomKiller.SETTING_KEY) }
        assertTrue(
            "设置键「${PhantomKiller.SETTING_KEY}」只允许在 PhantomKiller.kt 定义一次（单一实现点），" +
                "实际命中：${hits.map { repoRelative(it) }}",
            hits.size == 1 && hits[0].name == "PhantomKiller.kt",
        )
    }

    @Test
    fun `Store 的每个写设置调用都用同一个键常量`() {
        val file = File(repo, "app/src/main/java/dev/kair/app/ui/plugins/builtin/PhantomKillerStore.kt")
        val code = KotlinSourceScanner.stripped(file.readText(Charsets.UTF_8))
        val putLines = code.lines().filter { it.contains("Settings.Global.put") }
        assertTrue("PhantomKillerStore 必须有写设置调用（否则守卫空转）", putLines.isNotEmpty())
        val offenders = putLines.filterNot { it.contains("PhantomKiller.SETTING_KEY") }
        assertTrue(
            "持有 WRITE_SECURE_SETTINGS 但只许写 PhantomKiller.SETTING_KEY 这一个键；以下写调用用了别的键：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `PhantomKiller 是 core 纯层无中文无 android`() {
        val file = File(repo, "app/src/main/java/dev/kair/app/core/device/PhantomKiller.kt")
        val raw = file.readText(Charsets.UTF_8)
        val code = KotlinSourceScanner.stripped(raw)
        assertTrue(
            "core 纯层不得含中文字面量（文案走 strings.xml，AGENTS.md §4）",
            !Regex("[\\u4e00-\\u9fff]").containsMatchIn(code),
        )
        assertTrue(
            "core 纯层不得 import Android framework（ArchitectureGuard 同口径）",
            !raw.lines().any { it.trim().startsWith("import android.") },
        )
    }

    private fun mainSources(): List<File> =
        File(repo, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repoRelative(file: File): String =
        file.absolutePath.removePrefix(repo.absolutePath + File.separator).replace(File.separatorChar, '/')

    private val repo: File by lazy {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return@lazy dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），U90 守卫无法运行")
    }
}
