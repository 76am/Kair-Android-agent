package dev.kair.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.tools.FileBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 单文件查看 / 编辑上限；超过则不打开编辑器，避免把巨大文本塞进去。 */
internal const val FILE_EDITOR_MAX_BYTES = 256 * 1024L

/**
 * 「滚动交给外层」的文本行数上限（U75 真机反馈）。
 *
 * 行数在这一档以内：文本域按内容高度铺开，**域自身不再是滚动容器**，滚动由外层承担 ——
 * 首次获得焦点时就不会出现「把域内滚动拉回第一行」。超出这一档仍走域内滚动：
 * 数万行的文本全展开绘制会拖帧，此时保性能优先。
 *
 * 实测依据（2026-11-11）：`go引擎/.kair/memory.md` 原文 522 行、折行后 894 行，
 * 曾因阈值定在 400 而落到旧分支、跳回顶部照旧复现 —— 这一档必须能盖住几百行的记忆文件。
 */
private const val INLINE_SCROLL_MAX_LINES = 2000

/**
 * 文本布局坐标到滚动容器坐标的顶部补偿：`TextLayoutResult` 的 y 从文本首行量起，
 * `OutlinedTextField` 的内容区上还压着 contentPadding 与 label 区。取 20dp 近似就够 ——
 * 光标滚动的目标位置本来就留了一截空白（[CARET_SCREEN_RATIO]），十几 dp 误差看不出来。
 */
private val TEXT_FIELD_TOP_INSET = 20.dp

/** 光标滚入视野时停在视口上方这个比例处（下方留给键盘，中间留出刚编辑的上下文）。 */
private const val CARET_SCREEN_RATIO = 0.35f

/** 视口下方这一比例之内算「会被键盘盖住」，光标越过去就重新滚。 */
private const val CARET_VISIBLE_RATIO = 0.8f

/** 读取结果：区分「成功 / 超限 / 失败」，避免把三者都塞进可空字符串。 */
private sealed interface FileRead {
    data class Ok(val text: String) : FileRead
    data class TooLarge(val bytes: Long) : FileRead
    data class Failed(val message: String?) : FileRead
}

/**
 * 通用文件查看 / 编辑体：读取与保存都走 [backend]（与 `read` / `write` 工具、工作区文件
 * 面板同源），因此容器在场时，`container_shell`（root）建的文件本地读不动会自动升级到容器内读取，
 * 保存同理（失败升级到容器写入）。
 *
 * 工作区文件面板与「声明式设置面板」的文件字段（`SettingField.FileEditor`）共用本组件 ——
 * 两处的查看 / 编辑体验因此完全一致。
 *
 * @param readOnly 只读模式：仅查看，隐藏保存按钮。
 * @param onBack 顶部「返回」回调；为 null 时不显示返回按钮（嵌在对话框里由外部关闭）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileEditorBody(
    backend: FileBackend,
    absolutePath: String,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember(absolutePath) { mutableStateOf<TextFieldValue?>(null) }
    var blocked by remember(absolutePath) { mutableStateOf<String?>(null) }
    var status by remember(absolutePath) { mutableStateOf<String?>(null) }
    var dirty by remember(absolutePath) { mutableStateOf(false) }

    LaunchedEffect(absolutePath) {
        doc = null
        blocked = null
        status = null
        dirty = false
        // 读写都是阻塞 IO，不能留在主线程（大文件会直接冻结界面）。
        val result = withContext(Dispatchers.IO) {
            // 走 backend.size：容器内的文件本地 File.length() 恒为 0，会绕过上限判断。
            val bytes = runCatching { backend.size(absolutePath) }.getOrNull()
            if (bytes != null && bytes > FILE_EDITOR_MAX_BYTES) {
                FileRead.TooLarge(bytes)
            } else {
                runCatching { backend.readText(absolutePath) }.fold(
                    onSuccess = { content ->
                        // 容器内路径的本地 stat 可能为 0，读完再兜一层字符数上限。
                        if (content.length.toLong() > FILE_EDITOR_MAX_BYTES) FileRead.TooLarge(content.length.toLong())
                        else FileRead.Ok(content)
                    },
                    onFailure = { FileRead.Failed(it.message) },
                )
            }
        }
        when (result) {
            is FileRead.TooLarge ->
                blocked = context.getString(R.string.files_too_large, humanSize(result.bytes))

            is FileRead.Failed ->
                blocked = context.getString(R.string.files_read_failed, result.message ?: "")

            is FileRead.Ok ->
                if (result.text.contains('\u0000')) blocked = context.getString(R.string.files_binary)
                else doc = TextFieldValue(result.text)
        }
    }

    Column(modifier.imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { back -> TextButton(onClick = back) { Text(stringResource(R.string.action_back)) } }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!readOnly) {
                TextButton(
                    enabled = dirty,
                    onClick = {
                        val content = doc?.text ?: return@TextButton
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { backend.writeText(absolutePath, content) }
                            }
                            result
                                .onSuccess {
                                    dirty = false
                                    status = context.getString(R.string.files_saved)
                                    // 文件可能被插件用作系统提示段的外部状态（如记忆文件），
                                    // 保存后清一次段落缓存，让下一次请求读到新内容。
                                    SystemPromptBuilder.invalidate()
                                }
                                .onFailure {
                                    status = context.getString(R.string.files_save_failed, it.message ?: "")
                                }
                        }
                    },
                ) { Text(stringResource(R.string.files_save)) }
            }
        }

        val notice = blocked
        val message = status
        val content = doc
        when {
            notice != null -> FileEditorHint(notice)
            message != null && content == null -> FileEditorHint(message)
            content == null -> FileEditorHint(stringResource(R.string.files_loading))
            else -> {
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                var layoutResult by remember(absolutePath) { mutableStateOf<TextLayoutResult?>(null) }
                // `BasicTextField` 而不是 `OutlinedTextField`：M3 的装饰版本没有暴露 `onTextLayout`，
                // 拿不到光标位置，就没法把正在编辑的那一行滚到键盘上方（U75 之二）。
                // 外观自绘：圆角描边 + 基础色，与周边输入框同形。
                val fieldTextStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val outlinedShape = RoundedCornerShape(6.dp)
                val field: @Composable (Modifier) -> Unit = { fieldModifier ->
                    BasicTextField(
                        value = content,
                        onValueChange = { doc = it; dirty = true },
                        modifier = fieldModifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, outlinedShape)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        readOnly = readOnly,
                        textStyle = fieldTextStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { newLayout ->
                            // 只在布局真的变了才写入 state：无条件赋值会让「重组 → 重排 → 回调」
                            // 自激成每帧一圈（实测 12ms 一次，长文件直接发热）。
                            val old = layoutResult
                            if (old == null ||
                                old.size != newLayout.size ||
                                old.lineCount != newLayout.lineCount ||
                                old.layoutInput.text.length != newLayout.layoutInput.text.length
                            ) {
                                layoutResult = newLayout
                            }
                        },
                    )
                }
                if (content.text.count { it == '\n' } <= INLINE_SCROLL_MAX_LINES) {
                    // 外层滚动 + 域内不滚：聚焦时没有可被拉回的内部滚动（U75）。
                    val scrollState = rememberScrollState()
                    val density = LocalDensity.current
                    val topInsetPx = with(density) { TEXT_FIELD_TOP_INSET.toPx() }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        // 至少占满一屏：内容短时观感与原来的 fillMaxSize 文本域一致。
                        val viewportHeight = maxHeight
                        val viewportPx = with(density) { viewportHeight.toPx() }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                        ) {
                            field(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .heightIn(min = viewportHeight),
                            )
                        }
                        // 光标跟随（U75 之二）：点哪一行就把那一行滚到键盘上方，
                        // 首次聚焦不再「弹回顶部」—— 定视口的是光标，不是焦点。
                        LaunchedEffect(content.selection, layoutResult, viewportPx) {
                            val layout = layoutResult ?: return@LaunchedEffect
                            if (viewportPx <= 0f) return@LaunchedEffect
                            val offset = content.selection.start.coerceIn(0, layout.layoutInput.text.length)
                            val caretY = layout.getCursorRect(offset).top + topInsetPx
                            val top = scrollState.value.toFloat()
                            val bottom = top + viewportPx * CARET_VISIBLE_RATIO
                            // 光标已在安全区就什么都不做：输入时不会一直把自己往下顶。
                            if (caretY >= top && caretY <= bottom) return@LaunchedEffect
                            val target = (caretY - viewportPx * CARET_SCREEN_RATIO).toInt()
                            // 瞬时到位而不是动画：聚焦的同一帧里可能已发生一次拉回，
                            // 动画会把它演出来；直接落位用户看不到中间过程。
                            runCatching { scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue)) }
                        }
                    }
                } else {
                    field(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEditorHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
