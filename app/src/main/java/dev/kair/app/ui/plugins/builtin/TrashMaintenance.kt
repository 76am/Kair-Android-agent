package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.tools.FileBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 回收站的一个**顶层条目**：一次删除动作算一条。
 *
 * 为什么按顶层条目而不是按文件数：给模型的约定是把要删的东西 `mv` 进
 * `$KAIR_TRASH/<时间戳>/`（`BuiltinTools` 的 `BashTool` 守则），一次删除通常是一个目录；
 * 按文件数计会把「一次误删 3000 个文件」记成 3000 条，与用户理解的「回收站里有几件事」不符。
 * 散文件也吃：约定只是提示词里的要求，代码不保证。
 */
internal data class TrashItem(
    val path: String,
    val isDirectory: Boolean,
    val lastModifiedMs: Long,
    /** 目录 = 递归求和；关闭体积判据时填 0（见 [scanTrash] 的代价说明）。 */
    val sizeBytes: Long,
)

/**
 * 三条上限，**各自独立触发**：`0` = 该条不启用。
 *
 * 单位钉死在这里，别和设置页的显示单位混：条数是条数，体积是字节。
 */
internal data class TrashLimits(
    val maxAgeDays: Int,
    val maxItems: Int,
    val maxSizeBytes: Long,
) {
    /** 三条都不启用 —— 此时一轮扫描注定挑不出东西，调用方连盘都不必读。 */
    val allOff: Boolean get() = maxAgeDays <= 0 && maxItems <= 0 && maxSizeBytes <= 0L
}

/**
 * 判据：先清过期，再按条数、再按体积，每条都**从最旧开始**删到该项达标。
 *
 * 顺序是有意的：过期项无论如何都要走，先摘掉它们再算另两条，可以避免「已经超龄的条目
 * 还占着 `maxItems` 的名额」导致少删。反过来（先条数后年龄）会留下一堆早该没的东西。
 *
 * 纯函数：不碰文件系统、不读时钟 —— `nowMs` 与条目都由调用方给，这样三条判据能在 JVM 里测。
 * 返回顺序与输入一致（最旧在前），调用方按这个顺序逐条删。
 */
internal fun selectTrashPurge(items: List<TrashItem>, limits: TrashLimits, nowMs: Long): List<TrashItem> {
    val ordered = items.sortedWith(compareBy({ it.lastModifiedMs }, { it.path }))
    val doomed = HashSet<String>()
    var keep = ordered

    if (limits.maxAgeDays > 0) {
        val cutoff = nowMs - limits.maxAgeDays.toLong() * MILLIS_PER_DAY
        val (expired, survivors) = keep.partition { it.lastModifiedMs < cutoff }
        doomed.addAll(expired.map { it.path })
        keep = survivors
    }

    if (limits.maxItems > 0 && keep.size > limits.maxItems) {
        // keep 已按「最旧在前」排好，超额的部分就是开头那一段。
        val overflow = keep.take(keep.size - limits.maxItems)
        doomed.addAll(overflow.map { it.path })
        keep = keep.drop(overflow.size)
    }

    if (limits.maxSizeBytes > 0) {
        var total = keep.sumOf { it.sizeBytes }
        for (item in keep) {
            if (total <= limits.maxSizeBytes) break
            total -= item.sizeBytes
            doomed.add(item.path)
        }
    }

    // 从排好序的原表里筛，返回顺序天然是「最旧在前」；三段的并集不必自己排序。
    return ordered.filter { it.path in doomed }
}

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * 列出 [root] 的顶层条目。目录体积**递归求和**，代价是一次全量遍历。
 *
 * [needSizes] 为假时体积填 0（条数与年龄判据不需要它）—— 这条开关不是为了省时间而加的装饰：
 * 回收站堆到几十万条时，全量 stat 要跑很久，而那种情况下先触发的一定是条数与年龄判据。
 */
internal fun scanTrash(root: File, needSizes: Boolean): List<TrashItem> {
    val children = root.listFiles() ?: return emptyList()
    return children.map { child ->
        TrashItem(
            path = child.path,
            isDirectory = child.isDirectory,
            lastModifiedMs = child.lastModified(),
            sizeBytes = if (!needSizes) 0L else directorySize(child),
        )
    }
}

private fun directorySize(file: File): Long =
    if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

/** 一次清理的如实结果：请求了几条、真删掉几条、几条没删掉（带路径）。 */
internal data class TrashPurgeResult(
    val requested: Int,
    val deleted: Int,
    val failed: Int,
    val failedPaths: List<String>,
) {
    val fullyCleared: Boolean get() = requested > 0 && failed == 0
}

/**
 * 逐条删除并**删后复查残留**。
 *
 * 为什么不相信 `backend.delete`：它**没有返回值**，删不动也不报，调用方只看有没有抛异常，
 * 就会对着一堆残留报「已清空」（回收站条目在容器升级路径上具体怎么空转，见任务文档 §6 第 1 条 ——
 * 那条是代码级推断、未上真机，所以这里按「拿不准就复查」处理，不依赖那个推断成立）。
 *
 * 复查也走后端（`backend.exists`）而不是 `File.exists`：**看见什么得问同一个视图** ——
 * 删除可能升级到容器内以 root 执行，宿主的 `java.io.File` 与容器内的判定并不总是同时成立；
 * 用两个不同视图就会出现「后端说还在、本地说没了」这类假绿。
 */
internal suspend fun purgeTrash(backend: FileBackend, targets: List<String>): TrashPurgeResult =
    withContext(Dispatchers.IO) {
        var deleted = 0
        val failed = ArrayList<String>()
        for (path in targets) {
            val thrown = runCatching { backend.delete(path) }.isFailure
            val stillThere = runCatching { backend.exists(path) }.getOrDefault(true)
            if (!thrown && !stillThere) deleted++ else failed.add(path)
        }
        TrashPurgeResult(requested = targets.size, deleted = deleted, failed = failed.size, failedPaths = failed)
    }

/** 上限设置 → [TrashLimits]：设置页存的是「天」与「MB」，这里换成判据要的单位。 */
internal fun trashLimits(maxAgeDays: Int, maxItems: Int, maxSizeMb: Int) =
    TrashLimits(
        maxAgeDays = maxAgeDays.coerceAtLeast(0),
        maxItems = maxItems.coerceAtLeast(0),
        maxSizeBytes = if (maxSizeMb <= 0) 0L else maxSizeMb.toLong() * 1024 * 1024,
    )

/**
 * 一轮自动清理：扫 [root] 顶层 → 判据挑出该删的 → 逐条删并如实回报。
 *
 * 删除范围**只可能是 root 的顶层条目** —— 条目来自 [scanTrash]，不接受外部传入的路径，
 * 所以「自动清理会不会碰到工作区」这个问题在这层结构上就不成立（判据见 TrashMaintenanceTest）。
 *
 * [limits] 开着体积判据时才递归求和（[scanTrash] 的代价说明）：那种扫描很慢，
 * 只在真要用它时付这个钱。
 */
internal suspend fun sweepTrash(root: File, limits: TrashLimits, backend: FileBackend, nowMs: Long): TrashPurgeResult =
    withContext(Dispatchers.IO) {
        val items = scanTrash(root, needSizes = limits.maxSizeBytes > 0)
        purgeTrash(backend, selectTrashPurge(items, limits, nowMs).map { it.path })
    }

/**
 * 一键清空的**共用执行体**：回收站面板的按钮与设置分区的 Action 都调这一个函数，
 * 差别只在结果往哪儿显示（面板有读数，Action 没有）。
 *
 * 清的是 [root] 的顶层全部条目，不是「当前正浏览到的子目录」—— 用户在子目录里点清空
 * 也应该清空整个回收站，否则按钮名与实际行为不符。
 */
internal suspend fun emptyTrash(root: File, backend: FileBackend): TrashPurgeResult =
    withContext(Dispatchers.IO) {
        purgeTrash(backend, scanTrash(root, needSizes = false).map { it.path })
    }
