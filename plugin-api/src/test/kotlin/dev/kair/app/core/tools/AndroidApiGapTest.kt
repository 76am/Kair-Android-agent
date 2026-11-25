package dev.kair.app.core.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 机械守卫：**plugin-api 按 JDK 编译，却运行在 Android 上**。
 *
 * 这是本模块的结构性盲区。`android.jar` 只约束 app 模块；plugin-api 是纯 JVM 模块，
 * `java.lang.Process`、`java.nio.file.Files` 等类解析到的是 **JDK 17**，于是 Java 9+ 的
 * API 全部能过编译。JVM 单测同样看不见（测试进程就是 JDK），只有真机运行才炸。
 *
 * 现场（已发生过一次）：`killTree` 直接调 `Process.pid()`，真机点「停止」时在取消路径的
 * finally 里抛 `NoSuchMethodError`，直接把应用崩掉 —— 全量单测当时是绿的。
 *
 * 因此这里做**源码级**扫描：plugin-api 主源码禁止出现下表 API。确实需要（例如先反射试试
 * 再降级）的行，写 `android-gap-ok` 标记（同一行或紧邻上一行），并在标记旁说明为何安全。
 */
class AndroidApiGapTest {

    @Test
    fun `plugin-api never calls Java 9 plus APIs that Android lacks`() {
        val root = mainSourceRoot()
        val offenders = ArrayList<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, raw ->
                    if (isCommentLine(raw)) return@forEachIndexed
                    // 允许标记写在同一行，或紧邻的上一行（便于把理由写成独立注释行）。
                    val allowed = raw.contains(ALLOW_MARKER) ||
                        lines.getOrNull(index - 1)?.contains(ALLOW_MARKER) == true
                    if (allowed) return@forEachIndexed
                    FORBIDDEN.firstOrNull { raw.contains(it.token) }?.let { hit ->
                        offenders += "${file.relativeTo(root).path}:${index + 1}  `${hit.token}` —— ${hit.reason}\n    ${raw.trim()}"
                    }
                }
            }

        assertTrue(
            "plugin-api 里出现了 Android 运行时没有的 Java 9+ API（真机才会炸，单测永远绿）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 守卫自身必须真的在扫源码：找不到目录就报错，而不是静默通过。 */
    @Test
    fun `guard actually finds the main source root`() {
        val root = mainSourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.count()
        assertTrue("应该能扫到 plugin-api 主源码，实际 $files 个文件", files > 10)
    }

    /**
     * 从测试的工作目录往上找 `src/main/kotlin`。Gradle 把模块目录当工作目录，
     * 但 IDE / 不同 runner 可能不是，所以按候选列表试，不硬编码一级相对路径。
     */
    private fun mainSourceRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/kotlin"),
            File(cwd, "plugin-api/src/main/kotlin"),
            File(cwd.parentFile, "plugin-api/src/main/kotlin"),
        )
        return candidates.firstOrNull { File(it, "dev/kair/app/core/tools").isDirectory }
            ?: error("找不到 plugin-api 的 src/main/kotlin（cwd=${cwd.absolutePath}）")
    }

    /** 整行注释 / KDoc 行：注释里提到这些 API 是允许的（本文件就在提）。 */
    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private data class Forbidden(val token: String, val reason: String)

    private companion object {
        const val ALLOW_MARKER = "android-gap-ok"

        /**
         * 只收**确定**在目标 Android 版本上不存在、且**不会**和 Kotlin 标准库同名扩展混淆的
         * API。像 `.repeat(` / `.toList()` 这种 Kotlin 自己也有同名扩展的不收 —— 它们会
         * 解析到 kotlin stdlib，是安全的，收进来只会制造噪音。
         */
        val FORBIDDEN = listOf(
            // 这次真机崩溃的元凶：Java 9。
            Forbidden(".pid()", "Java 9 Process.pid()；Android 的 java.lang.Process 没有此方法"),
            Forbidden("toHandle()", "Java 9 Process.toHandle()；Android 没有 ProcessHandle"),
            Forbidden("ProcessHandle", "Java 9 ProcessHandle；Android 运行时与 android.jar 都没有"),
            // Java 9+ 集合工厂 / 工具：Android 需 API 30+。
            Forbidden("List.of(", "Java 9 集合工厂，Android 需 API 30+，而 minSdk 是 28"),
            Forbidden("Map.of(", "Java 9 集合工厂，Android 需 API 30+，而 minSdk 是 28"),
            Forbidden("Set.of(", "Java 9 集合工厂，Android 需 API 30+，而 minSdk 是 28"),
            Forbidden("List.copyOf(", "Java 10 不可变拷贝，Android 需 API 31+"),
            Forbidden("Map.copyOf(", "Java 10 不可变拷贝，Android 需 API 31+"),
            Forbidden("Set.copyOf(", "Java 10 不可变拷贝，Android 需 API 31+"),
            Forbidden("Objects.requireNonNullElse", "Java 9，Android 需 API 33+"),
            Forbidden("Collectors.toUnmodifiable", "Java 10，Android 需 API 33+"),
            // Java 11 字符串 / 流 / 文件。
            Forbidden(".strip()", "Java 11 String.strip()，Android 需 API 33+；用 Kotlin 的 trim()"),
            Forbidden(".stripLeading()", "Java 11，Android 需 API 33+"),
            Forbidden(".stripTrailing()", "Java 11，Android 需 API 33+"),
            Forbidden("Files.readString(", "Java 11 Files.readString，Android 需 API 33+"),
            Forbidden("Files.writeString(", "Java 11 Files.writeString，Android 需 API 33+"),
            Forbidden("String.formatted(", "Java 15，Android 需 API 34+"),
            // Java 9 并发 / 反射 / 诊断。
            Forbidden("Thread.onSpinWait", "Java 9，Android 需 API 33+"),
            Forbidden("StackWalker", "Java 9，Android 无此类"),
            Forbidden("VarHandle", "Java 9，Android 无此类"),
            Forbidden("onSpinWait", "Java 9 Thread.onSpinWait，Android 需 API 33+"),
        )
    }
}
