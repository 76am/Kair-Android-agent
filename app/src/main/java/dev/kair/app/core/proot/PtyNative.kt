package dev.kair.app.core.proot

/**
 * `libkair_pty.so` 的薄封装：**零逻辑**，只有四个 `external` 声明。
 *
 * 与 `docs/handoff/2026-11-07-用户终端PTY升级.md`（U59）§3 的契约一一对应；`spawn` 带 `envp` 是 D2-6 的修正：
 * proot 档靠 `PROOT_LOADER` 等三个变量拿 loader（U69 起无 `PROOT_L2S_DIR`），native 不许回落 `environ`。
 */
internal object PtyNative {
    init {
        System.loadLibrary("kair_pty")
    }

    /** 返回 master fd；失败 -1。 */
    external fun open(rows: Int, cols: Int): Int

    /** fork + setsid + TIOCSCTTY + dup2 + `execve(path, argv, envp)`；返回子进程 pid，失败 -1。 */
    external fun spawn(
        master: Int,
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
    ): Int

    /** `ioctl(TIOCSWINSZ)`；0 成功，-1 失败。 */
    external fun resize(master: Int, rows: Int, cols: Int): Int

    /** close(master) → SIGHUP → waitpid（带 TERM/KILL 兜底）；只在 IO 线程调用。 */
    external fun close(master: Int)
}
