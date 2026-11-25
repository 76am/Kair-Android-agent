package dev.kair.app.core.proot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「命令 ↔ 退出帧」配对队列（U16）：退出帧永远结算最早那条命令，被取消的命令自己吃掉
 * 自己的帧，不会把退出码与输出发给下一条。
 *
 * 纯状态机，不碰进程与线程，所以 Windows / Linux 都跑（不是 B 层用例）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecFrameQueueTest {

    @Test
    fun `an exit frame settles the oldest command, not the one queued behind it`() {
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val first = queue.enqueue()
        val second = queue.enqueue()

        assertSame(first, queue.completeHead(7))
        assertTrue(first.done.isCompleted)
        assertEquals(7, first.done.getCompleted())
        assertFalse("第二条命令不能拿到前一条的退出帧", second.done.isCompleted)

        assertSame(second, queue.completeHead(0))
        assertEquals(0, second.done.getCompleted())
        assertFalse(queue.isBusy())
    }

    @Test
    fun `output lines follow the running command and then the next one`() {
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val first = queue.enqueue()
        val second = queue.enqueue()

        queue.appendOutput("from-first")
        queue.completeHead(0)
        assertEquals("from-first\n", queue.outputOf(first))

        queue.appendOutput("from-second")
        assertEquals("from-first\n", queue.outputOf(first))
        assertEquals("from-second\n", queue.outputOf(second))
    }

    @Test
    fun `a cancelled command still consumes its own frame`() {
        // 取消发生在 await 上：条目不从队列里摘走（这正是修复的关键），所以帧照常结算它。
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val abandoned = queue.enqueue()
        val next = queue.enqueue()

        queue.completeHead(1)
        assertEquals(1, abandoned.done.getCompleted())
        assertFalse(next.done.isCompleted)

        queue.appendOutput("next-output")
        queue.completeHead(0)
        assertEquals("next-output\n", queue.outputOf(next))
        assertEquals(0, next.done.getCompleted())
    }

    @Test
    fun `a timed out command stays queued and consumes its own frame`() {
        // U60：exec 超时只返回结果，不 abandon 条目 —— 命令已经写进 shell，它的退出帧
        // 终将结算它；撤掉条目会让下一帧结算给下一条（配对错位，症状比原来更隐蔽）。
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val timedOut = queue.enqueue()
        val next = queue.enqueue()

        queue.appendOutput("late-output")
        queue.completeHead(0)
        assertEquals(0, timedOut.done.getCompleted())
        assertFalse("下一条不能拿到上一条的退出帧", next.done.isCompleted)
        assertEquals("late-output\n", queue.outputOf(timedOut))

        queue.appendOutput("next-output")
        queue.completeHead(3)
        assertEquals(3, next.done.getCompleted())
        assertEquals("next-output\n", queue.outputOf(next))
        assertFalse(queue.isBusy())
    }

    @Test
    fun `a command that never reached the shell is withdrawn instead of eating the next frame`() {
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val failedWrite = queue.enqueue()
        queue.abandon(failedWrite)
        val next = queue.enqueue()

        queue.appendOutput("next-output")
        assertSame(next, queue.completeHead(5))
        assertFalse(failedWrite.done.isCompleted)
        assertEquals(5, next.done.getCompleted())
        assertEquals("next-output\n", queue.outputOf(next))
    }

    @Test
    fun `a frame with nothing queued is dropped and the queue keeps working`() {
        val queue = ExecFrameQueue(outputLimit = 1_000)
        assertNull(queue.completeHead(3))

        val next = queue.enqueue()
        queue.appendOutput("after")
        queue.completeHead(0)
        assertEquals("after\n", queue.outputOf(next))
        assertEquals(0, next.done.getCompleted())
    }

    @Test
    fun `stopping the session completes every queued command and clears busy`() {
        val queue = ExecFrameQueue(outputLimit = 1_000)
        val first = queue.enqueue()
        val second = queue.enqueue()
        assertTrue(queue.isBusy())

        queue.completeAll(null)
        assertTrue(first.done.isCompleted)
        assertNull(first.done.getCompleted())
        assertNull(second.done.getCompleted())
        assertFalse(queue.isBusy())
        assertNull(queue.completeHead(0))
    }

    @Test
    fun `each command keeps only the tail of its own output`() {
        val queue = ExecFrameQueue(outputLimit = 8)
        val entry = queue.enqueue()
        queue.appendOutput("0123456789")
        // 缓冲区是「10 位 + 换行」裁到 8：留下 3456789 + 换行。
        assertEquals("3456789", queue.outputOf(entry).trimEnd())
    }

    @Test
    fun `a pasted block becomes one command per line`() {
        // 一行一个退出帧是配对的前提，所以整块输入必须拆开逐条下发。
        assertEquals(
            listOf("cd /projects", "ls -a", "echo done"),
            commandLines("cd /projects\n\n ls -a \necho done"),
        )
    }

    @Test
    fun `a single line stays a single command and blanks disappear`() {
        assertEquals(listOf("pwd"), commandLines("  pwd  "))
        assertEquals(emptyList<String>(), commandLines("   \n\n"))
    }
}
