package dev.kair.app.ui.screens.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 提问卡溢出守卫（U63）。
 *
 * 提问正文由模型生成，长度不可控；卡片在 ChatScreen 里是输入区上方的非滚动子项，
 * 内容一高就会把选项与输入框顶出屏幕（真机症状：底部小字被屏幕边缘裁掉、发送区不见）。
 * 这里钉住三件事：整卡按「可用高度 - Composer 预留」限高、正文吃掉页脚之外的余量并滚动、
 * 字号比原先更小（原 bodyLarge / bodyMedium）。
 *
 * 为什么按源码守：本仓没有 Compose UI 测试基建（无 createComposeRule），
 * 布局行为在 JVM 单测里没有观测面；口径同 `SystemNoteUiGuardTest`。
 * 高度预算本身是纯函数，另用数值用例守（[askCardMaxHeight]）。
 */
class AskUserCardLayoutGuardTest {

    private val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/ApprovalCards.kt")
        .readText(Charsets.UTF_8)

    @Test
    fun `ask card bounds the whole card and scrolls the body`() {
        val body = functionBody("AskUserCard")
        assertTrue(
            "AskUserCard 必须给整卡限高（Card(modifier = Modifier…heightIn(max = cardMaxHeight))）：" +
                "只限制正文的话，卡片页脚与下方 Composer 仍会被顶出屏幕。当前函数体：\n$body",
            Regex("""Card\(\s*modifier\s*=\s*Modifier[\s\S]{0,300}?heightIn\(\s*max\s*=\s*cardMaxHeight\s*\)""")
                .containsMatchIn(body) &&
                body.contains("askCardMaxHeight(maxHeight)"),
        )
        assertTrue(
            "正文（问题 + 选项）必须在同一 Modifier 链上先 weight(1f, fill = false) 再 verticalScroll(bodyScroll)，" +
                "否则「限高」可能落在正文而整卡无界，或滚动区把页脚一起带走。当前函数体：\n$body",
            Regex(
                """weight\(1f,\s*fill\s*=\s*false\)[\s\S]{0,120}?verticalScroll\(bodyScroll\)""",
            ).containsMatchIn(body),
        )
    }

    @Test
    fun `ask card budget always leaves room for the composer`() {
        // 常见手机可用高度：800dp 时给卡片 560dp 上限，预留 132dp 给 Composer 仍有余量。
        assertEquals(AskCardMaxHeightCap, askCardMaxHeight(800.dp))
        // 键盘弹出后可用高度约 420dp：420 - 132 = 288dp，页脚 + Composer 都在屏幕内。
        assertEquals(288.dp, askCardMaxHeight(420.dp))
        // 刚过兜底阈值：仍按「可用高度 - Composer 预留」走。
        assertEquals(228.dp, askCardMaxHeight(360.dp))
        assertTrue(askCardMaxHeight(400.dp) + AskCardComposerReserve <= 400.dp)
        // 视口连「页脚 + Composer」都放不下：高度全给卡片，不压成 0（提问仍可作答）。
        assertEquals(300.dp, askCardMaxHeight(300.dp))
        assertEquals(100.dp, askCardMaxHeight(100.dp))
        // 无界约束（例如将来被放进 LazyColumn）：落到上限，不返回 Infinity。
        assertEquals(AskCardMaxHeightCap, askCardMaxHeight(Dp.Infinity))
    }

    @Test
    fun `ask card uses compact typography for the question and options`() {
        val body = functionBody("AskUserCard")
        assertFalse(
            "提问正文还在用 bodyLarge —— 长问题会占掉半屏，必须降到 bodyMedium。",
            Regex("""Text\(\s*req\.question\s*,\s*style\s*=\s*MaterialTheme\.typography\.bodyLarge""")
                .containsMatchIn(body),
        )
        assertTrue(
            "提问正文应使用 bodyMedium、选项应使用 bodySmall（U63 设计）。当前函数体：\n$body",
            Regex("""Text\(\s*req\.question\s*,\s*style\s*=\s*MaterialTheme\.typography\.bodyMedium""")
                .containsMatchIn(body) &&
                Regex("""style\s*=\s*MaterialTheme\.typography\.bodySmall""").containsMatchIn(body),
        )
    }

    private fun functionBody(name: String): String {
        val scanned = KotlinSourceScanner.scan(source).singleOrNull { it.name == name }
            ?: error("ApprovalCards.kt 里找不到 $name —— 守卫扫描落空，不等于通过")
        return KotlinSourceScanner.stripped(source).lines()
            .subList(scanned.startLine - 1, scanned.endLine)
            .joinToString("\n")
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
