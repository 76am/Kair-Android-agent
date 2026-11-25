package dev.kair.app.core.proot

/**
 * 容器内 apt 的统一公共外壳（`AGENTS 10.4`：一个概念只有一个实现点）。
 *
 * 真机 2026-11-16：桌面安装与会话基础补装并发时，后到的一方直接
 * `E: Could not get lock /var/lib/apt/lists/lock. It is held by process NNN (apt-get)` + exit 100，
 * 用户连点几次「重试」才偶然成功。apt 自己的 `DPkg::Lock::Timeout` **只覆盖 dpkg 锁**，
 * lists / archives 锁是立即失败（2026-11-16 在 apt 2.8 实测：`-o DPkg::Lock::Timeout=4`
 * 仍然 0.08s 报错，`APT::Lock::Timeout` 这个选项根本不存在），所以由 [APT_WAIT_FN]
 * 自己重试到锁释放：最多 90 次 × 2s，覆盖会话补装的 180s 总超时。
 *
 * 只对锁冲突重试：其它错误立刻返回原退出码，不把断网 / 坏源拖成三分钟。
 * 变量名统一 `kair_lock_*`：`/bin/sh` 的函数变量是全局的，桌面脚本里已有 `i` / `rc` 这些名字。
 */
internal const val APT_WAIT_FN: String =
    "kair_apt() { kair_lock_n=0; while :; do " +
        "kair_lock_out=\$(\"\$@\" 2>&1); kair_lock_rc=\$?; printf '%s\\n' \"\$kair_lock_out\"; " +
        "[ \"\$kair_lock_rc\" -eq 0 ] && return 0; " +
        "printf '%s' \"\$kair_lock_out\" | " +
        "grep -qE 'Could not get lock|Unable to acquire the dpkg frontend lock' || return \"\$kair_lock_rc\"; " +
        "kair_lock_n=\$((kair_lock_n + 1)); [ \"\$kair_lock_n\" -ge 90 ] && return \"\$kair_lock_rc\"; " +
        "sleep 2; done; }; "

/**
 * 所有 apt/dpkg 入口的前缀：硬链接自检 + 清理 shadow 锁残留 + `dpkg` 自愈 + 锁等待函数。
 *
 * 自检的理由：设备是否允许硬链接决定 dpkg 能否工作（2026-11-16 真机 SELinux 直接
 * deny `link`，dpkg 的 `status-old` 建不出来）。这一行把「容器里到底能不能用」
 * 写进输出，下次出错时不必再猜是策略没生效还是别的原因。
 *
 * 清 shadow 锁的理由（2026-11-16 真机）：`--link2symlink` 生效后 dpkg 能解包了，
 * 但 `openssh-client` 的 postinst 在 `groupadd -r _ssh` 上失败：
 * `groupadd: /etc/group.lock: lock file already used` + `cannot lock /etc/group`，
 * apt 随之 exit 100；重装 APK、重启会话都一样，`/etc/group.lock` 停在首次失败的时刻。
 * shadow 的 `<file>.lock` 是 fcntl 锁，持锁的若是被强杀中断的 postinst（容器里很常见），
 * 锁文件会一直挡路。删掉锁文件不会影响正在持锁的进程（fcntl 锁挂在 inode 上），
 * 新进程立刻能拿到一把干净的锁 —— 这也是容器里处理 stale lock 的标准做法。
 *
 * 自愈的理由：容器里的 apt 可能被上一次会话/进程强杀打断（会话重启、后台补装被杀），
 * 留下 `dpkg was interrupted` 状态；此时 `apt-get update` 会在 Reading package lists 后
 * 直接 exit 100。
 */
internal const val APT_SHELL: String =
    "export DEBIAN_FRONTEND=noninteractive; " +
        // 模式标记由 baseArgs 以 guest env 传入（0/1），和下面的实测结果并排看：
        // 标记 1 但实测 FAILED = 模拟没兑现；标记 0 = 根本没启用。
        "echo \"kair: link2symlink=\${KAIR_LINK2SYMLINK:-unset}\"; " +
        "rm -f /tmp/kair-ln-src /tmp/kair-ln-probe; " +
        "echo kair > /tmp/kair-ln-src; " +
        // ln 的真实 errno 必须进面板：真机 2026-11-16 模拟没兑现时只看到 "FAILED"，
        // 分不清「store 目录缺失（ENOENT）」「符号链接被拒（EACCES）」还是别的。
        "kair_ln_err=\$(ln /tmp/kair-ln-src /tmp/kair-ln-probe 2>&1); kair_ln_rc=\$?; " +
        "if [ \"\$kair_ln_rc\" -eq 0 ] && cat /tmp/kair-ln-probe >/dev/null 2>&1; then " +
        // nlink 是 shadow 锁的命门：groupadd/useradd 建完 /etc/group.<pid> →
        // /etc/group.lock 的硬链后要 stat 源文件确认 st_nlink==2 才算拿到锁
        // （shadow 4.8.1 lib/commonio.c do_lock_file）。--link2symlink 的模拟
        // 必须把 stat 的 nlink 也伪造成 2，否则报 "lock file already used"。
        "echo \"kair: hardlink OK nlink=\$(stat -L -c %h /tmp/kair-ln-src)\"; " +
        "else echo \"kair: hardlink FAILED rc=\$kair_ln_rc: \$kair_ln_err\"; fi; " +
        "rm -f /tmp/kair-ln-src /tmp/kair-ln-probe; " +
        "rm -f /etc/passwd.lock /etc/group.lock /etc/shadow.lock /etc/gshadow.lock " +
        "/etc/.pwd.lock /etc/subuid.lock /etc/subgid.lock; " +
        "dpkg --configure -a >/dev/null 2>&1 || true; " + APT_WAIT_FN

/**
 * 开发工具一键安装的完整命令：索引为空先补 `apt-get update`，再离线无关地装包。
 *
 * 真机 2026-11-16（模拟修好后新暴露的一层）：`E: Unable to locate package curl` ——
 * 重装 rootfs 后 `RootfsPatcher` 清空了 `/var/lib/apt/lists`，而开发工具安装原来直接
 * `apt-get install`，没有索引自然什么都找不到。桌面链路自带 update（fetch 阶段），
 * 只有这条入口漏了。判据用 `Packages` 文件是否存在：update 成功后必然留下它们；
 * 有索引就不再 update，避免每次装工具都多下载 40 MB。
 */
internal fun aptInstallCommand(pkg: String): String =
    APT_SHELL +
        "ls /var/lib/apt/lists 2>/dev/null | grep -q Packages || kair_apt apt-get update; " +
        "kair_apt apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $pkg"
