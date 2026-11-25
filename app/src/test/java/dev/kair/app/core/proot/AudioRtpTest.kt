package dev.kair.app.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U89M 判据：RTP 时间戳 → 发送时钟毫秒 的换算。
 *
 * 这条换算是整套方案的地基：容器与 App 同内核（`CLOCK_MONOTONIC` 一致），
 * 时间戳按「每声道样本计数」递增，`timestamp / 48000` 即秒。
 * 换算错了，抖动缓冲就会永远追不上或永远在丢包 —— 且现象只是「声音不对」，极难定位。
 */
class AudioRtpTest {

    private fun packet(timestamp: Int, payloadSize: Int = AudioRtp.PAYLOAD_BYTES, pt: Int = 96): ByteArray {
        val p = ByteArray(AudioRtp.HEADER_BYTES + payloadSize)
        p[0] = 0x80.toByte() // V=2, 无 padding/扩展/CSRC
        p[1] = pt.toByte()
        p[2] = 0
        p[3] = 1
        p[4] = ((timestamp ushr 24) and 0xFF).toByte()
        p[5] = ((timestamp ushr 16) and 0xFF).toByte()
        p[6] = ((timestamp ushr 8) and 0xFF).toByte()
        p[7] = (timestamp and 0xFF).toByte()
        for (i in AudioRtp.HEADER_BYTES until p.size) p[i] = (i and 0xFF).toByte()
        return p
    }

    @Test
    fun `timestamp maps to send clock milliseconds`() {
        // 0 样本 = 第 0 毫秒
        assertEquals(0L, AudioRtp.parse(packet(0))!!.sendMillis)
        // 960 样本（20 ms）= 20 毫秒
        assertEquals(20L, AudioRtp.parse(packet(AudioRtp.SAMPLES_PER_PACKET))!!.sendMillis)
        // 48000 样本 = 1 秒
        assertEquals(1000L, AudioRtp.parse(packet(48000))!!.sendMillis)
        // 3 秒（测试音长度）
        assertEquals(3000L, AudioRtp.parse(packet(48000 * 3))!!.sendMillis)
    }

    @Test
    fun `payload is stripped of the 12 byte header`() {
        val parsed = AudioRtp.parse(packet(960))!!
        assertEquals(
            "payload 必须正好是去掉 RTP 头的那一整包",
            AudioRtp.PAYLOAD_BYTES,
            parsed.payload.size,
        )
    }

    @Test
    fun `wrong version or short packet is rejected`() {
        assertNull("短于 RTP 头的包必须丢弃", AudioRtp.parse(ByteArray(8)))
        val bad = packet(0)
        bad[0] = 0x40.toByte() // V=1
        assertNull("非 RTPv2 必须丢弃", AudioRtp.parse(bad))
        val wrongPt = packet(0, pt = 0)
        assertNull("payload type 不是 96 必须丢弃", AudioRtp.parse(wrongPt))
    }

    @Test
    fun `contract constants match the container side`() {
        // 与容器里的 rtpsend.py 和 .asoundrc 三方一致；改一处必须同步另两处。
        assertEquals("每包 20ms @48kHz", 960, AudioRtp.SAMPLES_PER_PACKET)
        assertEquals("payload 字节 = 960 × 2ch × 2B", 3840, AudioRtp.PAYLOAD_BYTES)
        assertEquals("payload type 固定 96", 96, AudioRtp.PAYLOAD_TYPE)
        assertTrue("时间戳按样本计数而不是字节数", AudioRtp.SAMPLES_PER_MILLI == 48.0)
    }

    @Test
    fun `jitter and late thresholds leave headroom`() {
        // 抖动缓冲必须明显小于迟到阈值，否则正常网络抖动会被误判成迟到而丢包。
        assertTrue("JITTER_MS 必须小于 LATE_MS", AudioRtp.JITTER_MS < AudioRtp.LATE_MS)
        assertTrue("两者都不得为 0（否则没有缓冲，首包必 underrun）", AudioRtp.JITTER_MS > 0)
        assertTrue("总延迟预算 = 抖动 + 音轨缓冲", AudioRtp.JITTER_MS + AudioRtp.TRACK_BUFFER_MS in 100..400)
    }

    // ---------------------------------------------------------- RtpClock（U89M）

    @Test
    fun `clock anchors on the first packet timestamp, not zero`() {
        // 真机 2026-11-15：RTP 时间戳是发送端绝对计数（播一会儿就几十万毫秒），
        // clock 若从 0 起算就永远追不上，包全部堆在队列里永不播出。
        val clock = RtpClock()
        val late = clock.tick(500_000L, 1_000_000_000L)
        assertEquals("首包不迟到也不提前", 0L, late)
        assertEquals("本地时钟必须锚在首包时间戳上", 500_000L, clock.now)
        assertEquals("首包不算重锚", 0, clock.resyncs)
    }

    @Test
    fun `clock re-anchors when the sender restarts its timestamps`() {
        // 容器侧每次重新拉起 sender，时间戳都从 0 重来；不重锚的话
        // late 恒大于 LATE_MS，每一包都会被丢弃 —— 真机表现「听到开头一小段就彻底没声」。
        val clock = RtpClock()
        clock.tick(500_000L, 1_000_000_000L)
        clock.tick(500_020L, 1_020_000_000L)
        val late = clock.tick(0L, 1_040_000_000L)
        assertEquals("时间戳回退必须重锚而不是判迟到", 0L, late)
        assertEquals("重锚后时钟回到新起点", 0L, clock.now)
        assertEquals("重锚计数可见", 1, clock.resyncs)
    }

    @Test
    fun `clock keeps offset small when arrival jitter averages one packet`() {
        // 到达间隔抖动（15/25ms 交替，中位数仍是 20ms）时，本地时钟必须跟住音频时钟。
        // 旧实现用 `err / 10`，|err| < 10 时整除为 0，误差会被永久保留。
        val clock = RtpClock()
        var nanos = 1_000_000_000L
        var send = 0L
        clock.tick(send, nanos)
        repeat(80) { i ->
            nanos += if (i % 2 == 0) 15_000_000L else 25_000_000L
            send += 20L
            clock.tick(send, nanos)
        }
        val offset = clock.now - send
        assertTrue("偏移必须保持在 ±10ms 内，实际 $offset ms", offset in -10L..10L)
    }

    @Test
    fun `clock advances about one packet per packet at steady state`() {
        val clock = RtpClock()
        var nanos = 1_000_000_000L
        var send = 100_000L
        clock.tick(send, nanos)
        repeat(100) {
            nanos += 20_000_000L
            send += 20L
            clock.tick(send, nanos)
        }
        val offset = clock.now - send
        assertTrue("稳态偏移应当很小，实际 $offset ms", offset in -5L..5L)
    }

    @Test
    fun `receive buffer must hold one whole packet`() {
        // 真机 2026-11-15 沙哑的根因：接收端用 ByteArray(2048)，而一包是 3852 字节，
        // DatagramPacket 静默截断 —— 每包只剩 2036 字节音频，PCM 从第一包起就残缺。
        assertEquals("整包 = 12 头 + 3840 payload", 3852, AudioRtp.MIN_RECEIVE_BUFFER)
        assertTrue("缓冲区常量必须远大于曾经写死的 2048", AudioRtp.MIN_RECEIVE_BUFFER > 2048)
    }

    @Test
    fun `parse rejects a truncated packet instead of playing half a frame`() {
        // 截断包绝不能进 AudioTrack：尾部是半截帧，写进去就是沙哑且一错到底。
        val full = ByteArray(AudioRtp.MIN_RECEIVE_BUFFER)
        full[0] = 0x80.toByte()
        full[1] = AudioRtp.PAYLOAD_TYPE.toByte()
        assertNotNull("完整包必须能解析", AudioRtp.parse(full))

        val truncated = full.copyOf(2048)
        assertNull("截断的包必须丢弃", AudioRtp.parse(truncated))
    }
}
