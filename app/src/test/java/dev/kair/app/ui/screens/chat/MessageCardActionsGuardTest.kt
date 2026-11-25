package dev.kair.app.ui.screens.chat

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 消息卡片「可见动作入口」的接线守卫（U24 追加需求：用户消息卡片也要有 `⋯`）。
 *
 * 为什么是源码守卫而不是纯函数用例：这条需求的全部内容就是「界面上摆不摆入口、
 * 与标签是不是同一行、点它弹的是不是那唯一的菜单」——把 `MoreActionsEntry` 的调用删掉，
 * 行为用例仍全绿，只有真机才看得出用户气泡又变回「必须长按」。同一弱点在 §B7.5 记过账。
 *
 * 结构类断言扫 `KotlinSourceScanner.stripped()`（剥注释后的源码）：留一行含
 * `MoreActionsEntry(` 的注释不该把守卫带绿。**但字面量必须扫原文** —— `stripped()`
 * 连字符串一起抹，用它找 `"⋯"` 永远是 0 处（本轮实测踩过，见第一条用例）。
 */
class MessageCardActionsGuardTest {

    private val listFile = "app/src/main/java/dev/kair/app/ui/screens/chat/MessageList.kt"
    private val actionsFile = "app/src/main/java/dev/kair/app/ui/screens/chat/MessageCardActions.kt"

    private fun funBody(file: String, name: String): String {
        val source = repoFile(file).readText(Charsets.UTF_8)
        val fn = KotlinSourceScanner.scan(source).singleOrNull { it.name == name }
            ?: error("$file 里找不到 $name —— 守卫扫描落空，不等于通过")
        return KotlinSourceScanner.stripped(source).lines()
            .subList(fn.startLine - 1, fn.endLine)
            .joinToString("\n")
    }

    /**
     * 用户回合（`Role.USER` 分支抽成了独立组合函数，免得 `MessageCard` 继续往上长）。
     *
     * 2026-11-17 U97 由 `UserBubbleCard` 改名 `UserMessage`；同日 U99 又把气泡放回来
     * （负责人：与 agent 同侧时「一眼分不出哪句是自己说的」）。无论外观怎么换，
     * **入口仍然必须有、仍然与时间同一行** —— 这两条是 U24 的原意，与换皮无关。
     */
    private fun userCard(): String = funBody(listFile, "UserMessage")

    /** agent 气泡分支：`MessageCard` 里 `else ->` 到函数体末尾。 */
    private fun agentBranch(): String {
        val body = funBody(listFile, "MessageCard")
        val start = body.indexOf("else ->")
        assertTrue("MessageCard 里找不到 agent 分支 `else ->` —— 守卫扫描落空，不等于通过", start >= 0)
        return body.substring(start)
    }

    @Test
    fun `both the user bubble and the agent bubble show the shared actions entry`() {
        val user = userCard()
        val agent = agentBranch()
        assertTrue("用户气泡必须有可见入口 MoreActionsEntry：\n$user", user.contains("MoreActionsEntry("))
        assertTrue("agent 气泡必须把入口接到底部行 AgentFooter：\n$agent", agent.contains("AgentFooter("))
        val footer = funBody(actionsFile, "AgentFooter")
        assertTrue("agent 气泡的底部行必须复用同一个入口：\n$footer", footer.contains("MoreActionsEntry("))
        assertTrue(
            "入口不许再用 BottomEnd 角标（会落在圆角上、与状态标签不平行）：\n$footer",
            !footer.contains("BottomEnd") && !agent.contains("BottomEnd"),
        )

        // 「只此一处实现」：字形不许再散回卡片里，否则两处的大小 / 对齐 / 无障碍会各自漂。
        // U105 起入口改用线稿 `PluginGlyph.More`，字面量「⋯」应当**归零**。
        // **必须扫原文并带上引号** —— `KotlinSourceScanner.stripped()` 连字符串一起抹。
        val glyphs = listOf(listFile, actionsFile)
            .sumOf { Regex("\"⋯\"").findAll(repoFile(it).readText(Charsets.UTF_8)).count() }
        assertEquals(
            "字面量「⋯」应当归零（入口已换线稿 PluginGlyph.More），实际 $glyphs 处",
            0,
            glyphs,
        )
        assertTrue(
            "入口必须使用线稿 PluginGlyph.More：\n$agent",
            repoFile(actionsFile).readText(Charsets.UTF_8).contains("PluginGlyph.More"),
        )
    }

    /**
     * U24 的平行口径：入口与发送时间**排在同一行**，不是叠在右下角压住时间；
     * agent 的入口在 `AgentFooter` 行里（与状态标签同排）。
     *
     * 2026-11-05 追加过「那一行不许 weight(1f)」，理由是气泡宽度要随内容收 ——
     * **2026-11-17 U97 撤销这一条**：气泡已经不在了，「你的话」现在是轨道上的一段正文，
     * 标签行本来就该占满整行、把 `⋯` 推到最右。仍然要守的是：两者同行、时间在前、
     * 也不许再拿 `BottomEnd` 把入口叠上去（那就是 U24 报的不平行）。
     * U99 气泡靠右回来，这一行仍然占满整行（靠右对齐），判据不变。
     */
    @Test
    fun `the user entry shares the timestamp row instead of overlaying it`() {
        val user = userCard()
        assertTrue("用户回合的「谁 · 时间」那一行必须是 Row：\n$user", user.contains("Row("))
        assertTrue(
            "入口不许再用 BottomEnd 角标叠加（那样会压住发送时间，就是 U24 报的不平行）：\n$user",
            !user.contains("BottomEnd"),
        )
        val timeIndex = user.indexOf("formatWhoTime")
        val entryIndex = user.indexOf("MoreActionsEntry(")
        assertTrue(
            "时间必须出现在入口之前（同一行、时间在左、⋯ 在最右）：time=$timeIndex entry=$entryIndex",
            timeIndex in 0 until entryIndex,
        )
        // 入口与时间同属一个 Row：从那一行的开头到入口之间不许再起一层 Row()。
        // 2026-11-17 U99 判据由 `Row(verticalAlignment` 放宽成 `Row(`：气泡回来之后那一行
        // 要占满整行（`Row(modifier = ..., verticalAlignment = ...)`），modifier 排到了第一位；
        // 再按旧串找是 0 处命中 —— 那是「扫描落空」，不是「结构正确」。
        val rowStart = user.lastIndexOf("Row(", entryIndex)
        assertTrue(
            "时间与入口必须在同一个 Row 里（中间不许再起一层 Row）：\n$user",
            rowStart in 0 until timeIndex,
        )
    }

    /** 入口不能是个假按钮：没有可执行动作时既不显示、也不挂长按手势。 */
    @Test
    fun `the entry and the long press are gated on the card actually having actions`() {
        val user = userCard()
        assertTrue(
            "用户气泡的动作集合就是「有正文可复制 / 可编辑」∪「可删除」：\n$user",
            Regex("""actionable\s*=\s*msg\.text\.isNotBlank\(\)\s*\|\|\s*deletable""").containsMatchIn(user),
        )
        assertTrue(
            "入口必须由 actionable 门控（空动作弹的是空弹窗，等于假入口）：\n$user",
            Regex("""if\s*\(\s*actionable\s*\)\s*\{\s*MoreActionsEntry""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(user),
        )
        assertTrue(
            "长按手势也要同一个门控：\n$user",
            Regex("""if\s*\(\s*actionable\s*\)\s*\{""").containsMatchIn(user) &&
                user.contains("detectTapGestures(onLongPress = { menuOpen = true })"),
        )
    }

    /**
     * 共用弹窗只有一套，入口的**视觉**仍是 24dp 圆形、**命中区**是 48dp（U105），
     * 并带无障碍标签（TalkBack 要能念出「更多操作」）。
     *
     * 2026-11-05 负责人拍板视觉由 48 缩到 24（48dp 塞在紫底里让一行字的气泡过大）；
     * U105 按界面审查把命中盒补回 48dp —— 视觉不变，只加外圈可点区域。
     */
    @Test
    fun `the entry keeps a 24dp visual inside a 48dp hit target and opens the one shared dialog`() {
        val source = repoFile(actionsFile).readText(Charsets.UTF_8)
        val entry = KotlinSourceScanner.scan(source).singleOrNull { it.name == "MoreActionsEntry" }
            ?: error("MessageCardActions.kt 里找不到 MoreActionsEntry —— 守卫扫描落空，不等于通过")
        val body = KotlinSourceScanner.stripped(source).lines()
            .subList(entry.startLine - 1, entry.endLine)
            .joinToString("\n")
        assertTrue("入口视觉边长必须仍是 24dp：\n$source", source.contains("MoreActionsEntrySize = 24.dp"))
        assertTrue("入口命中区必须是 48dp：\n$source", source.contains("MoreActionsHitSize = 48.dp"))
        assertTrue("视觉圆形必须按 24dp 绘制：\n$body", body.contains("size(MoreActionsEntrySize)"))
        assertTrue("外圈命中区必须按 48dp 布局：\n$body", body.contains("size(MoreActionsHitSize)"))
        assertTrue(
            "视觉圆必须是圆形（与插件页 IconButton 统一，不要再用圆角方框）：\n$body",
            body.contains("CircleShape"),
        )
        assertTrue(
            "必须有中文 contentDescription（chat_more_actions），TalkBack 才知道这是什么：\n$body",
            body.contains("R.string.chat_more_actions") && body.contains("contentDescription"),
        )

        // 一套弹窗：卡片侧不许再各自起 AlertDialog。
        val list = KotlinSourceScanner.stripped(repoFile(listFile).readText(Charsets.UTF_8))
        assertTrue("长按菜单只能有 MessageActionDialog 一处实现：\n$list", list.contains("MessageActionDialog("))
        assertTrue(
            "MessageList.kt 里不许再直接构造 AlertDialog（弹窗归 MessageCardActions.kt）",
            !Regex("""(?<![\w.])AlertDialog\(""").containsMatchIn(list),
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
