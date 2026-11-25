package dev.kair.app.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.kair.app.R

/** 主动提示只做一次；用户拒绝后仍可从「设置 → 插件 → 浏览器」手动开启。 */
private var promptShown = false

/** 跳系统「显示在其他应用上层」授权页；个别 ROM 没有该页面，退回应用详情。 */
internal fun openOverlayPermissionSettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.onFailure {
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(details) }
    }
}

/**
 * 后台悬浮截图缺权限时的主动提示（每个进程一次）。
 *
 * Android 没有悬浮窗的运行时权限弹窗，只能跳系统「显示在其他应用上层」页；
 * 所以先弹自己的理由框，用户点「去授权」再跳系统设置。
 * 只在面板可见且有页面时调用 —— 面板在根级常驻组合，不可见时不能把对话框弹到别的界面上。
 */
@Composable
internal fun BrowserOverlayPermissionPrompt(visible: Boolean, hasPage: Boolean) {
    if (!visible || !hasPage || promptShown) return
    val context = LocalContext.current
    if (Settings.canDrawOverlays(context)) return
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        promptShown = true
        show = true
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(stringResource(R.string.browser_overlay_prompt_title)) },
        text = { Text(stringResource(R.string.browser_overlay_prompt_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    show = false
                    openOverlayPermissionSettings(context)
                },
            ) { Text(stringResource(R.string.browser_overlay_grant)) }
        },
        dismissButton = {
            TextButton(onClick = { show = false }) {
                Text(stringResource(R.string.browser_overlay_later))
            }
        },
    )
}
