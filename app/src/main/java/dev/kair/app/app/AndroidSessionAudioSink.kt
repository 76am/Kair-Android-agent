package dev.kair.app.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import dev.kair.app.core.proot.SESSION_AUDIO_PORT
import dev.kair.app.core.proot.SessionAudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 会话音频接收端（U89 §E · 路线 B1）：容器 ALSA `file` 插件 → `nc -N 127.0.0.1:5902`
 * → 这里收裸 PCM（`s16le / 48000 / 2ch`）→ [AudioTrack]，走 AudioFlinger 正常出声。
 *
 * 归属容器会话层：由 [AppContainer] 注入 `ContainerSessionHost`，随会话 Running 起停
 * （2026-11-14 审阅回执二 §3：桌面只是使用者，音频是容器级能力）。
 * **不需要 root**：全程不碰 `/dev/snd`，音量/路由/设备切换都归系统音频栈。
 *
 * 容器侧契约由 `SessionAudio.kt` 的 `~/.asoundrc` 固化（`plug` 把任意输入转成
 * `S16_LE / 48000 / 2ch`，再用 `file` 插件写进管道）。
 */
internal class AndroidSessionAudioSink(
    private val scope: CoroutineScope,
    private val port: Int = SESSION_AUDIO_PORT,
) : SessionAudioSink {

    private val lock = Any()
    private var server: ServerSocket? = null
    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var connection: Socket? = null

    @Volatile
    private var track: AudioTrack? = null

    override fun start(): String? = synchronized(lock) {
        if (running) return null
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
            }
        } catch (e: Exception) {
            return "audio port $port is unavailable: ${e.message ?: e.javaClass.simpleName}"
        }
        server = socket
        running = true
        job = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        Log.d(TAG, "listening on $LOOPBACK:$port")
        null
    }

    override fun stop() {
        synchronized(lock) {
            if (!running && server == null) return
            running = false
            runCatching { server?.close() }
            server = null
            job?.cancel()
            job = null
        }
        runCatching { connection?.close() }
        connection = null
        // AudioTrack.write(WRITE_BLOCKING) 在缓冲满时会阻塞：pause + flush 才能让它立刻返回。
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        Log.d(TAG, "stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Log.d(TAG, "accept failed: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            if (!running) {
                runCatching { conn.close() }
                break
            }
            connection = conn
            try {
                play(conn)
            } finally {
                // restart 的 stop → start 窗口里旧连接可能晚于新连接退出：只清自己那一份。
                if (connection === conn) connection = null
                runCatching { conn.close() }
            }
        }
    }

    /** 一条连接 = 容器里某次打开默认 PCM（一个 `nc`）；无混音，第二条连接要等第一条结束。 */
    private fun play(socket: Socket) {
        // 接收缓冲压到 128 KB（≈0.68 秒）：默认 1 MB～16 MB 意味着允许积压 5.5～87 秒，
        // 真机就是「延迟几十秒」。又不能再小：试过 32 KB 会频繁断流，听感变成连续爆音。
        // 剩下那半秒多的定延迟是这条链路的固有代价，见 U89L 交接文档。
        runCatching { socket.receiveBufferSize = RECEIVE_BUFFER_BYTES }
        val audio = runCatching { createTrack() }.getOrElse {
            Log.d(TAG, "AudioTrack creation failed: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        track = audio
        try {
            val input = socket.getInputStream().buffered()
            // 多留一帧：socket 读可能把一帧切成两半，余字节搬到缓冲区头部等下一轮。
            val buffer = ByteArray(CHUNK_BYTES + FRAME_BYTES)
            var carry = 0
            var received = 0L
            var started = false
            while (running) {
                val n = input.read(buffer, carry, buffer.size - carry)
                if (n <= 0) break
                received += n
                val filled = carry + n
                val usable = filled - filled % FRAME_BYTES
                if (usable > 0) {
                    audio.write(buffer, 0, usable, AudioTrack.WRITE_BLOCKING)
                    // **先写数据再开播**（U89L）：`play()` 必须在有数据的时候调。
                    // 以前是先 play 再读 socket —— 而容器侧是 ALSA → `nc` → socket 的
                    // 接力，连接建立到第一块 PCM 到达之间有几十到几百毫秒的空窗。
                    // AudioTrack 那期间空转着消费缓冲，一断就是一次 underrun：
                    // `W AudioTrack: releaseBuffer() ... disabled due to previous underrun`。
                    // 真机上听到的就是「咔嗒 + 噪声 + 重启」的哔哔莎莎声（2026-11-14）。
                    if (!started) {
                        audio.play()
                        started = true
                        Log.d(TAG, "AudioTrack started (pre-filled $usable bytes)")
                    }
                }
                carry = filled - usable
                if (carry > 0) System.arraycopy(buffer, usable, buffer, 0, carry)
            }
            Log.d(TAG, "stream ended after $received bytes (started=$started)")
        } catch (e: Exception) {
            Log.d(TAG, "stream ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // 同 connection：restart 时旧连接收尾不得把新连接的 track 清掉。
            if (track === audio) track = null
            runCatching { audio.pause() }
            runCatching { audio.flush() }
            runCatching { audio.release() }
        }
    }

    private fun createTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        // 200ms 缓冲（U89L，原 100ms）：容器侧是 ALSA `file` 插件 → `nc` → socket → 这里，
        // 中间隔了一个 TCP 回环和两个进程，抖动远大于本机播放器；100ms 时真机反复 underrun。
        // 代价是音画约 200ms 的差（视频看得出一线，但不爆音）。
        val bufferBytes = if (min > BUFFER_BYTES) min else BUFFER_BYTES
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
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "KairAudio"
        private const val LOOPBACK = "127.0.0.1"
        private const val SAMPLE_RATE = 48000
        private const val FRAME_BYTES = 4 // s16le × 2ch
        private const val CHUNK_BYTES = SAMPLE_RATE * FRAME_BYTES / 10 // 100ms（单次读块）

        /** AudioTrack 缓冲：200ms，理由见 [createTrack]。 */
        private const val BUFFER_BYTES = SAMPLE_RATE * FRAME_BYTES / 5 // 200ms

        /**
         * TCP 接收缓冲：128 KB ≈ 0.68 秒 PCM。
         *
         * 默认（`tcp_rmem` 1 MB ~ 16 MB）会积压 5.5～87 秒；但压到 32 KB 又太小时会
         * 频繁断流 —— 实测听感是连续的「噗噗噗」爆音。128 KB 是这两条边界的折中：
         * 约 0.7 秒定延迟，数据不断流。
         */
        private const val RECEIVE_BUFFER_BYTES = 128 * 1024
    }
}
