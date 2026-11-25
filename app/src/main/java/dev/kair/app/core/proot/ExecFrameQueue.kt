package dev.kair.app.core.proot

import kotlinx.coroutines.CompletableDeferred

/**
 * 常驻会话的「命令 ↔ 退出帧」配对队列（[ContainerSession] 的内部件）。
 *
 * 为什么必须是队列而不是一个 `pending`：容器里的循环脚本对**每一条写进去的命令**打一个
 * 退出帧，帧与命令严格同序。只记「当前这一条」时，被取消的 `exec` 把自己的配对留在原地 ——
 * 它的退出帧于是结算给下一条命令，下一条拿到错位的退出码与输出。放进队列后，退出帧永远
 * 结算队首，谁被取消都不影响配对。
 *
 * 线程模型：读线程（[appendOutput] / [completeHead]）与 `exec` 的协程（[enqueue] /
 * [outputOf]）并发，状态只在这把锁里改；[completeHead] 在锁外唤醒等待者，免得结算路径
 * 跑到锁里去执行调用方的代码。
 *
 * 内存上界：队列长度 = 「已写进 shell 但帧还没到」的命令数，正常是 1，只有连续取消才
 * 会短暂 >1；每条命令的输出缓冲各自封顶 [outputLimit]。
 */
internal class ExecFrameQueue(private val outputLimit: Int) {

    /** 一条已写进 shell 的命令：等自己的退出帧，攒自己的输出。 */
    class Entry(val done: CompletableDeferred<Int?>) {
        val output = StringBuilder()
    }

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()

    /**
     * 在把命令写进会话**之前**入队。
     *
     * 入队与写入之间不得有挂起点：协程只在挂起点被取消，所以入队成功的命令必然写下去，
     * 不会出现「占着队列却没有命令」的条目去吃掉下一条的退出帧。
     */
    fun enqueue(): Entry = synchronized(lock) {
        Entry(CompletableDeferred()).also { queue.addLast(it) }
    }

    /** 写入失败时撤销自己那一条：命令没进 shell，不会有帧来结算它。 */
    fun abandon(entry: Entry) {
        synchronized(lock) { queue.remove(entry) }
    }

    /** 普通输出行归给正在跑的那条命令（队首）；队列空时只进转录，不归任何命令。 */
    fun appendOutput(text: String) {
        synchronized(lock) {
            queue.firstOrNull()?.output?.let { buf ->
                buf.append(text).append('\n')
                if (buf.length > outputLimit) buf.delete(0, buf.length - outputLimit)
            }
        }
    }

    /**
     * 退出帧结算队首并把它移出队列；队列已空（会话已停 / 条目已被撤销）时返回 null，
     * 这一帧就地丢弃。返回值是「哪条命令被结算了」，供测试与读码用；
     * 要刷新「忙」状态请读 [isBusy]（同一把锁下的当前队列）。
     */
    fun completeHead(code: Int): Entry? {
        val finished = synchronized(lock) { queue.removeFirstOrNull() }
        finished?.done?.complete(code)
        return finished
    }

    /** 会话停止 / 流断开：在队的命令都不再有帧来，等待者一律拿到 [code]。 */
    fun completeAll(code: Int?) {
        val drained = synchronized(lock) { ArrayList<Entry>(queue).also { queue.clear() } }
        drained.forEach { it.done.complete(code) }
    }

    /** 取某条命令攒下的输出。 */
    fun outputOf(entry: Entry): String = synchronized(lock) { entry.output.toString() }

    /** 队列里是否还有命令没结束（终端面板据此禁用输入）。 */
    fun isBusy(): Boolean = synchronized(lock) { queue.isNotEmpty() }
}

/**
 * 把一段输入拆成**逐条命令**：按行切开、丢掉空白行。
 *
 * 这是配对前提的守门函数 —— 容器内循环脚本一行一个退出帧，所以一次 `exec` 只准带一行。
 * 终端面板把用户粘贴的整块文本走这里逐条下发；[ContainerSession.exec] 用它拒绝多行。
 */
internal fun commandLines(block: String): List<String> =
    block.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
