package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.ui.components.pluginDisplayText
import dev.kair.app.ui.icons.PluginGlyph

class InfoPlugin(private val container: AppContainer) : Plugin {
    override val id = "dev.kair.plugin.info"
    override val name = "信息"
    override val description = "应用、服务商与插件详情。"

    override fun activate(ctx: PluginContext) {
        ctx.panel(
            PanelContribution(
                id = "panels.info",
                title = "信息",
                icon = PluginGlyph.Info,
                order = 1000,
                content = { InfoPanel(container) },
            ),
        )
    }
}

@Composable
private fun InfoPanel(container: AppContainer) {
    val active = container.activeModel()
    val def = active?.let { ModelCatalog.byId(it.providerId) }
    val plugins = container.plugins.installed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.info_subtitle), style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.info_provider), style = MaterialTheme.typography.labelMedium)
                Text(
                    def?.displayName ?: stringResource(R.string.info_none_selected),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.info_model), style = MaterialTheme.typography.labelMedium)
                Text(
                    active?.model.orEmpty().ifBlank { stringResource(R.string.info_none_selected) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.info_tools), style = MaterialTheme.typography.labelMedium)
                Text(container.tools.all().joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.info_plugins, plugins.size), style = MaterialTheme.typography.titleSmall)
        plugins.forEach { p ->
            Column {
                Text(pluginDisplayText(p.name, p.nameRes), style = MaterialTheme.typography.bodyMedium)
                Text(pluginDisplayText(p.description, p.descriptionRes), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
