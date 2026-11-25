package dev.kair.app.core.desktop

import dev.kair.app.core.proot.APT_SHELL
import dev.kair.app.core.proot.APT_WAIT_FN
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面脚本纯函数（U89 A/B/E）：启动脚本、容器侧第一层 setup / 菜单脚本、依赖命令。
 * 从 `DesktopRunner.kt` 拆出来的原因是体量守卫（单文件 500 行）：这批要加两份容器脚本，
 * 留在原处一定顶破；脚本内容是纯字符串，与宿主生命周期无关，独立成文件后各改各的。
 *
 * 判据见 `DesktopScriptTest`。
 */

/**
 * 桌面输出分辨率档位（U89 §F）：档位 = 设备横屏长边的分数。
 *
 * llvmpipe 的 fps 与像素数近似反比（1544×720 → 960×600 面积 −48%、fps 约翻倍），
 * 所以低档是 2D 游戏卡顿的解法之一。默认 [SHARP] 与切默认时的 2/3 完全一致，
 * 不变更存量行为。
 */
internal enum class DesktopResolution(val numerator: Int, val denominator: Int) {
    /** 2/3：默认（2400×1080 → 1600×720）。 */
    SHARP(2, 3),

    /** 1/2：面积约为高清的 56%（2400×1080 → 1200×540）。 */
    BALANCED(1, 2),

    /** 1/3：面积约为高清的 25%（2400×1080 → 800×360），游戏优先。 */
    SMOOTH(1, 3),
    ;

    companion object {
        /** 存档里没有 / 认不出时的档位；必须与切默认（2026-11-14）时的 2/3 一致。 */
        val DEFAULT = SHARP

        /** 存档 id → 档位；未知 / 空值静默回退默认（旧存档不能因此打不开桌面）。 */
        fun fromId(id: String?): DesktopResolution = entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

/**
 * Xvfb 分辨率：设备横屏长边 × 档位分数，短边同比例（保持设备宽高比；
 * 原分辨率一帧 10 MB 吃不下，且 llvmpipe 的 fps 与像素数近似反比）。
 */
internal fun desktopFramebufferSize(
    screenWidthPx: Int,
    screenHeightPx: Int,
    resolution: DesktopResolution = DesktopResolution.DEFAULT,
): Pair<Int, Int> {
    val long = max(screenWidthPx, screenHeightPx)
    val short = min(screenWidthPx, screenHeightPx)
    if (long <= 0 || short <= 0) return 0 to 0
    return (long * resolution.numerator / resolution.denominator) to
        (short * resolution.numerator / resolution.denominator)
}

/**
 * 启动脚本（内容固化在 Kotlin 常量，写到容器 `/tmp/kair-desktop.sh`，不依赖 C 层手写的）。
 *
 * 三条都是实测踩出来的：
 * - **等 X socket 就绪**：`Xvfb` 冷启动要加载 llvmpipe/LLVM（154 MB）。旧脚本零间隔就起
 *   WM / `xterm`，两者一次性连不上 `:1` 且**不重试** → 桌面永远起不来。C 层手起能跑是因为
 *   分步执行时 Xvfb 早就绪，把这个竞态掩盖了；
 * - **清 stale lock**：`Xvfb` 被 `killTree` 强杀来不及删 `/tmp/.X1-lock`，下一次起会直接
 *   `Server is already active for display 1` 退出 —— 第一次失败会污染第二次。
 *   若 `:1` 上还有**活的** Xvfb（C 层手起的桌面没停干净），则不碰它的锁、直接报错退出；
 * - **不用 `-bg` 这类后台逃逸**：留在脚本进程树里，`killTree()` 收得到，启动日志也进 `drainOutput()`。
 *
 * 2026-11-14 起 x11vnc 已删除：画面由 App 直接读 Xvfb `-fbdir /tmp` 的帧缓冲，
 * 输入由 App 写容器内常驻的 `xdotool -`（见 `DesktopRunner.startInput`）。
 *
 * U89 起 WM 换 `jwm`（§B）：它没有 `-rc`，固定读 `~/.jwmrc`，由 setup 脚本写好；
 * 桌面图标层 `pcmanfm --desktop` 必须由启动方在 WM 之后自己拉（WM 不会跑 startup，§A）。
 *
 * 末尾 `wait` 让 proot 长驻；栅栏超时直接 `exit 1`，宿主侧 `awaitReady()` 会因进程死亡
 * 立即返回失败，不必干等满超时窗。
 */
internal fun desktopScript(
    width: Int,
    height: Int,
): String = buildString {
    // 判据绝不能是 `pgrep -f "Xvfb :1"`：整段脚本是作为 `/bin/sh -c '<脚本>'` 的 argv 传进容器的，
    // 那个 sh 的命令行里就带着字面量 `Xvfb :1`，于是 pgrep -f 第一个命中的永远是脚本自己 ——
    // 每次启动都在这里 exit 1，Xvfb 永不启动，输入通道报
    // `xdotool input exited: Failed creating new xdo instance`（真机 2026-11-14 复现）。
    // 改读 Xvfb 自己写的锁文件 + `kill -0` 探活：语义更准（pid 复用也不会误判），且无自匹配。
    // 2026-11-14 真机：覆盖装 APK（或 App 被系统杀掉）时，Android 只杀 App 主进程，
    // proot 树里的 Xvfb / jwm / pcmanfm 会成孤儿继续占着 :1 —— 老守卫看到「活 Xvfb」
    // 就 exit 1，用户「点启动就失败」，只能先手动停桌面。这不是用户该记住的事：
    // 点「启动桌面」就是要一个干净桌面，所以启动前先把自己上一轮留下的进程收掉。
    // 记录：本脚本每起一个子进程就把 $! 追加进 /tmp/kair-desktop.pids（proot 不改 pid，
    // 拿到的是真实 pid）；回收时逐个校验该 pid 的 cmdline 是否命中白名单，
    // 不命中一概不碰（防 pid 复用误杀无关进程）。
    appendLine("# Reap leftovers from the previous run (APK reinstalled / App killed -> orphaned tree)")
    appendLine("if [ -f /tmp/kair-desktop.pids ]; then")
    appendLine("  for p in \$(cat /tmp/kair-desktop.pids 2>/dev/null); do")
    appendLine("    kill -0 \"\$p\" 2>/dev/null || continue")
    appendLine("    grep -qaE 'Xvfb|jwm|pcmanfm|xterm|dbus-daemon|xdotool' /proc/\$p/cmdline 2>/dev/null && kill -9 \"\$p\" 2>/dev/null")
    appendLine("  done")
    appendLine("fi")
    appendLine(": > /tmp/kair-desktop.pids")
    appendLine("# Refuse to start if a manually started Xvfb still owns :1 (its lock must not be clobbered)")
    appendLine("if [ -f /tmp/.X1-lock ]; then")
    appendLine("  XPD=\$(tr -dc '0-9' < /tmp/.X1-lock 2>/dev/null)")
    appendLine("  if [ -n \"\$XPD\" ] && kill -0 \"\$XPD\" 2>/dev/null; then")
    appendLine("    echo \"kair: another Xvfb :1 is alive (pid \$XPD); stop the manually started desktop first\"")
    appendLine("    exit 1")
    appendLine("  fi")
    appendLine("fi")
    appendLine("# Clear a stale display lock and framebuffer left by a killed Xvfb")
    // Xvfb mmaps its framebuffer into /tmp/Xvfb_screen0 (xwd); the App reads it directly
    // (proot does not isolate /tmp). Always on since x11vnc was removed (2026-11-14).
    appendLine("rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /tmp/$SHARED_FRAMEBUFFER_FILE_NAME")
    // llvmpipe 在 proot 里探测到的并发度偏低，但「显式给核数能提速」已被复测推翻：
    // 2026-11-14 同一把尺子（1600x720 全屏 glxgears，vblank 关，默认 vs 核数各 6 样本）
    // 中位数 40.4 vs 40.5 fps —— 差在噪声内；偶发 35/51 fps 的离群值来自调频与调度。
    // 这行保留：无代价，且不依赖 llvmpipe 的自动探测。必须赶在 Xvfb 之前 export ——
    // llvmpipe 在 X server 进程里首次初始化时就把线程数定下来了；
    // Xvfb / jwm / pcmanfm 以及桌面里双击启动的程序都是本脚本的后代，一行管全局。
    appendLine("export LP_NUM_THREADS=\$(nproc 2>/dev/null || echo 4)")
    // -nocursor：Xvfb 不把指针 sprite 画进帧缓冲（2026-11-14 真机）。
    // 为什么：帧缓冲模式下 App 读的是 Xvfb 的 fb，而指针由 X 端重绘 ——
    // 「触摸 → 输入通道 → xdotool → XWarpPointer → Xvfb 重绘 sprite」串了多段，
    // 实测（kair-cursor-test.sh）：X 自己匀速移动光标很顺、手指拖动就一顿一顿。
    // 不画进 fb 后，光标由 App 本地绘制（CursorOverlay），位置跟 cursorX/cursorY 走，
    // 完全绕开这条回路；同时也不可能出现 sprite 恢复/重绘的中间态（撕裂）。
    appendTmpProbe()
    // -nolock：锁的用途只是防同屏起第二个 server，而上面已经用 pidfile 回收 + 锁文件探活把关；
    // 反过来，设备上 `link()` 被拒时 X server 会**直接 fatal**（见 [appendTmpProbe] 的注释）。
    appendLine("Xvfb :1 -screen 0 ${width}x${height}x24 -nocursor -nolisten tcp -fbdir /tmp -nolock &")
    appendLine("echo \$! >> /tmp/kair-desktop.pids")
    appendLine("export DISPLAY=:1")
    // proot 的 env 由宿主清空后重建，HOME 不一定在；jwm/pcmanfm/ALSA 都按 $HOME 找配置文件，
    // 不钉死就会读到一个「没人写过配置」的家目录（U87 的 fluxbox 已踩过同一个坑）。
    appendLine("export HOME=/root")
    // 2026-11-14：gvfs 的 trash:// 需要 session bus。PCManFM 的原生回收站（还原 / 清空）
    // 走 gio 的 trash://；没有 DBUS_SESSION_BUS_ADDRESS 时 libfm 拿不到 gvfsd，于是
    //   ① 侧栏的 file:///trash 被当成普通目录（右键仍有 Move to Trash）；
    //   ② 在 /trash 里点 Move to Trash，文件被搬到 ~/.local/share/Trash —— 在回收站窗口里凭空消失。
    // `--fork` 的 daemon 是本脚本的后代（wait 期间常驻，killTree 收得到）；起不来只是少一项侧栏，
    // 桌面照常起 —— 所以这里任何失败都不退出。
    // 2026-11-14 真机翻车一次：`--fork --print-address | head -1` 里的 daemon 子进程继承了 stdout，
    // `$(...)` 等不到管道 EOF，脚本卡死在这一行 —— Xvfb 已起但 jwm/pcmanfm 永不启动，
    // App 侧表现为「桌面启动失败：xdotool input exited: Failed creating new xdo instance」。
    // 改法：写普通文件（不等 EOF 就不会卡）+ `--nofork` 配 `&` —— daemon 留在脚本进程树里，
    // killTree 收得到（`--fork` 会自己 setsid，留下孤儿 bus）。
    appendLine("rm -f /tmp/kair-dbus-addr")
    appendLine("dbus-daemon --session --nofork --print-address=1 > /tmp/kair-dbus-addr 2>/dev/null &")
    appendLine("echo \$! >> /tmp/kair-desktop.pids")
    appendLine("i=0")
    appendLine("while [ ! -s /tmp/kair-dbus-addr ] && [ \"\$i\" -lt 3 ]; do sleep 1; i=\$((i+1)); done")
    appendLine("DBUS_ADDR=\$(head -1 /tmp/kair-dbus-addr 2>/dev/null)")
    appendLine("if [ -n \"\$DBUS_ADDR\" ]; then export DBUS_SESSION_BUS_ADDRESS=\"\$DBUS_ADDR\"; fi")
    appendLine("# Do not start X clients before the socket exists (busybox sleep takes whole seconds)")
    appendLine("i=0")
    appendLine("while [ ! -S /tmp/.X11-unix/X1 ] && [ \"\$i\" -lt 10 ]; do")
    appendLine("  sleep 1")
    appendLine("  i=\$((i+1))")
    appendLine("done")
    appendLine("if [ ! -S /tmp/.X11-unix/X1 ]; then")
    appendLine("  echo \"kair: Xvfb :1 socket did not appear in 10s\"")
    appendLine("  exit 1")
    appendLine("fi")
    // 配置必须先于 WM：jwm 固定读 ~/.jwmrc；包已由 App 的 fetch/add 装好，所以 --no-install。
    appendLine("/usr/local/bin/kair-desktop-setup.sh --no-install")
    appendLine("jwm &")
    appendLine("echo \$! >> /tmp/kair-desktop.pids")
    // WM 起来后再拉图标层。fluxbox 实测不跑 ~/.fluxbox/startup，jwm 同样不会替我们拉。
    appendLine("sleep 1")
    appendLine("pcmanfm --desktop &")
    appendLine("echo \$! >> /tmp/kair-desktop.pids")
    appendLine("xterm -title Kair &")
    appendLine("echo \$! >> /tmp/kair-desktop.pids")
    append("wait")
}

/**
 * `/tmp` 自检：把「能建文件 / 能硬链接」写进启动日志。
 *
 * 2026-11-15 新调试机（MIX 2S / Android 10，Kair 第一次在 Android 13 以外的设备上跑桌面）
 * 首启只留下 `Linking lock file (/tmp/.X1-lock) in place failed: Permission denied` 一行，
 * 其余两行都是它的收尾噪声，`socket did not appear in 10s` 只是栅栏——一句话都没有说清是
 * 「/tmp 不可写」还是「硬链接被拒」。
 *
 * 那句话只在 `link()` 失败时出现：X server 先写 `/tmp/.tX1-lock`，再用硬链接把它落到
 * `/tmp/.X1-lock`（不是单纯 create），所以「建文件成功、链接失败」是两种完全不同的病因
 * （文件系统不支持硬链接 / SELinux 拒 `link`），必须分开量。探针只在启动时跑一次，成本可忽略。
 */
private fun StringBuilder.appendTmpProbe() {
    appendLine("# /tmp self-check: Xvfb's display lock is a hard link, not a plain create")
    appendLine("rm -f /tmp/.kair-probe /tmp/.kair-probe-link")
    appendLine("echo \"kair: tmp=\$(ls -ld /tmp 2>&1)\"")
    appendLine("touch /tmp/.kair-probe 2>/dev/null && echo 'kair: /tmp writable' || echo 'kair: /tmp NOT writable'")
    appendLine("ln /tmp/.kair-probe /tmp/.kair-probe-link 2>/dev/null && echo 'kair: hardlink ok' || echo 'kair: hardlink REFUSED'")
    appendLine("rm -f /tmp/.kair-probe /tmp/.kair-probe-link")
}

/**
 * 容器侧第一层 setup（U89 §A/§B/§E，写到 `/usr/local/bin/kair-desktop-setup.sh`）。
 *
 * 幂等：所有文件都是覆盖写，目录 `mkdir -p`；`--no-install` 跳过装包（App 已用
 * apt fetch/add 装过，这个开关也留给容器内手工复跑）。三件事：pcmanfm 图标/右键菜单、
 * 桌面快捷方式、jwm 菜单。
 * 音频（`.asoundrc`）已归**容器会话**（`SessionAudio.kt`），不再由桌面 setup 写。
 * pcmanfm 的右键开关**只认** `desktop-items-0.conf` 的 `[*]` 段
 * （写进 `pcmanfm.conf` 的 `[desktop]` 实测无效）。
 */
internal fun desktopSetupScript(packages: List<String>): String = buildString {
    appendLine("#!/bin/sh")
    appendLine("# Kair desktop layer setup (U89 A/B/E). Idempotent: safe to re-run.")
    appendLine("set -u")
    appendLine(APT_WAIT_FN.trimEnd())
    appendLine("if [ \"\${1:-}\" != \"--no-install\" ]; then")
    appendLine("  kair_apt apt-get install -y --no-install-recommends ${packages.joinToString(" ")} || exit 1")
    appendLine("fi")
    appendLine("# U89 A: desktop window + icons. The right-click app menu switch lives in the")
    appendLine("# [*] section of desktop-items-0.conf -- pcmanfm.conf's [desktop] section is ignored.")
    appendLine("mkdir -p /root/.config/pcmanfm/default /root/Desktop")
    appendLine("cat > /root/.config/pcmanfm/default/pcmanfm.conf <<'KAIR_EOF'")
    appendLine("[config]")
    appendLine("bm_open_method=0")
    appendLine()
    appendLine("[desktop]")
    appendLine("show_desktop=1")
    appendLine("KAIR_EOF")
    appendLine("cat > /root/.config/pcmanfm/default/desktop-items-0.conf <<'KAIR_EOF'")
    appendLine("[*]")
    appendLine("wallpaper_mode=color")
    appendLine("desktop_bg=#5a7287")
    appendLine("show_wm_menu=1")
    appendLine("KAIR_EOF")
    // 2026-11-14：侧栏 Places。两项回收站语义不同，必须并列：
    // - `file:///trash` = Kair 共享回收站（Agent / 工作区删除，扁平批次目录，供面板整批恢复）；
    // - `trash:///`     = gio 系统回收站（桌面 Move to Trash 的落点，PCManFM 提供还原 / 清空）。
    // 只给 file:// 的后果是实测过的：用户在回收站窗口里再点一次 Move to Trash，文件被搬到
    // ~/.local/share/Trash，界面上「消失」（数据没丢但找不到）。
    // 只在缺失时生成整份；已有则只补 trash:/// 一行，不覆盖用户自己加的项。
    appendLine("mkdir -p /root/.config/gtk-3.0")
    appendLine("if [ ! -f /root/.config/gtk-3.0/bookmarks ]; then")
    appendLine("cat > /root/.config/gtk-3.0/bookmarks <<'KAIR_EOF'")
    appendLine("file:/// 容器根目录")
    appendLine("file:///projects 项目")
    appendLine("file:///sdcard 手机存储")
    appendLine("file:///trash Kair 回收站（Agent 删除）")
    appendLine("trash:/// 系统回收站（桌面删除）")
    appendLine("KAIR_EOF")
    appendLine("fi")
    appendLine("grep -q '^trash:///' /root/.config/gtk-3.0/bookmarks 2>/dev/null || echo 'trash:/// 系统回收站（桌面删除）' >> /root/.config/gtk-3.0/bookmarks")
    appendLine("# U89 A: desktop shortcuts must be executable, otherwise double-click asks first.")
    appendLine("cat > /root/Desktop/xterm.desktop <<'KAIR_EOF'")
    appendLine("[Desktop Entry]")
    appendLine("Type=Application")
    appendLine("Name=终端")
    appendLine("Exec=xterm -title Kair")
    appendLine("Icon=xterm")
    appendLine("Terminal=false")
    appendLine("KAIR_EOF")
    appendLine("cat > /root/Desktop/pcmanfm.desktop <<'KAIR_EOF'")
    appendLine("[Desktop Entry]")
    appendLine("Type=Application")
    appendLine("Name=文件管理器")
    appendLine("Exec=pcmanfm")
    appendLine("Icon=system-file-manager")
    appendLine("Terminal=false")
    appendLine("KAIR_EOF")
    appendLine("chmod 755 /root/Desktop/xterm.desktop /root/Desktop/pcmanfm.desktop")
    appendLine("# Menu is regenerated by its own script so `apt-get` can refresh it without re-setup.")
    appendLine("/usr/local/bin/kair-desktop-menu.sh")
}

/**
 * jwm 菜单生成（U89 §B，写到 `/usr/local/bin/kair-desktop-menu.sh`）：扫 XDG 条目拼 `~/.jwmrc`。
 *
 * jwm 编译含 pango/nls/xft，中文标签正常（fluxbox 的 Alpine 构建是 `-NLS`，永远是方框）。
 * 同时写底栏：`<TaskList/>` 给任务列表、`<RootMenu>` 给「菜单」按钮 —— 手机上不用右键戳空白。
 * 末尾 `jwm -restart` 让已在运行的 WM 立即读到新菜单；WM 没起来时静默跳过。
 */
internal fun desktopMenuScript(): String = buildString {
    appendLine("#!/bin/sh")
    appendLine("# Regenerate ~/.jwmrc (U89 B). Idempotent; safe while jwm is running.")
    appendLine("set -u")
    appendLine("{")
    appendLine("  echo '<?xml version=\"1.0\"?>'")
    appendLine("  echo '<JWM>'")
    appendLine("  echo '  <RootMenu onroot=\"123\" height=\"30\">'")
    appendLine("  echo '    <Program label=\"终端\" icon=\"xterm\">xterm -title Kair</Program>'")
    appendLine("  echo '    <Program label=\"文件管理器\" icon=\"system-file-manager\">pcmanfm</Program>'")
    appendLine("  echo '    <Separator/>'")
    appendLine("  for f in /usr/share/applications/*.desktop; do")
    appendLine("    [ -f \"\$f\" ] || continue")
    appendLine("    grep -q '^NoDisplay=true' \"\$f\" && continue")
    appendLine("    name=\$(sed -n 's/^Name=//p' \"\$f\" | head -n1)")
    appendLine("    exec=\$(sed -n 's/^Exec=//p' \"\$f\" | head -n1)")
    appendLine("    [ -n \"\$name\" ] && [ -n \"\$exec\" ] || continue")
    appendLine("    case \"\$name\" in 终端|文件管理器) continue ;; esac")
    appendLine("    exec=\$(printf '%s' \"\$exec\" | sed 's/%[fFuUdDnNickvm]//g')")
    appendLine("    printf '    <Program label=\"%s\">%s</Program>\\n' \"\$name\" \"\$exec\"")
    appendLine("  done")
    appendLine("  echo '    <Separator/>'")
    appendLine("  echo '    <Program label=\"刷新菜单\">kair-desktop-menu.sh</Program>'")
    appendLine("  echo '  </RootMenu>'")
    appendLine("  echo '  <TaskList/>'")
    appendLine("  echo '  <Tray x=\"0\" y=\"-1\"><Clock format=\"%H:%M\"/></Tray>'")
    appendLine("  echo '  <MenuStyle>'")
    appendLine("  echo '    <Font>sans-10</Font>'")
    appendLine("  echo '    <Foreground>#e8e8e8</Foreground>'")
    appendLine("  echo '    <Background>#2b2f36</Background>'")
    appendLine("  echo '  </MenuStyle>'")
    appendLine("  echo '  <WindowStyle>'")
    appendLine("  echo '    <Font>sans-10</Font>'")
    appendLine("  echo '    <Active><Foreground>#ffffff</Foreground><Background>#2b5c8a</Background></Active>'")
    appendLine("  echo '    <Inactive><Foreground>#d0d0d0</Foreground><Background>#3a3f4b</Background></Inactive>'")
    appendLine("  echo '  </WindowStyle>'")
    appendLine("  echo '  <Desktops><Desktop><Background type=\"solid\">#5a7287</Background></Desktop></Desktops>'")
    appendLine("  echo '</JWM>'")
    appendLine("} > /root/.jwmrc")
    appendLine("command -v jwm >/dev/null 2>&1 && jwm -restart 2>/dev/null || true")
}

/** 容器内 apt 下载缓存：fetch 的落地目标，也是安装进度的主要来源。 */
internal const val APT_CACHE_DIR = "/var/cache/apt/archives"

/**
 * apt 入口的统一前缀：[APT_SHELL]（dpkg 自愈 + 锁等待），见 `AptShell.kt`。
 *
 * 锁等待不能只靠 `DPkg::Lock::Timeout`：它只覆盖 dpkg 锁，lists / archives 锁仍然立即失败
 * （真机 2026-11-16：桌面安装与会话补装并发 → `apt fetch exit=100`，再点几次重试才成功）。
 */
private const val APT_PRELUDE = APT_SHELL

private const val APT_LOCK_TIMEOUT = "-o DPkg::Lock::Timeout=120"

/**
 * 安装前先清上一次失败留下的残留，再重新下载。
 *
 * 真机 2026-11-16：第一次桌面安装失败后，反复点「重试」都在同一处失败，重装 rootfs 才成功 ——
 * 残留（半配置的包、半下载的 `.deb`、索引分片）会让每次重试都在同一个脏状态上重演。
 * 所以重试 = 先清理：让 apt 自己修坏依赖（`-f install`）、丢掉半下载的缓存与索引分片，
 * 再把整套包重新下一遍；不要求用户重装 rootfs。
 *
 * `apt-get clean` 必须放在 fetch 之前：离线安装（`--no-download`）依赖缓存，
 * 清在 fetch 之后会让它无包可装。
 */
internal fun desktopRepairCommand(): String =
    APT_PRELUDE +
        "kair_apt apt-get $APT_LOCK_TIMEOUT -f install -y >/dev/null 2>&1 || true; " +
        "apt-get clean; " +
        "rm -rf /var/lib/apt/lists/partial /var/cache/apt/archives/partial"

/**
 * 依赖下载命令：先清残留（[desktopRepairCommand]），再 `apt-get update` 只下载不安装
 * （`--download-only`），字节进度由 `/var/cache/apt/archives` 与 `/var/lib/apt/lists`
 * 两处共同反映（索引也占 40 MB）。
 */
internal fun desktopFetchCommand(packages: List<String>): String =
    desktopRepairCommand() + " && kair_apt apt-get update && " +
        "kair_apt apt-get $APT_LOCK_TIMEOUT install --download-only -y --no-install-recommends ${packages.joinToString(" ")}"

/**
 * 一次性探测「桌面包是不是全装了」（一条 shell 循环，一个 proot 进程）。
 *
 * 面板每次进入都要判它：装过就不该再显示「安装并启动」（真机上点下去并不会重新装，
 * 只是直接连上，文案与实际行为对不上）。exit 0 = 全在，1 = 有缺。
 */
internal fun desktopProbeCommand(packages: List<String>): String =
    "ok=1; for p in ${packages.joinToString(" ")}; do " +
        "dpkg-query -W -f='${'$'}{Status}' \"\$p\" 2>/dev/null | grep -q 'install ok installed' || ok=0; done; " +
        "[ \"\$ok\" -eq 1 ]"

/**
 * 移除桌面环境（审阅回执 §5.2）：`apt-get remove --purge` + `autoremove` 之外还要清掉
 * setup 写进 rootfs 的配置与快捷方式。只删包会留下 `.jwmrc` / 桌面图标 —— 功能上无害
 * （重装会覆盖），但会让「移除」看起来没生效。
 *
 * `.asoundrc` 与音频依赖 **不在这里**（2026-11-14 审阅回执二 §3）：音频归容器会话，
 * 移除桌面不该把终端里的声音一起弄断。
 */
internal fun desktopRemoveEnvironmentCommand(packages: List<String>): String =
    APT_PRELUDE +
        "kair_apt apt-get $APT_LOCK_TIMEOUT remove --purge -y ${packages.joinToString(" ")}; rc=\$?; " +
        // 存量 rootfs 里还装着已下线的 x11vnc；它不在 DESKTOP_PACKAGES 里，主列表带不走。
        "kair_apt apt-get $APT_LOCK_TIMEOUT remove --purge -y x11vnc >/dev/null 2>&1 || true; " +
        "kair_apt apt-get $APT_LOCK_TIMEOUT autoremove -y >/dev/null 2>&1 || true; " +
        "rm -f /root/.jwmrc /root/Desktop/xterm.desktop /root/Desktop/pcmanfm.desktop " +
        "/usr/local/bin/kair-desktop-setup.sh /usr/local/bin/kair-desktop-menu.sh; exit \$rc"

/**
 * 离线安装命令：包已由 [desktopFetchCommand] 下载到 `/var/cache/apt/archives`；
 * `--no-download` 只从缓存装，不再联网。用包名而不是 `dpkg -i *.deb` ——
 * dpkg 不解析依赖顺序，glob 顺序不对就会失败；apt 会用已下载的依赖。
 */
internal fun desktopAddCommand(packages: List<String>): String =
    APT_PRELUDE +
        "kair_apt apt-get $APT_LOCK_TIMEOUT install -y --no-install-recommends --no-download ${packages.joinToString(" ")}"
