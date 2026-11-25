package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回收站「清空只有一条路径」的源码守卫（U38 判据 7）。
 *
 * 为什么是源码守卫而不是行为用例：两个清空入口（面板按钮、设置分区 Action）都不起真容器、
 * 也没有可断言的界面壳子，把它们改成各删各的，单测照样全绿 —— 而那时「同一件事两套实现」
 * （`AGENTS.md` §10.4）与「一键清空放大谎报」（任务文档 §6 第 1 条）就都回来了。
 * 同样地，文件管理器里那颗跳转按钮属于**被替换的旧路径**（§10.3），删干净没有也靠这里钉。
 *
 * 一律扫 `KotlinSourceScanner.stripped()`（剥注释后的源码）：注释里提到 `emptyTrash` 不计，
 * **扫描落空不等于通过**（找不到文件即 error）。
 */
class TrashClearPathGuardTest {

    private val pluginPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/TrashPlugin.kt"
    private val maintenancePath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/TrashMaintenance.kt"
    private val filesPluginPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/WorkspaceFilesPlugin.kt"
    private val filesUiPath = "app/src/main/java/dev/kair/app/ui/plugins/builtin/WorkspaceFilesUi.kt"

    private fun stripped(path: String): String = KotlinSourceScanner.stripped(repoFile(path).readText(Charsets.UTF_8))

    /** 两个清空入口共用同一个执行体：面板与设置 Action 各一次 `emptyTrash(`。 */
    @Test
    fun `both clear entries go through the one emptyTrash body`() {
        val body = stripped(pluginPath)
        val clearCalls = Regex("""emptyTrash\(""").findAll(body).count()
        assertTrue(
            "面板按钮与设置 Action 必须都调 emptyTrash（实际 $clearCalls 处）；" +
                "多一处 = 第二套清空实现，少一处 = 某个入口不再清空",
            clearCalls == 2,
        )
        assertTrue(
            "自动清理轮询只有 sweepTrash 一处调用点",
            Regex("""sweepTrash\(""").findAll(body).count() == 1,
        )
    }

    /** 删除动作只有一个实现点：逐条 `backend.delete` 只出现在 purgeTrash 里。 */
    @Test
    fun `the delete loop exists only inside purgeTrash`() {
        val body = stripped(maintenancePath)
        assertTrue(
            "`backend.delete(` 在 TrashMaintenance 里只许出现一次（实际 ${count(body, "backend.delete(")} 次）：" +
                "删除动作有第二处就意味着残留复查可以被绕过",
            count(body, "backend.delete(") == 1,
        )
        assertTrue(
            "声明只有一份 purgeTrash",
            count(body, "fun purgeTrash(") == 1,
        )
        // 清空入口不许自己删：TrashPlugin 里不得直接出现 purgeTrash 调用（全经 emptyTrash / sweepTrash）。
        assertTrue(
            "TrashPlugin 不许自己调 purgeTrash：那样就绕过了共用的清空执行体",
            count(stripped(pluginPath), "purgeTrash(") == 0,
        )
        assertTrue(
            "不许在插件里另写递归删除",
            count(stripped(pluginPath), "deleteRecursively(") == 0,
        )
    }

    /** 被替换的旧路径必须真删掉：文件管理器不再持有回收站跳转（§10.3）。 */
    @Test
    fun `the file manager no longer carries the trash jump`() {
        val files = stripped(filesPluginPath) + stripped(filesUiPath)
        assertTrue(
            "跳转按钮与它的 trashPath 参数都要消失，否则回收站有两个入口、两套实现",
            count(files, "trashPath") == 0 && !files.contains("R.string.files_trash"),
        )
        assertTrue(
            "文件管理器与回收站共用同一份 FileList（一个概念一个实现点）",
            count(stripped(filesPluginPath), "FileList(") == 1 && count(stripped(filesUiPath), "fun FileList(") == 1,
        )
    }

    /**
     * 总开关的门控在**唯一读取点**上，且只挡自动清理、不挡手动清空。
     *
     * 为什么这里要源码守卫：轮询协程跑在 `ctx.scope` 里，JVM 单测既起不了插件作用域也没有容器，
     * 「开关关着却仍在删」这条只能按接线钉。行为侧的等价判据在 `TrashMaintenanceTest` 的
     * `the master switch off makes every rule inactive`（关着 → 三条全 0 → 真条目一条都不动），
     * 两条合起来才是完整的：那条测「全 0 不删」，这条测「循环真的用的是那个读取点」。
     */
    @Test
    fun `the master switch gates the auto sweep and nothing else`() {
        val body = stripped(pluginPath)
        assertTrue(
            "轮询里那唯一一处 sweepTrash 必须被 allOff 门控挡住：\n${lineOf(body, "sweepTrash(")}",
            Regex("""if \(!limits\.allOff\) sweepTrash\(""").containsMatchIn(body),
        )
        assertTrue(
            "开关只在 trashLimitsOf 一处读（两处自动路径共用同一个读取点，漏读一处就是「以为没开却在删」）",
            count(body, "getBoolean(TRASH_KEY_ENABLED") == 1,
        )
        assertTrue(
            "两个手动入口都不许经阈值读取：关了开关照样要能一键清空",
            count(body, "emptyTrash(root, backend)") == 2 && count(body, "fun trashLimitsOf(") == 1,
        )
    }

    private fun lineOf(source: String, needle: String): String =
        source.lines().filter { it.contains(needle) }.joinToString("\n").ifEmpty { "（一处都没扫到）" }

    private fun count(source: String, needle: String) = Regex(Regex.escape(needle)).findAll(source).count()

    private fun repoFile(path: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return File(dir, path)
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），无法定位 $path")
    }
}
