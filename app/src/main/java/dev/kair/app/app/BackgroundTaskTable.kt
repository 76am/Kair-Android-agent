package dev.kair.app.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 后台任务表的写入原语：插入 / 终态推进 / 截断。
 *
 * 唯一的并发约束：**任何**读-改-写都必须在 CAS 失败后重读重算，不能拿旧快照直接写回。
 * 旧实现（`val old = _tasks.value` + `_tasks.value = next`）在跨会话并发启动时丢记录，
 * start 与 watcher 收尾重叠时把终态写回 RUNNING（`hasRunning` 永久为真，会话不判空闲、
 * 保活不释放，常驻通知虚报一个「会话」）。
 *
 * 从 `BackgroundTasks.kt` 拆出来也为了守住 B8 体量守卫：管理器本身保持在 500 行以内。
 */

/**
 * 把 [task] 插入任务表；表满且没有可丢的条目时返回 null。
 *
 * 与 [transitionTask] 共用同一个约束：**任何**对任务表的读-改-写都必须在 CAS 失败后
 * 重读重算，不能拿旧快照直接写回。
 */
internal fun insertTask(
    tasks: MutableStateFlow<List<BackgroundTask>>,
    task: BackgroundTask,
    maxTasks: Int = MAX_TASKS,
): List<BackgroundTask>? {
    while (true) {
        val old = tasks.value
        val next = trimTasks(listOf(task) + old, maxTasks) ?: return null
        if (tasks.compareAndSet(old, next)) return next
    }
}

/**
 * 原子地把运行中的任务推进到终态；已被取消 / 已完成时返回 null（幂等）。
 *
 * [delivering] 在同一次 CAS 里落表（不是旁边另记一个集合）：终态与「通知待投递」
 * 必须一起可见，否则两者之间的窗口会让 `hasRunning` 短暂变假、截断把任务当可丢的。
 */
internal fun transitionTask(
    tasks: MutableStateFlow<List<BackgroundTask>>,
    id: String,
    state: BackgroundTaskState,
    exitCode: Int?,
    finishedAt: Long,
    delivering: Boolean = false,
): BackgroundTask? {
    while (true) {
        val list = tasks.value
        val task = list.firstOrNull { it.id == id && it.running } ?: return null
        val next = task.copy(state = state, finishedAt = finishedAt, exitCode = exitCode, delivering = delivering)
        if (tasks.compareAndSet(list, list.map { if (it.id == id) next else it })) return next
    }
}

/** 投递结束（含失败 / 取消）：原子摘掉 [BackgroundTask.delivering]；任务已不在表里则无事可做。 */
internal fun markDelivered(tasks: MutableStateFlow<List<BackgroundTask>>, id: String) {
    while (true) {
        val list = tasks.value
        val task = list.firstOrNull { it.id == id && it.delivering } ?: return
        val next = list.map { if (it.id == id) it.copy(delivering = false) else it }
        if (tasks.compareAndSet(list, next)) return
    }
}

/**
 * 保留最近 [max] 条，但**绝不丢运行中或通知待投递的任务**。
 *
 * [BackgroundTask] 一旦出列，它的 `watch` 协程在下一次轮询就找不到自己而静默退出 ——
 * 从此没有完成检测、没有超时、`cancel` 也找不到入口，进程却还在容器里跑。已终态但
 * 通知还在投递（[BackgroundTask.delivering]）同理不能丢：`hasRunning` 靠表里的它
 * 保持会话「忙」，丢了通知就没有接收方。截断只丢「已终态且不在投递」的（最旧先丢）。
 *
 * @return 挤不下时返回 null（运行中 / 投递中的任务本身已经占满 [max]），由调用方拒绝启动。
 */
internal fun trimTasks(tasks: List<BackgroundTask>, max: Int): List<BackgroundTask>? {
    if (tasks.size <= max) return tasks
    var drop = tasks.size - max
    val kept = ArrayList<BackgroundTask>(max)
    for (task in tasks.asReversed()) { // 最旧的先判：能丢就丢
        if (drop > 0 && !task.running && !task.delivering) {
            drop--
            continue
        }
        kept += task
    }
    if (kept.size > max) return null
    return kept.asReversed()
}
