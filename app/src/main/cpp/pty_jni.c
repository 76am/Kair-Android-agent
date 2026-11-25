/*
 * pty_jni.c — 用户终端的 native 薄层（U59 路线 H）。
 *
 * 只做四件事：开一对 PTY、fork+exec 一个子进程挂上 slave、转发 resize、关闭并回收。
 * 解析 / 编码 / 渲染全部在 Kotlin（见 core/term 与 PtySession）。
 *
 * 构建：scripts/build-pty.sh（与 build-proot.sh 同形：锁定 NDK、产物随仓库提交）。
 * 四个函数与 docs/handoff/2026-11-07-用户终端PTY升级.md §3 一致，不扩。
 *
 * 三条硬纪律（违反会在真机上出诡异 bug）：
 * 1. fork 之后不碰 JVM：argv/path 都在 fork 前整理成 C 字符串，子进程只做
 *    setsid / ioctl(TIOCSCTTY) / dup2 / execve，任何失败路径 _exit(127)。
 * 2. 父进程立刻 close(slave)；master fd 留给 Kotlin，resize 才能生效。
 * 3. close(master) 之后必须回收子进程（SIGHUP 可能被容器内进程忽略，
 *    所以带 TERM → KILL 的升级路径），不允许留僵尸。
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/* 同时存活的 PTY 会话上限：本批只支持单个用户终端，留一点余量便于排查。 */
#define KAIR_PTY_JOBS 4

static struct {
    int master;
    pid_t pid;
} g_jobs[KAIR_PTY_JOBS];

/* 静态数组默认是 0，而 0 是合法 fd：显式置 -1 表示空槽（在 JNI_OnLoad 里做一次）。 */
static void jobs_init(void) {
    int i;
    for (i = 0; i < KAIR_PTY_JOBS; i++) {
        g_jobs[i].master = -1;
        g_jobs[i].pid = -1;
    }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    jobs_init();
    return JNI_VERSION_1_6;
}

static void job_put(int master, pid_t pid) {
    int i;
    for (i = 0; i < KAIR_PTY_JOBS; i++) {
        if (g_jobs[i].master < 0) {
            g_jobs[i].master = master;
            g_jobs[i].pid = pid;
            return;
        }
    }
}

static pid_t job_take(int master) {
    int i;
    pid_t pid = -1;
    for (i = 0; i < KAIR_PTY_JOBS; i++) {
        if (g_jobs[i].master == master) {
            pid = g_jobs[i].pid;
            g_jobs[i].master = -1;
            g_jobs[i].pid = -1;
            return pid;
        }
    }
    return pid;
}

static void winsize_of(int rows, int cols, struct winsize *ws) {
    memset(ws, 0, sizeof(*ws));
    ws->ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws->ws_col = (unsigned short) (cols > 0 ? cols : 80);
}

/*
 * 等 pid 退出；先等 SIGHUP 生效，超时后 TERM → KILL 整个进程组
 * （子进程是 setsid 出来的 session leader，pid 即 pgid）。
 * 返回最后一个 waitpid 的结果（尽量把僵尸收掉）。
 */
static void reap_child(pid_t pid) {
    int status = 0;
    int i;
    if (pid <= 0) return;
    /* SIGHUP 路径：最多等 2s（20 x 100ms），正常 shell 会立刻退出。 */
    for (i = 0; i < 20; i++) {
        if (waitpid(pid, &status, WNOHANG) == pid) return;
        usleep(100 * 1000);
    }
    kill(-pid, SIGTERM);
    for (i = 0; i < 10; i++) {
        if (waitpid(pid, &status, WNOHANG) == pid) return;
        usleep(100 * 1000);
    }
    kill(-pid, SIGKILL);
    /* SIGKILL 之后必然可回收；仍给 2s 上界，避免 native 线程无限挂住。 */
    for (i = 0; i < 20; i++) {
        if (waitpid(pid, &status, WNOHANG) == pid) return;
        usleep(100 * 1000);
    }
    kill(pid, SIGKILL);
    waitpid(pid, &status, 0);
}

JNIEXPORT jint JNICALL
Java_dev_kair_app_core_proot_PtyNative_open(JNIEnv *env, jobject thiz, jint rows, jint cols) {
    int master = -1;
    int slave = -1;
    struct winsize ws;
    (void) env;
    (void) thiz;
    winsize_of(rows, cols, &ws);
    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) return -1;
    /* 父进程不持有 slave：spawn 时按 master 的 pty 号重新打开一份。 */
    close(slave);
    return master;
}

/*
 * 返回子进程 pid；失败返回 -1。
 *
 * [envp] 必须显式传（NULL 表示空环境）：proot 档靠 PROOT_LOADER / PROOT_LOADER_32 /
 * PROOT_TMP_DIR 三个宿主变量拿 loader，继承 Kair 的 environ 起不来，
 * 还会把 Android 的 LD_PRELOAD 泄进容器（任务文档 §3 D2-6）。因此只用 execve，不用 execvp。
 *
 * 失败原因分不清时由 Kotlin 侧按「openpty / 挂载 / execve」三态上报：
 * 子进程 execve 失败固定走 _exit(127)，挂载脚本失败由既有的 exit 90 传出，二者都能从退出码区分。
 */
JNIEXPORT jint JNICALL
Java_dev_kair_app_core_proot_PtyNative_spawn(JNIEnv *env, jobject thiz, jint master,
                                             jstring path, jobjectArray argv, jobjectArray envp,
                                             jint rows, jint cols) {
    pid_t pid;
    int slave = -1;
    int argc = 0;
    int envc = 0;
    int i;
    char pty_path[64];
    unsigned int ptn = 0;
    const char *cpath;
    jstring *items = NULL;
    jstring *envItems = NULL;
    char **cargv = NULL;
    char **cenvp = NULL;
    struct winsize ws;
    (void) thiz;

    if (master < 0 || path == NULL || argv == NULL) return -1;
    cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) return -1;
    argc = (*env)->GetArrayLength(env, argv);
    envc = envp == NULL ? 0 : (*env)->GetArrayLength(env, envp);
    /* fork 前把 argv / envp 全部整理成 C 字符串（子进程里绝不再碰 JVM）。
     * 长度为 0 时也各分配 1 个槽：calloc(0, …) 的返回值在 bionic 上不可依赖。 */
    items = (jstring *) calloc((size_t) (argc > 0 ? argc : 1), sizeof(jstring));
    cargv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    envItems = (jstring *) calloc((size_t) (envc > 0 ? envc : 1), sizeof(jstring));
    cenvp = (char **) calloc((size_t) envc + 1, sizeof(char *));
    if (items == NULL || cargv == NULL || envItems == NULL || cenvp == NULL) goto fail;
    for (i = 0; i < argc; i++) {
        items[i] = (jstring) (*env)->GetObjectArrayElement(env, argv, i);
        if (items[i] == NULL) goto fail;
        cargv[i] = (char *) (*env)->GetStringUTFChars(env, items[i], NULL);
        if (cargv[i] == NULL) goto fail;
    }
    cargv[argc] = NULL;
    for (i = 0; i < envc; i++) {
        envItems[i] = (jstring) (*env)->GetObjectArrayElement(env, envp, i);
        if (envItems[i] == NULL) goto fail;
        cenvp[i] = (char *) (*env)->GetStringUTFChars(env, envItems[i], NULL);
        if (cenvp[i] == NULL) goto fail;
    }
    cenvp[envc] = NULL;

    /* 从 master 找回 pty 编号，再打开 slave（O_NOCTTY：控制终端由子进程自己拿）。 */
    if (ioctl(master, TIOCGPTN, &ptn) != 0) goto fail;
    snprintf(pty_path, sizeof(pty_path), "/dev/pts/%u", ptn);
    slave = open(pty_path, O_RDWR | O_NOCTTY);
    if (slave < 0) goto fail;

    winsize_of(rows, cols, &ws);
    ioctl(slave, TIOCSWINSZ, &ws);

    pid = fork();
    if (pid < 0) goto fail;

    if (pid == 0) {
        /* 子进程：只碰 fd 与信号，不碰 JVM。 */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        /* exec 会保留被忽略的信号与信号掩码：先恢复默认，否则 SIGPIPE 等会漏给 shell。 */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        execve(cpath, cargv, cenvp);
        _exit(127);
    }

    /* 父进程：立刻放掉 slave，记账以便 close 时回收。 */
    close(slave);
    job_put(master, pid);
    for (i = 0; i < argc; i++) (*env)->ReleaseStringUTFChars(env, items[i], cargv[i]);
    for (i = 0; i < envc; i++) (*env)->ReleaseStringUTFChars(env, envItems[i], cenvp[i]);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    free(items);
    free(envItems);
    free(cargv);
    free(cenvp);
    return (jint) pid;

fail:
    if (slave >= 0) close(slave);
    for (i = 0; i < argc; i++) {
        if (cargv != NULL && cargv[i] != NULL) {
            (*env)->ReleaseStringUTFChars(env, items[i], cargv[i]);
        }
    }
    for (i = 0; i < envc; i++) {
        if (cenvp != NULL && cenvp[i] != NULL) {
            (*env)->ReleaseStringUTFChars(env, envItems[i], cenvp[i]);
        }
    }
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    free(items);
    free(envItems);
    free(cargv);
    free(cenvp);
    return -1;
}

JNIEXPORT jint JNICALL
Java_dev_kair_app_core_proot_PtyNative_resize(JNIEnv *env, jobject thiz, jint master, jint rows,
                                              jint cols) {
    struct winsize ws;
    (void) env;
    (void) thiz;
    if (master < 0 || rows <= 0 || cols <= 0) return -1;
    winsize_of(rows, cols, &ws);
    return ioctl(master, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

/*
 * 关闭：close(master) → 子进程收 SIGHUP → waitpid 回收（带 TERM/KILL 兜底）。
 * 必须在 IO 线程调用：极端情况下这里会阻塞几秒。
 * 同一 master 重复调用是安全的（第二次找不到记账条目，只做一次 close）。
 */
JNIEXPORT void JNICALL
Java_dev_kair_app_core_proot_PtyNative_close(JNIEnv *env, jobject thiz, jint master) {
    pid_t pid;
    (void) env;
    (void) thiz;
    if (master < 0) return;
    pid = job_take(master);
    close(master);
    reap_child(pid);
}
