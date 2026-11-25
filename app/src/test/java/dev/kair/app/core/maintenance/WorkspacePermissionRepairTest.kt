package dev.kair.app.core.maintenance

import dev.kair.app.core.assumeLinux
import dev.kair.app.core.tools.CommandOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePermissionRepairTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `container relative keeps workspace paths relative and rejects outside paths`() {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        assertEquals(".", containerRelative(ws, ws))
        assertEquals("./.kair/memory.md", containerRelative(ws, File(ws, ".kair/memory.md")))
        assertNull(containerRelative(ws, File(ws.parentFile, "elsewhere.txt")))
    }

    @Test
    fun `resolve target treats leading slash as absolute`() {
        val ws = tmp.newFolder("ws")
        assertEquals(File(ws, ".kair/memory.md"), resolveTarget(ws, ".kair/memory.md"))
        assertEquals(File("/data/x"), resolveTarget(ws, "/data/x"))
    }

    @Test
    fun `needs repair ignores missing files and healthy files`() {
        val ws = tmp.newFolder("ws")
        assertFalse(needsRepair(File(ws, "missing.md")))
        val file = File(ws, "normal.md").apply { writeText("x") }
        assertFalse(needsRepair(file))
        assertFalse(needsRepair(ws))
    }

    @Test
    fun `repair chowns only broken declared paths inside the workspace`() = runBlocking {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        File(ws, ".kair").mkdirs()
        val memory = File(ws, ".kair/memory.md").apply { writeText("x") }
        File(ws, "src").mkdirs()
        File(ws, "src/a.txt").writeText("x")
        val runner = RootRunner()
        val progress = mutableListOf<String>()

        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { listOf(".kair/memory.md", "src/a.txt", "/tmp/outside") },
            rootRun = runner::run,
            onProgress = { progress += it },
            // 模拟：只有记忆文件是 root 留下、应用碰不了的。
            shouldRepair = { it == memory },
        )

        assertEquals(1, report.checked)
        assertEquals(1, report.repaired)
        assertEquals(0, report.failed)
        assertEquals(listOf("./.kair/memory.md"), progress)
        assertEquals(1, runner.commands.size)
        val command = runner.commands.single()
        assertTrue(command.contains("chown -R 10234:10234 "))
        // 真 su 跑在宿主侧：命令用绝对路径，不再经容器相对路径。
        assertTrue(command.contains("'${memory.absolutePath}'"))
        // immutable 会让 chown 失败，必须先清标志。
        assertTrue(command.indexOf("chattr -i") < command.indexOf("chown -R"))
        // 自证：chown 之后必须 stat 属主，假的成功（proot）在这里被识破。
        assertTrue(command.indexOf("chown -R") < command.indexOf("stat -c %u"))
    }

    @Test
    fun `detects a broken child even when its parent directory looks healthy`() = runBlocking {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        File(ws, ".kair").mkdirs()
        // 真实场景：.kair 目录本身是应用自己的（健康），只有 root 替换过的 memory.md 坏掉。
        val memory = File(ws, ".kair/memory.md").apply { writeText("x") }
        val runner = RootRunner()

        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { emptyList() },
            rootRun = runner::run,
            shouldRepair = { it == memory },
        )

        assertEquals(1, report.checked)
        assertEquals(1, report.repaired)
        assertEquals(0, report.failed)
        assertTrue(runner.commands.single().contains("'${memory.absolutePath}'"))
    }

    @Test
    fun `shared trash is repaired through its host path`() = runBlocking {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        val trash = tmp.newFolder("trash")
        val entry = File(trash, "20261018-162801/ocx").apply {
            File(trash, "20261018-162801").mkdirs()
            writeText("x")
        }
        val runner = RootRunner()

        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { emptyList() },
            rootRun = runner::run,
            shouldRepair = { it == entry },
            extraMounts = listOf(trash to "/trash"),
        )

        assertEquals(1, report.checked)
        assertEquals(1, report.repaired)
        assertEquals(
            "command -v chattr >/dev/null 2>&1 && chattr -i -R '${entry.absolutePath}' 2>/dev/null; " +
                "chown -R 10234:10234 '${entry.absolutePath}' && stat -c %u '${entry.absolutePath}'",
            runner.commands.single(),
        )
    }

    /**
     * A-5（U78 核心判据）：假 root 的 chown 报「成功」不算修复。
     *
     * proot 的 `fake_id0` 会让 `chown` 返回 0，但 `stat -c %u` 仍是 0 —— 旧实现只看
     * `exitCode`，于是界面报「已修复」。现在末尾的 stat 是自证：属主没变就不计 repaired。
     */
    @Test
    fun `chown success without real root is not counted as repaired`() = runBlocking {
        val ws = tmp.newFolder("ws")
        File(ws, ".kair").mkdirs()
        val memory = File(ws, ".kair/memory.md").apply { writeText("x") }
        // 假 root：chown 命令退出 0，但 stat 报 root（0），不是应用 uid。
        val fakeRoot = RootRunner(output = "0", exitCode = 0)
        val cannotAdopt: suspend (File) -> Boolean = { false }

        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { listOf(".kair/memory.md") },
            rootRun = fakeRoot::run,
            shouldRepair = { it == memory },
            adopt = cannotAdopt,
        )

        assertEquals(1, report.checked)
        assertEquals("假成功不得计入 repaired", 0, report.repaired)
        assertEquals(1, report.failed)
    }

    @Test
    fun `repair counts failed chown when adoption is impossible`() = runBlocking {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        File(ws, ".kair").mkdirs()
        val memory = File(ws, ".kair/memory.md").apply { writeText("x") }
        val declared = { _: File -> listOf(".kair/memory.md") }
        val broken = { _: File -> false }
        // 只让记忆文件需要修复；.kair 目录本身不触发。
        val onlyMemory = { f: File -> f == memory }
        // 模拟 600 / 目录这类无法降级的场景：chown 不可用就只能计失败。
        val cannotAdopt: suspend (File) -> Boolean = { false }

        val failed = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = declared,
            rootRun = RootRunner(exitCode = 1)::run,
            shouldRepair = onlyMemory,
            adopt = cannotAdopt,
        )
        assertEquals(1, failed.failed)
        assertEquals(0, failed.repaired)

        val noRunner = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = declared,
            rootRun = null,
            shouldRepair = onlyMemory,
            adopt = cannotAdopt,
        )
        assertEquals(1, noRunner.failed)

        // 健康文件不进入修复：runner 与结果都不该被使用。
        val normal = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = declared,
            rootRun = RootRunner()::run,
            shouldRepair = broken,
        )
        assertEquals(0, normal.checked)
        assertEquals(0, normal.failed)
    }

    @Test
    fun `failed or missing root falls back to app-side adoption`() = runBlocking {
        assumeLinux("依赖 chmod / chown 语义")
        val ws = tmp.newFolder("ws")
        File(ws, ".kair").mkdirs()
        val memory = File(ws, ".kair/memory.md").apply { writeText("remember me") }
        val onlyMemory = { f: File -> f == memory }

        // chown 失败（root 丢失 / su 被拒）时，可读文件仍能以应用身份重建。
        val afterFailedChown = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { listOf(".kair/memory.md") },
            rootRun = RootRunner(exitCode = 1)::run,
            shouldRepair = onlyMemory,
        )
        assertEquals(1, afterFailedChown.repaired)
        assertEquals(0, afterFailedChown.failed)
        assertEquals("remember me", memory.readText())

        // 整个真 root 执行器不在场也一样能降级修复。
        val withoutRunner = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { listOf(".kair/memory.md") },
            rootRun = null,
            shouldRepair = onlyMemory,
        )
        assertEquals(1, withoutRunner.repaired)
        assertEquals(0, withoutRunner.failed)
    }

    @Test
    fun `adoption refuses directories and preserves readable file content`() = runBlocking {
        val ws = tmp.newFolder("ws")
        val file = File(ws, "memory.md").apply { writeText("remember me") }

        assertFalse(adoptWithoutRoot(ws))
        assertTrue(adoptWithoutRoot(file))
        assertEquals("remember me", file.readText())
        assertFalse(needsRepair(file))
    }

    @Test
    fun `full workspace chowns the root without a local accessibility check`() = runBlocking {
        val ws = tmp.newFolder("ws")
        val runner = RootRunner()

        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(ws),
            declaredPaths = { emptyList() },
            rootRun = runner::run,
            fullWorkspace = true,
            shouldRepair = { false },
        )

        assertEquals(1, report.checked)
        assertEquals(1, report.repaired)
        assertEquals(
            "command -v chattr >/dev/null 2>&1 && chattr -i -R '${ws.absolutePath}' 2>/dev/null; " +
                "chown -R 10234:10234 '${ws.absolutePath}' && stat -c %u '${ws.absolutePath}'",
            runner.commands.single(),
        )
    }

    @Test
    fun `non-directory workspace is skipped`() = runBlocking {
        val file = File(tmp.root, "not-a-dir").apply { writeText("x") }
        val report = WorkspacePermissionRepair(uid = 10234).repair(
            workspaces = listOf(file),
            declaredPaths = { listOf(".kair") },
            rootRun = RootRunner()::run,
            fullWorkspace = true,
        )
        assertEquals(0, report.checked)
    }

    /** 真 su 的假件：记录命令，按给定输出 / 退出码回答（默认输出应用 uid = 成功）。 */
    private class RootRunner(
        private val output: String = "10234",
        private val exitCode: Int = 0,
    ) {
        val commands = mutableListOf<String>()

        suspend fun run(command: String): CommandOutput {
            commands += command
            return CommandOutput(output, exitCode)
        }
    }
}
