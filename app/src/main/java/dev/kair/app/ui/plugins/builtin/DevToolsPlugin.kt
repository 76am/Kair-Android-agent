package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.plugin.CommandContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.SettingsSectionContribution

/**
 * Demonstrates the extension points that go beyond panels/actions: slash commands,
 * a Settings section, and a request-header hook. Every contribution here is what a
 * third-party plugin could do — none of it needs core edits.
 */
class DevToolsPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.devtools"
    override val name = "开发者工具"
    override val description = "斜杠命令、运行时信息，演示命令 / 设置分区 / 请求头钩子。"

    override fun activate(ctx: PluginContext) {
        ctx.command(
            CommandContribution(
                name = "compact",
                title = "压缩当前会话",
                description = "把此前的对话折叠成一份检查点摘要。",
                run = { it.session?.compactNow() },
            ),
        )
        ctx.command(
            CommandContribution(
                name = "abort",
                title = "停止当前生成",
                description = "中止正在进行的回合并丢弃排队消息。",
                run = { it.session?.abort() },
            ),
        )
        ctx.command(
            CommandContribution(
                name = "reload",
                title = "重载插件",
                description = "重新扫描并装载 plugins/ 下的动态插件（重读磁盘清单与脚本）；已打开的会话下一回合即生效，无需新建会话。",
                run = { container.dynamicPlugins.discover() },
            ),
        )
        ctx.command(
            CommandContribution(
                name = "gc",
                title = "清理未引用附件",
                description = "回收当前项目下没有被任何会话引用的图片本体（压缩、编辑撤回、删除会话后可能残留，体积只增不减）。",
                run = { c -> c.session?.let { container.gcAttachmentsFor(it) } },
            ),
        )

        ctx.settingsSection(
            SettingsSectionContribution(
                id = "settings.devtools",
                title = "插件运行时",
                order = 500,
                content = { DevToolsSection(container) },
            ),
        )

        // before_provider_headers: tag outbound requests so an endpoint's logs can
        // attribute them to this client. Benign for OpenAI-compatible and Anthropic APIs.
        ctx.onBeforeProviderHeaders { headers -> headers + ("X-Kair-Client" to "kair") }
    }
}

/** Settings section contributed by [DevToolsPlugin]: a read-only runtime summary. */
@Composable
private fun DevToolsSection(container: AppContainer) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.devtools_providers, ModelCatalog.providers.size),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.devtools_commands, container.plugins.commands().size),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.devtools_hooks, container.plugins.hooks.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
