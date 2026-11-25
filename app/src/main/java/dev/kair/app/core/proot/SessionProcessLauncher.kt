package dev.kair.app.core.proot

/**
 * 起容器会话进程：把「通道 → 启动三元组 → 真实进程」这三步从 `ContainerSession` 里分出来。
 *
 * 抽出来的理由是职责：`ContainerSession` 的主题是**状态机**（READY / 提前断流 / 启动代次），
 * 起进程只是它的一个副作用；另外体量守卫对那个文件只准减不准增。
 *
 * U78 起只有 proot 一档（chroot 已删除，见 ADR 0007）；[spawnProcess] 是测试注入点（U44），
 * 单测用它顶替真 proot，生产走 `ProcessBuilder`。
 *
 * [onFailure] 只上报「为什么」（通道标签 / 路径 / 原始详情），**用户文案由调用方组装** ——
 * core 的中文文本按 `AGENTS.md` §4 例外登记在 `ContainerSession`，不在新文件里另开一处。
 */
internal class SessionProcessLauncher(
    private val launchFactory: SessionLaunchFactory,
    private val spawnProcess: (List<String>, Map<String, String>) -> Process,
    private val onFailure: (label: String, path: String?, detail: String?) -> Unit,
) {

    /** 起 proot 会话进程；失败已经通过 [onFailure] 报告过原因，返回 null。 */
    fun start(mounts: List<Mount>): Process? = spawn("proot", launchFactory.proot(mounts))

    private fun spawn(label: String, launch: Result<SessionLaunch>): Process? {
        val triple = launch.getOrElse {
            onFailure(label, null, it.message)
            return null
        }
        return runCatching { spawnProcess(triple.argv, triple.env) }.getOrElse {
            onFailure(label, triple.path, it.message)
            null
        }
    }
}
