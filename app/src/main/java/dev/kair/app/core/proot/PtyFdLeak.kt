package dev.kair.app.core.proot

/**
 * U71：PTY fd 泄漏判据（纯函数）。
 *
 * 真机冒烟 `PtySmokeTest` 原先数整个 `/proc/self/fd` 表，表里混着 `/proc`、ashmem、ged、mali
 * 等非 PTY fd（U69 交接 §17.4：三次 FAIL 形态互不相同、还出现「不涨反降」）——判据太宽。
 * 这里把「过滤」与「是否泄漏」抽成纯函数：JVM 单测与 androidTest 共用同一实现，
 * 反向变异不再依赖真机仪器化测试。
 */
internal object PtyFdLeak {

    /** PTY 自有 fd：目标路径是 `/dev/ptmx` 或在 `/dev/pts/` 之下。 */
    fun isPtyTarget(path: String): Boolean =
        path == "/dev/ptmx" || path.startsWith("/dev/pts/")

    /** 从 fd 目标路径列表里只数 PTY 自有 fd；`/proc`、ashmem、ged、mali 等一律不计。 */
    fun countPtyFds(targets: List<String>): Int = targets.count { isPtyTarget(it) }

    /**
     * 泄漏判定：后三轮计数稳定 ±1 且末值不超过首值 +3 → 无泄漏（返回 false）。
     * 语义与 `PtySmokeTest` 原判据一致，输入是逐轮计数序列。
     */
    fun hasLeak(before: Int, counts: List<Int>): Boolean {
        val after = counts.last()
        val stable = counts.drop(2).all { kotlin.math.abs(it - after) <= 1 }
        return !(stable && after <= before + 3)
    }
}
