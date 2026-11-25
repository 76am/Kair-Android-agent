package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记忆面板编辑器折叠守卫（加单 2026-11-09，负责人拍板）：顶栏图标直达面板后，
 * 首屏只应看到设置项与「查看 / 编辑」按钮 —— 项目切换 chips 与记忆文件编辑器
 * **点击后才出现**，与旧设置面板「按钮 → 编辑器」的交互模型一致（只是编辑器
 * 换成可切项目的那一套，不再弹只能编辑当前会话的对话框）。
 *
 * 为什么按源码守：本仓没有 Compose UI 测试基建（无 createComposeRule），
 * 展开 / 收起在 JVM 单测里没有观测面；口径同 `AskUserCardLayoutGuardTest`。
 */
class MemoryPanelEditorGateGuardTest {

    private val source = repoFile("app/src/main/java/dev/kair/app/ui/plugins/builtin/MemoryPanel.kt")
        .readText(Charsets.UTF_8)

    @Test
    fun `editor and project chips stay collapsed behind the gate until opened`() {
        val body = functionBody("MemoryPanel")
        val lines = body.lines()
        val decl = indexOf(lines, 0, "var editing", "rememberPanelBackOpen()")
        assertTrue(
            "MemoryPanel 必须声明折叠态（var editing by rememberPanelBackOpen()）——" +
                "默认展开等于回到「进面板就摊开编辑器」，正是本批要改掉的形状；" +
                "用 rememberPanelBackOpen 而不是裸 remember { mutableStateOf(false) }：" +
                "状态与返回接管必须同源（U53 返工，2026-11-11）。函数体：\n$body",
            decl >= 0,
        )
        val gate = indexOf(lines, maxOf(decl, 0), "if (!editing)")
        assertTrue("找不到 if (!editing) 折叠闸：编辑器区必须放在它的展开分支里。函数体：\n$body", gate >= 0)
        val chips = lines.indexOfFirst { it.contains("FilterChip") }
        assertTrue(
            "项目切换 FilterChip 行只能出现在折叠闸之后（chips 行 $chips，闸行 $gate）；" +
                "chips 常显 = 按钮没把跨项目入口收进去。",
            chips > gate,
        )
        val editor = lines.indexOfFirst { it.contains("FileEditorBody(") }
        assertTrue("FileEditorBody 只能出现在折叠闸之后（编辑器行 $editor，闸行 $gate）。", editor > gate)
    }

    @Test
    fun `collapsed state renders only the reused view-edit button`() {
        val body = functionBody("MemoryPanel")
        val lines = body.lines()
        val gate = indexOf(lines, 0, "if (!editing)")
        val elseOpen = indexOf(lines, maxOf(gate, 0) + 1, "} else {")
        assertTrue("折叠闸之后必须有 else 展开分支。函数体：\n$body", gate >= 0 && elseOpen > gate)
        val button = indexOfInRange(lines, gate + 1, elseOpen, "OutlinedButton")
        assertTrue("收起态必须是 OutlinedButton（与声明式文件字段同款），位置 $button 不在 ($gate, $elseOpen) 区间内。", button >= 0)
        val label = indexOfInRange(lines, gate + 1, elseOpen, "plugins_settings_file_open")
        assertTrue(
            "按钮文案必须复用现成资源 plugins_settings_file_open（查看 / 编辑）——" +
                "用户认识的正是这个按钮，另造文案等于第二个入口模型。",
            label >= 0,
        )
        val toggle = indexOfInRange(lines, gate + 1, elseOpen, "editing = true")
        assertTrue("按钮 onClick 必须把折叠态置真（editing = true）。", toggle >= 0)
    }

    @Test
    fun `expanded editor offers a way back to the collapsed panel`() {
        val body = functionBody("MemoryPanel")
        val lines = body.lines()
        val editor = lines.indexOfFirst { it.contains("FileEditorBody(") }
        assertTrue("MemoryPanel 里找不到 FileEditorBody 调用。函数体：\n$body", editor >= 0)
        val back = indexOf(lines, editor, "onBack", "editing = false")
        assertTrue(
            "展开的编辑器必须传 onBack = { editing = false }：FileEditorBody 会据此渲染顶部「返回」，" +
                "收起后设置区仍在原位 —— 否则编辑器只有进门没有出门。",
            back >= 0,
        )
    }

    /** 从 [from] 起找第一行同时包含全部 [needles] 的行号；找不到返回 -1。 */
    private fun indexOf(lines: List<String>, from: Int, vararg needles: String): Int =
        lines.indices.drop(from).firstOrNull { i -> needles.all { lines[i].contains(it) } } ?: -1

    private fun indexOfInRange(lines: List<String>, fromInclusive: Int, toExclusive: Int, vararg needles: String): Int {
        val hit = indexOf(lines, fromInclusive, *needles)
        return if (hit in fromInclusive until toExclusive) hit else -1
    }

    private fun functionBody(name: String): String {
        val scanned = KotlinSourceScanner.scan(source).singleOrNull { it.name == name }
            ?: error("MemoryPanel.kt 里找不到 $name —— 守卫扫描落空，不等于通过")
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
