package dev.kair.app.ui.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.browser.BrowserMode
import dev.kair.app.core.browser.BrowserPanelState
import dev.kair.app.ui.icons.PluginGlyph
import dev.kair.app.ui.icons.SvgPathIcon
import dev.kair.app.ui.theme.MonoTraceMeta
import kotlinx.coroutines.launch

@Composable
internal fun BrowserDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
internal fun BrowserHeader(
    fullscreen: Boolean,
    capturing: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SvgPathIcon(PluginGlyph.Globe, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.browser_plugin_name),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onToggleFullscreen != null) {
            TextButton(onClick = onToggleFullscreen, enabled = !capturing) {
                Text(
                    stringResource(
                        if (fullscreen) {
                            R.string.browser_action_exit_fullscreen
                        } else {
                            R.string.browser_action_fullscreen
                        },
                    ),
                )
            }
        }
        TextButton(onClick = onClose, enabled = !capturing) {
            Text(stringResource(R.string.browser_action_close))
        }
    }
}

@Composable
internal fun BrowserToolbar(controller: AndroidBrowserController, state: BrowserPanelState) {
    val backLabel = stringResource(R.string.browser_back)
    val reloadLabel = stringResource(R.string.browser_reload)
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // 子智能体没跑的时候地址栏可以直接输入：用户自己开页面，不用先点接管。
    val agentRunning by BrowserHost.agentRun.collectAsState()
    var editingAddress by rememberSaveable { mutableStateOf(false) }
    var addressDraft by rememberSaveable { mutableStateOf("") }
    val addressFocus = remember { FocusRequester() }
    LaunchedEffect(agentRunning) { if (agentRunning) editingAddress = false }
    LaunchedEffect(editingAddress) {
        if (editingAddress) {
            addressFocus.requestFocus()
            keyboard?.show()
        }
    }
    val submitAddress: () -> Unit = {
        val target = addressDraft.trim()
        if (target.isEmpty()) {
            editingAddress = false
        } else {
            scope.launch {
                val opened = runCatching { controller.navigateFromUser(target) }.isSuccess
                if (opened) {
                    // 用户自己打开的页面直接归用户操作：不用再点接管。
                    controller.setMode(BrowserMode.HUMAN)
                    editingAddress = false
                    keyboard?.hide()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.browser_url_invalid),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = controller::goBack,
            enabled = state.canGoBack,
            modifier = Modifier.semantics { contentDescription = backLabel },
        ) {
            SvgPathIcon(
                PluginGlyph.Back,
                if (state.canGoBack) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                Modifier.size(18.dp),
            )
        }
        IconButton(
            onClick = controller::reload,
            modifier = Modifier.semantics { contentDescription = reloadLabel },
        ) {
            SvgPathIcon(PluginGlyph.Reload, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
        }
        Surface(
            modifier = Modifier.weight(1f).height(34.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SvgPathIcon(
                    PluginGlyph.Lock,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                if (editingAddress) {
                    BasicTextField(
                        value = addressDraft,
                        onValueChange = { addressDraft = it },
                        singleLine = true,
                        textStyle = MonoTraceMeta.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                        modifier = Modifier.weight(1f).focusRequester(addressFocus),
                    )
                } else {
                    Text(
                        text = state.url.takeIf { it.isNotBlank() }?.let(::compactUrl)
                            ?: stringResource(R.string.browser_url_hint),
                        style = MonoTraceMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = !agentRunning) {
                                addressDraft = state.url
                                editingAddress = true
                            },
                    )
                }
                Text(
                    stringResource(R.string.browser_dev_badge),
                    style = MonoTraceMeta,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
internal fun BrowserMeta(controller: AndroidBrowserController, state: BrowserPanelState) {
    val human = state.mode == BrowserMode.HUMAN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (human) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                when {
                    human -> R.string.browser_status_human
                    state.busy -> R.string.browser_status_agent
                    else -> R.string.browser_status_idle
                },
            ),
            style = MonoTraceMeta,
            color = if (human) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { controller.close() },
            enabled = !state.capturing,
        ) {
            Text(stringResource(R.string.browser_close_page))
        }
    }
}

@Composable
internal fun BrowserTabRow(
    selected: Int,
    state: BrowserPanelState,
    onSelect: (Int) -> Unit,
) {
    val consoleError = state.console.any { it.level == "error" }
    val networkError = state.network.any { it.error != null || (it.status ?: 0) >= 400 }
    Row(Modifier.fillMaxWidth()) {
        BrowserTab(
            label = stringResource(R.string.browser_tab_console),
            selected = selected == TAB_CONSOLE,
            dot = consoleError,
            onClick = { onSelect(TAB_CONSOLE) },
            modifier = Modifier.weight(1f),
        )
        BrowserTab(
            label = stringResource(R.string.browser_tab_network),
            selected = selected == TAB_NETWORK,
            dot = networkError,
            onClick = { onSelect(TAB_NETWORK) },
            modifier = Modifier.weight(1f),
        )
        BrowserTab(
            label = stringResource(R.string.browser_tab_actions),
            selected = selected == TAB_ACTIONS,
            dot = false,
            onClick = { onSelect(TAB_ACTIONS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowserTab(
    label: String,
    selected: Boolean,
    dot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clickable(role = Role.Tab, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            if (dot) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** 地址栏把 scheme 去掉，手机宽度下优先露出主机与端口。 */
private fun compactUrl(url: String): String =
    url.removePrefix("http://").removePrefix("https://").trimEnd('/')
