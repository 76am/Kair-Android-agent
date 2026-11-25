package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 受保护进程：登记的持久化与「重启后按需拉起」的脚本生成。
 *
 * 判据的重点不是「能存能读」，而是两件容易写错的事：
 * - 判重要靠**命令行完全相等**，不能靠 PID（重启后必变）；
 * - 恢复前必须先判「已经在跑」—— 这些进程在清扫时被跳过，所以通常还活着，
 *   无条件再起一个就是两份抢同一端口。
 */
class ProtectedProcessesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File = tmp.newFolder("storage")) = ProtectedProcessStore(root)

    @Test
    fun `add is idempotent per command and survives a reload`() {
        val root = tmp.newFolder("storage")
        val store = ProtectedProcessStore(root)

        val first = store.add("python3 -m http.server 8080")
        assertNotNull("首次登记必须成功", first)
        assertNull("同一条命令行再登记应返回 null（不重复）", store.add("python3 -m http.server 8080"))
        assertNotNull("不同命令行应另建一条", store.add("node server.js"))
        assertEquals(2, store.entries.value.size)

        // 换一个实例从磁盘读回：跨 App 进程就是这样恢复的。
        val reopened = ProtectedProcessStore(root)
        assertEquals(
            listOf("python3 -m http.server 8080", "node server.js"),
            reopened.entries.value.map { it.command },
        )
    }

    @Test
    fun `remove drops only the requested entry`() {
        val store = store()
        val a = store.add("python3 -m http.server 8080")!!
        store.add("node server.js")

        store.remove(a.id)

        assertEquals(listOf("node server.js"), store.entries.value.map { it.command })
    }

    @Test
    fun `blank command is rejected`() {
        val store = store()
        assertNull(store.add("   "))
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `restore returns null when nothing is registered`() {
        assertNull("没有登记项时不该生成 exec 命令（不白占一次终端转录）", ProtectedProcesses.restoreCommand(emptyList()))
    }

    @Test
    fun `restore checks whether the process is already running before starting it`() {
        val entry = ProtectedProcess("pp-1", "python3 -m http.server 8080", 0L)
        val command = ProtectedProcesses.restoreCommand(listOf(entry))!!

        // 顺序是语义：先扫 /proc 判在不在跑（`running=1`），只有不在跑才 setsid 起来。
        val scan = command.indexOf("running=0")
        val detect = command.indexOf("[ \"\$c\" = \"\$pcmd\" ] && { running=1; break; }")
        val branch = command.indexOf("if [ \"\$running\" = 1 ]")
        val start = command.indexOf("setsid sh -c \"\$pcmd\"")
        assertTrue("缺「已在跑」的探测", scan >= 0 && detect > scan)
        assertTrue("缺按 running 的分支", branch > detect)
        assertTrue("缺重新启动", start > branch)
        // 命令行原样带上，且走 sh -c（不做词分割，否则 `sh -c "a b"` 会被拆坏）。
        assertTrue(command.contains("pcmd='python3 -m http.server 8080'"))
        assertTrue(command.contains("sh -c \"\$pcmd\""))
        assertTrue(command.contains(ProtectedProcesses.logPath("pp-1")))
    }

    @Test
    fun `restore script is installed as a file because the session reads one line at a time`() {
        val rootfs = tmp.newFolder("rootfs")
        val entry = ProtectedProcess("pp-1", "python3 -m http.server 8080", 0L)

        val path = ProtectedProcesses.installScript(rootfs, listOf(entry))

        // 会话入口是逐行 read + eval：多行脚本必须落盘后再 `sh <file>`，
        // 直接把脚本文本交给 session.exec 会被拆成多条残缺命令。
        assertEquals(ProtectedProcesses.RESTORE_SCRIPT, path)
        val script = File(rootfs, ProtectedProcesses.RESTORE_SCRIPT.removePrefix("/"))
        assertTrue("恢复脚本必须落进 rootfs", script.exists())
        assertTrue(script.readText().contains("setsid sh -c \"\$pcmd\""))
        assertNull("没有登记项不写文件", ProtectedProcesses.installScript(rootfs, emptyList()))
    }

    @Test
    fun `restore keeps every registered entry`() {
        val entries = listOf(
            ProtectedProcess("pp-1", "python3 -m http.server 8080", 0L),
            ProtectedProcess("pp-2", "node server.js", 0L),
        )

        val command = ProtectedProcesses.restoreCommand(entries)!!

        assertTrue(command.contains("pcmd='python3 -m http.server 8080'"))
        assertTrue(command.contains("pcmd='node server.js'"))
        // 每个条目生成独立一段（各自扫 /proc、各自起）：两段 = 两次 pcmd 赋值。
        assertEquals("两个条目必须各自生成一段", 2, Regex("pcmd='").findAll(command).count())
    }
}
