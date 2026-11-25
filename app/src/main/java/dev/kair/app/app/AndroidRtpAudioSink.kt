package dev.kair.app.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.kair.app.core.proot.AudioRtp
import dev.kair.app.core.proot.RtpClock
import dev.kair.app.core.proot.SESSION_AUDIO_RTP_PORT
import dev.kair.app.core.proot.SessionAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 会话音频接收端 · RTP 路线（U89M）。
 *
 * 容器侧：ALSA `file` 插件（节流 + 转 `S16_LE/48000/2ch`）→ `rtpsend.py` 打成
 * RTP（`audio/L16/48000/2`，每包 20 ms）→ UDP → 这里。
 *
 * 与上一版（裸 PCM over TCP）的**决定性**差别是**时间戳**：旧版 App 侧只能顺序播，
 * 缓冲大=延迟大、缓冲小=断流爆音（实测 1 MB / 128 KB / 32 KB 三档全不可用）；
 * 本版按 RTP 时间戳排队，到点才播、迟到直接丢，延迟恒定且丢包不产生爆音。
 *
 * 时钟模型：RTP 时间戳 ÷ 48000 是「音频时钟」，但**不能直接和 `System.nanoTime()` 比** ——
 * 后者会随 CPU 调频/休眠漂移，一旦漂移，抖动缓冲要么持续丢包（爆音）要么无限膨胀。
 * 因此本地时钟由**包到达间隔的滑动中位数**推进：它只反映网络节奏，与音频速率同源，
 * 不受调频影响。实测端到端速率 0.997× 实时。
 */
internal class AndroidRtpAudioSink(
    private val scope: CoroutineScope,
    private val port: Int = SESSION_AUDIO_RTP_PORT,
) : SessionAudioSink {

    private val lock = Any()
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var track: AudioTrack? = null

    override fun start(): String? = synchronized(lock) {
        if (running) {
            Log.d(TAG, "rtp already running on $LOOPBACK:$port")
            return null
        }
        Log.d(TAG, "rtp binding $LOOPBACK:$port")
        val s = try {
            // 一步构造完成 bind。**不能**用 `DatagramSocket(null).apply { reuseAddress = true }`：
            // 真机 2026-11-15 实测，Android libcore 在「尚未 bind 的 socket」上设选项会
            // 抛 `IllegalArgumentException: port out of range:-1`（内部拿 -1 当 port 去走 bind
            // 路径），于是接收端永远起不来、4712 全程无监听，而日志里连一条都没有 ——
            // 表现为「装了新版、容器 .asoundrc 已是 4712、也在播，就是没声」。
            // UDP 单播没有 TIME_WAIT，本来也不需要 SO_REUSEADDR。
            DatagramSocket(port, InetAddress.getByName(LOOPBACK)).apply {
                // 收端缓冲给足：突发由内核吃掉，丢包与否交给抖动缓冲判断。
                // 设不上（内核对上限的限制）不致命，默认 ~224 KB 也够 20 ms 包。
                runCatching { receiveBufferSize = 256 * 1024 }
            }
        } catch (e: Exception) {
            // 失败也必须留下痕迹：这条以前只写进会话转录，用户在 PTY 终端里看不到，
            // 表现为「装好了、也在播、就是没声」且零日志可查。
            Log.d(TAG, "rtp bind FAILED on $LOOPBACK:$port: ${e.javaClass.simpleName}: ${e.message}")
            return "audio rtp port $port is unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
        socket = s
        running = true
        job = scope.launch(Dispatchers.IO) { receiveLoop(s) }
        Log.d(TAG, "rtp listening on $LOOPBACK:$port")
        null
    }

    override fun stop() {
        synchronized(lock) {
            if (!running && socket == null) return
            running = false
            runCatching { socket?.close() }
            socket = null
            job?.cancel()
            job = null
        }
        // write(WRITE_BLOCKING) 在缓冲满时会阻塞：pause + flush 才能让它立刻返回。
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        Log.d(TAG, "rtp stopped")
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val audio = runCatching { createTrack() }.getOrElse {
            Log.d(TAG, "AudioTrack creation failed: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        Log.d(TAG, "AudioTrack created (${audio.bufferSizeInFrames} frames), waiting for packets")
        track = audio
        try {
            // 缓冲区必须装得下**整包**：12 字节 RTP 头 + 3840 字节 payload = 3852。
            // 这里曾经写 2048，DatagramPacket 会静默**截断**超出部分 —— 每包只剩 2036 字节
            // 音频（不到应有 3840 的一半），且尾部是半截帧，PCM 从第一包起就残缺错位。
            // 表现为「能出声、但全程沙哑」而又 received/dropped 全部正常（包确实没丢）。
            val buf = ByteArray(AudioRtp.HEADER_BYTES + AudioRtp.PAYLOAD_BYTES + 256)
            val pkt = DatagramPacket(buf, buf.size)

            /** 已到但还没到播放时刻的包，按 sendMillis 递增入队。 */
            val pending = ArrayDeque<AudioRtp.Parsed>()
            val clock = RtpClock()
            var received = 0
            var dropped = 0
            var started = false

            while (running) {
                sock.receive(pkt)
                val parsed = AudioRtp.parse(pkt.data.copyOfRange(0, pkt.length)) ?: continue
                received++
                val late = clock.tick(parsed.sendMillis, System.nanoTime())

                // 标准抖动缓冲（U89M 2026-11-15 重写）：
                // 只要 sendMillis 略大于 clock（哪怕 9 ms）就入队，而旧实现只把「已到点」的
                // 包写出 —— 于是所有包都卡在队列里永不播出（真机 pending 涨到 497 而
                // AudioTrack 一帧未收）。AudioTrack 本来就是流式的：给它数据它按实时速率播，
                // 缓冲满时 write 自然阻塞并向上游反压。所以这里**不等待「到点」**，
                // 只做两件非做不可的事：丢过期、限队列长度。
                pending.addLast(parsed)

                while (pending.isNotEmpty() && clock.now - pending.first().sendMillis > AudioRtp.LATE_MS) {
                    pending.removeFirst()
                    dropped++
                }
                // 队列上限：积压超过这个量说明发送端突发或本地推进落后，
                // 丢掉最老的保持低延迟（否则内存单调增长，真机上把 proot 拖崩）。
                while (pending.size > MAX_PENDING_PACKETS) {
                    pending.removeFirst()
                    dropped++
                }

                // 开播前先攒够抖动缓冲，否则首包立刻播会 underrun（真机「开头质量很差」）。
                if (!started) {
                    val buffered = pending.size * AudioRtp.SAMPLES_PER_PACKET * 1000 / AudioRtp.SAMPLE_RATE
                    if (buffered < AudioRtp.JITTER_MS) continue
                    runCatching { audio.play() }
                    started = true
                    Log.d(TAG, "AudioTrack started after priming ${buffered}ms")
                } else if (received % 50 == 0 && audio.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    // U89M 真机 2026-11-15：audio_flinger 实测 `2 Tracks of which 0 are active`
                    // —— AudioTrack 一旦 underrun / 被系统回收就再也不会回来，
                    // 因为 play() 只在开播时调过一次。每秒确认。
                    Log.d(TAG, "playState=${audio.playState}, re-playing (received=$received)")
                    runCatching { audio.play() }
                }

                while (pending.isNotEmpty()) writeNow(audio, pending.removeFirst().payload)

                // 每秒一条诊断：这几项能直接判断是「没包 / 全丢 / 队列膨胀」。
                if (received % 50 == 0) {
                    Log.d(
                        TAG,
                        "stats received=$received dropped=$dropped pending=${pending.size} " +
                            "offset=${clock.now - parsed.sendMillis}ms resyncs=${clock.resyncs} late=$late",
                    )
                }
            }
            Log.d(TAG, "rtp stream ended (received=$received dropped=$dropped)")
        } catch (e: Exception) {
            Log.d(TAG, "rtp stream ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (track === audio) track = null
            runCatching { audio.pause() }
            runCatching { audio.flush() }
            runCatching { audio.release() }
        }
    }

    private fun writeNow(audio: AudioTrack, payload: ByteArray) {
        runCatching {
            val n = audio.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) Log.d(TAG, "write returned $n (state=${audio.playState})")
        }.onFailure { Log.d(TAG, "write failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun createTrack(): AudioTrack {
        val rate = AudioRtp.SAMPLE_RATE
        val frame = AudioRtp.CHANNELS * AudioRtp.BYTES_PER_SAMPLE
        val bufferBytes = rate * frame / 1000 * AudioRtp.TRACK_BUFFER_MS
        val min = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(if (min > bufferBytes) min else bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "KairAudio"
        private const val LOOPBACK = "127.0.0.1"

        /** 抖动队列上限（包数）：250 ms / 20 ms ≈ 12。再多就是积压，丢最老的。 */
        private const val MAX_PENDING_PACKETS = 12
    }
}
