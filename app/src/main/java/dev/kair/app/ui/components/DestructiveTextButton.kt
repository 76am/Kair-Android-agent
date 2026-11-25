package dev.kair.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 破坏性操作（删除 / 覆盖 / 清空）的确认按钮。
 *
 * 标签统一用 error 色：确认框里两个按钮长得一样时，误点「确定」的代价不可逆，
 * 颜色是唯一在点下去之前就能读到的警示。**新增破坏性确认框一律用它**，
 * 不要各写各的 `TextButton { Text(...) }`。
 */
@Composable
internal fun DestructiveTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}
