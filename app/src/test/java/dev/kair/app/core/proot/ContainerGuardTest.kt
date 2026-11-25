package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器内存守卫的阈值与退出分类：真机「重计算 → 闪退」的修复要靠这三件事分开——
 * 内存中止、信号崩溃、普通超时。阈值是产品取舍，用用例钉住，别在改动里悄悄漂。
 */
class ContainerGuardTest {

    private val mb = 1024L
    private val gb = 1024L * 1024L

    @Test
    fun `low available memory with a heavy tree aborts`() {
        assertTrue(
            shouldAbortForMemory(
                totalKb = 4 * gb,
                availableKb = 200 * mb,
                treeRssKb = 600 * mb,
            ),
        )
    }

    @Test
    fun `enough available memory does not abort`() {
        assertFalse(
            shouldAbortForMemory(
                totalKb = 4 * gb,
                availableKb = 800 * mb,
                treeRssKb = 600 * mb,
            ),
        )
    }

    @Test
    fun `light tree is not aborted even when the device is tight`() {
        assertFalse(
            shouldAbortForMemory(
                totalKb = 4 * gb,
                availableKb = 200 * mb,
                treeRssKb = 100 * mb,
            ),
        )
    }

    @Test
    fun `unknown memory never aborts`() {
        assertFalse(shouldAbortForMemory(totalKb = 0, availableKb = 0, treeRssKb = 2 * gb))
    }

    @Test
    fun `exit classification separates timeout crash and memory abort`() {
        assertEquals(ContainerExitKind.OK, classifyExit(0, timedOut = false, memoryAborted = false))
        assertEquals(ContainerExitKind.TIMEOUT, classifyExit(1, timedOut = true, memoryAborted = false))
        assertEquals(ContainerExitKind.MEMORY_ABORT, classifyExit(137, timedOut = false, memoryAborted = true))
        assertEquals(ContainerExitKind.CRASH, classifyExit(139, timedOut = false, memoryAborted = false))
        assertEquals(ContainerExitKind.SIGNAL, classifyExit(137, timedOut = false, memoryAborted = false))
        assertEquals(ContainerExitKind.NON_ZERO, classifyExit(1, timedOut = false, memoryAborted = false))
    }

    @Test
    fun `diagnostics name the cause in model readable english`() {
        val memory = exitDiagnostic(ContainerExitKind.MEMORY_ABORT, 137, 2 * gb, 42_000)
        assertTrue(memory!!.contains("memory is low"))
        assertTrue(memory.contains("OPENBLAS_NUM_THREADS=1"))

        val crash = exitDiagnostic(ContainerExitKind.CRASH, 139, 1 * gb, 12_000)
        assertTrue(crash!!.contains("SIGSEGV"))

        val killed = exitDiagnostic(ContainerExitKind.SIGNAL, 137, 2 * gb, 87_000)
        assertTrue(killed!!.contains("SIGKILL"))

        assertNull(exitDiagnostic(ContainerExitKind.OK, 0, 0, 1_000))
        assertNull(exitDiagnostic(ContainerExitKind.NON_ZERO, 1, 0, 1_000))
        assertNull(exitDiagnostic(ContainerExitKind.TIMEOUT, 124, 0, 1_000))
    }
}
