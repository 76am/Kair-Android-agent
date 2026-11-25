package dev.kair.app.core.proot

import java.io.File

/** 容器音频管道端口（容器 `nc` → App 接收端）；脚本与宿主实现共用，避免两处漂移。 */
internal const val SESSION_AUDIO_PORT = 5902

/**
 * 音频 RTP 端口（U89M）：容器 `rtpsend.py` → UDP → App 接收端。
 *
 * 与 [SESSION_AUDIO_PORT] 并存而不替换：旧路线（TCP 裸 PCM）留作回退，
 * 因为它在纯音频场景下已被验证可用，而 RTP 路线尚未真机验证。
 */
internal const val SESSION_AUDIO_RTP_PORT = 4712

/** `.asoundrc` 的归属标记：没有它的文件视为用户自己写的，永不覆盖。 */
internal const val SESSION_AUDIO_MARKER = "KAIR_MANAGED"

/**
 * 容器会话级音频（U89 §E · 路线 B1）：容器里的 ALSA `file` 插件把裸 PCM 管道给
 * `nc -N 127.0.0.1:5902`，宿主侧接收端负责监听并交给系统音频输出。
 *
 * 归属**容器会话**而不是桌面（2026-11-14 审阅回执二 §3）：`/root/.asoundrc` 是整个容器的
 * ALSA 默认设备，桌面只是它的一个使用者。绑在桌面上会出现三种静默失败 —— 不开桌面、
 * 停桌面、覆盖安装后，播放仍返回 0 但没有声音。所以：
 * - 接收端随会话 Running 起停（[ContainerSessionHost]）；
 * - `.asoundrc` 与会话入口一起写入（[installSessionAudio]）；
 * - `netcat-openbsd` 是会话依赖，不随「移除桌面环境」被删。
 *
 * 接口留在 core（纯 JVM、无 Android 依赖）：实现要用 `android.media.AudioTrack`，
 * 只能由宿主层注入（`ArchitectureGuardTest` 禁止 core 引 Android framework）。
 *
 * 线程约定：两个方法都由 [ContainerSessionHost] 在 IO / 主协程调用；
 * 实现内部的读循环自己起协程，不得阻塞调用方。
 */
internal interface SessionAudioSink {
    /** 开始监听；成功返回 null，失败返回可直接展示的原因（端口占用等）。 */
    fun start(): String?

    /** 停止监听并释放音频输出；幂等。 */
    fun stop()

    /** JVM 测试与未注入实现时的空实现（会话照常跑，只是没有声音）。 */
    object None : SessionAudioSink {
        override fun start(): String? = null
        override fun stop() = Unit
    }
}

/**
 * `~/.asoundrc` 契约（U89M · RTP 路线）：`plug` 把任意输入转成 `S16_LE / 48000 / 2ch`，
 * 再由 `file` 插件**节流地**写进文件，由 `kair-rtpsend.sh` 打成 RTP 发往 App。
 *
 * 为什么不是旧的 `nc` 管道：旧路线（裸 PCM over TCP）没有时间戳，App 侧无法判断
 * 数据是否过期，缓冲大了延迟几十秒、小了断流爆音，实测三档全不可用。
 *
 * `file` 插件的「节流」是实测确认的：3 秒测试音落盘正好 576000 字节（1.00×），
 * 所以这里不需要额外的限速层，只缺时间戳 —— 那正是 RTP 提供的。
 *
 * 文件路径必须与 [sessionRtpSendScript] 的 SRC 一致。
 */
internal fun sessionAudioConfig(port: Int = SESSION_AUDIO_RTP_PORT): String = buildString {
    appendLine("# $SESSION_AUDIO_MARKER: written by Kair when a container session starts.")
    appendLine("# Edit freely: a file without this marker is treated as user-owned and never overwritten.")
    appendLine("# U89M RTP 路线：file 插件 → kair-rtpsend.sh → UDP $port → App")
    appendLine("# 必须直送管道，不能落盘：文件只增不减，sender 晚启动会把历史数据全量重发")
    appendLine("pcm.kair_null { type null }")
    appendLine("pcm.kair_sink {")
    appendLine("    type plug")
    appendLine("    slave {")
    appendLine("        pcm {")
    appendLine("            type file")
    appendLine("            slave.pcm \"kair_null\"")
    appendLine("            file \"|/usr/local/bin/kair-rtpsend.sh $port 20\"")
    appendLine("            format \"raw\"")
    appendLine("        }")
    appendLine("        format S16_LE")
    appendLine("        rate 48000")
    appendLine("        channels 2")
    appendLine("    }")
    appendLine("}")
    appendLine("pcm.!default { type plug; slave { pcm \"kair_sink\" } }")
    appendLine("ctl.!default { type null }")
}

/**
 * 会话启动时写容器侧音频文件（宿主侧纯文件操作，不进容器）：
 * - `/root/.asoundrc`：不存在或带 [SESSION_AUDIO_MARKER] 时写入/更新；用户改过的保持原样
 *   （旧版本写过的同款管道也认作受管，便于升级）；
 * - `/usr/local/bin/kair-audio-status`：只读自查脚本，总是覆盖（它是我们的入口）。
 */
internal fun installSessionAudio(rootfs: File, port: Int = SESSION_AUDIO_RTP_PORT) {
    val asoundrc = File(rootfs, "root/.asoundrc")
    // 读不出来（权限等）时按「用户自己的」处理：宁可不动，也不覆盖别人的配置。
    val existing = runCatching { asoundrc.takeIf { it.isFile }?.readText() }.getOrNull()
    val managed = if (!asoundrc.isFile) {
        true
    } else {
        existing != null &&
            (existing.contains(SESSION_AUDIO_MARKER) ||
                // 旧版 TCP 路线写的管道（5902）也要认，否则存量配置永远升级不了
                existing.contains("|/usr/bin/nc -N 127.0.0.1 $SESSION_AUDIO_PORT"))
    }
    if (managed) {
        asoundrc.parentFile?.mkdirs()
        asoundrc.writeText(sessionAudioConfig(port))
    }
    val status = File(rootfs, "usr/local/bin/kair-audio-status")
    status.parentFile?.mkdirs()
    status.writeText(sessionAudioStatusScript(port))
    status.setExecutable(true, false)

    // U89M：RTP 路线还要写发送脚本。它必须常驻（退出即静音），
    // 与旧路线的 `nc` 一样是「会话级依赖」，不是可选项。
    val rtpSender = File(rootfs, "usr/local/bin/kair-rtpsend.sh")
    rtpSender.writeText(sessionRtpSendScript())
    rtpSender.setExecutable(true, false)
    val rtpPython = File(rootfs, "usr/local/lib/kair-rtpsend.py")
    rtpPython.parentFile?.mkdirs()
    rtpPython.writeText(sessionRtpSendPython())
}

/**
 * RTP 发送脚本（U89M）：写到容器 `/usr/local/bin/kair-rtpsend.sh`。
 *
 * 它必须常驻（进程退出即静音，与旧路线的 `nc` 同理），由会话入口拉起。
 * 脚本内容里的时间戳换算与 [AudioRtp] 一一对应，改一处必须同步。
 */
/**
 * RTP 发送端的 Python 主体（U89M）：写到容器 `/usr/local/lib/kair-rtpsend.py`。
 *
 * 独立成文件而不是写在 shell 的 heredoc 里：`python3 - <<'PY'` 的 heredoc 会**占用 stdin**，
 * 于是脚本读不到 PCM（本机实测 `read` 返回 0 字节，表现为完全没声音）。
 *
 * 参数顺序是「端口在前、每包毫秒在后」，与 shell 用法一致 —— 早期版本两者写反，
 * 端口被当成包时长（4712ms → 每包 904KB）导致 `Message too large`。
 */
internal fun sessionRtpSendPython(
    port: Int = SESSION_AUDIO_RTP_PORT,
    ptimeMs: Int = 20,
): String = buildString {
    appendLine("import socket, sys")
    appendLine("# 参数顺序与 shell 用法一致：端口在前，每包毫秒在后")
    appendLine("PORT = int(sys.argv[1])")
    appendLine("PTIME = int(sys.argv[2])")
    appendLine("DST = ('127.0.0.1', PORT)")
    appendLine("RATE, CH, WIDTH = 48000, 2, 2")
    appendLine("SAMPLES = RATE * PTIME // 1000")
    appendLine("PAYLOAD = SAMPLES * CH * WIDTH")
    appendLine("s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)")
    appendLine("s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)")
    appendLine("")
    appendLine("# **按实时速率发送**（关键）：每包的发车时刻 = 起点 + n × PTIME。")
    appendLine("# 实测教训：不节流时 sender 全速读全速发，速率 20× 实时（真机表现为")
    appendLine("# 「视频被加速」）。慢下来后管道写满，file 插件的写入随之阻塞，")
    appendLine("# 背压自然传回播放方 —— 这就是我们要的节流。")
    appendLine("start = None")
    appendLine("ts = seq = 0")
    appendLine("buf = b''")
    appendLine("while True:")
    appendLine("    # **必须凑满一个整包再发**：管道 read() 经常只返回一部分（ALSA file 插件")
    appendLine("    # 部分写），旧版用 b'\\x00' 补齐 —— 每补一次就是插一段静音，且后续字节")
    appendLine("    # 全部错位，真机听起来就是持续卡顿、杂音、失真（2026-11-15）。")
    appendLine("    while len(buf) < PAYLOAD:")
    appendLine("        chunk = sys.stdin.buffer.read(PAYLOAD - len(buf))")
    appendLine("        if not chunk:")
    appendLine("            break")
    appendLine("        buf += chunk")
    appendLine("    if len(buf) < PAYLOAD:")
    appendLine("        break  # 流结束：丢弃不足一包的尾巴（补零会制造一次爆音）")
    appendLine("    payload, buf = buf[:PAYLOAD], buf[PAYLOAD:]")
    appendLine("    import time")
    appendLine("    now = time.monotonic()")
    appendLine("    if start is None:")
    appendLine("        start = now")
    appendLine("    delay = start + seq * (PTIME / 1000.0) - now")
    appendLine("    if delay > 0:")
    appendLine("        time.sleep(delay)")
    appendLine("    hdr = bytes([0x80, 96]) + seq.to_bytes(2, 'big') + ts.to_bytes(4, 'big') + b'Kair'")
    appendLine("    s.sendto(hdr + payload, DST)")
    appendLine("    seq = (seq + 1) & 0xFFFF")
    appendLine("    ts += SAMPLES")
}

internal fun sessionRtpSendScript(
    port: Int = SESSION_AUDIO_RTP_PORT,
    ptimeMs: Int = 20,
): String = buildString {
    appendLine("#!/bin/sh")
    appendLine("# Kair 音频 RTP 发送端（U89M）。从 stdin 流式读 PCM → UDP。")
    appendLine("# **不落盘**：文件只增不减，sender 晚启动会把历史数据全量重发（真机表现为")
    appendLine("# 视频被加速、音频乱套，2026-11-15 实测）。")
    appendLine("# 用法: kair-rtpsend.sh [端口] [每包毫秒]")
    appendLine("set -u")
    appendLine("exec python3 /usr/local/lib/kair-rtpsend.py \"${'$'}{1:-$port}\" \"${'$'}{2:-$ptimeMs}\"")
}

/**
 * 容器内自查脚本（写到 `/usr/local/bin/kair-audio-status`）：一句话结论，exit 0 = 应该能出声。
 *
 * 存在的理由：接收端没起来时播放是**静默成功**（`aplay` 仍返回 0），人耳之外没有判据；
 * agent 在终端里也看不见。
 *
 * U89M 改成 RTP 路线：查 `.asoundrc` 是否指向 `kair-rtpsend.sh`、发送端脚本是否在、
 * 以及 UDP 端口是否在听（`/proc/net/udp`，不再是 TCP）。
 */
internal fun sessionAudioStatusScript(port: Int = SESSION_AUDIO_RTP_PORT): String = buildString {
    // /proc/net/udp 的本地地址列也是「HEX:HEX」形式（地址:端口）
    val hex = port.toString(16).uppercase().padStart(4, '0')
    val dollar = "${'$'}"
    val listenAwk = "FNR > 1 && ${dollar}2 ~ (p \"${dollar}\") { found = 1 } END { exit !found }"
    appendLine("#!/bin/sh")
    appendLine("# Kair session audio status (U89M, RTP route). exit 0 = 应该能出声。")
    appendLine("set -u")
    appendLine("PORT=$port")
    appendLine("HEX=$hex")
    appendLine("CFG=0")
    appendLine("grep -q \"kair-rtpsend.sh $port\" /root/.asoundrc 2>/dev/null && CFG=1")
    appendLine("SND=0")
    appendLine("[ -x /usr/local/bin/kair-rtpsend.sh ] && [ -f /usr/local/lib/kair-rtpsend.py ] && [ -x /usr/bin/python3 ] && SND=1")
    appendLine("LSN=0")
    appendLine("awk -v p=\":\$HEX\" '$listenAwk' /proc/net/udp 2>/dev/null && LSN=1")
    appendLine("awk -v p=\":\$HEX\" '$listenAwk' /proc/net/udp6 2>/dev/null && LSN=1")
    appendLine("printf 'asoundrc: '")
    appendLine("if [ \"\$CFG\" = 1 ]; then echo \"已配置（→ RTP \$PORT）\"; else echo \"未配置（会话未初始化？）\"; fi")
    appendLine("printf 'sender:   '")
    appendLine("if [ \"\$SND\" = 1 ]; then echo \"已就位\"; else echo \"缺失（会话未初始化？）\"; fi")
    appendLine("printf \"\$PORT/udp: \"")
    appendLine("if [ \"\$LSN\" = 1 ]; then echo \"LISTEN（App 接收端在听）\"; else echo \"无人监听（会话未运行或接收端没起来）\"; fi")
    appendLine("if [ \"\$CFG\" = 1 ] && [ \"\$SND\" = 1 ] && [ \"\$LSN\" = 1 ]; then")
    appendLine("  echo \"结论: 可出声\"")
    appendLine("  exit 0")
    appendLine("fi")
    appendLine("echo \"结论: 不会出声\"")
    appendLine("exit 1")
}

/** 生涯首次会话补装基础依赖；只在宿主侧看不到 python3 / ss / aplay 时才跑（自带超时，断网不挂）。 */
internal fun sessionBaseEnsureCommand(): String =
    // [APT_SHELL]：dpkg 自愈 + 锁等待（dpkg 锁 / lists 锁都可能被并发 apt 占着）。
    APT_SHELL +
        "kair_apt timeout 180 apt-get update && " +
        "kair_apt timeout 180 apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends " +
        "python3-minimal ca-certificates iproute2 libasound2 alsa-utils 2>&1"

/**
 * 首次会话补装基础依赖：
 * - `python3-minimal`：RTP 发送端（`kair-rtpsend.py`）；
 * - `ca-certificates`：git / pip 的 HTTPS 根证书；
 * - `iproute2`：端口面板的 `ss`（Ubuntu base 不带 net-tools）；
 * - `libasound2` / `alsa-utils`：`.asoundrc` 的 `plug` / `file` / `null` 是 libasound2
 *   内置插件（不在 `libasound2-plugins`）；`aplay` 是唯一能人工验证出声的入口。
 *
 * 只在宿主侧看不到 `/usr/bin/python3`、`/usr/bin/ss` 或 `/usr/bin/aplay` 时起一条
 * **一次性** proot，异步跑，不占会话命令队列、不挡启动。
 *
 * 它归会话而不是桌面（审阅回执二 §3 第 4 条）：先开桌面再装音频的存量用户
 * 不必重装桌面；「移除桌面环境」也不会再把音频依赖删掉。命令自带 180s 超时（apt update
 * 约 40 MB 索引），断网时留不下永远挂着的 apt 进程。
 */
internal fun ensureSessionBase(rootfs: File, mounts: List<Mount>, proot: ProotCommandRunner) {
    if (File(rootfs, "usr/bin/python3").exists() &&
        File(rootfs, "usr/bin/ss").exists() &&
        File(rootfs, "usr/bin/aplay").exists()
    ) {
        return
    }
    runCatching {
        val proc = proot.spawnOneShot(mounts, sessionBaseEnsureCommand())
        Thread { runCatching { proc.inputStream.bufferedReader().readText() } }
            .apply { isDaemon = true; start() }
    }
}
