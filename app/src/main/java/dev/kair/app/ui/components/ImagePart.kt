package dev.kair.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.model.Part
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码态：`null`（produceState 初值）表示**解码中**；[bitmap] 为 null 表示
 * **解码失败 / 不可用**。用一个包装类型区分「尚未解码」与「解码失败」，避免解码
 * 完成前误显示「图片不可用」。
 */
private class ImageLoad(val bitmap: ImageBitmap?)

/**
 * 进程内图片位图缓存：按 base64 内容的稳定 key 缓存已解码位图，列表滚动回看时不再
 * 重复解码。容量按条目数计（16 张），足以覆盖一屏图片又不至于常驻大量大图。
 * 用 `by lazy` 延迟创建，避免仅引用本文件其它顶层函数的 JVM 单测在类加载时就
 * 触碰 Android 的 `LruCache`（单测里 android 桩会抛异常）。
 */
private val imageCache: LruCache<String, ImageBitmap> by lazy { LruCache(16) }

/**
 * 渲染一个图片部件。**延迟解码**：仅当本条消息被 LazyColumn 组合（进入可视区）时，
 * 才在后台线程把 base64 解码为位图 —— 长列表首屏因此不再同步解码全部大图而造成掉帧。
 * 解码结果进 [imageCache]，回看免再解码。解码失败（或只有 url）时退化为一行提示，
 * 避免整条消息因图片坏掉而消失。点按可放大查看。
 */
@Composable
internal fun ImagePart(
    image: Part.Image,
    modifier: Modifier = Modifier,
    /** 引用式图片（本体在附件存储、不在日志里）的取本体入口；内联 base64 时可为 null。 */
    attachments: AttachmentStore? = null,
) {
    // 缓存 key：内联用 base64 / url 全串，引用式用内容寻址的 sha256 —— 三者都稳定，
    // 且读回本体前即可定位，避免同一张图每次组合都重新解码。
    val cacheKey = remember(image.base64, image.url, image.ref) {
        image.base64 ?: image.url ?: image.ref?.sha256
    }
    var enlarged by remember { mutableStateOf(false) }
    val load by produceState<ImageLoad?>(
        initialValue = cacheKey?.let { key -> imageCache.get(key)?.let { ImageLoad(it) } },
        key1 = cacheKey,
    ) {
        val key = cacheKey
        val cached = key?.let { imageCache.get(it) }
        if (cached != null) {
            value = ImageLoad(cached)
            return@produceState
        }
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                // 内联：解码 base64；引用式：从附件存储读回本体字节。两者都缺 → 不可用。
                val bytes = image.base64?.let { Base64.decode(it, Base64.DEFAULT) }
                    ?: image.ref?.let { attachments?.readBytes(it) }
                    ?: return@runCatching null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null && key != null) imageCache.put(key, decoded)
        value = ImageLoad(decoded)
    }

    val state = load
    if (state == null) {
        // 解码中：占据给定尺寸的空白占位块，避免解码完成后列表跳动。
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        return
    }
    val bitmap = state.bitmap
    if (bitmap == null) {
        Text(
            stringResource(R.string.chat_image_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.chat_image),
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { enlarged = true },
    )
    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.chat_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
    }
}

/** 从系统图片选择器返回的 Uri 读出 base64 图片部件；失败返回 null。 */
internal fun readImagePart(container: AppContainer, uri: Uri): Part.Image? = runCatching {
    val resolver = container.appContext.contentResolver
    val mime = resolver.getType(uri) ?: "image/png"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    Part.Image(base64 = Base64.encodeToString(bytes, Base64.NO_WRAP), mediaType = mime)
}.getOrNull()
