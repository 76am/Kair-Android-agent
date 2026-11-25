package dev.kair.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Android 侧 `java.lang.Process` 没有 `pid()` / `ProcessHandle`：`resolvePid()` 必须能从
 * `java.lang.UNIXProcess.toString()` 的 `Process[pid=N, ...]` 里取回 PID。
 *
 * 这段字符串是 Android SDK 源码 API 26–35 的稳定形状（`java/lang/UNIXProcess.java`），
 * 但仍是实现细节 —— 因此单独把解析器钉住；取不到时按 0 处理（退回旧行为，绝不误杀）。
 */
class ProcessPidTest {

    @Test
    fun `parses the pid from the running android description`() {
        assertEquals(1234L, parseProcessPid("Process[pid=1234, hasExited=false]"))
    }

    @Test
    fun `parses the pid from the exited android description`() {
        assertEquals(42L, parseProcessPid("Process[pid=42 ,hasExited=true, exitcode=0]"))
    }

    @Test
    fun `rejects descriptions without a pid`() {
        assertEquals(0L, parseProcessPid("Process[pid=, hasExited=false]"))
        assertEquals(0L, parseProcessPid("not a process"))
        assertEquals(0L, parseProcessPid(""))
    }

    @Test
    fun `resolvePid falls back to the description when pid is unavailable`() {
        // 真机上 Process::pid 反射失败；这里用 pid() 返回 0 的子类模拟同一条路径。
        assertEquals(4242L, FakeProcess("Process[pid=4242, hasExited=false]").resolvePid())
    }

    @Test
    fun `resolvePid and killTree never throw when toString is hostile`() {
        // resolvePid() 是 killTree 的默认参数，在 killTree 函数体的 runCatching **之外**求值：
        // 自定义 Process 的 toString() 抛错会直接穿透取消路径的「绝不抛错」契约
        // （与当年真机 NoSuchMethodError 崩在停止按钮同形）。取不到 pid 可以，抛错不行。
        val process = object : FakeProcess("Process[pid=7, hasExited=false]") {
            override fun toString(): String = throw IllegalStateException("hostile toString")
        }
        assertEquals(0L, process.resolvePid())
        process.killTree()
    }

    private open class FakeProcess(private val description: String) : Process() {
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = Unit
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun pid(): Long = 0L
        override fun isAlive(): Boolean = true
        override fun toString(): String = description
    }
}
