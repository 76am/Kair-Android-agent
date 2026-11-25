package dev.kair.app.core.storage

import dev.kair.app.core.ai.Json
import dev.kair.app.core.model.AttachmentRef
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * 附件存储：内容寻址落盘 / 读回、去重、宽高解析，以及「引用式图片不进日志但能水合」的
 * 序列化兼容（旧的内联 base64 会话仍可读）。
 */
class AttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AttachmentStore(tmp.newFolder())

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // ---- 基本读写 ------------------------------------------------------------

    @Test
    fun `put then read round-trips the body`() {
        val store = store()
        val bytes = "fake-image-bytes".toByteArray()
        val ref = store.put(b64(bytes), "image/png")

        assertNotNull(ref)
        assertEquals(b64(bytes), store.read(ref!!))
        assertTrue(store.fileFor(ref).isFile)
    }

    @Test
    fun `identical bytes dedupe to a single file`() {
        val store = store()
        val bytes = "same".toByteArray()
        val first = store.put(b64(bytes), "image/png")!!
        val second = store.put(b64(bytes), "image/jpeg")!!

        assertEquals(first.sha256, second.sha256)
        assertEquals(store.fileFor(first), store.fileFor(second))
        // 不同 mediaType 只是引用上的元数据，本体仍是一份。
        assertEquals(1, store.fileFor(first).parentFile!!.listFiles()!!.size)
    }

    @Test
    fun `put rejects invalid base64 without throwing`() {
        assertNull(store().put("not base64!!", "image/png"))
    }

    @Test
    fun `read of a missing ref returns null`() {
        assertNull(store().read(AttachmentRef(sha256 = "deadbeef")))
        assertFalse(store().fileFor(AttachmentRef(sha256 = "deadbeef")).exists())
    }

    // ---- 宽高解析 ------------------------------------------------------------

    @Test
    fun `imageSize parses png dimensions`() {
        val (w, h) = AttachmentStore.imageSize(png(640, 480))
        assertEquals(640, w)
        assertEquals(480, h)
    }

    @Test
    fun `imageSize parses gif dimensions`() {
        val (w, h) = AttachmentStore.imageSize(gif(12, 34))
        assertEquals(12, w)
        assertEquals(34, h)
    }

    @Test
    fun `imageSize parses jpeg dimensions`() {
        val (w, h) = AttachmentStore.imageSize(jpeg(1920, 1080))
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `imageSize is zero for unknown formats`() {
        val (w, h) = AttachmentStore.imageSize("hello".toByteArray())
        assertEquals(0, w)
        assertEquals(0, h)
    }

    @Test
    fun `put records the parsed dimensions`() {
        val ref = store().put(b64(png(800, 600)), "image/png")!!
        assertEquals(800, ref.width)
        assertEquals(600, ref.height)
    }

    // ---- 序列化：引用进日志、本体不进 ----------------------------------------

    @Test
    fun `ref-only image serializes without base64 and hydrates from the ref sha`() {
        val message = ChatMessage(
            role = Role.USER,
            parts = listOf(Part.Image(ref = AttachmentRef(sha256 = "abc123", width = 10, height = 20))),
        )
        val encoded = Json.encodeToString(message)

        assertFalse("base64 本体不应进入日志", encoded.contains("base64"))
        assertTrue(encoded.contains("\"ref\""))
        assertTrue(encoded.contains("abc123"))

        val decoded = Json.decodeFromString<ChatMessage>(encoded)
        assertEquals("abc123", decoded.images.single().ref?.sha256)
        assertNull(decoded.images.single().base64)
    }

    @Test
    fun `legacy inline-base64 image still deserializes with a null ref`() {
        // 旧会话格式：只有 base64、没有 ref。必须原样读回，ref 为 null。
        val legacy = """{"id":"m1","role":"user","parts":[{"type":"image","base64":"AAAA","mediaType":"image/png"}]}"""
        val decoded = Json.decodeFromString<ChatMessage>(legacy)

        assertEquals("AAAA", decoded.images.single().base64)
        assertNull(decoded.images.single().ref)
    }

    @Test
    fun `unknown json keys are tolerated`() {
        // 向前兼容：未来版本的字段不应让旧内核读会话失败。
        val json = """{"id":"m1","role":"user","parts":[{"type":"image","base64":"AAAA","future":1}]}"""
        assertEquals("AAAA", Json.decodeFromString<ChatMessage>(json).images.single().base64)
    }

    // ---- 回收：删除无人引用的本体 -------------------------------------------

    @Test
    fun `sweep deletes unreferenced bodies and keeps referenced ones`() {
        val store = store()
        val keep = store.put(b64("keep".toByteArray()), "image/png")!!
        val drop = store.put(b64("drop".toByteArray()), "image/png")!!

        assertEquals(1, store.sweep(setOf(keep.sha256)))

        assertTrue(store.fileFor(keep).isFile)
        assertFalse(store.fileFor(drop).exists())
    }

    @Test
    fun `sweep removes the emptied bucket directory`() {
        val store = store()
        val ref = store.put(b64("only".toByteArray()), "image/png")!!
        val bucket = store.fileFor(ref).parentFile!!

        store.sweep(emptySet())

        assertFalse("分桶清空后应一并删除，避免残留成片空目录", bucket.exists())
    }

    @Test
    fun `sweep spares bodies newer than the grace period`() {
        val store = store()
        val fresh = store.put(b64("fresh".toByteArray()), "image/png")!!

        assertEquals(0, store.sweep(emptySet(), minAgeMillis = 60_000L))

        assertTrue("新近写入的本体在宽限期内应豁免", store.fileFor(fresh).isFile)
    }

    @Test
    fun `sweep ignores non-sha files such as in-flight temp files`() {
        val store = store()
        val ref = store.put(b64("body".toByteArray()), "image/png")!!
        val tmp = File(store.fileFor(ref).parentFile, "${ref.sha256}.tmp").apply { writeText("half-written") }

        store.sweep(emptySet())

        assertTrue("写入中途的 .tmp 不应被回收", tmp.isFile)
    }

    @Test
    fun `sweep on a missing root is a no-op`() {
        assertEquals(0, AttachmentStore(File(tmp.root, "nope")).sweep(emptySet()))
    }

    // ---- 最小测试图（只需头部，CRC / 数据段可省） ----------------------------

    private fun png(w: Int, h: Int): ByteArray {
        val b = ByteArray(24)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(b, 0)
        // 偏移 8：长度(4) + "IHDR"；偏移 16/20：宽 / 高（大端 32 位）。
        "IHDR".toByteArray().copyInto(b, 12)
        writeBe32(b, 16, w)
        writeBe32(b, 20, h)
        return b
    }

    private fun gif(w: Int, h: Int): ByteArray {
        // "GIF89a"(6) + 逻辑屏幕宽/高（小端 16 位，偏移 6 / 8）。
        val b = ByteArray(10)
        "GIF89a".toByteArray().copyInto(b, 0)
        writeLe16(b, 6, w)
        writeLe16(b, 8, h)
        return b
    }

    private fun jpeg(w: Int, h: Int): ByteArray {
        // FF D8 + SOF0 段：长度(2)=11, 精度(1), 高(2 大端), 宽(2 大端)。
        val b = ByteArray(13)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte()
        b[2] = 0xFF.toByte(); b[3] = 0xC0.toByte()
        writeBe16(b, 4, 11)
        b[6] = 8
        writeBe16(b, 7, h)
        writeBe16(b, 9, w)
        return b
    }

    private fun writeBe16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
    }

    private fun writeLe16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte()
    }

    private fun writeBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }
}
