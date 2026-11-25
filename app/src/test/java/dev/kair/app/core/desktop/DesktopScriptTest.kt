package dev.kair.app.core.desktop

import dev.kair.app.core.proot.sessionBaseEnsureCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U86 §5.1 · 启动脚本与就绪判据：分辨率按横屏屏宽 2/3 且保持宽高比、
 * x11vnc 已删除（画面只走 Xvfb `-fbdir` 帧缓冲，输入走容器内 xdotool）。
 */
class DesktopScriptTest {

    @Test
    fun `script does not start x11vnc anymore`() {
        val s = desktopScript(1600, 720)
        assertFalse("2026-11-14 负责人拍板：x11vnc 已下线", s.contains("x11vnc"))
        assertFalse("旧 RFB 端口不应再出现", s.contains("5901"))
    }

    @Test
    fun `script pins display port and backend flags`() {
        val s = desktopScript(1600, 720)
        assertTrue(s.contains("Xvfb :1 -screen 0 1600x720x24 -nocursor -nolisten tcp -fbdir /tmp -nolock"))
        // 2026-11-15 新调试机（MIX 2S）首启死在 `Linking lock file (/tmp/.X1-lock) ... Permission denied`：
        // 那句话只在 `link()` 失败时出现（X server 写临时文件后硬链接过去，不是单纯 create），
        // 于是「/tmp 不能硬链接」的设备上一个 display 锁就能让整个桌面起不来。
        // 锁要防的「同屏两个 server」已由 pidfile 回收 + 活 Xvfb 探活把住，故这里不靠 X 的锁。
        assertTrue("Xvfb 必须 -nolock（不依赖硬链接建锁）", s.contains("-nolock"))
        // 病因要能自证：把 /tmp 能不能建文件、能不能硬链接分开打出来。
        val probe = s.indexOf("kair: hardlink")
        assertTrue("启动日志必须带 /tmp 硬链接探针", probe > 0)
        assertTrue("探针必须在 Xvfb 之前跑", probe < s.indexOf("Xvfb :1 -screen"))
        // 2026-11-14 真机：指针 sprite 留在帧缓冲里，会让“触摸→输入→X 重绘”那条回路
        // 变成可见的一顿一顿（glxgears 齿轮却顺）。-nocursor 后改由 App 本地画
        // （CursorOverlay），位置直接跟 cursorX/cursorY 走。
        assertTrue("Xvfb 必须带 -nocursor", s.contains("-nocursor"))
        assertTrue(s.contains("DISPLAY=:1"))
        // jwm / pcmanfm / ALSA 都按 $HOME 找配置；proot 的 env 是重建的，必须钉死。
        assertTrue(s.contains("export HOME=/root"))
        // 2026-11-14：trash:// 需要 session bus（gvfsd-trash）。没有它 PCManFM 把 /trash 当普通目录，
        // 在回收站窗口里再点 Move to Trash 会把文件搬到 ~/.local/share/Trash（界面上凭空消失）。
        val dbus = s.indexOf("dbus-daemon --session")
        val home = s.indexOf("export HOME=/root")
        assertTrue("必须起 dbus session（trash:// 的后端）", dbus > 0)
        assertTrue("必须导出 DBUS_SESSION_BUS_ADDRESS（否则子进程拿不到 bus）", s.contains("export DBUS_SESSION_BUS_ADDRESS"))
        assertTrue("dbus session 必须在 HOME 钉死之后起", dbus > home)
        assertFalse("dbus 只是侧栏增强，不得因它失败而拦住桌面", s.contains("dbus-daemon --session") && s.contains("|| exit 1"))
        // 2026-11-14 真机卡死一次：`--fork --print-address | head -1` 的 daemon 子进程继承 stdout，
        // `$(...)` 等不到 EOF，脚本停在那一行 —— Xvfb 起了但 jwm/pcmanfm 永不启动，
        // App 报「桌面启动失败：xdotool input exited」。必须写文件 + --nofork 留在进程树内。
        assertFalse("不得用 dbus-daemon --fork（daemon 继承 stdout 会卡死命令替换）", s.contains("--fork"))
        assertTrue("dbus 地址必须写文件而不是走管道", s.contains("--print-address=1 > /tmp/kair-dbus-addr"))
        // 2026-11-14 真机：覆盖装 APK 后 Android 只杀 App 主进程，proot 树里的 Xvfb 成孤儿
        // 继续占着 :1，老守卫看到「活 Xvfb」就 exit 1 —— 用户「点启动就失败」，只能手动停桌面。
        assertTrue("必须记录本脚本起的 pid（回底用）", s.contains("/tmp/kair-desktop.pids"))
        assertTrue("回收前必须按 cmdline 白名单校验（防 pid 复用误杀）", s.contains("grep -qaE"))
        assertTrue("Xvfb 的 pid 必须写入 pid 文件", s.contains("echo \$! >> /tmp/kair-desktop.pids"))
        // U89 §F：唯一画面来源，必须永远开 -fbdir。
        assertTrue("必须带 -fbdir /tmp", s.contains("-fbdir /tmp"))
        // U89 §B：WM 换 jwm（fluxbox 的 Alpine 构建是 -NLS，中文菜单永远是方框）。
        assertTrue(s.contains("jwm &"))
        assertFalse("不得再启动 fluxbox", s.contains("fluxbox"))
        assertFalse("jwm 没有 -rc，固定读 ~/.jwmrc", s.contains("kair-fluxbox-init"))
        // U89 §A：WM 起来之后由启动方拉桌面图标层（WM 不会跑 startup）。
        assertTrue(s.contains("pcmanfm --desktop"))
        assertTrue(s.contains("xterm"))
    }

    @Test
    fun `packages switch fluxbox to jwm and add the first desktop layer`() {
        val packages = DesktopRunner.DESKTOP_PACKAGES
        assertTrue("jwm 必须进包列表，否则「移除桌面环境」后没有 WM", "jwm" in packages)
        assertFalse("fluxbox 已由 jwm 取代", "fluxbox" in packages)
        assertFalse("x11vnc 已删除，不该再装", "x11vnc" in packages)
        for (pkg in listOf("pcmanfm", "fonts-wqy-zenhei", "fonts-dejavu-core", "hicolor-icon-theme", "adwaita-icon-theme", "xdotool", "libgl1-mesa-dri")) {
            assertTrue("$pkg 必须在桌面包列表里", pkg in packages)
        }
        assertFalse("Alpine 包名已不再适用", "mesa-dri-gallium" in packages)
        assertFalse("Alpine 包名已不再适用", "font-wqy-zenhei" in packages)
        // U89 E · B1/U89M：音频依赖归**会话**，不属于桌面包；
        // RTP sender 需要 python3，nc 只用于旧 .asoundrc 迁移。
        assertFalse("netcat-openbsd 已归会话，不在桌面包列表里", "netcat-openbsd" in packages)
        val ensure = sessionBaseEnsureCommand()
        assertTrue("会话侧必须补 python3（RTP sender 依赖）", "python3-minimal" in ensure)
        assertTrue("会话侧必须补 ca-certificates（git https 需要）", "ca-certificates" in ensure)
        assertTrue("会话侧必须补 iproute2（端口面板的 ss）", "iproute2" in ensure)
        assertTrue("会话侧必须补 libasound2（.asoundrc 的 plug/file）", "libasound2" in ensure)
        assertTrue("会话侧必须补 alsa-utils（aplay 自查）", "alsa-utils" in ensure)
        assertFalse("音频依赖不得进桌面包列表", "libasound2" in packages)
        assertFalse("音频依赖不得进桌面包列表", "alsa-utils" in packages)
        assertTrue("断网 / apt 失败必须留超时", "timeout 180" in ensure)
        assertFalse("不得再走 apk", ensure.contains("apk "))
        // 2026-11-14：trash:// 的两个依赖 —— dbus（session bus）与 gvfs（gvfsd-trash）。
        // 缺任一个，侧栏「系统回收站」就叫不出来，回收站右键的分歧依旧存在。
        assertTrue("dbus 必须进包列表（session bus）", "dbus" in packages)
        assertTrue("gvfs 必须进包列表（trash:// 后端）", "gvfs" in packages)
    }

    @Test
    fun `setup script writes the desktop layer configs idempotently`() {
        val s = desktopSetupScript(DesktopRunner.DESKTOP_PACKAGES)
        assertTrue("必须支持 --no-install（App 已经用 fetch/add 装过包）", s.contains("--no-install"))
        // pcmanfm 右键菜单只认 desktop-items-0.conf 的 [*] 段；写进 pcmanfm.conf 无效。
        assertTrue(s.contains("desktop-items-0.conf"))
        assertTrue(s.contains("show_wm_menu=1"))
        // 真机反馈：默认背景黑黑的 —— pcmanfm 的颜色键是 desktop_bg，wallpaper_color 不生效。
        assertTrue("壁纸颜色必须用 desktop_bg（pcmanfm 认这个键）", s.contains("desktop_bg=#5a7287"))
        assertTrue(s.contains("wallpaper_mode=color"))
        assertTrue("桌面快捷方式必须 chmod 755，否则双击会问「是否执行」", s.contains("chmod 755"))
        // 2026-11-14：侧栏必须并列两个回收站 —— file:///trash（Kair 批次目录，供面板恢复）与
        // trash:///（gio 系统回收站，PCManFM 提供还原/清空）。只有前者时，在回收站里再点
        // Move to Trash 会把文件搬到 ~/.local/share/Trash，界面上消失（实测 2026-11-14）。
        assertTrue("必须写 Places 的 bookmarks", s.contains("/root/.config/gtk-3.0/bookmarks"))
        assertTrue("Kair 回收站必须是 file:///trash", s.contains("file:///trash"))
        assertTrue("系统回收站必须是 trash:///（还原/清空靠它）", s.contains("trash:///"))
        assertTrue("已有 bookmarks 时只补 trash:///，不覆盖用户自己加的项", s.contains("grep -q '^trash:///'"))
        // U89 §E：音频已归容器会话（`core/proot/SessionAudio.kt`），桌面 setup 不得再写
        // `.asoundrc` —— 否则「移除桌面环境」与桌面生命周期又会把容器级音频绑回去。
        assertFalse("桌面 setup 不得写 .asoundrc（音频归会话）", s.contains(".asoundrc"))
        assertFalse("桌面 setup 不得再出现音频管道", s.contains("127.0.0.1 5902"))
        // 菜单由独立脚本生成（apt install 后只需重跑它）。
        assertTrue(s.contains("kair-desktop-menu.sh"))
    }

    @Test
    fun `menu script generates a jwm root menu from xdg entries`() {
        val s = desktopMenuScript()
        assertTrue("jwm 固定读 ~/.jwmrc", s.contains(".jwmrc"))
        assertTrue(s.contains("<RootMenu"))
        assertTrue(s.contains("Program"))
        assertTrue(s.contains("/usr/share/applications"))
        // WM 自己的根窗口背景也必须是同一个颜色（pcmanfm 没起来时不至于黑屏）。
        assertTrue(s.contains("#5a7287"))
        // 底栏任务列表与菜单按钮（手机上不用右键戳空白）。
        assertTrue(s.contains("<TaskList/>"))
    }

    @Test
    fun `probe command checks every package in one shell`() {
        // 真机反馈：装过以后每次进面板还是「安装并启动」。面板进入时用一条命令探全量包。
        val cmd = desktopProbeCommand(listOf("jwm", "pcmanfm", "xvfb"))
        assertTrue(cmd.contains("dpkg-query"))
        assertTrue(cmd.contains("jwm"))
        assertTrue(cmd.contains("pcmanfm"))
        assertTrue(cmd.contains("xvfb"))
    }

    @Test
    fun `setup runs before the wm and pcmanfm starts after it`() {
        val s = desktopScript(1600, 720)
        val setup = s.indexOf("kair-desktop-setup.sh --no-install")
        val wm = s.indexOf("jwm &")
        val desktop = s.indexOf("pcmanfm --desktop")
        assertTrue("setup 必须在 WM 之前写好 ~/.jwmrc 与 pcmanfm 配置", setup in 1 until wm)
        assertTrue("pcmanfm 必须在 WM 之后启动", wm in 1 until desktop)
    }

    @Test
    fun `script waits for the X socket before starting any X client`() {
        // 真机首次启动：Xvfb 冷启动要加载 llvmpipe/LLVM（154 MB），旧脚本零间隔就起
        // WM/xterm，两者一次性连不上 :1 且不重试 → 桌面永远起不来。
        val s = desktopScript(1600, 720)
        val xvfb = s.indexOf("Xvfb :1 -screen") // 精确锚点：不能命中前面的 pgrep -f "Xvfb :1"
        val barrier = s.indexOf("-S /tmp/.X11-unix/X1")
        val setup = s.indexOf("kair-desktop-setup.sh --no-install")
        val client = s.indexOf("jwm &")
        assertTrue("必须轮询 X socket 就绪", barrier > 0)
        assertTrue("栅栏必须在 Xvfb 之后", xvfb >= 0 && xvfb < barrier)
        assertTrue("所有 X client 必须在栅栏之后", setup > barrier && client > barrier)
        assertTrue(s.contains("sleep 1"))
    }

    @Test
    fun `remove environment keeps session audio intact`() {
        // 审阅回执 §5.2：只删包会留下 .jwmrc / 桌面图标与容器脚本，「移除桌面环境」看起来没生效。
        // 2026-11-14 审阅回执二 §3：`.asoundrc` / 音频依赖已归会话，移除桌面不得把它们删掉。
        val cmd = desktopRemoveEnvironmentCommand(listOf("jwm", "xvfb"))
        assertTrue(cmd.contains("remove --purge -y jwm xvfb"))
        assertTrue("上次 apt 被强杀后要能自愈 dpkg", cmd.contains("dpkg --configure -a"))
        assertTrue("要等 dpkg 锁", cmd.contains("DPkg::Lock::Timeout=120"))
        assertTrue(
            "被强杀中断的 postinst 会留下 shadow 锁文件，不清掉 groupadd 永远 lock file already used",
            cmd.contains("rm -f /etc/passwd.lock /etc/group.lock"),
        )
        assertTrue("存量 rootfs 的 x11vnc 也要清掉", cmd.contains("remove --purge -y x11vnc"))
        assertTrue("包删完要清依赖", cmd.contains("autoremove -y"))
        assertFalse("音频归会话，移除桌面不得删 .asoundrc", cmd.contains("/root/.asoundrc"))
        assertFalse("音频依赖是会话级，不应出现在桌面移除列表", cmd.contains("python3-minimal"))
        assertFalse("音频依赖是会话级，不应出现在桌面移除列表", cmd.contains("libasound2"))
        assertFalse("音频依赖是会话级，不应出现在桌面移除列表", cmd.contains("alsa-utils"))
        assertTrue(cmd.contains("/root/.jwmrc"))
        assertTrue(cmd.contains("/root/Desktop/xterm.desktop"))
        assertTrue(cmd.contains("/usr/local/bin/kair-desktop-setup.sh"))
        assertTrue("apt remove 的退出码必须保住（rm 不能把它盖掉）", cmd.contains("exit \$rc"))
    }

    @Test
    fun `space preflight blocks before dpkg fails midway`() {
        // 安装峰值约 1 GB；低于它时直接给「需要多少 / 当前多少」，不要让 apt 装到一半才报 dpkg 错误。
        assertNull(desktopSpaceError(0))
        assertNull(desktopSpaceError(1500L * 1024 * 1024))
        val reason = desktopSpaceError(600L * 1024 * 1024)
        assertNotNull(reason)
        assertTrue(reason!!.contains("磁盘空间不足"))
        assertTrue(reason.contains("600"))
    }

    @Test
    fun `apt failure excerpt keeps the real error instead of the package list tail`() {
        // 真机 2026-11-16：面板只给尾部 2000 字，看到的是「Errors were encountered」+ 包名，
        // 第一条 dpkg 错误在更前面被截掉。过滤后必须留下原因行。
        val output = buildString {
            appendLine("kair: link2symlink=1")
            appendLine("kair: hardlink FAILED")
            appendLine("dpkg: error processing package adwaita-icon-theme (--configure):")
            appendLine(" installed adwaita-icon-theme package post-installation script subprocess returned error exit status 1")
            appendLine("Setting up gvfs:arm64 (1.48.2-0ubuntu1.3) ...")
            repeat(200) { appendLine("Setting up filler$it:arm64 (1.0) ...") }
            appendLine("Errors were encountered while processing:")
            appendLine(" adwaita-icon-theme")
            appendLine("E: Sub-process /usr/bin/dpkg returned an error code (1)")
        }
        val excerpt = aptFailureExcerpt(output)
        assertTrue("必须留下最先出现的 dpkg 错误", excerpt.contains("post-installation script subprocess returned error"))
        assertTrue(excerpt.contains("error code (1)"))
        // 自检行是判断「模拟到底有没有生效」的唯一现场证据，不能被过滤掉。
        assertTrue(excerpt.contains("kair: link2symlink=1"))
        assertTrue(excerpt.contains("kair: hardlink FAILED"))
        assertTrue("不该把 200 行安装日志当错误尾部塞进来", excerpt.length < 600)
    }

    @Test
    fun `apt failure excerpt keeps account tool errors from postinst`() {
        // 真机 2026-11-16：openssh-client 装不上，真因是 postinst 里的 groupadd，
        // 它不以 `E: ` 开头，旧过滤只剩 `E: Sub-process ...`，看不出真正原因。
        val output = buildString {
            appendLine("Setting up openssh-client (1:8.9p1-3ubuntu0.18) ...")
            appendLine("groupadd: cannot lock /etc/group; try again later.")
            appendLine("groupadd: '/sbin/groupadd -g 101 _ssh' returned error code 10. Exiting.")
            appendLine("dpkg: error processing package openssh-client (--configure):")
            appendLine("E: Sub-process /usr/bin/dpkg returned an error code (1)")
        }
        val excerpt = aptFailureExcerpt(output)
        assertTrue(excerpt.contains("groupadd: cannot lock /etc/group"))
        assertTrue(excerpt.contains("groupadd -g 101 _ssh"))
    }

    @Test
    fun `generated scripts are syntactically valid sh`() {
        // 三份脚本最终都由容器里的 /bin/sh 执行。引号 / heredoc 写错在 JVM 侧看不见，
        // 真机上却会让桌面起不来（setup 失败 → 没有 ~/.jwmrc / pcmanfm 配置）。
        for ((name, script) in listOf(
            "desktop" to desktopScript(1600, 720),
            "setup" to desktopSetupScript(DesktopRunner.DESKTOP_PACKAGES),
            "menu" to desktopMenuScript(),
        )) {
            val proc = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
            proc.outputStream.use { it.write(script.toByteArray(Charsets.UTF_8)) }
            val out = proc.inputStream.bufferedReader().readText()
            assertEquals("$name 脚本语法错误：$out", 0, proc.waitFor())
        }
    }

    @Test
    fun `live display guard probes the lock pid instead of pgrep -f`() {
        // 真机 2026-11-14：`pgrep -f "Xvfb :1"` 会命中执行脚本的 `/bin/sh -c '<脚本>'` 自己
        // —— 脚本正文里就含这个字面量。于是每次启动都在守卫处 exit 1：Xvfb 永不启动，
        // 输入通道报 `xdotool input exited: Failed creating new xdo instance`。
        val s = desktopScript(1600, 720)
        assertFalse(
            "不能用 pgrep -f 扫命令行（必自匹配脚本自己），改用锁文件 + kill -0 探活",
            s.contains("pgrep -f \"Xvfb"),
        )
        assertTrue("守卫必须靠 kill -0 对锁里的 pid 探活", s.contains("kill -0"))
        assertTrue("探活必须在清锁之前（否则活着的 Xvfb 会被抢锁）",
            s.indexOf("kill -0") < s.indexOf("rm -f /tmp/.X1-lock"))
    }

    @Test
    fun `script clears the stale display lock before Xvfb`() {
        // Xvfb 被 killTree 强杀来不及删 /tmp/.X1-lock；下一次起直接
        // "Server is already active for display 1" 退出 —— 第一次失败会污染第二次。
        val s = desktopScript(1600, 720)
        assertTrue("必须清强杀残留的 .X1-lock", s.contains("rm -f /tmp/.X1-lock"))
        assertTrue("清锁必须在 Xvfb 启动行之前", s.indexOf("rm -f /tmp/.X1-lock") < s.indexOf("Xvfb :1 -screen"))
    }

    @Test
    fun `shared framebuffer is always on and clears the stale copy`() {
        // U89 §F：Xvfb 把帧缓冲落成 /tmp/Xvfb_screen0（xwd），App 直接 mmap。
        // 2026-11-14 负责人拍板切默认并删 x11vnc 后，这里不再有开关分支。
        val s = desktopScript(1600, 720)
        assertTrue("必须带 -fbdir /tmp", s.contains("-fbdir /tmp"))
        assertTrue(
            "启动前必须清掉上一次残留的帧缓冲（否则就绪判据会读到旧帧）",
            s.contains("rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 /tmp/Xvfb_screen0"),
        )
    }

    @Test
    fun `script enables llvmpipe multithreading before Xvfb`() {
        // 复测（2026-11-14，1600x720 全屏 glxgears，vblank 关，默认 vs 核数各 6 样本）：
        // 中位数 40.4 vs 40.5 fps，无稳定差异。断言只锁「必须在 Xvfb 之前 export」这个顺序。
        // 必须在 Xvfb 之前 export —— llvmpipe 在 X server 进程里首次初始化时读它；
        // 桌面程序都继承 Xvfb 的环境，一行管全局。
        val s = desktopScript(1600, 720)
        assertTrue("必须显式开 llvmpipe 多线程", s.contains("export LP_NUM_THREADS="))
        val export = s.indexOf("export LP_NUM_THREADS=")
        assertTrue("LP_NUM_THREADS 必须在 Xvfb 启动之前 export", export < s.indexOf("Xvfb :1 -screen"))
    }

    @Test
    fun `ready timeout leaves room for the Xvfb barrier`() {
        // 独立字面量，不从实现推导：栅栏最多 10s + X client 启动，8s 窗口会把正常冷启动误判成失败。
        assertTrue(DesktopRunner.READY_TIMEOUT_MS >= 20_000L)
    }

    @Test
    fun `framebuffer size is two thirds of landscape width keeping aspect`() {
        // 2400x1080（竖持）与 1080x2400（横持）都按横屏长边算：1600x720
        assertEquals(1600 to 720, desktopFramebufferSize(1080, 2400))
        assertEquals(1600 to 720, desktopFramebufferSize(2400, 1080))
        // 1920x1080 → 1280x720
        assertEquals(1280 to 720, desktopFramebufferSize(1920, 1080))
    }

    @Test
    fun `framebuffer size follows the resolution preset`() {
        // llvmpipe 的 fps 与像素数近似反比：降档是 2D 游戏卡顿的解法（U89 §F）。
        assertEquals(1600 to 720, desktopFramebufferSize(2400, 1080, DesktopResolution.SHARP))
        assertEquals(1200 to 540, desktopFramebufferSize(2400, 1080, DesktopResolution.BALANCED))
        assertEquals(800 to 360, desktopFramebufferSize(2400, 1080, DesktopResolution.SMOOTH))
        // 竖持同样按长边算，保持宽高比。
        assertEquals(1200 to 540, desktopFramebufferSize(1080, 2400, DesktopResolution.BALANCED))
        // 非法尺寸不分档，仍是 0（调用方据此放弃启动）。
        assertEquals(0 to 0, desktopFramebufferSize(0, 1080, DesktopResolution.SMOOTH))
    }

    @Test
    fun `resolution id falls back to the sharp default`() {
        // 旧存档没有这个键 / 认不出档位时不能打不开桌面：静默回退默认（= 切默认时的 2/3）。
        assertEquals(DesktopResolution.SHARP, DesktopResolution.fromId(null))
        assertEquals(DesktopResolution.SHARP, DesktopResolution.fromId("not-a-preset"))
        assertEquals(DesktopResolution.SMOOTH, DesktopResolution.fromId("SMOOTH"))
    }

    // Phase 0（Ubuntu 22.04）判据：fetch 必须 `apt-get update` + `--download-only`，
    // add 必须 `--no-download` 用包名（dpkg -i 不解析依赖顺序）。
    @Test
    fun `fetch downloads debs with byte progress source`() {
        val cmd = desktopFetchCommand(listOf("xvfb", "scrot"))
        assertTrue(cmd.contains("apt-get update"))
        assertTrue(cmd.contains("install --download-only"))
        assertTrue("必须关推荐包，否则体积不可控", cmd.contains("--no-install-recommends"))
        assertTrue("上次 apt 被强杀后要能自愈 dpkg", cmd.contains("dpkg --configure -a"))
        assertTrue("桌面安装与会话补装并发时要等锁", cmd.contains("DPkg::Lock::Timeout=120"))
        assertTrue(cmd.contains("xvfb"))
        assertTrue(cmd.contains("scrot"))
    }

    @Test
    fun `retry cleans leftovers before re-downloading`() {
        // 真机 2026-11-16：第一次失败后反复「重试」都在同一处失败，重装 rootfs 才成功 ——
        // 半配置 / 半下载的残留必须由重试自己清掉。顺序也重要：clean 在 fetch 之前，
        // 否则离线安装（--no-download）缓存被清空，无包可装。
        val repair = desktopRepairCommand()
        assertTrue("半配置的包要交给 apt 修", repair.contains("-f install"))
        assertTrue("半下载的 .deb 要丢掉", repair.contains("apt-get clean"))
        assertTrue(repair.contains("rm -rf /var/lib/apt/lists/partial"))
        val fetch = desktopFetchCommand(listOf("xvfb"))
        assertTrue(fetch.contains("-f install"))
        assertTrue(
            "清理必须在下载之前",
            fetch.indexOf("apt-get clean") in 0..<fetch.indexOf("apt-get update"),
        )
    }

    @Test
    fun `every apt call goes through the lock-waiting wrapper`() {
        // 真机 2026-11-16：桌面安装与会话基础补装并发，后到的一方拿到
        // `E: Could not get lock /var/lib/apt/lists/lock` + exit 100，用户要点几次重试才成功。
        // apt 的 DPkg::Lock::Timeout 不覆盖 lists 锁，所以所有 apt-get 都必须走 kair_apt。
        listOf(
            desktopRepairCommand(),
            desktopFetchCommand(listOf("xvfb")),
            desktopAddCommand(listOf("xvfb")),
            desktopRemoveEnvironmentCommand(listOf("xvfb")),
        ).forEach { cmd ->
            assertTrue("必须自带锁等待函数", cmd.contains("kair_apt() {"))
            assertTrue("必须识别 apt 的锁错误", cmd.contains("Could not get lock"))
            // 真机只能看到面板里的几行，自检行必须自带「模拟是否启用 + 是否真的可用」。
            assertTrue("自检行要带模式标记", cmd.contains("kair: link2symlink="))
            assertTrue("自检行要实测 link", cmd.contains("kair: hardlink"))
            // shadow 的 groupadd/useradd 拿锁后要 stat 源文件确认 nlink==2（do_lock_file），
            // 模拟不伪造 stat 就会报 "lock file already used"（真机 2026-11-16）。
            // 探针必须量这一层：nlink=2 / nlink=1 直接区分「模拟兑现」与「模拟没伪造 stat」。
            assertTrue("自检行要实测 stat 的 nlink", cmd.contains("nlink=\$(stat -L -c %h"))
            // `apt-get clean` 不碰锁，允许裸调用；其余一律要前缀 kair_apt。
            val naked = Regex("(?<!kair_apt )apt-get (?!clean)").findAll(cmd)
                .map { cmd.substring(it.range.first, minOf(it.range.first + 40, cmd.length)) }
                .toList()
            assertTrue("存在没走 kair_apt 的 apt-get：$naked", naked.isEmpty())
        }
    }

    @Test
    fun `add installs from the apt cache without network`() {
        val cmd = desktopAddCommand(listOf("xvfb", "scrot"))
        assertTrue(cmd.contains("install -y --no-install-recommends --no-download"))
        assertTrue("必须只吃缓存，不再联网", cmd.contains("--no-download"))
        assertTrue("上次 apt 被强杀后要能自愈 dpkg", cmd.contains("dpkg --configure -a"))
        assertTrue("要等 dpkg 锁", cmd.contains("DPkg::Lock::Timeout=120"))
        assertTrue(cmd.contains("xvfb"))
        assertTrue(cmd.contains("scrot"))
    }
}
