package dev.kair.app.crash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.kair.app.MainActivity
import dev.kair.app.R
import dev.kair.app.core.plugin.dynamic.DynamicPluginHost
import dev.kair.app.ui.theme.KairTheme
import java.io.File

/**
 * 崩溃界面：运行在独立的 `:crash` 进程，主进程崩溃后被系统拉起，展示错误摘要与
 * 完整堆栈，并允许把日志导出到共享交付目录 `/sdcard/Download/KairDownloads/`。
 *
 * 若崩溃来自第三方插件，这里还会给出恢复手段：单独禁用该插件或一键禁用全部第三方
 * 插件并重启（直接写 `plugins/disabled.json`，下次启动不再装载它们）。
 *
 * 之所以独立进程：主线程的 `Looper` 已随崩溃退出，同进程内再起 Activity 也收不到
 * 生命周期回调。独立进程不受影响，能正常渲染。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
        val report = intent.getStringExtra(EXTRA_LOG_PATH)
            ?.let { path -> runCatching { File(path).readText() }.getOrNull() }
            .orEmpty()
        val exportedPath = intent.getStringExtra(EXTRA_EXPORTED_PATH)
        // 直接扫描插件目录做来源识别：本进程没有主进程的注册表，只能按清单匹配堆栈。
        val pluginsRoot = File(filesDir, "kair/plugins")
        val installed = CrashAnalyzer.scanInstalledPlugins(pluginsRoot)
        val origin = CrashAnalyzer.origin(report, installed)

        setContent {
            KairTheme {
                CrashScreen(
                    summary = summary,
                    report = report,
                    exportedPath = exportedPath,
                    origin = origin,
                    thirdPartyIds = installed.map { it.id },
                    pluginsRoot = pluginsRoot,
                )
            }
        }
    }

    companion object {
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_LOG_PATH = "log_path"
        const val EXTRA_EXPORTED_PATH = "exported_path"
    }
}

@Composable
private fun CrashScreen(
    summary: String,
    report: String,
    exportedPath: String?,
    origin: CrashOrigin,
    thirdPartyIds: List<String>,
    pluginsRoot: File,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.crash_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.crash_intro), style = MaterialTheme.typography.bodyMedium)
            if (!exportedPath.isNullOrBlank()) {
                Text(
                    stringResource(R.string.crash_already_exported, exportedPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 崩溃来源：第三方插件（定位到具体插件时给出名称）或内核错误。
            Text(
                originText(origin),
                style = MaterialTheme.typography.bodyMedium,
                color = if (origin is CrashOrigin.Kernel) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            // 恢复手段：来自第三方插件时才提供（内核错误禁用插件无济于事）。
            if (origin is CrashOrigin.Plugin) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { disableAndRestart(context, pluginsRoot, setOf(origin.id)) },
                ) { Text(stringResource(R.string.crash_disable_plugin, origin.name)) }
            }
            if (thirdPartyIds.isNotEmpty()) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { disableAndRestart(context, pluginsRoot, thirdPartyIds.toSet()) },
                ) { Text(stringResource(R.string.crash_disable_all_plugins)) }
            }
            // 完整堆栈：可滚动、可选中复制；占满剩余高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                        Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { (context as? Activity)?.finish(); Process.killProcess(Process.myPid()) }) {
                    Text(stringResource(R.string.crash_exit))
                }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        val saved = CrashHandler.exportToDownload(report)
                        status = if (saved != null) {
                            context.getString(R.string.crash_exported_hint, saved.absolutePath)
                        } else {
                            context.getString(R.string.crash_export_failed)
                        }
                    },
                ) { Text(stringResource(R.string.crash_export)) }
            }
        }
    }
}

/** 崩溃来源文案。 */
@Composable
private fun originText(origin: CrashOrigin): String = when (origin) {
    CrashOrigin.Kernel -> stringResource(R.string.crash_origin_kernel)
    CrashOrigin.UnknownPlugin -> stringResource(R.string.crash_origin_unknown_plugin)
    is CrashOrigin.Plugin -> stringResource(R.string.crash_origin_plugin, origin.name, origin.id)
}

/** 禁用给定第三方插件 id（写 `disabled.json`）并重启主界面，随后结束崩溃进程。 */
private fun disableAndRestart(context: Context, pluginsRoot: File, ids: Set<String>) {
    runCatching { DynamicPluginHost.disable(pluginsRoot, ids) }
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    runCatching { context.startActivity(intent) }
    (context as? Activity)?.finish()
    Process.killProcess(Process.myPid())
}
