package dev.kair.app.core.proot

/**
 * 音频 RTP 包（U89M · 新音频架构）。
 *
 * 与旧架构（ALSA `file` 插件 → `nc` → TCP → 裸 PCM）的根本差别：**带时间戳**。
 * 旧架构 App 侧只能顺序播，于是「延迟」和「爆音」二选一（缓冲大=不同步，缓冲小=断流爆音），
 * 实测三个档位（1 MB / 128 KB / 32 KB）全部不可用，见
 * `docs/handoff/2026-11-14-U89L-容器音频探底.md`。
 *
 * 新架构：容器侧把 PCM 打成 RTP（payload = `audio/L16/48000/2`，每包 20 ms = 960 样本），
 * App 侧按 RTP 时间戳把每包换算成「发送时钟的第几毫秒」，排队等它到点再播、
 * 迟到的直接丢 —— 延迟可控，且丢包不会产生爆音。
 */
internal object AudioRtp {
    /** 容器侧约定的参数；改这里必须同步改容器里的 `rtpsend.py` 与 `.asoundrc`。 */
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val SAMPLES_PER_PACKET = 960 // 20 ms

    /** RTP payload type（动态段，容器侧固定用 96）。 */
    const val PAYLOAD_TYPE = 96

    /** RTP 头固定 12 字节。 */
    const val HEADER_BYTES = 12

    /** 20 ms 的 payload 字节数。 */
    const val PAYLOAD_BYTES = SAMPLES_PER_PACKET * CHANNELS * BYTES_PER_SAMPLE

    /**
     * 收包缓冲区的最小尺寸（字节）：一整个 RTP 包。
     *
     * 真机 2026-11-15：接收端用了 `ByteArray(2048)`，而一包是 3852 字节，
     * `DatagramPacket` 会静默截断 —— 每包只剩 2036 字节音频，PCM 从第一包起就残缺，
     * 听起来就是全程沙哑，而 `received`/`dropped` 又全部正常（包确实没丢）。
     */
    const val MIN_RECEIVE_BUFFER = HEADER_BYTES + PAYLOAD_BYTES

    /** 发送端每毫秒对应的样本数（时间戳换算用）。 */
    const val SAMPLES_PER_MILLI = SAMPLE_RATE / 1000.0

    /**
     * 抖动缓冲：开播前先攒这么多「发送时钟」的数据（毫秒）。
     *
     * 取 60 ms 是折中：太小则网络一抖就断流（爆音），太大则端到端延迟叠加。
     * 加上 AudioTrack 自身的 200 ms 缓冲，稳态延迟约 260 ms。
     */
    const val JITTER_MS = 60

    /**
     * 迟到阈值（毫秒）：比「当前应播时刻」还早这么多以上的包直接丢。
     *
     * 必须显著大于 [JITTER_MS]，否则正常抖动会被误判成迟到。
     */
    const val LATE_MS = 200

    /** AudioTrack 缓冲（毫秒）。 */
    const val TRACK_BUFFER_MS = 200

    /**
     * 把 RTP 包解析成「payload + 该包在发送时钟上的起始毫秒」。
     *
     * 时间戳是「每声道样本计数」（RFC 3551 对 `audio/L16/48000` 的定义），
     * 所以 `timestamp / SAMPLE_RATE` 就是秒。返回 null 表示包不可用（太短 / 版本位不对）。
     */
    fun parse(packet: ByteArray): Parsed? {
        if (packet.size < HEADER_BYTES) return null
        val version = (packet[0].toInt() shr 6) and 0x03
        if (version != 2) return null
        if ((packet[1].toInt() and 0x7F) != PAYLOAD_TYPE) return null
        // 长度必须正好一包。截断的包（收包缓冲区给小了）绝不能拿去播：
        // 尾部是半截帧，写进 AudioTrack 就是沙哑，而且一错到底。
        if (packet.size != MIN_RECEIVE_BUFFER) return null
        val timestamp = ((packet[4].toInt() and 0xFF) shl 24) or
            ((packet[5].toInt() and 0xFF) shl 16) or
            ((packet[6].toInt() and 0xFF) shl 8) or
            (packet[7].toInt() and 0xFF)
        return Parsed(
            payload = packet.copyOfRange(HEADER_BYTES, packet.size),
            sendMillis = (timestamp.toLong() * 1000L) / SAMPLE_RATE,
        )
    }

    data class Parsed(val payload: ByteArray, val sendMillis: Long)
}

/**
 * 滑动窗口中位数：包到达间隔的稳健估计。
 *
 * 用中位数而不是均值，是因为网络抖动里混着大量「瞬间延迟」的离群值 ——
 * 均值会被拖慢，本地时钟就会走得比音频慢，队列随之无限膨胀。
 */
internal class RunningMedian(private val window: Int = 8) {
    private val samples = ArrayDeque<Int>(window)

    fun add(ms: Int) {
        if (ms <= 0) return
        samples.addLast(ms)
        while (samples.size > window) samples.removeFirst()
    }

    /** 包间隔的稳健估计；样本不足时按 20 ms 兜底（与容器侧 ptime 一致）。 */
    fun median(): Int {
        if (samples.isEmpty()) return AudioRtp.SAMPLES_PER_PACKET * 1000 / AudioRtp.SAMPLE_RATE
        val sorted = samples.sorted()
        val n = sorted.size
        // 偶数个样本必须取**中间两个的平均**，不能直接拿下标 n/2（那是上中位数）：
        // 15/25 ms 交替时上中位数是 25，时钟就会每包多走 5 ms 并单向偏快。
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
}

/**
 * RTP 时间戳 → 本地播放时钟（U89M）。
 *
 * 两条铁律，都是真机（2026-11-15）踩出来的：
 *
 * 1. **必须以首包的 RTP 时间戳为锚点**，不能从 0 开始。RTP 时间戳是发送端的绝对
 *    计数（播一会儿就几十万），clock 从 0 走得再快也追不上，包会全部落入「还没
 *    到点」队列而永不播出；反过来，容器侧每次重新拉起 sender 时间戳都从 0 重来，
 *    而 clock 已累积到分钟级 —— `late` 恒大于 [AudioRtp.LATE_MS]，全部丢弃，
 *    表现为「听到开头一小段就彻底没声」。所以时间戳回退要重锚。
 *
 * 2. 推进量用**包到达间隔的滑动中位数**，再向 sendMillis 做 10% 慢速伺服。
 *    只用到达间隔会单向累积误差（持续丢包或队列无限膨胀）；只用 `nanoTime` 会
 *    随 CPU 调频漂移。两者结合才能长期跟住。
 */
internal class RtpClock {
    private val interval = RunningMedian()
    private var clock = -1L
    private var lastSend = -1L
    private var lastArrival = 0L

    /** 重锚次数（发送端重启 / 首包）。诊断用。 */
    var resyncs = 0
        private set

    /** 本地时钟当前值（毫秒，RTP 时间轴）。 */
    val now: Long get() = if (clock < 0) 0 else clock

    /**
     * 推进一次并返回**迟到毫秒数**（`clock - sendMillis`）：
     * 正值 = 已经晚了多少（超过 [AudioRtp.LATE_MS] 就该丢），
     * 负值 = 还没到点（该排队）。
     */
    fun tick(sendMillis: Long, nowNanos: Long): Long {
        if (lastArrival != 0L) interval.add(((nowNanos - lastArrival) / 1_000_000L).toInt())
        lastArrival = nowNanos

        // 首包，或时间戳明显回退（发送端重启）：重锚。
        // 阈值 2 秒：正常乱序远达不到，而 sender 重启一定超过。
        if (clock < 0 || lastSend < 0 || sendMillis + RESYNC_MS < lastSend) {
            if (clock >= 0) resyncs++
            clock = sendMillis
            lastSend = sendMillis
            return 0
        }

        clock += interval.median().toLong()
        val err = sendMillis - clock
        // 四舍五入而不是整除：`err / 10` 在 |err| < 10 时等于 0，误差就会被永久保留
        // （真机 offset 卡在 -9 ms 不变，正是这个原因）。
        if (err in -SERVO_RANGE_MS..SERVO_RANGE_MS && err != 0L) {
            clock += if (err > 0) (err + 5) / 10 else -((-err + 5) / 10)
        }
        if (sendMillis > lastSend) lastSend = sendMillis
        return clock - sendMillis
    }

    companion object {
        /** 时间戳回退超过这个值就当发送端换了：重锚本地时钟。 */
        internal const val RESYNC_MS = 2_000L

        /** 慢速伺服的生效范围：偏差大到这个程度就不校正（那是真的断流，不是漂移）。 */
        internal const val SERVO_RANGE_MS = 500L
    }
}
