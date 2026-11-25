package dev.kair.app.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import dev.kair.app.core.agent.ImageDownscaler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 图片缩放的平台实现（U23）：内核在唯一入库路径 [ImageDownscaler.attach] 上调它，
 * `core` 因此不必引 Android 图形类型（`docs/代码规范.md` §2、`ArchitectureGuardTest`）。
 *
 * 全仓**只这一处**做位图解码 / 缩放 / 重编码，由 `ImageScalingGuardTest` 钉死。
 *
 * 质量口径：`inSampleSize` 先按 2 的幂降到仍在上限之上（避免 12MP 原图常驻内存），
 * 再用 `createScaledBitmap(filter = true)` 收到准确尺寸；不做激进质量压缩 ——
 * 无 alpha 走 JPEG 92，有 alpha（截图 / 图标）走 PNG 无损，避免「压糊」与透明区变黑。
 */
object BitmapImageScaler : ImageDownscaler.Scaler {

    /**
     * 解码 → 按 EXIF 转正 → 缩到上限内 → 重编码。尺寸超限需要缩放时才动字节。
     *
     * **允许抛**（坏 base64 / 解码失败 / 内存不足）：降级为原图的落点只有一个，
     * 在内核侧 [ImageDownscaler.store] 里，这里不再自己吞异常。
     */
    override fun scale(base64: String, mediaType: String, maxPixels: Long): ImageDownscaler.Scaled? {
        val bytes = Base64.getDecoder().decode(base64)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // BitmapFactory 不应用 EXIF 方向：目标尺寸要按「转正之后」的宽高算，
        // 否则竖拍照片（Orientation=6/8，宽高互换）会被缩成一个躺倒的尺寸。
        val orientation = orientationOf(bytes)
        // 标记 5 / 6 / 7 / 8（转置 / 90° / 反转置 / 270°）会交换宽高，其余不交换。
        val swap = orientation >= EXIF_TRANSPOSE
        val displayWidth = if (swap) bounds.outHeight else bounds.outWidth
        val displayHeight = if (swap) bounds.outWidth else bounds.outHeight
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        // 走到这里 target 为 null 只有一种正常含义：尺寸本来没超限。
        // bounds=0x0 是解码器读不出尺寸（坏字节），日志里靠 bounds 字段把两者分开。
        val target = ImageDownscaler.targetSize(displayWidth, displayHeight, maxPixels)
            ?: return logNull(
                "under-cap",
                "bounds=${bounds.outWidth}x${bounds.outHeight} exif=$orientation cap=$maxPixels",
            )
        // 降采样后仍解不出位图，真机上基本都是分配失败；内核那行会同时给出退回原图的证据。
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return logNull(
            "decode-failed",
            "bounds=${bounds.outWidth}x${bounds.outHeight} sample=$sample cap=$maxPixels",
        )
        val oriented = orient(decoded, orientation)
        val scaled = if (oriented.width == target.width && oriented.height == target.height) {
            oriented
        } else {
            Bitmap.createScaledBitmap(oriented, target.width, target.height, true)
        }
        val lossless = scaled.hasAlpha()
        val out = ByteArrayOutputStream()
        val format = if (lossless) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        if (!scaled.compress(format, if (lossless) 100 else 92, out)) {
            return logNull("compress-failed", "out=${scaled.width}x${scaled.height} format=$format")
        }
        val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
        logImageScale(
            "kair.bitmap branch=scaled bounds=${bounds.outWidth}x${bounds.outHeight} exif=$orientation " +
                "target=${target.width}x${target.height} sampled=${decoded.width}x${decoded.height} " +
                "out=${scaled.width}x${scaled.height} type=${if (lossless) "image/png" else "image/jpeg"} " +
                "b64=${base64.length}->${encoded.length} in=$mediaType",
        )
        return ImageDownscaler.Scaled(
            base64 = encoded,
            mediaType = if (lossless) "image/png" else "image/jpeg",
        )
    }

    /** 缩放器「不动字节」的三条落点，各自留一行；返回 null 让内核按原图继续落库。 */
    private fun logNull(branch: String, detail: String): ImageDownscaler.Scaled? {
        logImageScale("kair.bitmap branch=$branch $detail")
        return null
    }

    /** 2 的幂降采样：停在「降一级就掉到上限以下」之前，最后一级由 [Bitmap.createScaledBitmap] 收准。 */
    private fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while (true) {
            val next = sample * 2
            val w = width / next
            val h = height / next
            if (w <= 0 || h <= 0 || w.toLong() * h <= maxPixels) return sample
            sample = next
        }
    }

    /** JPEG 里的 EXIF Orientation；读不到（非 JPEG / 无该段）按「正向」处理。 */
    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 按 EXIF 标记把位图转正。方向 5 / 7（转置 / 反转置）含镜像，按业界通用矩阵表处理；
     * 真机如出现镜像反过来，属这一条，不影响常见的 6 / 8（竖拍）。
     */
    private fun orient(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            EXIF_FLIP_HORIZ -> matrix.postScale(-1f, 1f)
            EXIF_ROTATE_180 -> matrix.postRotate(180f)
            EXIF_FLIP_VERT -> matrix.postScale(1f, -1f)
            EXIF_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            EXIF_ROTATE_90 -> matrix.postRotate(90f)
            EXIF_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            EXIF_ROTATE_270 -> matrix.postRotate(270f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // EXIF Orientation 取值（android.media.ExifInterface 的常量在 when 分支里不能当 case 用，
    // 这里给本地别名，语义与官方常量一一对应）。
    private const val EXIF_FLIP_HORIZ = 2
    private const val EXIF_ROTATE_180 = 3
    private const val EXIF_FLIP_VERT = 4
    private const val EXIF_TRANSPOSE = 5
    private const val EXIF_ROTATE_90 = 6
    private const val EXIF_TRANSVERSE = 7
    private const val EXIF_ROTATE_270 = 8
}

/**
 * 把位图编码成 PNG 字节。截图（WebView 视口）不是缩放，但位图格式串全仓只允许这一处，
 * 由 `ImageScalingGuardTest` 钉住；调用方负责 recycle。
 */
fun encodePng(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    out.toByteArray()
}

/**
 * 缩放决策日志的唯一出口：内核侧 [ImageDownscaler.store] 那行与这里的位图行共用一个 tag，
 * 设备上 `adb logcat -s KairImage` 一次就能看出「端点有没有上限、缩到了多少、在哪条落点
 * 退回了原图」。`core` 不引 Android，所以接缝由宿主注入（`AppContainer` 的
 * `imageLog = ::logImageScale`），漏接由 `ImageScalingGuardTest` 钉住。
 */
internal fun logImageScale(line: String) {
    Log.i("KairImage", line)
}
