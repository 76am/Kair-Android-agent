package dev.kair.app.ui.plugins.builtin

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.device.PhantomKiller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * U90 「ADB」面板：无线调试的配对 / 连接 / 授权，以及由它支撑的进程保活开关。
 *
 * 两个区块各自独立、都常驻显示：上面是 adb 连接（配对、连接、清除密钥，任何授权态都可见），
 * 下面是进程保活（版本闸 → 已授权给开关 → 未授权给提示）。清除密钥必须常驻可达——已授权时
 * 面板不再只有引导页，否则想重置配对就无处可点。
 *
 * 面板自负责滚动（PanelContribution 契约）；文案全走 strings.xml。
 */
@Composable
internal fun PhantomKillerPanel(container: AppContainer) {
    val context = container.appContext
    var granted by remember { mutableStateOf(PhantomKillerStore.isGranted(context)) }
    // root 探测会 exec su，放 IO 线程，避免组合期阻塞主线程（结果进程内缓存）。
    var rootOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rootOk = withContext(Dispatchers.IO) { PhantomKillerRoot.available() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdbSection(granted) { granted = PhantomKillerStore.isGranted(context) }
        KeepAliveSection(granted, rootOk)
    }
}

/** adb 连接区块：状态行 + 配对/连接 + 密钥管理。任何授权态都显示。 */
@Composable
private fun AdbSection(granted: Boolean, onStateChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adb = remember { AdbLink(context) }
    // 配对端口是临时的（系统弹窗一关就拒连），连接端口常驻；所以配对过就不该再要配对码。
    var paired by remember { mutableStateOf(adb.hasPairedKeys()) }
    var hasKeys by remember { mutableStateOf(adb.hasKeys()) }
    var pairAddress by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectAddress by remember { mutableStateOf(adb.lastConnectAddress().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 完成态默认收起连接地址（见下）；只有重连失败时才重新放出来让用户改——换了 Wi-Fi
    // 或端口对不上时，没有这个入口就只能「清除密钥」从头来，太重。
    var addressOverride by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phantom_adb_section), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(adbStatusText(granted, paired)),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 已授权且已配对时整段引导都是废话（步骤已走完，且第 3 步指的按钮已经变成 ✓），
            // 换成一句「现在能干什么」。引导只给还没连上的人看。
            val ready = granted && paired
            if (ready) {
                // 完成态不写引导（步骤已走完、按钮已变成 ✓），改为交代「现在能干什么」：
                // 用户配完这一趟就是为了让模型能操作手机，这里是他唯一能看到工具名的位置。
                // 「换 Wi-Fi 工具不消失」写在这儿，是因为工具是常驻注册的（U91 §8.7），
                // 而用户最可能担心的正是换网络之后是不是要从头配一遍。
                Text(
                    stringResource(R.string.phantom_tools_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(if (paired) R.string.phantom_setup_guide_paired else R.string.phantom_setup_guide),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!paired) {
                    PhantomPairFields(pairAddress, { pairAddress = it }, pairingCode, { pairingCode = it })
                }
            }
            if (!ready || addressOverride) {
                OutlinedTextField(
                    value = connectAddress,
                    onValueChange = { connectAddress = it },
                    label = { Text(stringResource(R.string.phantom_connect_host_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val runConnect: () -> Unit = {
                scope.launch {
                    busy = true
                    val failure = runConnectOrPair(
                        context, adb, paired, pairAddress, pairingCode, connectAddress,
                    )
                    busy = false
                    // 连接会生成 / 刷新密钥与配对标记，状态要跟着重查，否则清除密钥按钮不出现。
                    hasKeys = adb.hasKeys()
                    paired = adb.hasPairedKeys()
                    if (failure == null) {
                        addressOverride = false
                        onStateChanged()
                    } else {
                        error = failure
                        addressOverride = true
                    }
                }
            }
            // 主操作位：没完成时是一颗按钮；完成后变成 ✓ 徽章，把「重连」降到下方文字按钮，
            // 免得一个已经做完的动作永远占着主位、还看不出它已经做完了。
            if (ready) {
                AdbAuthorizedBadge()
            } else {
                Button(enabled = !busy, onClick = runConnect) {
                    Text(stringResource(if (paired) R.string.phantom_action_connect else R.string.phantom_action_pair))
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(
                        stringResource(if (paired) R.string.phantom_connecting else R.string.phantom_pairing),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // 已授权后主按钮消失了，但「端口变了 / 换了 Wi-Fi」仍然需要重连一次，能力不能跟着没。
            if (ready) {
                TextButton(enabled = !busy, onClick = runConnect) {
                    Text(stringResource(R.string.phantom_action_reconnect))
                }
            }
            // 已配对态给一个回退：系统里「已配对设备」被删掉过，重新配对才能恢复。
            if (paired) {
                TextButton(
                    onClick = {
                        error = null
                        notice = null
                        paired = false
                    },
                ) {
                    Text(stringResource(R.string.phantom_action_repair))
                }
            }
            if (hasKeys) {
                TextButton(
                    onClick = {
                        adb.clearPaired()
                        hasKeys = false
                        paired = false
                        connectAddress = ""
                        error = null
                        notice = context.getString(R.string.phantom_keys_cleared)
                    },
                ) {
                    Text(stringResource(R.string.phantom_action_clear_keys))
                }
            }
        }
    }
}

/** 状态行：授权与配对是两个独立维度，四种组合各说一句。 */
private fun adbStatusText(granted: Boolean, paired: Boolean): Int = when {
    granted && paired -> R.string.phantom_status_ready
    granted -> R.string.phantom_status_granted
    paired -> R.string.phantom_status_paired_ungranted
    else -> R.string.phantom_status_fresh
}

/**
 * 已授权且已配对的完成态：占住原来主操作那一格，显示 ✓。
 *
 * 刻意做成**不可点**的徽章而不是「点了会重连」的按钮：一个已经做完的动作长期占着主位，
 * 用户会反复去点它、也看不出它已经做完了。重连需求仍在，但它是低频操作，降到下方文字按钮。
 */
@Composable
private fun AdbAuthorizedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.phantom_adb_authorized),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 配对专用两栏（配对地址 + 6 位码）；单独成函数避开方法长度棘轮。 */
@Composable
private fun PhantomPairFields(
    pairAddress: String,
    onPairAddressChange: (String) -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = pairAddress,
        onValueChange = onPairAddressChange,
        label = { Text(stringResource(R.string.phantom_pair_host_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pairingCode,
        onValueChange = onPairingCodeChange,
        label = { Text(stringResource(R.string.phantom_pair_code_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 进程保活区块：版本闸 → 已授权（或 root）给开关 → 否则给「先在上面授权」的说明。 */
@Composable
private fun KeepAliveSection(granted: Boolean, rootOk: Boolean) {
    if (!PhantomKiller.needed(Build.VERSION.SDK_INT)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.phantom_keepalive_title), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.phantom_not_needed), style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.phantom_keepalive_title), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.phantom_explain), style = MaterialTheme.typography.bodySmall)
            if (granted || rootOk) {
                PhantomSwitchContent(rootOk)
            } else {
                Text(
                    stringResource(R.string.phantom_permission_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.phantom_need_grant_first),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                stringResource(R.string.phantom_shizuku_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 已授权（或 root）：一个开关。保活开 = 查杀关。写失败（权限被收回）静默回弹。 */
@Composable
private fun PhantomSwitchContent(rootOk: Boolean) {
    val context = LocalContext.current
    var killerOn by remember { mutableStateOf(PhantomKillerStore.readKillerEnabled(context) ?: true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.phantom_switch_label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = !killerOn,
            onCheckedChange = { keepAlive ->
                val target = !keepAlive
                val ok = if (rootOk) {
                    PhantomKillerRoot.write(target)
                } else {
                    PhantomKillerStore.writeKillerEnabled(context, target)
                }
                if (ok) killerOn = target
            },
        )
    }
    Text(
        stringResource(if (killerOn) R.string.phantom_state_off else R.string.phantom_state_on),
        style = MaterialTheme.typography.bodySmall,
        color = if (killerOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(if (rootOk) R.string.phantom_root_fast else R.string.phantom_granted),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.phantom_switch_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 走连接或配对 + 授权；成功返回 null，失败返回要展示的文案。 */
private suspend fun runConnectOrPair(
    context: Context,
    adb: AdbLink,
    paired: Boolean,
    pairAddress: String,
    pairingCode: String,
    connectAddress: String,
): String? {
    val conn = PhantomKiller.parseAddress(connectAddress)
    val pair = PhantomKiller.parseAddress(pairAddress)
    when {
        conn == null -> return context.getString(R.string.phantom_error_invalid_address)
        paired -> Unit
        pair == null -> return context.getString(R.string.phantom_error_invalid_address)
        pairingCode.isBlank() -> return context.getString(R.string.phantom_error_code)
    }
    val command = PhantomKiller.grantCommand(context.packageName)
    return try {
        if (pair == null) {
            adb.connectAndRun(conn.first, conn.second, command)
        } else {
            adb.pairAndGrant(pair.first, pair.second, pairingCode.trim(), conn.first, conn.second)
        }
        if (PhantomKillerStore.isGranted(context)) null
        else context.getString(R.string.phantom_error_grant, "pm grant")
    } catch (_: TimeoutCancellationException) {
        context.getString(R.string.phantom_error_timeout)
    } catch (e: Exception) {
        context.getString(
            if (paired) R.string.phantom_error_connect else R.string.phantom_error_pair,
            e.message ?: e.javaClass.simpleName,
        )
    }
}
