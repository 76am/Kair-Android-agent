package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.agent.QueueKind
import dev.kair.app.core.agent.QueuedMessage
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 停止确认弹窗（§B5.6 第 7 条，负责人 2026-11-01 拍板：不做事后提示，
 * **有挂着的引导消息时点停止要先问**）。
 *
 * 守三件事：① 只有用户自己的排队消息算「会被丢弃」（系统通知不参与，沿用 U18 口径）；
 * ② 列表只展开前几行、余量如实报数；③ 停止键**不再无条件 abort** —— 弹窗的确认路径才中止，
 * 「继续运行」不能中止。③ 按源码守接线（与 `GuideComposerTest` 同一手法，扫剥注释后的文本）。
 */
class StopConfirmTest {

    private fun row(text: String, systemNote: Boolean = false, imageOnly: Boolean = false) = QueuedMessage(
        message = ChatMessage(
            role = Role.USER,
            parts = if (imageOnly) {
                listOf(Part.Image(base64 = "aGVsbG8=", mediaType = "image/png"))
            } else {
                listOf(Part.Text(text))
            },
            isSystemNote = systemNote,
        ),
        kind = QueueKind.FOLLOW_UP,
    )

    // ---------------------------------------------------------------- 纯逻辑

    @Test
    fun `only the user's own queued messages count as dropped`() {
        val model = stopConfirmModel(listOf(row("第一条"), row("通知", systemNote = true), row("第二条")))
        assertEquals("系统通知不算「会被丢弃的引导消息」（U18：它不在排队区露面）", 2, model.dropped)
        assertEquals(listOf("第一条", "第二条"), model.previews)
        assertEquals(0, model.hidden)
    }

    @Test
    fun `the list caps at max rows and reports the rest honestly`() {
        val queue = (1..6).map { row("第${it}条") }
        val model = stopConfirmModel(queue, maxRows = 4)
        assertEquals(listOf("第1条", "第2条", "第3条", "第4条"), model.previews)
        assertEquals("超出展开上限的条数必须如实报出来，不然用户以为只有这 4 条", 2, model.hidden)
        assertEquals(6, model.dropped)
    }

    @Test
    fun `a long message is truncated and an image-only row keeps its slot`() {
        val long = "x".repeat(200)
        val model = stopConfirmModel(listOf(row(long), row("", imageOnly = true)))

        assertEquals("预览要截断，弹窗不该被一条长消息撑爆", 80, model.previews.first().length)
        assertEquals(
            "纯图片的排队消息必须占一行且正文为空串 —— 界面据此换成占位文案，不能整行静默消失",
            listOf("x".repeat(80), ""),
            model.previews,
        )
        assertEquals(2, model.dropped)
    }

    // ---------------------------------------------------------------- 接线守卫

    @Test
    fun `the stop button asks instead of aborting outright`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt").readText(Charsets.UTF_8)
        val body = functionBody(source, "ChatScreen")

        assertTrue(
            "停止键必须走 StopConfirm(…)：有挂着的引导消息时先弹窗问，不能直接中止。当前函数体：\n$body",
            body.contains("StopConfirm("),
        )
        val aborts = Regex("""session\.abort\(\)""").findAll(body).count()
        assertEquals(
            "ChatScreen 里只应有一处 session.abort()，且它是传给 StopConfirm 的 onAbort —— " +
                "出现第二处就等于留了一条绕过确认弹窗的停止路径。当前 $aborts 处。",
            1,
            aborts,
        )
    }

    @Test
    fun `confirming aborts and continuing running does not`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/StopConfirm.kt").readText(Charsets.UTF_8)
        val body = functionBody(source, "StopConfirm")

        val confirm = body.indexOf("confirmButton")
        val dismiss = body.indexOf("dismissButton")
        assertTrue(
            "StopConfirm 必须有确认与取消两个按钮（负责人要的「是否停止」）。当前函数体：\n$body",
            confirm >= 0 && dismiss >= 0,
        )
        // 截到 `return {` 之前：函数尾那段是「没有排队消息时直接停止」的快路径，
        // 那里出现 onAbort() 是对的（测试④守的就是它），把它算进「取消段」会误判。
        val afterDialog = body.indexOf("return {", dismiss).takeIf { it >= 0 } ?: body.length
        val confirmBlock = body.substring(confirm, dismiss)
        val dismissBlock = body.substring(dismiss, afterDialog)
        assertTrue(
            "确认按钮必须真的调用 onAbort()，否则弹窗是个假承诺。当前确认段：\n$confirmBlock",
            confirmBlock.contains("onAbort()"),
        )
        assertTrue(
            "「继续运行」不得中止回合（少写一处就会把取消变成停止）。当前取消段：\n$dismissBlock",
            !dismissBlock.contains("onAbort()"),
        )
        assertTrue(
            "空队列不该弹窗、直接停止（多数情况下点停止没有东西要丢，别多加一次点击）。当前函数体：\n$body",
            body.contains("dropped > 0"),
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun functionBody(source: String, name: String): String {
        val stripped = KotlinSourceScanner.stripped(source)
        val function = KotlinSourceScanner.scan(source).singleOrNull { it.name == name }
            ?: error("$source 里找不到函数 $name —— 守卫扫描落空，不等于通过")
        return stripped.lines().subList(function.startLine - 1, function.endLine).joinToString("\n")
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
