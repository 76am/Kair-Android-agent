package dev.kair.app.ui.screens.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结束原因的**展示覆盖**守卫（U98）。
 *
 * 为什么要有它：`stopReason` 会随会话日志持久化（`plugin-api` 的 `StopReason` 只许增不许改），
 * 而展示侧是一段 `when`。有人给它加一个新常量、忘了在这里加分支，界面会走进
 * `else ->` 分支 —— 用户看到的是「已结束（stepLimit）」这种带协议值的兜底文案。
 * 这类漏配**不会让任何行为用例变红**（值存在、流程也通），只有真机才看得见，
 * 所以只能从源码结构上钉：每一个常量都必须有自己的分支。
 *
 * 扫的是 **`StopReason` 对象里真实的常量清单**，不是手抄的副本 —— 抄一份清单等于
 * 把守卫写成「我知道的那几个还在」，新常量加进来时守卫照样绿。
 */
class StopReasonLabelGuardTest {

    @Test
    fun `every stop reason constant has its own label branch`() {
        val constants = stopReasonConstants()
        assertTrue("没从 StopReason 里解析出常量 —— 守卫扫描落空，不等于通过", constants.size >= 8)
        val shown = File(repoRoot(), LABELS).readText(Charsets.UTF_8)
        val missing = constants.filter { !shown.contains("StopReason.$it ->") }
        assertTrue(
            "这些结束原因没有中文标签，界面会露出协议原文：$missing\n" +
                "在 `$LABELS` 的 stopReasonLabel 里补一条分支 + strings.xml 补一条文案。",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the wire value never reaches the screen verbatim`() {
        val source = File(repoRoot(), LABELS).readText(Charsets.UTF_8)
        val body = source.substringAfter("private fun stopReasonLabel")
            .substringBefore("\n}")
        assertTrue(
            "`else ->` 不许把 raw 值直接返回（那正是协议字段上屏的路径）：\n$body",
            !Regex("""else\s*->\s*stopReason\s*$""", RegexOption.MULTILINE).containsMatchIn(body),
        )
    }

    private fun stopReasonConstants(): List<String> {
        val types = File(repoRoot(), TYPES).readText(Charsets.UTF_8)
        val block = types.substringAfter("object StopReason {").substringBefore("\n}")
        return Regex("""const val (\w+) = """).findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/）")
    }

    private companion object {
        const val LABELS = "app/src/main/java/dev/kair/app/ui/screens/chat/MessageCardActions.kt"
        const val TYPES = "plugin-api/src/main/kotlin/dev/kair/app/core/model/Types.kt"
    }
}
