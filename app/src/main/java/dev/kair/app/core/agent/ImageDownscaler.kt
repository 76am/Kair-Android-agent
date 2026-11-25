package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.model.AttachmentRef
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 图片**入库前**按端点像素上限缩放的**内核侧接缝**（U23）。
 *
 * 为什么放在入库路径上：用户附图、`read_image` 工具截图、MCP 图片三个构造点产出的都是内联
 * `Part.Image`，全部经 [AgentSession.append] → [attach] 落库；把缩放放在这条**唯一入库路径**上，
 * 既保证「写进历史的图已经合规」，又不碰请求装配（不改写已有回合，缓存前缀稳定）。
 *
 * 本文件**不引 Android 图形类型**（`docs/代码规范.md` §2 与 `ArchitectureGuardTest`）：
 * 像素级解码 / 重编码是**能力**，由装配层实现 [Scaler] 注入（见 `app/app/BitmapImageScaler.kt`）。
 * 端点上限来自 `ProviderTransport.maxImagePixels`；未声明上限的端点保持原字节路径不变。
 */
object ImageDownscaler {

    /** 缩放后的目标尺寸。 */
    data class PixelSize(val width: Int, val height: Int)

    /** 缩放后的图片本体与新 MIME 类型（重编码后类型可能变化，如 PNG → JPEG）。 */
    data class Scaled(val base64: String, val mediaType: String)

    /**
     * 位图缩放能力：把内联 base64 等比缩到 [maxPixels] 以内并重编码；
     * 无需缩放、解码失败或平台不支持时返回 `null`（= 按原图落库，不静默丢图）。
     */
    fun interface Scaler {
        fun scale(base64: String, mediaType: String, maxPixels: Long): Scaled?
    }

    /**
     * 不缩放。只给单测用；生产必须由宿主注入位图实现，
     * 漏接会由 `ImageScalingGuardTest` 的接线断言跑红（不是靠这里兜住）。
     */
    val None: Scaler = Scaler { _, _, _ -> null }

    /**
     * 缩放决策日志的默认落点（什么都不做）。宿主把它接到 logcat，见 `AppContainer` 的
     * `imageLog = `；生产接线由 `ImageScalingGuardTest` 钉住，不靠默认值兜。
     */
    val NoLog: (String) -> Unit = {}

    /** 计算等比缩放后的目标尺寸；未超限、尺寸非法或上限非法时返回 null（= 不动原图）。 */
    fun targetSize(width: Int, height: Int, maxPixels: Long): PixelSize? {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return null
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return null
        val ratio = sqrt(maxPixels.toDouble() / pixels.toDouble())
        var targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        var targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        // 浮点取整可能让乘积比上限多出个位数，按较长边再收一点，保持视觉比例几乎不变。
        while (targetWidth.toLong() * targetHeight > maxPixels) {
            if (targetWidth >= targetHeight) targetWidth-- else targetHeight--
            if (targetWidth <= 0 || targetHeight <= 0) return null
        }
        return PixelSize(targetWidth, targetHeight)
    }

    /**
     * 消息级入口：把一条消息里的内联图片按端点上限缩放后落地为内容寻址附件，
     * 返回「引用式」消息 —— **本体不进日志**。
     *
     * 纯文字、已是引用式、或纯 url（远程图片）的部件原样返回；落地失败（坏 base64 / 磁盘错误）
     * 则退回内联 base64，不阻断发送。
     *
     * [configProvider] 用函数而不是值传：早退分支必须在**取配置之前**，
     * 否则纯文字消息也会去解析一次端点配置（取配置有副作用，测试底座按序号取脚本）。
     */
    suspend fun attach(
        message: ChatMessage,
        configProvider: () -> ProviderConfig,
        attachments: AttachmentStore,
        scaler: Scaler,
        log: (String) -> Unit = NoLog,
    ): ChatMessage {
        if (message.images.none { it.ref == null && !it.base64.isNullOrBlank() }) return message
        val parts = withContext(Dispatchers.IO) {
            val config = configProvider()
            message.parts.map { store(it, config, attachments, scaler, log) }
        }
        return message.copy(parts = parts)
    }

    /**
     * 把一个消息部件按端点上限缩放后落盘；非内联图片原样返回。
     *
     * [scaler] 是平台能力（内核不实现位图）；端点上限读取失败 / 插件未声明一律按「无上限」降级，
     * 不阻断发送 —— 但**每条落点都会经 [log] 留一行**，否则真机上「缩放没生效」与
     * 「缩放器崩了」看起来一模一样（U23 追因踩过的坑）。
     */
    fun store(
        part: Part,
        config: ProviderConfig,
        attachments: AttachmentStore,
        scaler: Scaler,
        log: (String) -> Unit = NoLog,
    ): Part {
        val image = part as? Part.Image ?: return part
        val base64 = image.base64
        if (image.ref != null || base64.isNullOrBlank()) return part
        var capFailure: Throwable? = null
        val maxPixels = runCatching {
            ProviderTransports.byId(config.transportId)?.maxImagePixels(config)
        }.onFailure { capFailure = it }.getOrNull()
        // 缩放能力由外部注入，可能抛（坏 base64 / 内存不足）：**降级为原图的落点只有这一处**，
        // 保证「缩放失败不丢图、不阻断发送」这条不变量由内核自己守住。
        var scaleFailure: Throwable? = null
        val scaled = maxPixels?.takeIf { it > 0 }?.let { cap ->
            runCatching { scaler.scale(base64, image.mediaType, cap) }
                .onFailure { scaleFailure = it }
                .getOrNull()
        }
        val storedBase64 = scaled?.base64 ?: base64
        val storedType = scaled?.mediaType ?: image.mediaType
        val ref = attachments.put(storedBase64, storedType)
        log(decisionLine(config, base64, image.mediaType, maxPixels, capFailure, scaleFailure, scaled != null, ref))
        return when {
            ref != null -> image.copy(base64 = null, ref = ref, mediaType = storedType)
            // 落盘失败但已缩放：至少把缩小后的内联本体发出去，别退回超限原图。
            scaled != null -> image.copy(base64 = storedBase64, mediaType = storedType)
            else -> image
        }
    }

    /**
     * 一次入库决策的一行日志（ASCII，供 `adb logcat` 归因；不是给用户看的文案）。
     *
     * 为什么必须有：真机报「大图还是 400」而 JVM 单测全绿，因为**四条降级落点在设备上
     * 长得一模一样** —— 端点没声明上限、读上限抛、缩放器返回 null、缩放器抛（多为位图
     * OOM）都表现为「原样发出去」。`stored=` 取的是**落盘后**引用的真实尺寸，不是入参猜测。
     */
    private fun decisionLine(
        config: ProviderConfig,
        base64: String,
        mediaType: String,
        maxPixels: Long?,
        capFailure: Throwable?,
        scaleFailure: Throwable?,
        scaled: Boolean,
        ref: AttachmentRef?,
    ): String = buildString {
        append("kair.image branch=")
        append(
            when {
                capFailure != null -> "cap-error"
                scaleFailure != null -> "degraded"
                maxPixels == null || maxPixels <= 0L -> "no-cap"
                !scaled -> "no-scale"
                ref == null -> "store-failed"
                else -> "scaled"
            },
        )
        append(" provider=").append(config.providerId)
        append(" transport=").append(config.transportId)
        // baseUrl 不含密钥（key 走独立字段），整条打出来才能看出「自定义条目指的是哪个端点」。
        append(" url=").append(config.baseUrl)
        append(" cap=").append(maxPixels?.toString() ?: "none")
        append(" b64=").append(base64.length)
        append(" in=").append(mediaType)
        append(" stored=")
        if (ref != null) {
            append(ref.mediaType).append(' ').append(ref.width).append('x').append(ref.height)
        } else {
            append("inline")
        }
        (capFailure ?: scaleFailure)?.let {
            append(" err=").append(it.javaClass.simpleName).append(": ").append(it.message)
        }
    }
}
