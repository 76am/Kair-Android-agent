package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U71：PTY fd 判据的 JVM 单测——取代真机才能做的反向变异。
 */
class PtyFdLeakTest {

    /** 造 n 个非 PTY fd 的目标路径（模拟真机表里的 ashmem / proc / ged / mali 抖动）。 */
    private fun nonPty(n: Int): List<String> = List(n) { "/memfd:ashmem-regulator-$it (deleted)" }

    /**
     * 真机 FAIL 形态（U69 交接 §17.4）：非 PTY fd 大幅抖动、PTY 自有 fd 归零——不该判泄漏。
     * 旧判据数全表，这条输入必须红，证明「判据太宽会误报」。
     */
    @Test
    fun non_pty_churn_with_pty_at_zero_is_not_a_leak() {
        val before = PtyFdLeak.countPtyFds(nonPty(104))
        val counts = listOf(104, 154, 175, 175, 176).map { PtyFdLeak.countPtyFds(nonPty(it)) }
        assertFalse("非 PTY 抖动不该触发泄漏判定：before=$before counts=$counts", PtyFdLeak.hasLeak(before, counts))
    }

    /** 反向变异①：人为构造「PTY 自有 fd 不归零」（漏 `close`）——判据必须红。 */
    @Test
    fun pty_fds_not_released_is_a_leak() {
        val before = PtyFdLeak.countPtyFds(nonPty(104))
        val counts = listOf(1, 2, 3, 4, 5).map { n -> PtyFdLeak.countPtyFds(List(n) { "/dev/ptmx" }) }
        assertTrue("PTY fd 逐轮不归零必须判为泄漏：before=$before counts=$counts", PtyFdLeak.hasLeak(before, counts))
    }

    /** 反向变异②：混入大量非 PTY fd 但 PTY 归零——判据必须绿（与全表计数互为对照，判据非永真）。 */
    @Test
    fun mixed_targets_count_only_pty_fds() {
        val targets = nonPty(200) + listOf("/dev/ptmx", "/dev/pts/5")
        assertEquals(2, PtyFdLeak.countPtyFds(targets))
        val counts = List(5) { PtyFdLeak.countPtyFds(nonPty(150) + listOf("/dev/ptmx")) }
        assertFalse(PtyFdLeak.hasLeak(PtyFdLeak.countPtyFds(nonPty(104) + listOf("/dev/ptmx")), counts))
    }

    /** 过滤判据本身：只认 `/dev/ptmx` 与 `/dev/pts/` 下的项，前缀相近的不算。 */
    @Test
    fun pty_target_recognition() {
        assertTrue(PtyFdLeak.isPtyTarget("/dev/ptmx"))
        assertTrue(PtyFdLeak.isPtyTarget("/dev/pts/7"))
        assertFalse(PtyFdLeak.isPtyTarget("/dev/pts"))
        assertFalse(PtyFdLeak.isPtyTarget("/dev/ptmxr"))
        assertFalse(PtyFdLeak.isPtyTarget("/proc/self/fd/3"))
        assertFalse(PtyFdLeak.isPtyTarget("/memfd:ashmem-regulator-0 (deleted)"))
    }
}
