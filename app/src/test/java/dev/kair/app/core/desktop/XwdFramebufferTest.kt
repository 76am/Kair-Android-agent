package dev.kair.app.core.desktop

import dev.kair.app.core.KotlinSourceScanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * U89 §F · 共享帧缓冲：xwd 头解析与像素色序（纯函数，合成头，不需要容器）。
 *
 * 头字段偏移直接按 X11 的 XWDFileHeader 定义手算，**不从实现推导**。
 */
class XwdFramebufferTest {

    @Test
    fun `parses a standard xvfb x24 header`() {
        val header = parseXwdFramebufferHeader(xwdHeader(width = 1600, height = 720))
        assertNotNull(header)
        requireNotNull(header)
        assertEquals(1600, header.width)
        assertEquals(720, header.height)
        assertEquals(24, header.depth)
        assertEquals(32, header.bitsPerPixel)
        assertEquals(6400, header.bytesPerLine)
        assertTrue(header.lsbFirst)
        assertEquals(100, header.pixelOffset)
        // 一帧 = 100 字节头 + 1600*4*720 像素
        assertEquals(100L + 6400L * 720L, header.requiredFileSize)
    }

    @Test
    fun `pixel offset includes the window name and the color table`() {
        val withName = parseXwdFramebufferHeader(
            xwdHeader(headerSize = 132),
        )
        assertEquals("header_size 含窗口名", 132, requireNotNull(withName).pixelOffset)

        val withColors = parseXwdFramebufferHeader(
            xwdHeader(ncolors = 2),
        )
        assertEquals("颜色表按 12 字节/项", 100 + 2 * 12, requireNotNull(withColors).pixelOffset)
    }

    @Test
    fun `byte order selects the pixel byte layout`() {
        assertTrue(requireNotNull(parseXwdFramebufferHeader(xwdHeader(byteOrder = 0))).lsbFirst)
        assertFalse(requireNotNull(parseXwdFramebufferHeader(xwdHeader(byteOrder = 1))).lsbFirst)
    }

    @Test
    fun `rejects files xvfb never writes`() {
        assertNull("版本不是 7", parseXwdFramebufferHeader(xwdHeader(version = 6)))
        assertNull("不是 ZPixmap", parseXwdFramebufferHeader(xwdHeader(format = 1)))
        assertNull("不是 24 位深", parseXwdFramebufferHeader(xwdHeader(depth = 16)))
        assertNull("不是 32bpp", parseXwdFramebufferHeader(xwdHeader(bitsPerPixel = 24)))
        assertNull("行宽小于 width*4", parseXwdFramebufferHeader(xwdHeader(bytesPerLine = 6400 - 4)))
        assertNull("行宽不是 4 的倍数", parseXwdFramebufferHeader(xwdHeader(bytesPerLine = 6402)))
        assertNull("非 TrueColor", parseXwdFramebufferHeader(xwdHeader(visualClass = 5)))
        assertNull("RGB 掩码不是标准布局", parseXwdFramebufferHeader(xwdHeader(redMask = 0x000000FF.toInt())))
        assertNull("头太短", parseXwdFramebufferHeader(xwdHeader(headerSize = 99)))
        assertNull("半截文件", parseXwdFramebufferHeader(ByteArray(64)))
    }

    @Test
    fun `little endian bgra swizzles to argb`() {
        // 内存里是 B=0x33 G=0x22 R=0x11 X=0xAB，小端读成 0xAB112233。
        assertEquals(0xFF112233.toInt(), xwdPixelToArgb(0xAB112233.toInt(), lsbFirst = true))
    }

    @Test
    fun `big endian rgbx swizzles to argb`() {
        // 内存里是 R=0x11 G=0x22 B=0x33 X=0xAB，大端读成 0x112233AB。
        assertEquals(0xFF112233.toInt(), xwdPixelToArgb(0x112233AB, lsbFirst = false))
    }

    @Test
    fun `ready requires the full frame to be written`() {
        val file = File.createTempFile("kair-xwd-", ".bin")
        try {
            val header = requireNotNull(parseXwdFramebufferHeader(xwdHeader(width = 8, height = 4)))
            assertFalse("空文件不算就绪", isXwdFramebufferReady(file))
            file.writeBytes(xwdHeader(width = 8, height = 4))
            assertFalse("只有头、没有像素不算就绪", isXwdFramebufferReady(file))
            file.appendBytes(ByteArray(header.pixelBytes.toInt()))
            assertTrue("头 + 整帧像素才就绪", isXwdFramebufferReady(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `frame read skips the header and the color table`() {
        // 真机形态：Xvfb 即使 TrueColor 也写 256 项颜色表 ⇒ 像素从 3232 字节处才开始。
        // 漏掉 pixelOffset 会让整幅画面右移 pixelOffset/4 像素（真机 808 px，右侧被裁）。
        val header = requireNotNull(
            parseXwdFramebufferHeader(xwdHeader(width = 4, height = 2, ncolors = 3)),
        )
        assertEquals("头 100 + 3×12 颜色表", 136, header.pixelOffset)
        val buffer = ByteBuffer.allocate(header.requiredFileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        // 头 + 颜色表：填成醒目的哨兵值，被误读成像素就一眼看出来
        repeat(header.pixelOffset / 4) { buffer.putInt(0x00FF00FF) }
        val pattern = IntArray(header.width * header.height) { 0x00203040 + it }
        pattern.forEach { buffer.putInt(it) }
        buffer.flip() // 取的是 remaining，写完要翻回来

        val out = ByteArray(pattern.size * 4)
        readRawFrame(buffer, header, out)
        val decoded = IntArray(pattern.size) { argbAt(out, it * 4, lsbFirst = true) }
        assertArrayEquals("行起点要在颜色表之后", pattern.map { OPAQUE_BLACK_A or it }.toIntArray(), decoded)
    }

    @Test
    fun `frame read advances by bytes per line not by width`() {
        // bytes_per_line 大于 width*4 时（填充），每行必须按行宽跳，不能按像素数跳。
        val header = requireNotNull(
            parseXwdFramebufferHeader(xwdHeader(width = 3, height = 2, bytesPerLine = 16)),
        )
        val buffer = ByteBuffer.allocate(header.requiredFileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        // 头占位（真实文件里是头 + 颜色表，这里内容无所谓，只要位置对齐）
        repeat(header.pixelOffset / 4) { buffer.putInt(0) }
        // 行 0：3 个像素 + 1 个填充；行 1：另 3 个像素 + 1 个填充
        listOf(1, 2, 3, 0xDEAD, 4, 5, 6, 0xDEAD).forEach { buffer.putInt(it) }
        buffer.flip()
        val out = ByteArray(6 * 4)
        readRawFrame(buffer, header, out)
        val decoded = IntArray(6) { argbAt(out, it * 4, lsbFirst = true) }
        assertArrayEquals(
            intArrayOf(1, 2, 3, 4, 5, 6).map { OPAQUE_BLACK_A or it }.toIntArray(),
            decoded,
        )
    }

    @Test
    fun `dirty bands only reports the bands that changed`() {
        // 4 行一带、8 行高 ⇒ 2 带；只改第 5 行的 1 个像素，应只有第 2 带为 true。
        val width = 4
        val current = ByteArray(width * 8 * 4)
        val previous = current.copyOf()
        current[(5 * width + 2) * 4] = 0x7F
        val changed = BooleanArray(2)
        assertTrue(changedBandsBytes(current, previous, width, 8, 4, changed))
        assertArrayEquals(booleanArrayOf(false, true), changed)

        // 一模一样 ⇒ false，且一个带都不标
        val none = BooleanArray(2) { true }
        assertFalse(changedBandsBytes(previous, previous, width, 8, 4, none))
        assertArrayEquals(booleanArrayOf(false, false), none)
    }

    @Test
    fun `band rects carry the band position and argb pixels`() {
        val width = 2
        val height = 5
        val bandRows = 2
        // 每行一个可辨认的灰度（小端 BGRA：B=G=R=v, X=0）
        val frame = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val v = (i / width).toByte()
            frame[i * 4] = v
            frame[i * 4 + 1] = v
            frame[i * 4 + 2] = v
        }
        val changed = booleanArrayOf(false, true, true) // 带 1 = 第 2–3 行，带 2 = 第 4 行（不足一带）
        val rects = bandRectsFromBytes(frame, changed, width, height, bandRows, lsbFirst = true)

        assertEquals(2, rects.size)
        assertEquals(2, rects[0].y)
        assertEquals(2, rects[0].h)
        assertEquals(width, rects[0].w)
        assertEquals(0, rects[0].x)
        assertArrayEquals(
            intArrayOf(0xFF020202.toInt(), 0xFF020202.toInt(), 0xFF030303.toInt(), 0xFF030303.toInt()),
            rects[0].pixels,
        )
        // 最后一带只有 1 行，不能越过帧高
        assertEquals(4, rects[1].y)
        assertEquals(1, rects[1].h)
        assertEquals(2, rects[1].pixels.size)
    }

    @Test
    fun `frame read must use bulk byte copy not int by int iteration`() {
        // 防复发（2026-11-14 真机 25 fps）：`asIntBuffer().get(IntArray)` 在 Java 层
        // 逐 int 循环，一帧 4.4 MB 要 ~20 ms；换成 ByteBuffer.get 的原生 memcpy 只要 ~0.7 ms。
        // 一旦有人改回 IntBuffer 逐元素读，这条就红灯。
        val source = sourceFile("app/src/main/java/dev/kair/app/core/desktop/XwdFramebuffer.kt").readText()
        val code = stripComments(source)
        assertFalse(
            "读帧不得再走 asIntBuffer 逐 int 循环（4.4 MB / 20 ms → 60 fps 卡在 25）",
            code.contains("asIntBuffer"),
        )
        assertTrue("readRawFrame 必须用 ByteBuffer.get 做整块 memcpy", code.contains("buffer.get(out, 0,"))
    }

    @Test
    fun `reader only needs a valid header to start`() {
        // 就绪判据与读循环共用同一份头解析：这里只钉住「文件大小」口径。
        val header = requireNotNull(parseXwdFramebufferHeader(xwdHeader(width = 8, height = 4)))
        assertEquals(32, header.bytesPerLine)
        assertEquals(100L + 32L * 4L, header.requiredFileSize)
    }

    /** 从仓库根开始找文件（守卫要读源码；测试 cwd 是 app/）。 */
    private fun sourceFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) {
                return dir.resolve(relativePath)
            }
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/）")
    }

    private fun stripComments(source: String): String = KotlinSourceScanner.stripped(source)

    /** 合成 xwd 固定头（100 字节，大端），字段偏移按 X11 XWDFileHeader。 */
    private fun xwdHeader(
        width: Int = 1600,
        height: Int = 720,
        depth: Int = 24,
        bitsPerPixel: Int = 32,
        bytesPerLine: Int = width * 4,
        byteOrder: Int = 0,
        ncolors: Int = 0,
        headerSize: Int = 100,
        version: Int = 7,
        format: Int = 2,
        visualClass: Int = 4,
        redMask: Int = 0x00FF0000,
        greenMask: Int = 0x0000FF00,
        blueMask: Int = 0x000000FF,
    ): ByteArray {
        val bytes = ByteArray(maxOf(headerSize, XWD_HEADER_SIZE))
        fun put(offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
        put(0, headerSize)
        put(4, version)
        put(8, format)
        put(12, depth)
        put(16, width)
        put(20, height)
        put(28, byteOrder)
        put(32, 32) // bitmap_unit
        put(36, 0) // bitmap_bit_order
        put(40, 32) // bitmap_pad
        put(44, bitsPerPixel)
        put(48, bytesPerLine)
        put(52, visualClass)
        put(56, redMask)
        put(60, greenMask)
        put(64, blueMask)
        put(68, 8) // bits_per_rgb
        put(72, 0) // colormap_entries
        put(76, ncolors)
        put(80, width) // window_width
        put(84, height) // window_height
        return bytes
    }
}

/** ARGB 的不透明位（测试里的期望值拼装用）。 */
private const val OPAQUE_BLACK_A = 0xFF000000.toInt()
