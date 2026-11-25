package dev.kair.app.core.proot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话转录缓冲：面板上看到的连续输出（只留内存，超出上限按行裁剪）。
 *
 * 抽出来的理由是职责与体量：`ContainerSession` 的主题是状态机，转录只是它的输出口；
 * 而体量守卫对那个文件只准减不准增（U44 加了启动闸门，必须等量腾地方）。
 *
 * 线程模型：读线程写、UI 读，内部一把锁。裁剪**对齐换行**，避免切出半行（日志仍可读）。
 */
internal class SessionTranscript(private val limit: Int) {

    private val lock = Any()
    private val buf = StringBuilder()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    fun append(text: String) {
        synchronized(lock) {
            buf.append(text)
            if (buf.length > limit) {
                val from = buf.length - limit
                val cut = buf.substring(from).indexOf('\n').let { if (it < 0) from else from + it + 1 }
                buf.delete(0, cut)
            }
            _log.value = buf.toString()
        }
    }

    fun clear() {
        synchronized(lock) {
            buf.setLength(0)
            _log.value = ""
        }
    }

    /** 末尾 [maxChars] 个字符（去掉首尾空白）：出错时给用户一段上下文。 */
    fun tail(maxChars: Int): String = synchronized(lock) { buf.toString().trim().takeLast(maxChars) }
}
