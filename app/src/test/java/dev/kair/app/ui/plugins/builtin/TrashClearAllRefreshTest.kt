package dev.kair.app.ui.plugins.builtin

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回收站「一键清空」后列表必须当场刷新（加单 2026-11-12，负责人真机报的体验问题）。
 *
 * 症状：在回收站根目录点「一键清空」，顶部摘要变成 `0 条 · 0 B`、提示也写「已清空 1 条」，
 * **但列表里的条目还在**，退出面板再进来才消失。单条删除（行尾菜单 → 删除）却是当场消失的。
 *
 * 根因是两个独立的刷新计数：
 * - 面板层 `TrashPanel.refresh` 只喂顶部摘要的 `LaunchedEffect(root, refresh)`；
 * - 列表由 `FileList` 自己的 `entries` 管理，key 是 `remember(backend, dir)`。
 *
 * 在根目录清空时 `dir = root.path` 赋的是同一个值、`backend` 也没变 → `FileList` 的
 * `LaunchedEffect` 不重启 → 列表停在旧快照。子目录里清空会先跳回根（`dir` 变了）所以看起来正常，
 * 单条删除由 `FileList` 内的 `run()` 推动它自己的 `refresh`，所以也正常。
 *
 * 为什么按源码守：本仓没有 Compose UI 测试基建，重组行为在 JVM 里没有观测面；
 * 口径同 `MemoryPanelEditorGateGuardTest` / `AskUserCardLayoutGuardTest`。
 */
class TrashClearAllRefreshTest {

    private val filesUi = repoFile("app/src/main/java/dev/kair/app/ui/plugins/builtin/WorkspaceFilesUi.kt")
        .readText(Charsets.UTF_8)
    private val trash = repoFile("app/src/main/java/dev/kair/app/ui/plugins/builtin/TrashPlugin.kt")
        .readText(Charsets.UTF_8)

    @Test
    fun `the file list takes an external refresh key`() {
        assertTrue(
            "FileList 必须暴露 refreshKey 参数：面板层的外部变更（回收站一键清空）无法通过 " +
                "dir / backend 的变化通知到列表，只能靠这个键。FileList 签名：\n" +
                filesUi.lineSequence().filter { it.contains("fun FileList(") }.joinToString("\n"),
            filesUi.contains("refreshKey: Int"),
        )
    }

    @Test
    fun `both the state and the fetch effect are keyed by it`() {
        // 只加参数不纳进 key 等于没加：状态与拉取必须同时跟它走。
        assertTrue(
            "FileList 里 `var entries` / `var refresh` 的 remember 必须带 refreshKey，" +
                "否则外部刷新只会重建一次 UI、列表内容仍来自旧快照。",
            filesUi.contains("remember(backend, dir, refreshKey)"),
        )
        assertTrue(
            "拉取目录的 LaunchedEffect 必须同时带内部 refresh 与外部 refreshKey ——" +
                "只带 refreshKey 会让列表内的删除 / 新建不再刷新，只带 refresh 就是本 bug 本身。",
            filesUi.contains("LaunchedEffect(backend, dir, refresh, refreshKey)"),
        )
    }

    @Test
    fun `the trash panel hands its own refresh counter to the list`() {
        val callSite = trash.lineSequence()
            .dropWhile { !it.contains("FileList(") }
            .take(12)
            .joinToString("\n")
        assertTrue(
            "回收站面板调用 FileList 时必须传 refreshKey = refresh —— 它的 refresh++ 现在只喂顶部摘要，" +
                "不传下去就等于「清空了、摘要对了、列表还是旧的」。FileList 调用点：\n$callSite",
            callSite.contains("refreshKey = refresh"),
        )
    }

    private fun repoFile(relativePath: String): File = findRepoRoot().resolve(relativePath)

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（从 ${File(".").absolutePath} 往上没有 docs/ + app/）")
    }
}
