package dev.kair.app.app

import dev.kair.app.core.proot.RootCommandRunner

/**
 * 后台任务的容器侧脚手架：任务脚本、runner 脚本。
 *
 * 单独成文件：这里生成的是**容器 shell 片段**，与任务表 / 状态机无关；也让管理器
 * 守住 500 行体量守卫（B8）。测试同包直接调用这些 internal 函数。
 *
 * U60 起不再有 `startCommand` / `parseStarted` / `terminateCommand`：启动改为宿主侧
 * 直接持有进程（见 `BackgroundTaskDetach.kt`），pid 由脚本自己写进 pid 文件。
 */

/**
 * 任务进程的**单文件**上限（`ulimit -f`，512B 块）：3_145_728 → 1.5 GiB。
 *
 * `ulimit -f` 是进程级全局，不是「日志上限」：任务进程及其子进程写出的每个文件都受它约束。
 * 10 MiB 会让 >10 MiB 的正常产物直接 `File too large`（debug APK 实测 19.6 MB）；这条上限
 * 只管「失控」，不该挡住离线工具链包 / 大 APK 这类正常产物，所以给到 1.5 GiB。
 * 要只管日志得换机制，见 `docs/架构与内核边界.md` 后台任务一节。
 */
internal const val MAX_FILE_SOFT_BLOCKS: Int = 3_145_728

/** 同一条上限的对外口径：由块数换算，工具描述引用它，不留第二处手抄数字。 */
internal val MAX_FILE_SOFT_LABEL: String = "${MAX_FILE_SOFT_BLOCKS * 512L / (1024.0 * 1024 * 1024)} GiB"

/** 容器内执行的包装脚本：固定 cwd、给任务进程设单文件上限、退出时写退出码（含 `exit` / 异常退出）。 */
internal fun taskScript(id: String, cwd: String, command: String, rcPath: String): String = buildString {
    val quotedRc = RootCommandRunner.shellQuote(rcPath)
    appendLine("#!/bin/sh")
    appendLine("# Kair background task $id — generated, do not edit.")
    appendLine("umask 000")
    // 带 `-S`：只降 soft。不带就是 soft+hard 一起降，而抬 hard 要 `CAP_SYS_RESOURCE` ——
    // proot 档的任务进程是 app uid fork 的「模拟 root」（CapEff=0），`ulimit -f unlimited`
    // 会直接 EPERM，上限就成了抬不动的墙（真机：128 MiB 的产物写到 134217728 字节收 SIGXFSZ）。
    // U78 起只有这一档，容器里没有这个 capability。留 hard 在原值，
    // 任务要写更大的文件时自己 `ulimit -f unlimited` 抬 soft 就行。
    appendLine("ulimit -Sf $MAX_FILE_SOFT_BLOCKS 2>/dev/null")
    appendLine("rc() { echo \$? > $quotedRc; }")
    appendLine("trap rc EXIT")
    appendLine("cd ${RootCommandRunner.shellQuote(cwd)} || exit 126")
    appendLine(command)
}

/**
 * 容器内的 runner 脚本：把任务脚本的 stdout / stderr 重定向到日志。
 *
 * 为什么重定向必须在容器内：宿主侧 `ProcessBuilder` 的 stdout 是一根没人读的管道，
 * 任务输出写满管道缓冲（约 64KB）就会把任务自己阻塞住。runner 把输出落到共享目录里的
 * `.log`，宿主只轮询 `.rc`，不读管道。
 */
internal fun runnerScript(scriptPath: String, logPath: String): String = buildString {
    appendLine("#!/bin/sh")
    appendLine("# Kair background task runner — generated, do not edit.")
    appendLine(
        "exec /bin/sh ${RootCommandRunner.shellQuote(scriptPath)} " +
            "> ${RootCommandRunner.shellQuote(logPath)} 2>&1",
    )
}
