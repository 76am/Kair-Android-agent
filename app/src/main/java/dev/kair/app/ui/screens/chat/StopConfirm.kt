package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.QueuedMessage

/** 预览最多展开几条；超出只报数（余量不能说谎）。 */
private const val MAX_PREVIEW_ROWS = 4

/** 单条预览的字符上限 —— 弹窗不该被一条长消息撑爆。 */
private const val MAX_PREVIEW_CHARS = 80

/** 停止确认弹窗要展示的内容（纯数据，可 JVM 单测）。 */
internal data class StopConfirmModel(val dropped: Int, val previews: List<String>, val hidden: Int)

/**
 * 算出「点停止会丢掉什么」：**只数用户自己的排队消息** —— 系统通知沿用 U18 口径
 * （不在排队区露面，也不该出现在这句承诺里）。预览正文为空串表示这条是纯图片，
 * 由界面换成占位文案，不能整行静默消失。
 */
internal fun stopConfirmModel(
    queue: List<QueuedMessage>,
    maxRows: Int = MAX_PREVIEW_ROWS,
): StopConfirmModel {
    val rows = visibleQueue(queue)
    val previews = rows.take(maxRows).map { it.message.text.trim().take(MAX_PREVIEW_CHARS) }
    return StopConfirmModel(dropped = rows.size, previews = previews, hidden = rows.size - previews.size)
}

/**
 * 「停止」键的守门人：返回真正挂给按钮的回调。
 *
 * 有挂着的引导消息时**先弹窗问、确认才中止**。原因是内核那条既有语义：
 * `abort()` 的第一件事就是 `clearQueue()`（`AgentSession.kt:434`），排队的用户消息会被丢掉；
 * 而 U22 之后排队区每条都写着「撤回编辑」，用户自然以为撤回是找回路径 ——
 * 所以丢弃必须发生在**点下去之前**的确认里，事后提示等于没说。
 *
 * 没有东西可丢时不弹窗（多数停止没有任何排队消息，别多加一次点击）。
 * [queue] 传的是**取值函数**而不是快照：列表读的是实况，确认期间回合自己结束、
 * 队列排空时它会跟着变（与 U22 撤回那处同一口径 —— 读实况，不读旧帧）。
 */
@Composable
internal fun StopConfirm(queue: () -> List<QueuedMessage>, onAbort: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val model = stopConfirmModel(queue())

    if (asking && model.dropped > 0) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.chat_stop_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_stop_confirm_body, model.dropped))
                    model.previews.forEach { preview ->
                        Text(
                            text = "· " + preview.ifBlank { stringResource(R.string.chat_stop_confirm_image_only) },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (model.hidden > 0) {
                        Text(
                            stringResource(R.string.chat_stop_confirm_more, model.hidden),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.chat_stop_confirm_note),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onAbort()
                    },
                ) { Text(stringResource(R.string.chat_stop_confirm_action)) }
            },
            // 「继续运行」而不是「取消」：后者在这个语境里会被读成「取消这些引导消息」，正好相反。
            dismissButton = {
                TextButton(onClick = { asking = false }) {
                    Text(stringResource(R.string.chat_stop_confirm_keep))
                }
            },
        )
    }

    return {
        if (stopConfirmModel(queue()).dropped > 0) asking = true else onAbort()
    }
}
