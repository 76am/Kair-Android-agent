package dev.kair.app.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.ai.CapturedRequest
import dev.kair.app.core.ai.exportTo
import dev.kair.app.core.storage.Delivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出「真实发出过的请求」。
 *
 * 会话只保留最近几次请求（[dev.kair.app.core.ai.RequestCapture.MAX_ENTRIES]），这里全部列出
 * 由新到旧供选择：端点拒收往往不是最后一次，而是刚才某一次带坏参数的尝试。
 *
 * 默认脱敏图片：请求里可能带着用户截图，几十 MB 的 base64 写进 Download 目录既占地方
 * 又泄露隐私。需要复现多模态问题时再勾选；请求体早先因超限被清掉图片的情况，勾选也
 * 补不回来，界面会直接说明。
 */
@Composable
internal fun RequestExportDialog(
    requests: List<CapturedRequest>,
    onDismiss: () -> Unit,
    /** 传回写好的文件；null 表示写失败（无存储权限 / 目录不可写）。 */
    onDone: (File?) -> Unit,
) {
    // 由新到旧：最近一次在最上面，默认选中它。
    val ordered = remember(requests) { requests.asReversed() }
    var selectedIndex by remember(requests) { mutableStateOf(0) }
    val selected = ordered.getOrNull(selectedIndex) ?: return
    // 换一条请求时重置「包含图片」：上一条勾了不代表这一条要带原图。
    var includeImages by remember(selected.at) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.request_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ordered.size > 1) {
                    Text(
                        stringResource(R.string.request_export_pick, ordered.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ordered.forEachIndexed { index, item ->
                        val streamSuffix = if (item.streamFailure != null) {
                            " · " + stringResource(R.string.request_export_stream_failed)
                        } else {
                            ""
                        }
                        RequestEntryRow(
                            label = stringResource(
                                R.string.request_export_entry,
                                timeFormat.format(Date(item.at)),
                                if (item.statusCode != null) {
                                    stringResource(R.string.request_export_status, item.statusCode)
                                } else {
                                    stringResource(R.string.request_export_no_status)
                                },
                                item.body.length / 1024,
                            ) + streamSuffix,
                            selected = index == selectedIndex,
                            onSelect = { selectedIndex = index },
                        )
                    }
                }
                Text(selected.url, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (selected.statusCode != null) {
                        stringResource(
                            R.string.request_export_meta,
                            selected.statusCode,
                            selected.body.length / 1024,
                        )
                    } else {
                        // 没拿到响应：别写成「状态码 0」，那会让人以为服务端回了 0。
                        stringResource(
                            R.string.request_export_meta_no_status,
                            selected.body.length / 1024,
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selected.streamFailure?.let { reason ->
                    // 200 + 空流：状态码那行看不出问题，这里必须把流失败原因摆出来。
                    Text(
                        stringResource(R.string.request_export_stream_failed_reason, reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (selected.bodySkipped) {
                    // 体积过大时连请求体都没序列化，别让用户以为导出的是完整请求。
                    Text(
                        stringResource(R.string.request_export_body_skipped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!selected.imagesKept) {
                    // 请求体超过内存上限时只留了脱敏版，勾选也补不回来 —— 先说明，
                    // 免得用户以为导出的是原图。
                    Text(
                        stringResource(R.string.request_export_images_dropped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = includeImages,
                            onCheckedChange = { includeImages = it },
                        )
                        Text(stringResource(R.string.request_export_include_images))
                    }
                }
                Text(
                    stringResource(R.string.request_export_to, Delivery.PATH),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(Environment.getExternalStorageDirectory(), Delivery.RELATIVE)
                            selected.exportTo(dir, includeImages)
                        }
                        onDone(file)
                    }
                },
            ) { Text(stringResource(R.string.request_export_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.request_export_close)) }
        },
    )
}

@Composable
private fun RequestEntryRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
