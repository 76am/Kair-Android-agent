package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * U89 E · 接收端从桌面解耦到容器会话（审阅回执二 §3）的判据：
 * `.asoundrc` 幂等且不覆盖用户改动、自查脚本可执行、`nc` 归会话补装。
 */
class SessionAudioTest {

    private fun tempRootfs(): File = Files.createTempDirectory("kair-audio").toFile()

    @Test
    fun `config keeps the B1 contract`() {
        val s = sessionAudioConfig()
        assertTrue("必须有归属标记（没有标记的文件视为用户自己的）", s.contains(SESSION_AUDIO_MARKER))
        assertTrue("必须是 file 插件而不是 null 兜底", s.contains("type file"))
        // U89M：改成节流落盘（RTP 路线），不再走 nc 管道
        assertFalse("旧 nc 管道已废弃（裸 PCM 无时间戳）", s.contains("nc -N"))
        assertTrue("必须直送 kair-rtpsend.sh（不落盘）", s.contains("|/usr/local/bin/kair-rtpsend.sh"))
        // 端口必须与 App 接收端一致：写成 5902 会「容器发 5902、App 听 4712」，
        // 真机表现为完全没声音（2026-11-15 实测，白跑一轮）
        assertTrue("管道端口必须是 RTP 端口", s.contains("kair-rtpsend.sh $SESSION_AUDIO_RTP_PORT"))
        assertFalse("不得写旧 TCP 端口", s.contains("kair-rtpsend.sh $SESSION_AUDIO_PORT "))
        assertFalse("不得落盘（文件只增不减会重放历史）", s.contains("/tmp/kair-audio.raw"))
        assertTrue("raw 输出（App 侧不收容器头）", s.contains("format \"raw\""))
        assertTrue("plug 必须把输入统一转成 S16_LE", s.contains("S16_LE"))
        assertTrue("采样率必须钉死 48000", s.contains("rate 48000"))
        assertTrue("声道必须钉死 2ch", s.contains("channels 2"))
    }

    @Test
    fun `install writes the managed config and an executable status script`() {
        val root = tempRootfs()
        installSessionAudio(root)
        val asoundrc = File(root, "root/.asoundrc")
        assertTrue("首次安装必须写 .asoundrc", asoundrc.isFile)
        assertTrue(asoundrc.readText().contains(SESSION_AUDIO_MARKER))
        val status = File(root, "usr/local/bin/kair-audio-status")
        assertTrue("必须落自查脚本", status.isFile)
        assertTrue("自查脚本必须可执行（用户 / agent 都会直接跑）", status.canExecute())
        assertTrue("脚本里必须带 RTP 端口", status.readText().contains(SESSION_AUDIO_RTP_PORT.toString()))

        // U89M：RTP 发送脚本必须一起落，且可执行
        val rtp = File(root, "usr/local/bin/kair-rtpsend.sh")
        assertTrue("必须落 RTP 发送脚本（它常驻，退出即静音）", rtp.isFile)
        assertTrue("RTP 脚本必须可执行", rtp.canExecute())
        assertTrue("RTP 脚本必须指向 RTP 端口", rtp.readText().contains(SESSION_AUDIO_RTP_PORT.toString()))
        // python 主体必须独立成文件：heredoc 会占用 stdin，脚本就读不到 PCM
        val py = File(root, "usr/local/lib/kair-rtpsend.py")
        assertTrue("必须落 python 发送端主体", py.isFile)
        assertTrue("python 主体必须从 stdin 读", py.readText().contains("sys.stdin.buffer.read"))
    }

    @Test
    fun `install preserves a user modified asoundrc`() {
        val root = tempRootfs()
        val custom = "pcm.!default { type null }\n"
        val asoundrc = File(root, "root/.asoundrc").apply {
            parentFile?.mkdirs()
            writeText(custom)
        }
        installSessionAudio(root)
        assertEquals("没有 KAIR_MANAGED 标记的文件是用户自己的，不得覆盖", custom, asoundrc.readText())
    }

    @Test
    fun `install upgrades the legacy kair pipe without a marker`() {
        // 桌面时代写过的 `.asoundrc` 没有标记，但内容就是我们的管道：认作受管并升级，
        // 否则存量用户永远拿不到后续修正。
        val root = tempRootfs()
        val asoundrc = File(root, "root/.asoundrc").apply {
            parentFile?.mkdirs()
            writeText("file \"|/usr/bin/nc -N 127.0.0.1 $SESSION_AUDIO_PORT\"\n")
        }
        installSessionAudio(root)
        assertTrue(asoundrc.readText().contains(SESSION_AUDIO_MARKER))
    }

    @Test
    fun `status script is valid sh and probes the listening socket`() {
        val s = sessionAudioStatusScript()
        val proc = ProcessBuilder("sh", "-n").redirectErrorStream(true).start()
        proc.outputStream.use { it.write(s.toByteArray(Charsets.UTF_8)) }
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("status 脚本语法错误：$out", 0, proc.waitFor())
        // 4712 = 0x1268：RTP 走 UDP，所以探的是 /proc/net/udp（不是 TCP）。
        assertTrue("4712 = 0x1268；端口以十六进制喂给 /proc/net/udp 的判据", s.contains("HEX=1268"))
        assertTrue(s.contains("/proc/net/udp"))
        assertTrue("必须查 .asoundrc 指向 RTP 发送端", s.contains("kair-rtpsend.sh"))
        assertTrue("必须查发送端脚本与 python 主体都在", s.contains("kair-rtpsend.py"))
        assertTrue("必须给出可操作结论", s.contains("结论"))
    }

    @Test
    fun `ensure command installs python3 ca-certificates iproute2 and alsa with a timeout`() {
        val cmd = sessionBaseEnsureCommand()
        assertTrue("RTP sender 依赖 python3，首次会话要能补装", cmd.contains("python3-minimal"))
        assertTrue("git https 需要根证书", cmd.contains("ca-certificates"))
        assertTrue("端口面板依赖 iproute2 的 ss", cmd.contains("iproute2"))
        assertTrue(".asoundrc 的 plug/file/null 是 libasound2 内置插件", cmd.contains("libasound2"))
        assertTrue("音频自查需要 alsa-utils 的 aplay", cmd.contains("alsa-utils"))
        assertTrue("apt update + install 需要更长超时", cmd.contains("timeout 180"))
        assertTrue("上次 apt 被会话重启强杀后要能自愈 dpkg", cmd.contains("dpkg --configure -a"))
        assertTrue("要等 dpkg 锁", cmd.contains("DPkg::Lock::Timeout=120"))
        assertTrue("桌面安装并发时要等 apt 自己的锁（lists 锁不受 DPkg::Lock::Timeout 管）", cmd.contains("kair_apt timeout 180 apt-get"))
        assertFalse("解耦后不应再走桌面安装路径", cmd.contains("kair-desktop"))
        assertFalse("不得再走 apk", cmd.contains("apk "))
    }

    @Test
    fun `audio sink keeps listening when the session leaves Running`() {
        // U89L（2026-11-14 真机）：Chromium 崩溃把会话带回 Stopped，旧逻辑在此关掉 5902，
        // 之后浏览器重启再放视频就永远没声（aplay 直接 `write failed: Broken pipe`），
        // 只有重新进桌面才恢复。接收端的关闭点只允许是「会话对象被清除」。
        assertTrue("Running 时必须确保在听", shouldStartAudio(SessionState.Running))
        assertFalse("Stopped 不得关掉接收端（崩溃后要立刻有声音）", shouldStartAudio(SessionState.Stopped))
        assertFalse("Error 同上", shouldStartAudio(SessionState.Error("boom")))
        assertFalse("Starting 期间保持现状", shouldStartAudio(SessionState.Starting))
    }
}
