package dev.kair.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.browser.BrowserActionLine
import dev.kair.app.core.browser.BrowserConsoleLine
import dev.kair.app.core.browser.BrowserNetworkLine
import dev.kair.app.core.browser.BrowserPanelState
import dev.kair.app.ui.theme.MonoTrace
import dev.kair.app.ui.theme.MonoTraceMeta
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun timeOf(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat)

@Composable
internal fun BrowserConsoleTab(
    controller: AndroidBrowserController,
    state: BrowserPanelState,
    modifier: Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ListHeader(state.console.size, onClear = controller::clearConsole)
            if (state.console.isEmpty()) {
                EmptyList(R.string.browser_console_empty, Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.console.asReversed()) { line -> ConsoleRow(line) }
                }
            }
        }
    }
}

@Composable
internal fun BrowserNetworkTab(
    controller: AndroidBrowserController,
    state: BrowserPanelState,
    modifier: Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ListHeader(state.network.size, onClear = controller::clearNetwork)
            if (state.network.isEmpty()) {
                EmptyList(R.string.browser_network_empty, Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.network.asReversed()) { line -> NetworkRow(line) }
                }
            }
        }
    }
}

@Composable
internal fun BrowserActionsTab(state: BrowserPanelState, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            ListHeader(state.actions.size, onClear = null)
            if (state.actions.isEmpty()) {
                EmptyList(R.string.browser_actions_empty, Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.actions.asReversed()) { line -> ActionRow(line) }
                }
            }
        }
    }
}

@Composable
private fun ListHeader(count: Int, onClear: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            count.toString(),
            style = MonoTraceMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.browser_clear)) }
        }
    }
}

@Composable
private fun EmptyList(textRes: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConsoleRow(line: BrowserConsoleLine) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.level.uppercase(),
                style = MonoTraceMeta,
                color = consoleColor(line.level),
                modifier = Modifier.weight(1f),
            )
            Text(timeOf(line.at), style = MonoTraceMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            line.message,
            style = MonoTrace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NetworkRow(line: BrowserNetworkLine) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.method, style = MonoTraceMeta, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                line.status?.toString() ?: if (line.error != null) "ERR" else "—",
                style = MonoTraceMeta,
                color = networkColor(line),
            )
            line.durationMs?.let {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${it}ms",
                    style = MonoTraceMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(timeOf(line.at), style = MonoTraceMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            line.url,
            style = MonoTrace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        line.error?.let {
            Text(
                it,
                style = MonoTrace,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionRow(line: BrowserActionLine) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line.tool, style = MonoTraceMeta, color = actionColor(line.ok), modifier = Modifier.weight(1f))
            Text(timeOf(line.at), style = MonoTraceMeta, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            line.detail,
            style = MonoTrace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun consoleColor(level: String): Color = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun networkColor(line: BrowserNetworkLine): Color = when {
    line.error != null || (line.status ?: 0) >= 500 -> MaterialTheme.colorScheme.error
    (line.status ?: 0) >= 400 -> MaterialTheme.colorScheme.tertiary
    line.status != null -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun actionColor(ok: Boolean): Color =
    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
