package dev.kair.app.core.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * U89 §F · 共享帧缓冲（Xvfb `-fbdir`）。
 *
 * Xvfb 把屏幕像素直接写进 `<rootfs>/tmp/Xvfb_screen0`（xwd 格式，in-place 更新）。
 * proot 不隔离文件系统，App 在宿主侧直接打开同一个文件 mmap 即可读到像素，
 * 省掉「x11vnc 抓屏 → Raw 编码 → socket → App 解析」整条链路。
 *
 * 本文件只有两件事：xwd 头的纯函数解析（判据 `XwdFramebufferTest`）与读循环。
 * 无 Android 依赖，core 的架构守卫照样通过。
 */

/** xwd 固定头长度（XWDFileHeader）。窗口名与颜色表紧跟其后。 */
internal const val XWD_HEADER_SIZE = 100

/** Xvfb 落帧缓冲的文件名（容器 `/tmp` 下；proot 不隔离 /tmp）。 */
internal const val SHARED_FRAMEBUFFER_FILE_NAME = "Xvfb_screen0"

/** 一帧里的一个已解码矩形（整帧 / 脏带一起交给 UI，**不逐像素过 Flow**）。 */
internal data class DesktopRect(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray)

private const val XWD_FILE_VERSION = 7

/** xwd 的 pixmap_format：2 = ZPixmap（Xvfb 只写这种）。 */
private const val XWD_ZPIXMAP = 2

/** XWDColor 在文件里的长度：pixel(4) + RGB(6) + flags(1) + pad(1)。 */
private const val XWD_COLOR_SIZE = 12

/** TrueColor 的 visual_class。 */
private const val XWD_TRUE_COLOR = 4

private const val FRAME_POLL_MS = 16L

/** 等 vsync 的超时兜底：UI 线程被卡住时不让读循环永久阻塞（那会让桌面冻结）。 */
private const val VSYNC_TIMEOUT_MS = 20L

/** 空闲兜底轮询：多久没变化、也没输入才降到这个间隔（4 次/秒）。 */
private const val IDLE_POLL_MS = 250L

/**
 * 活跃期：有变化或被唤醒后，这段时间内保持 [FRAME_POLL_MS] 快轮询。
 * 0.7 s 足够盖住任何“动画在两次读之间还没重绘”的空档，又不会把无人看时的 CPU 吊死。
 */
private const val ACTIVE_HOLD_MS = 700L
private const val READY_POLL_MS = 50L
private const val READY_TIMEOUT_MS = 10_000L
private val NEXT_READER_ID = AtomicInteger()

/** 解析后的 xwd 头；[requiredFileSize] 是判定“一帧写全了”的唯一依据。 */
internal data class XwdFramebufferHeader(
    val width: Int,
    val height: Int,
    val depth: Int,
    val bitsPerPixel: Int,
    val bytesPerLine: Int,
    val lsbFirst: Boolean,
    val pixelOffset: Int,
) {
    /** 像素区字节数（含每行末尾按 bytes_per_line 对齐的填充）。 */
    val pixelBytes: Long get() = bytesPerLine.toLong() * height

    /** 文件至少这么大，才说明 X 已经把整帧写进去了。 */
    val requiredFileSize: Long get() = pixelOffset + pixelBytes
}

/**
 * 解析 xwd 固定头（[XWD_HEADER_SIZE] 字节，全部大端）。
 *
 * 只接受 Xvfb x24 会写出的形态：ZPixmap / 32bpp / TrueColor / 标准 RGB 掩码。
 * 返回 null = 这份文件不是我们认识的样子（或还没写全），由调用方继续轮询 / 报错，
 * **不要**在这里抛异常：文件在 X 启动过程中随时可能是半截的。
 */
internal fun parseXwdFramebufferHeader(bytes: ByteArray): XwdFramebufferHeader? {
    if (bytes.size < XWD_HEADER_SIZE) return null
    fun u32(offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    val headerSize = u32(0)
    if (u32(4) != XWD_FILE_VERSION.toLong()) return null
    if (u32(8) != XWD_ZPIXMAP.toLong()) return null
    val depth = u32(12)
    val width = u32(16)
    val height = u32(20)
    val byteOrder = u32(28)
    val bitsPerPixel = u32(44)
    val bytesPerLine = u32(48)
    val visualClass = u32(52)
    val redMask = u32(56)
    val greenMask = u32(60)
    val blueMask = u32(64)
    val ncolors = u32(76)

    if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return null
    if (depth != 24L) return null
    if (bitsPerPixel != 32L) return null
    if (byteOrder != 0L && byteOrder != 1L) return null
    if (headerSize < XWD_HEADER_SIZE.toLong()) return null
    if (visualClass != XWD_TRUE_COLOR.toLong()) return null
    if (redMask != 0x00FF0000L || greenMask != 0x0000FF00L || blueMask != 0x000000FFL) return null
    if (bytesPerLine < width * 4 || bytesPerLine % 4 != 0L) return null
    if (ncolors > MAX_COLORS) return null

    val pixelOffset = headerSize + ncolors * XWD_COLOR_SIZE
    if (pixelOffset > Int.MAX_VALUE) return null
    return XwdFramebufferHeader(
        width = width.toInt(),
        height = height.toInt(),
        depth = depth.toInt(),
        bitsPerPixel = bitsPerPixel.toInt(),
        bytesPerLine = bytesPerLine.toInt(),
        lsbFirst = byteOrder == 0L,
        pixelOffset = pixelOffset.toInt(),
    )
}

/**
 * 把映射里读到的一个 32 位像素转成 `Bitmap.setPixels` 要的 ARGB。
 *
 * 32bpp 下文件里的 4 个字节顺序由头里的 byte_order 决定：
 * - LSBFirst（x86 / ARM 的 Xvfb）：B,G,R,X → 小端读成 `0xXXRRGGBB`，掩掉高位即可；
 * - MSBFirst：R,G,B,X → 大端读成 `0xRRGGBBXX`，右移 8 位。
 * 两种都把透明通道强制成不透明（X 的 X 字节是填充，不是 alpha）。
 */
internal fun xwdPixelToArgb(pixel: Int, lsbFirst: Boolean): Int {
    val rgb = if (lsbFirst) pixel and 0x00FFFFFF else (pixel ushr 8) and 0x00FFFFFF
    return OPAQUE_BLACK or rgb
}

/**
 * 从映射里取一帧**原始字节**，写进 [out]（紧凑排列，每行 `width * 4` 字节，不含行尾填充）。
 *
 * **必须用 [ByteBuffer.get] 而不是 `asIntBuffer().get(IntArray)`**（2026-11-14 真机实测）：
 * 后者在 Java 层逐 int 循环（1.1 M 次 + 端序转换），一帧要 20 ms 左右；而 `ByteBuffer.get`
 * 走 JNI 的 memcpy，同样的 4.4 MB 只要 0.7 ms。差 30 倍，直接决定了 25 fps 还是 60 fps ——
 * 真机读数：`fps≈25 reads=52 idle=52 maxMs=33`，而 glxinfo/glxgears 显示 llvmpipe 能跑 200 fps。
 *
 * **每行起点都要加 [XwdFramebufferHeader.pixelOffset]**，不是从文件第 0 字节开始：
 * `-fbdir` 落的是完整 xwd ——「固定头 + 窗口名 + 颜色表」之后才是像素。Xvfb 即使
 * TrueColor 也会写一份 256 项颜色表，实测头 160 + 256×12 = **3232 字节**。
 * 漏了这一行，整幅画面就会右移 `pixelOffset / 4` 像素、右侧被裁掉，看上去像
 * 「内容全挤到右边、指针卡在左边」（2026-11-13 真机：1544 宽屏面右移 808 px）。
 */
internal fun readRawFrame(buffer: ByteBuffer, header: XwdFramebufferHeader, out: ByteArray) {
    val rowBytes = header.width * 4
    if (header.bytesPerLine == rowBytes) {
        // 无行尾填充（Xvfb 实测形态）：整块一次 memcpy，只走一次 JNI。
        buffer.position(header.pixelOffset)
        buffer.get(out, 0, rowBytes * header.height)
    } else {
        var dst = 0
        for (y in 0 until header.height) {
            buffer.position(header.pixelOffset + y * header.bytesPerLine)
            buffer.get(out, dst, rowBytes)
            dst += rowBytes
        }
    }
}

/**
 * 取 [index] 处（第一个字节）的像素转成 `Bitmap.setPixels` 要的 ARGB。
 *
 * 内存里 4 个字节的顺序由头里的 byte_order 决定：
 * - LSBFirst（x86 / ARM 的 Xvfb）：B,G,R,X → `0xFFRRGGBB`；
 * - MSBFirst：R,G,B,X → 同样结果。
 * 透明通道强制成不透明（X 的那个字节是填充，不是 alpha）。
 */
internal fun argbAt(frame: ByteArray, index: Int, lsbFirst: Boolean): Int {
    val b0 = frame[index].toInt() and 0xFF
    val b1 = frame[index + 1].toInt() and 0xFF
    val b2 = frame[index + 2].toInt() and 0xFF
    return if (lsbFirst) {
        OPAQUE_BLACK or (b2 shl 16) or (b1 shl 8) or b0
    } else {
        OPAQUE_BLACK or (b0 shl 16) or (b1 shl 8) or b2
    }
}

/** 脏带行数：一次最多推 [DIRTY_BAND_ROWS] 行（1544×32×4 ≈ 200 KB），而不是整帧 4.4 MB。 */
private const val DIRTY_BAND_ROWS = 32

/**
 * 按行带（[bandRows] 行一带）比对新旧帧，把“哪些带变了”写进 [out]。
 *
 * 为什么要分带：光标移动一次只动几十个像素，整帧重推 = 白白把 4.4 MB 搬两遍
 * （拷 + swizzle + `setPixels` + 纹理上传），既是垃圾分配源也是掉帧源。
 * 分带后一次拖动只推 1–2 带（≈200 KB），其余帧一次 `!=` 扫描就能判定“没变”。
 *
 * @return 有任何一带变化
 */
internal fun changedBandsBytes(
    current: ByteArray,
    previous: ByteArray,
    width: Int,
    height: Int,
    bandRows: Int,
    out: BooleanArray,
): Boolean {
    val rowBytes = width * 4
    var any = false
    for (b in out.indices) {
        val start = b * bandRows * rowBytes
        val end = min((b + 1) * bandRows, height) * rowBytes
        var i = start
        var diff = false
        while (i < end) {
            if (current[i] != previous[i]) {
                diff = true
                break
            }
            i++
        }
        out[b] = diff
        any = any || diff
    }
    return any
}

/** 把 [changed] 为 true 的带各自转成 ARGB，一个新的 [DesktopRect]（y/height 就是带的位置）。 */
internal fun bandRectsFromBytes(
    current: ByteArray,
    changed: BooleanArray,
    width: Int,
    height: Int,
    bandRows: Int,
    lsbFirst: Boolean,
): List<DesktopRect> {
    val rects = ArrayList<DesktopRect>(2)
    val rowBytes = width * 4
    for (b in changed.indices) {
        if (!changed[b]) continue
        val y0 = b * bandRows
        val rows = min(bandRows, height - y0)
        val pixels = IntArray(width * rows)
        var src = y0 * rowBytes
        var dst = 0
        repeat(rows) {
            repeat(width) {
                pixels[dst++] = argbAt(current, src, lsbFirst)
                src += 4
            }
        }
        rects.add(DesktopRect(0, y0, width, rows, pixels))
    }
    return rects
}

/** 整帧转 ARGB（只在首帧用一次；之后的帧走 [bandRectsFromBytes] 只转变化带）。 */
internal fun argbFromBytes(current: ByteArray, header: XwdFramebufferHeader): IntArray {
    val out = IntArray(header.width * header.height)
    var src = 0
    for (i in out.indices) {
        out[i] = argbAt(current, src, header.lsbFirst)
        src += 4
    }
    return out
}

/** 文件是否已经写出完整一帧（就绪判据，纯 IO 薄封装）。 */
internal fun isXwdFramebufferReady(file: File): Boolean {
    val header = readXwdHeader(file) ?: return false
    return runCatching { file.length() >= header.requiredFileSize }.getOrDefault(false)
}

/** 读出固定头并解析；文件不存在 / 还没写全 / 形态不认识都返回 null。 */
internal fun readXwdHeader(file: File): XwdFramebufferHeader? = runCatching {
    if (file.length() < XWD_HEADER_SIZE) return@runCatching null
    val head = ByteArray(XWD_HEADER_SIZE)
    RandomAccessFile(file, "r").use { it.readFully(head) }
    parseXwdFramebufferHeader(head)
}.getOrNull()

/**
 * 共享帧缓冲读循环：等文件写全 → mmap → 按 [XwdFramebufferHeader.bytesPerLine] 逐行取像素 →
 * 变化才投递（**按脏带**推 [DesktopRect]，UI 直接消费）。
 *
 * **事件驱动 + 空闲退避**（2026-11-14 真机反馈“轮询费 CPU”）：
 * - `inotify` 对 mmap 写**不发事件**（实测 errno 11，两轮写入零事件）——“等文件写入”只能从
 *   另一头等：`wake` 收到指针/键盘事件就立刻读一次；
 * - 无事件时退到 [IDLE_POLL_MS] 兜底轮询（画面自己变的情况，如时钟/光标闪烁）；
 * - **活跃期**：一旦有变化或被唤醒，接下来 [ACTIVE_HOLD_MS] 内都用 [FRAME_POLL_MS] 快轮询；
 * - 活跃期过期且连续无变化，才退到 [IDLE_POLL_MS] 兜底轮询。
 *
 * 为什么需要活跃期（2026-11-14 真机：画面“很有规律一顿一顿”）：
 * 第一版写成「有变化就快、没变化就马上退 250ms」，而动画在相邻两次读之间往往还没重绘 ——
 * 于是轮询变成 `16ms → 250ms → 16ms → 250ms` 的规律交替，松手看画面动就是 4 fps 一格一格。
 * 日志实锤：`fps≈10 reads=29 wake=0 idle=29`（零唤醒、画面自己在变、全走兜底）。
 * 退避必须依据“一段时间内没变化”，不能依据“这一帧没变化”。
 *
 * 为什么是脏带而不是整帧（2026-11-13 真机：F 比 RFB “不够顺滑”）：
 * x11vnc 是**事件驱动 + 只传脏块**，一次光标移动几十个像素就完事；而轮询整帧时，
 * 指针每动一下都要「拷 4.4 MB + 全帧转换 + 新分配 4.4 MB + `setPixels` 4.4 MB + 纹理上传」，
 * 再叠上 33 ms 固定轮询与 GC 抖动，自然比 RFB 钝。分带 + 补偿轮询间隔 + 60 fps 目标
 * 后单帧成本从 MB 级降到百 KB 级。
 *
 * 不做双缓冲：Xvfb 没有撕裂保护（任务书 §F 已登记），实测明显撕裂再补。
 * 只读 mmap 是 in-place 的，所以每帧都要扫一遍与上一帧比对 —— 3.5 MB 的比较远比
 * 每帧都分配并上屏便宜。
 */
internal class XwdFramebufferReader(
    private val file: File,
    private val scope: CoroutineScope,
    /**
     * 「有事发生」信号（U89 F二 · 事件驱动）：UI 在指针/键盘事件时 `trySend`；
     * 读循环等它，等不到就按 [IDLE_POLL_MS] 兜底读一次。CONFLATED：连发只留最新，不堆队列。
     */
    private val wake: ReceiveChannel<Unit>,
    /**
     * 显示节拍（垂直同步）：非 null 时活跃期内**由它定节拍**，不再用 [FRAME_POLL_MS] 自由跑。
     *
     * 为什么需要（2026-11-14）：固定 8 ms 节拍与屏幕的 8.33 ms（120 Hz）vsync 不同步，
     * 相位一直漂 —— 推帧节奏与上屏时机错开，实测 97 fps 但不是 vsync 的整数分频，看着会有轻微 judder。
     * 改成每 vsync 读一次后，帧率稳定落在 120 或 60（看单帧工量是否装得下 8.33 ms），
     * 数字可能变小，但节奏稳定。
     */
    private val vsync: ReceiveChannel<Unit>? = null,
    private val log: (String) -> Unit = {},
    /**
     * 读循环失败回调（x11vnc 下线后 UI 没有 RFB 连接态可看，必须在画面上说出来）：
     * 帧缓冲迟迟不就绪、mmap/读帧抛异常都走这里，参数是可直接展示的中文/技术原因。
     */
    private val onError: (String) -> Unit = {},
    private val onFrame: (List<DesktopRect>) -> Unit,
) {
    private val id = NEXT_READER_ID.incrementAndGet()

    fun start(): Job = scope.launch(Dispatchers.IO) { runLoop() }

    private suspend fun runLoop() {
        val header = awaitReady()
        if (header == null) {
            log("reader #$id not ready: ${file.path}")
            onError("framebuffer not ready: ${file.path}")
            return
        }
        log(
            "reader #$id start ${header.width}x${header.height} depth=${header.depth} " +
                "bpp=${header.bitsPerPixel} stride=${header.bytesPerLine} lsb=${header.lsbFirst}",
        )
        try {
            RandomAccessFile(file, "r").use { raf ->
                val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, header.requiredFileSize)
                val rowBytes = header.width * 4
                val frameBytes = rowBytes * header.height
                val bands = (header.height + DIRTY_BAND_ROWS - 1) / DIRTY_BAND_ROWS
                val changed = BooleanArray(bands)
                var current = ByteArray(frameBytes)
                var previous = ByteArray(frameBytes)
                var hasFrame = false
                var activeUntilMs = 0L
                var pushed = 0
                var reads = 0
                var wakes = 0
                var idles = 0
                var maxWorkNs = 0L
                var windowStart = System.nanoTime()
                while (true) {
                    // 等「有事发生」。
                    // **活跃期内不等待**（2026-11-14 第二个 bug）：以前写成「先 withTimeoutOrNull(16ms)
                    // 再干活」，而读完要 20 ms，于是周期 = 16 + 22 = 38 ms → 只有 26 fps。
                    // 日志特征 `reads=52 idle=52`（每一轮都吃满了 16 ms 超时）。
                    // 正确节拍：活跃期立刻读，读完把「干活耗时」补到 16 ms（见循环尾）。
                    val inActive = System.currentTimeMillis() < activeUntilMs
                    val woken = awaitSignal(inActive)
                    if (woken) wakes++ else if (!inActive) idles++
                    reads++
                    val startedAt = System.nanoTime()
                    readRawFrame(mapped, header, current)
                    var changedNow = false
                    if (!hasFrame) {
                        // 首帧必须整帧（UI 那边是黑屏，只推脏带等于永远残影）。
                        onFrame(listOf(DesktopRect(0, 0, header.width, header.height, argbFromBytes(current, header))))
                        current.copyInto(previous)
                        hasFrame = true
                        changedNow = true
                        pushed++
                    } else if (changedBandsBytes(current, previous, header.width, header.height, DIRTY_BAND_ROWS, changed)) {
                        onFrame(
                            bandRectsFromBytes(current, changed, header.width, header.height, DIRTY_BAND_ROWS, header.lsbFirst),
                        )
                        pushed++
                        changedNow = true
                        val swap = previous
                        previous = current
                        current = swap
                    }
                    // 有变化或被唤醒 → 续活跃期（不是只快一帧）；否则活跃期到点自然退到兜底轮询。
                    val now = System.nanoTime()
                    if (changedNow || woken) activeUntilMs = System.currentTimeMillis() + ACTIVE_HOLD_MS
                    val workNs = now - startedAt
                    if (workNs > maxWorkNs) maxWorkNs = workNs
                    if (now - windowStart >= LOG_WINDOW_NS) {
                        // 让“顺不顺滑”变成可比较的数字：fps 低且 maxMs 小 = 推得少；maxMs 大 = 读得慢。
                        log(
                            "reader #$id fps≈${pushed * 1_000_000_000L / (now - windowStart)} " +
                                "reads=$reads wake=$wakes idle=$idles maxMs=${maxWorkNs / 1_000_000} bands=$bands",
                        )
                        pushed = 0
                        reads = 0
                        wakes = 0
                        idles = 0
                        maxWorkNs = 0L
                        windowStart = now
                    }
                    // 活跃期内把节拍补到 16ms（防连发唤醒把 CPU 打满）；
                    // 兜底超时路径 wait 已经 ≥16ms，这里自然不补（否则周期会翻倍）。
                    // **有 vsync 通道时不补**：节拍由显示端定，再补就变成「vsync + 固定延时」。
                    if (vsync == null && System.currentTimeMillis() < activeUntilMs) {
                        val elapsedMs = workNs / 1_000_000L
                        if (elapsedMs < FRAME_POLL_MS) delay(FRAME_POLL_MS - elapsedMs)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读循环一死，UI 只会停在最后一帧 —— 必须把原因交给画面上的遮罩，
            // 否则用户看到的是「画面定格但点不动」（与旧 RFB 断开同型）。
            log("reader #$id failed: ${e.javaClass.simpleName}: ${e.message}")
            onError(e.message ?: e.javaClass.simpleName)
        } finally {
            // 退出必须留痕：旧读循环没死 = 两个读者互抢 CPU（真机“一顿一顿”排查过一次）。
            log("reader #$id exit")
        }
    }

    /**
     * 等下一拍。活跃期 vsync 模式等 vsync（超时兜底），否则非阻塞取输入信号；
     * 空闲期最多等 [IDLE_POLL_MS]（vsync 模式也只看它，避免空闲时强行每帧读）。
     *
     * @return 是否被输入事件唤醒（vsync 打的拍不算唤醒）
     */
    private suspend fun awaitSignal(inActive: Boolean): Boolean {
        if (inActive) {
            if (vsync != null) {
                // 节拍交给显示端；带超时兜底，防 UI 线程卡住时读循环永久阻塞（桌面冻结）。
                withTimeoutOrNull(VSYNC_TIMEOUT_MS) { vsync.receive() }
                return false
            }
            return wake.tryReceive().isSuccess
        }
        val signal = withTimeoutOrNull(IDLE_POLL_MS) {
            if (vsync != null) vsync.receive() else wake.receive()
        }
        return signal != null
    }

    private suspend fun awaitReady(): XwdFramebufferHeader? {
        var waited = 0L
        while (waited < READY_TIMEOUT_MS) {
            val header = readXwdHeader(file)
            if (header != null && runCatching { file.length() >= header.requiredFileSize }.getOrDefault(false)) {
                return header
            }
            delay(READY_POLL_MS)
            waited += READY_POLL_MS
        }
        return null
    }
}

private const val MAX_DIMENSION = 16_384L
private const val LOG_WINDOW_NS = 2_000_000_000L
private const val MAX_COLORS = 65_536L
private const val OPAQUE_BLACK = 0xFF000000.toInt()
