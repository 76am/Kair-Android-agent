package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.ScannedFunction
import dev.kair.app.core.agent.QueueKind
import dev.kair.app.core.model.AttachmentRef
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.CommandContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引导消息（U22）：默认发送等回合结束（`FOLLOW_UP`），三角按钮才是插话（`STEER`）；
 * 「引导中」每条可撤回编辑（铅笔）或丢弃（垃圾桶）。
 *
 * 渲染效果没法在 JVM 里验（仓库无 Robolectric），所以这里守两件能守的事：
 * ① 输入区解析 / 撤回载荷这两段纯逻辑；② 接线落点按函数体扫源码
 * （与 `QueueVisibilityTest` / `SystemNoteUiGuardTest` 同一手法）。
 */
class GuideComposerTest {

    private val echo = CommandContribution(name = "echo", title = "Echo", run = {})

    // ---------------------------------------------------------------- 输入区解析

    @Test
    fun `a registered slash command runs as a command and keeps its arguments`() {
        val action = resolveSubmit("/echo hi", null, listOf(echo))
        assertEquals(SubmitAction.Command(echo, "hi"), action)
    }

    @Test
    fun `an unknown slash word is sent to the model, not swallowed as a command`() {
        val action = resolveSubmit("/not-a-command hi", null, listOf(echo))
        assertEquals(SubmitAction.Message("/not-a-command hi", null), action)
    }

    @Test
    fun `blank input with an image still becomes an image-only message`() {
        val image = Part.Image(base64 = "img", mediaType = "image/png")
        assertEquals(SubmitAction.Message("", image), resolveSubmit("   ", image, emptyList()))
    }

    @Test
    fun `blank input without an image has nothing to send`() {
        assertEquals(SubmitAction.Ignore, resolveSubmit("  \n ", null, emptyList()))
    }

    // ---------------------------------------------------------------- 撤回载荷

    @Test
    fun `recall returns both the text and the attached image`() {
        val image = Part.Image(base64 = "img", mediaType = "image/jpeg")
        val message = ChatMessage(role = Role.USER, parts = listOf(Part.Text("看图"), image))

        assertEquals(RecallPayload("看图", image), recallPayload(message))
    }

    @Test
    fun `recall keeps a referenced image so it is not silently dropped`() {
        val ref = AttachmentRef(sha256 = "abc", mediaType = "image/png")
        val image = Part.Image(ref = ref)
        val message = ChatMessage(role = Role.USER, parts = listOf(image, Part.Text("看这张")))

        assertEquals(image, recallPayload(message).image)
        assertNull(recallPayload(ChatMessage(role = Role.USER, parts = listOf(Part.Text("纯文字")))).image)
    }

    // ---------------------------------------------------------------- 接线守卫

    /**
     * 输入区**只有一个提交键**（纸飞机 = 默认发送，忙时入 `FOLLOW_UP`、回合末才发）。
     * 负责人 2026-11-01 纠正：插话不是第二个发送键，而是排队区每条的改道动作（见下面两条用例）——
     * 常驻两个「发送」形状按钮，用户分不清按下去是等回合还是插话。
     */
    @Test
    fun `the composer has one send button that lands on FOLLOW_UP`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt")
            .readText(Charsets.UTF_8)
        val submit = KotlinSourceScanner.scan(source).singleOrNull { it.name == "submit" }
            ?: error("ChatScreen.kt 里找不到局部函数 submit —— 守卫扫描落空，不等于通过")
        val body = functionBody(source, submit)

        assertTrue(
            "默认发送必须走 session.send（忙时入 FOLLOW_UP，回合结束后才发）。当前函数体：\n$body",
            Regex("""session\.send\(""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body),
        )
        assertTrue(
            "输入区不得再有插话分支：插话改由排队区的△发起（`session.steer(` 不该出现在 submit 里）。" +
                "当前函数体：\n$body",
            !body.contains("session.steer("),
        )
        assertTrue(
            "SubmitMode 随第二个键一起删掉，别留一个没人用的枚举（AGENTS §10.3 替换必须删旧路径）。" +
                "当前函数体：\n$body",
            !body.contains("SubmitMode"),
        )
    }

    /**
     * 排队区每条三个动作：铅笔「撤回编辑」、三角「改成立即插话」、垃圾桶「丢弃」。
     * 三角**只对还没插话的行显示**（`kind != STEER`）—— 对已经是插话的行再按一次只会把它挪到队尾，
     * 是纯损耗。旧的 X 不再保留。
     */
    @Test
    fun `queue rows offer recall, promote-to-steer and trash`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/Composer.kt")
            .readText(Charsets.UTF_8)
        val section = KotlinSourceScanner.scan(source).singleOrNull { it.name == "QueueSection" }
            ?: error("Composer.kt 里找不到 QueueSection —— 守卫扫描落空，不等于通过")
        val body = functionBody(source, section)

        assertTrue("QueueSection 必须接收撤回回调 onRecall。当前函数体：\n$body", body.contains("onRecall"))
        assertTrue(
            "QueueSection 必须接收改道回调 onPromote（△ 把这条从回合末发送改成立即插话）。当前函数体：\n$body",
            body.contains("onPromote"),
        )
        assertTrue(
            "改道入口只对未插话的行显示：canPromote 判据是 kind != QueueKind.STEER。当前函数体：\n$body",
            body.contains("canPromote"),
        )
        assertTrue(
            "撤回编辑要用铅笔 Icons.Filled.Edit。当前函数体：\n$body",
            body.contains("Icons.Filled.Edit"),
        )
        assertTrue(
            "丢弃要用垃圾桶 Icons.Filled.Delete。当前函数体：\n$body",
            body.contains("Icons.Filled.Delete"),
        )
        assertTrue(
            "插话用自绘的真三角（需求 §3.2 把 Icons.Filled.ChangeHistory 说成「描边圆角三角向上」是错的，" +
                "它是时钟+回转箭头，于是输入行看起来像两个发送键）。当前函数体：\n$body",
            body.contains("ic_queue_steer"),
        )
        assertTrue(
            "三个按钮都要中文 contentDescription（撤回 / 改插话 / 丢弃），TalkBack 才能区分。当前函数体：\n$body",
            body.contains("R.string.chat_queue_recall") && body.contains("R.string.chat_queue_promote"),
        )
        assertTrue(
            "队列条目不应再出现旧的 X 图标（Icons.Filled.Close）。当前函数体：\n$body",
            !body.contains("Icons.Filled.Close"),
        )
    }

    /** 纯判据：哪一行该给△。抽出成函数是为了能被真测，而不是只被源码扫到。 */
    @Test
    fun `only rows that are not already steers can be promoted`() {
        assertTrue(canPromote(QueueKind.FOLLOW_UP))
        assertTrue(canPromote(QueueKind.STEER).not())
    }

    /**
     * 改道 = **先确认仍在队列 → 移除队列里那条 → 再 steer**。少移除就是「队列里还有 + 又插一条」
     * = 重复发送（与撤回同一条不变量）。实现在 `Composer.kt` 的 `promoteToSteer`，
     * `ChatScreen` 只有一行调用点，所以守卫扫那个函数体。
     */
    @Test
    fun `promoting removes the queued copy before steering`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/Composer.kt")
            .readText(Charsets.UTF_8)
        val promote = KotlinSourceScanner.scan(source).singleOrNull { it.name == "promoteToSteer" }
            ?: error("Composer.kt 里找不到 promoteToSteer —— 守卫扫描落空，不等于通过")
        val body = functionBody(source, promote)

        val guard = body.indexOf("queue.none {")
        val remove = body.indexOf("session.removeQueued(", guard.coerceAtLeast(0))
        val steer = body.indexOf("session.steer(", remove.coerceAtLeast(0))
        assertTrue(
            "改道前必须确认那条仍在队列里（回合已结束、这条已被排空发出时，直接 steer 等于当场重发）。" +
                "当前 guard=$guard",
            guard >= 0,
        )
        assertTrue(
            "必须先 session.removeQueued 再 session.steer，否则这条会被发两次。" +
                "当前 remove=$remove、steer=$steer\n$body",
            remove >= 0 && steer > remove,
        )
    }

    /**
     * 撤回 = 回填输入框 + 回填图片 + **移除队列里那条** + 焦点回输入框。
     * 少了「移除」就是「输入框有、队列里还有」，再发一次会重复发送。
     */
    @Test
    fun `recall fills the composer, removes the queued copy, and focuses the input`() {
        val source = repoFile("app/src/main/java/dev/kair/app/ui/screens/chat/ChatScreen.kt")
            .readText(Charsets.UTF_8)
        val screen = KotlinSourceScanner.scan(source).singleOrNull { it.name == "ChatScreen" }
            ?: error("ChatScreen.kt 里找不到 ChatScreen —— 守卫扫描落空，不等于通过")
        val body = functionBody(source, screen)

        val recall = body.indexOf("recallPayload(")
        val remove = body.indexOf("session.removeQueued(", recall.coerceAtLeast(0))
        assertTrue(
            "ChatScreen 的撤回回调必须先 recallPayload 取回内容，再 session.removeQueued 移除队列条目。" +
                "当前函数体里 recallPayload 位置=$recall、removeQueued 位置=$remove。",
            recall >= 0 && remove > recall,
        )
        assertTrue(
            "撤回后文字必须回填输入框（input = recall.text）。当前函数体：\n$body",
            body.contains("input = recall.text"),
        )
        assertTrue(
            "撤回后图片必须回到输入框预览（pendingImage = recall.image），否则会静默吞掉用户附的图。当前函数体：\n$body",
            body.contains("pendingImage = recall.image"),
        )
        assertTrue(
            "撤回后焦点要回到输入框（focusRequester.requestFocus()）。当前函数体：\n$body",
            body.contains("focusRequester.requestFocus()"),
        )
        assertTrue(
            "撤回必须先确认那条**仍在队列里**（一帧竞态：FOLLOW_UP 已在回合末被排空发出，" +
                "removeQueued 对已出队的消息静默无效）——不在队列就返回，否则留下" +
                "「已发出 + 输入框又有一份」。当前函数体：\n$body",
            body.contains("queue.none { it.message.id == queued.message.id }"),
        )
    }

    /**
     * U18 的硬约束：系统通知仍走 `steer(systemNote = true)`，不得因为用户消息默认改成 `send`
     * 而跟着落到 `FOLLOW_UP`（那会等回合结束才投递，正是 U18 修掉的 188 秒延迟）。
     */
    @Test
    fun `system notes still go through steer with the system-note flag`() {
        val source = repoFile("app/src/main/java/dev/kair/app/app/BackgroundTaskDelivery.kt")
            .readText(Charsets.UTF_8)
        val sendNote = KotlinSourceScanner.scan(source).singleOrNull { it.name == "sendNote" }
            ?: error("BackgroundTaskDelivery.kt 里找不到 sendNote —— 守卫扫描落空，不等于通过")
        val body = functionBody(source, sendNote)

        assertTrue("系统通知必须走 session.steer。当前函数体：\n$body", body.contains("steer("))
        assertTrue("系统通知必须带 systemNote = true。当前函数体：\n$body", body.contains("systemNote = true"))
        assertTrue(
            "系统通知不得改走 session.send（忙时入 FOLLOW_UP = 等回合结束，U18 修掉的延迟会回来）。当前函数体：\n$body",
            !body.contains("session.send("),
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun functionBody(source: String, function: ScannedFunction): String =
        KotlinSourceScanner.stripped(source).lines()
            .subList(function.startLine - 1, function.endLine)
            .joinToString("\n")

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
