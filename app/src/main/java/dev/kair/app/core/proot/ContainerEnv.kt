package dev.kair.app.core.proot

import java.util.UUID

/**
 * 容器内干净环境的唯一事实源。U78 起只有 proot 一档（chroot 已删除，见 ADR 0007），
 * 所以这里只有**一张**环境表，不再有「两条通道必须一致」的对齐要求。
 *
 * proot 侧本就 `env -i`，用本常量拼装。
 */
object ContainerEnv {
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** 容器进程标记：进程面板据此只列容器内进程，绝不扫宿主 /proc 去猜。 */
    const val MARKER = "KAIR_CONTAINER=1"

    /** 代际变量名：与 [instance] 组成 `KAIR_INSTANCE=<token>`，用于区分「上一代 App 留下的」。 */
    const val INSTANCE = "KAIR_INSTANCE"

    /**
     * 本 App 进程的代际标记（每次进程启动生成一次，容器命令全部继承）。
     *
     * 存在的唯一理由是**跨 App 进程的回收**：容器进程由 `su` / proot 拉起，不是 App 的子进程，
     * App 被杀时 Android 不会连带收掉；而 [MARKER] 只能回答「是不是容器进程」，回答不了
     * 「哪一代的」。会话启动时据此把上一代全清（见 `ContainerProcesses.reapCommand`）。
     *
     * 只含 `[0-9a-f]`：值直接拼进 `env -i ...` 与 shell 判据，不能需要转义。
     */
    val instance: String = "i" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** 执行通道标记变量名：模型用它**自查**当前的执行后端（见 [pairs]）。 */
    const val CHANNEL = "KAIR_CHANNEL"

    /**
     * `env -i` 之后的完整环境变量表（顺序稳定，脚本/argv 断言依赖它）。
     *
     * [CHANNEL] 的取值恒为 [ContainerRunner.MODE_PROOT]（U78 起只有一档）。它仍然保留，
     * 是因为 `container_shell` 守则用「变量是否定义」区分「在容器里」与「回退到了设备 shell」
     * （后者不经过本表，变量未定义）。值只进容器环境、**绝不进请求** —— 进了系统提示前缀
     * 就变成「按设备变化 + 一切换就打飞缓存」。
     */
    fun pairs(): List<String> = listOf(
        "PATH=$PATH",
        "HOME=/root",
        MARKER,
        "$INSTANCE=$instance",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "CI=true",
        "NO_COLOR=1",
        "PAGER=cat",
        // 回收站路径：模型据此把要删的文件 `mv` 进去，而不是直接删（见 TrashMounts）。
        "${TrashMounts.ENV}=${TrashMounts.CONTAINER}",
        // 执行通道：模型据此自查 `setsid` 出口在不在（见 BashTool 的 setsid 守则）。
        "$CHANNEL=${ContainerRunner.MODE_PROOT}",
    )
}
