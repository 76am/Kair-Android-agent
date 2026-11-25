package dev.kair.app.core.proot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 设备 root 授权的探测入口（U116）。
 *
 * 冷启动（`KairApplication.onCreate`）自动探测一次 `su -c id -u`；设置页的「检测 Root」
 * 按钮可随时重测。首次未授权会弹 Root 管理器授权框（负责人 2026-11-22 确认接受）。
 * 探测结论留在进程内：
 * - 设置页显示细分的 `RootProbeResult`；
 * - 会话下一回合把「已授权 / 未授权」作为 `[root]` system note 追加到历史尾部
 *   （见 `core/agent/RootNote.kt` 与 `TurnRunner.notifyRootNoteIfNeeded`）。
 *
 * 不持久化：进程重启后回到「未检测」，由冷启动探测立即补上；Root 管理器通常记得
 * 授权，不会有「上次授权、这次已被收回」的过期状态混进新会话。
 *
 * su 的定位与探测复用 [RootCommandRunner] 的候选表 / 10 秒超时；本对象不持有会话，
 * 也不依赖容器是否安装 —— `android_shell` 本身就能跑 `su`。
 */
internal object RootAccess {

    private val _result = MutableStateFlow<RootProbeResult?>(null)

    /** 最近一次探测结论；null = 本进程尚未探测。设置页直接订阅它。 */
    val result: StateFlow<RootProbeResult?> = _result.asStateFlow()

    /** 给会话侧的三态：null = 未探测；true / false = 已探测的结论。 */
    val granted: Boolean?
        get() = _result.value?.let { it == RootProbeResult.GRANTED }

    private val probeMutex = Mutex()

    /** 手动探测一次（并发点击串行化）。IO 线程执行，最长约 10 秒（Root 管理器授权弹窗的等待上限）。 */
    suspend fun probe(): RootProbeResult = withContext(Dispatchers.IO) {
        probeMutex.withLock {
            val su = runCatching {
                RootCommandRunner.locateSu(RootCommandRunner.DEFAULT_SU_CANDIDATES)
            }.getOrNull()
            val result = if (su == null) {
                RootProbeResult.NO_SU
            } else {
                runCatching { RootCommandRunner.probeRoot(su) }
                    .getOrDefault(RootProbeResult.DENIED)
            }
            _result.value = result
            result
        }
    }
}
