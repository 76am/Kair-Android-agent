package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.math.abs

/**
 * 大图入库前缩放（U23）：端点上限是**端点能力**（[ProviderTransport.maxImagePixels]），
 * 缩放发生在**唯一入库路径**上。这里只测 JVM 可验的部分：目标尺寸数学 + 入库路由；
 * 真实 Bitmap 解码 / 重编码只能上机看（`BitmapFactory` 在 JVM 单测里不可用）。
 */
class ImageDownscalerTest {

    /** 合法 1×1 PNG（本仓库图片用例的既有桩）。 */
    private val tinyPng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

    private val longcatLimit = 5_242_880L

    @Test
    fun `a 12MP image is scaled under the endpoint cap without changing aspect`() {
        val target = ImageDownscaler.targetSize(4032, 3000, longcatLimit)
        assertNotNull("12101120px 同级大图必须触发缩放", target)
        target!!
        assertTrue(
            "缩放后像素数必须 ≤ 端点上限，实际 ${target.width}×${target.height}",
            target.width.toLong() * target.height <= longcatLimit,
        )
        val ratio = 4032.0 / 3000.0
        assertTrue(
            "长宽比必须保持（实际 ${target.width}×${target.height}）",
            abs(target.width.toDouble() / target.height - ratio) < 0.01,
        )
    }

    @Test
    fun `the exact reported pixel count is brought under the cap`() {
        // 真机报错 actual: 12101120px；这里用等价像素数的极端长宽比验证数学本身。
        val target = ImageDownscaler.targetSize(512, 23_635, longcatLimit)
        assertNotNull(target)
        assertTrue(target!!.width.toLong() * target.height <= longcatLimit)
    }

    @Test
    fun `images within the cap keep their original bytes`() {
        assertNull("未超限不应缩放", ImageDownscaler.targetSize(1000, 1000, longcatLimit))
        assertNull("非法尺寸不缩放", ImageDownscaler.targetSize(0, 100, longcatLimit))
        assertNull("未声明上限（0 / 负数）不缩放", ImageDownscaler.targetSize(4000, 3000, 0))
    }

    @Test
    fun `a failing scaler degrades to the original bytes without blocking the send`() {
        // 缩放能力是注入进来的（内核不引 Android 图形类型），它抛异常由内核这一处兜住：
        // 原图照常落库，不丢图、不打断发送。
        val workspace = Files.createTempDirectory("kair-scale-throw").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("throwing", longcatLimit)
        ProviderTransports.register(transport)
        try {
            val stored = ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> error("坏 base64 / 内存不足一类平台失败") },
            ) as Part.Image
            assertNotNull("缩放失败也必须落库", stored.ref)
            assertEquals("降级后存的必须是原图字节", tinyPng, store.read(stored.ref!!))
        } finally {
            ProviderTransports.unregister(transport.id)
        }
    }

    @Test
    fun `attach leaves a text-only message untouched`() {
        // 纯文字路径字节不变（缓存前缀契约）：早退必须在取配置与缩放之前。
        // 「取配置」不是无害读操作 —— 宿主按当前会话模型解析端点，测试底座还按序号取脚本，
        // 多取一次就会把后面的回合错位（B 审时踩过）。
        val workspace = Files.createTempDirectory("kair-scale-text").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val message = ChatMessage(role = Role.USER, parts = listOf(Part.Text("hello")))
        var called = 0
        var configsRead = 0
        val attached = runBlocking {
            ImageDownscaler.attach(
                message,
                configProvider = { configsRead++; config("none") },
                attachments = store,
                scaler = { _, _, _ -> called++; null },
            )
        }
        assertEquals("纯文字消息不该走缩放", 0, called)
        assertEquals("纯文字消息也不该去解析端点配置", 0, configsRead)
        assertEquals("原对象原样返回", message, attached)
        assertSame(message, attached)
    }

    @Test
    fun `attach scales an inline image once and returns a reference`() {
        val workspace = Files.createTempDirectory("kair-scale-attach").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("attach-capped", longcatLimit)
        ProviderTransports.register(transport)
        try {
            val message = ChatMessage(
                role = Role.USER,
                parts = listOf(Part.Text("看这张"), Part.Image(base64 = tinyPng, mediaType = "image/png")),
            )
            var calls = 0
            val attached = runBlocking {
                ImageDownscaler.attach(
                    message,
                    configProvider = { config(transport.id) },
                    attachments = store,
                    scaler = { _, _, _ ->
                        calls++
                        ImageDownscaler.Scaled(Base64.getEncoder().encodeToString(byteArrayOf(9)), "image/jpeg")
                    },
                )
            }
            assertEquals("一条消息里的内联图只缩放一次", 1, calls)
            val image = attached.images.single()
            assertNotNull("应返回引用式部件", image.ref)
            assertNull("本体不再内联", image.base64)
            assertEquals("正文部件不受影响", "看这张", (attached.parts.first() as Part.Text).text)
        } finally {
            ProviderTransports.unregister(transport.id)
        }
    }

    @Test
    fun `store routes an over-cap image through the single scaler before persisting`() {
        val workspace = Files.createTempDirectory("kair-scale").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("capped", longcatLimit)
        ProviderTransports.register(transport)
        var calls = 0
        try {
            val scaled = ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ ->
                    calls++
                    ImageDownscaler.Scaled(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "image/jpeg")
                },
            ) as Part.Image
            assertEquals("缩放器必须被调用一次", 1, calls)
            assertNotNull("缩放后的本体应落为内容寻址引用", scaled.ref)
            assertNull("本体不应再内联在日志里", scaled.base64)
            assertEquals("重编码后 MIME 必须跟着变", "image/jpeg", scaled.mediaType)
            assertEquals(
                "落盘字节必须是缩放器的输出，而不是原图",
                Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                store.read(scaled.ref!!),
            )
        } finally {
            ProviderTransports.unregister(transport.id)
        }
    }

    @Test
    fun `store keeps the original bytes when the endpoint declares no cap`() {
        val workspace = Files.createTempDirectory("kair-scale-none").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("uncapped", null)
        ProviderTransports.register(transport)
        var calls = 0
        try {
            val stored = ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> calls++; null },
            ) as Part.Image
            assertEquals("未声明上限时不应调用缩放器", 0, calls)
            assertNotNull("无上限也要照旧落盘", stored.ref)
            assertEquals("字节路径必须与改动前一致", tinyPng, store.read(stored.ref!!))
            assertEquals("MIME 不变", "image/png", stored.mediaType)
        } finally {
            ProviderTransports.unregister(transport.id)
        }
    }

    @Test
    fun `store never touches ref-only or non-image parts`() {
        val workspace = Files.createTempDirectory("kair-scale-ref").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val ref = store.put(tinyPng, "image/png")!!
        val refOnly = Part.Image(ref = ref, base64 = null)
        val text = Part.Text("hello")
        val noScaler = ImageDownscaler.Scaler { _, _, _ -> error("不该被调用") }
        assertEquals(refOnly, ImageDownscaler.store(refOnly, config("none"), store, noScaler))
        assertEquals(text, ImageDownscaler.store(text, config("none"), store, noScaler))
    }

    /**
     * 真机报「缩放没生效」而单测全绿 —— 因为降级路径**一行日志都没有**，四条落点
     * （没上限 / 上限读取失败 / 缩放器返回 null / 缩放器抛）在设备上无法区分。
     * 这里钉住：每条决策都必须留下一行，且带判定所需的字段。
     */
    @Test
    fun `the log names the branch and the endpoint when no cap is declared`() {
        val workspace = Files.createTempDirectory("kair-log-nocap").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("log-uncapped", null)
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> null },
                log = { lines += it },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
        }
        val line = lines.single()
        assertTrue("必须写明落点：\n$line", line.contains("branch=no-cap"))
        assertTrue("必须写明调的是哪个端点（provider / transport / url）：\n$line", line.contains("url=https://example.test/v1"))
        assertTrue("必须写明上限本身：\n$line", line.contains("cap=none"))
        assertTrue("必须写明内联本体大小：\n$line", line.contains("b64=${tinyPng.length}"))
    }

    /** 缩放器抛（真机上最可能是位图解码 OOM）不能静默吞掉：异常类型与消息要落进同一行。 */
    @Test
    fun `the log carries the failure when the scaler throws`() {
        val workspace = Files.createTempDirectory("kair-log-degraded").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("log-throwing", longcatLimit)
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            val stored = ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> throw OutOfMemoryError("Failed to allocate a 48318352 byte allocation") },
                log = { lines += it },
            ) as Part.Image
            assertTrue("降级必须留痕并带上异常类型：\n${lines.single()}", lines.single().contains("branch=degraded"))
            assertTrue(
                "异常消息是判定依据，必须一起落进日志：\n${lines.single()}",
                lines.single().contains("OutOfMemoryError"),
            )
            assertNotNull("抛了也要照旧落库（不丢图）", stored.ref)
        } finally {
            ProviderTransports.unregister(transport.id)
        }
    }

    /** 缩放成功那条要写明**真正落盘的像素与 MIME**（stored=WxH）—— 这是「到底有没有缩小」的直接证据。 */
    @Test
    fun `the log reports the pixels and mime actually stored`() {
        val workspace = Files.createTempDirectory("kair-log-scaled").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("log-scaled", longcatLimit)
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            ImageDownscaler.store(
                Part.Image(base64 = bigJpegStub, mediaType = "image/jpeg"),
                config(transport.id),
                store,
                // 桩：输出换成合法的 1×1 PNG，验证日志读的是**落盘后**的尺寸而不是入参。
                scaler = { _, _, _ -> ImageDownscaler.Scaled(tinyPng, "image/png") },
                log = { lines += it },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
        }
        val line = lines.single()
        assertTrue("必须写明落点：\n$line", line.contains("branch=scaled"))
        assertTrue("必须写明落盘的 MIME 与像素：\n$line", line.contains("stored=image/png 1x1"))
        assertTrue("必须写明端点上限：\n$line", line.contains("cap=$longcatLimit"))
    }

    /** 缩放器返回 null（未超限或解码失败）与「没上限」是两条不同落点，日志必须分得开。 */
    @Test
    fun `the log distinguishes a scaler that declined from an endpoint without a cap`() {
        val workspace = Files.createTempDirectory("kair-log-null").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("log-null", longcatLimit)
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> null },
                log = { lines += it },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
        }
        val line = lines.single()
        assertTrue("有上限但缩放器没动字节：\n$line", line.contains("branch=no-scale"))
        assertTrue("上限要照常写明：\n$line", line.contains("cap=$longcatLimit"))
    }

    /** 读上限本身抛（注册的传输层实现有 bug）也是独立落点：不能混进「这端点没上限」。 */
    @Test
    fun `the log separates a cap lookup that threw from an endpoint without a cap`() {
        val workspace = Files.createTempDirectory("kair-log-caperror").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = object : ProviderTransport {
            override val id = "log-cap-throw"
            override fun maxImagePixels(config: ProviderConfig): Long? = error("插件实现崩了")
            override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = emptyFlow()
        }
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            ImageDownscaler.store(
                Part.Image(base64 = tinyPng, mediaType = "image/png"),
                config(transport.id),
                store,
                scaler = { _, _, _ -> null },
                log = { lines += it },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
        }
        val line = lines.single()
        assertTrue("读上限抛必须与「未声明上限」分开：\n$line", line.contains("branch=cap-error"))
        assertTrue("异常类型要落进同一行：\n$line", line.contains("err=IllegalStateException"))
    }

    /** 纯文字路径一行都不许打：它既拖慢入库，也会把真机日志冲得看不出重点。 */
    @Test
    fun `attach logs one decision per inline image and nothing for text-only messages`() {
        val workspace = Files.createTempDirectory("kair-log-attach").toFile()
        val store = AttachmentStore.forWorkspace(workspace)
        val transport = CappedTransport("log-attach", longcatLimit)
        ProviderTransports.register(transport)
        val lines = mutableListOf<String>()
        try {
            val textOnly = ChatMessage(role = Role.USER, parts = listOf(Part.Text("hello")))
            runBlocking {
                ImageDownscaler.attach(
                    textOnly,
                    configProvider = { config(transport.id) },
                    attachments = store,
                    scaler = { _, _, _ -> null },
                    log = { lines += it },
                )
                ImageDownscaler.attach(
                    ChatMessage(
                        role = Role.USER,
                        parts = listOf(Part.Text("看这两张"), Part.Image(base64 = tinyPng, mediaType = "image/png")),
                    ),
                    configProvider = { config(transport.id) },
                    attachments = store,
                    scaler = { _, _, _ -> null },
                    log = { lines += it },
                )
            }
        } finally {
            ProviderTransports.unregister(transport.id)
        }
        assertEquals("纯文字不产生日志，一张内联图恰好一条", 1, lines.size)
    }

    private fun config(
        transportId: String,
        baseUrl: String = "https://example.test/v1",
    ) = ProviderConfig(
        providerId = "test",
        transportId = transportId,
        baseUrl = baseUrl,
        apiKey = "",
    )

    /** 非 1×1 的桩本体，用来确认日志里的 `b64=` 取的是入参长度、`stored=` 取的是落盘尺寸。 */
    private val bigJpegStub = Base64.getEncoder().encodeToString(ByteArray(64) { (it % 251).toByte() })

    private class CappedTransport(
        override val id: String,
        private val limit: Long?,
    ) : ProviderTransport {
        override fun maxImagePixels(config: ProviderConfig): Long? = limit
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = emptyFlow()
    }
}
