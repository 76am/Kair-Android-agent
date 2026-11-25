package dev.kair.app.core.proot

/**
 * 起一个容器会话所需的启动三元组：宿主侧绝对路径 + argv + 环境。
 *
 * 存在的理由是**两条通道共用一套构造**（任务文档 §3 D2-6）：
 * 行式 `ContainerSession` 用 [SessionLaunchFactory] 起 `ProcessBuilder`；
 * 用户终端 `PtySession` 拿同一份结果交给 native `execve`。
 * native 里不拼参数、不回落 `environ` —— proot 档靠 [env] 里的三个 `PROOT_*` 变量拿 loader（U69）。
 */
internal data class SessionLaunch(
    val path: String,
    val argv: List<String>,
    val env: Map<String, String>,
)
