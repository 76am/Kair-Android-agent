package dev.kair.app.ui.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SlotArea
import dev.kair.app.core.plugin.SlotContext
import dev.kair.app.core.plugin.SlotElement
import dev.kair.app.ui.components.pressLayer
import dev.kair.app.ui.components.rememberPressLayer
import dev.kair.app.ui.icons.SvgPathIcon
import kotlin.math.roundToInt

/**
 * 声明式 UI 槽位的**宿主渲染器**，同时提供顶部 / 底部区的**统一 chip 与圆环**。
 *
 * 关键约定：插件声明的槽位元素统一走 [DockChip] / [DockGauge]——只有一份 chip、一份圆环实现。
 * 插件只给数据（[SlotElement]），UI 全部由宿主提供，插件因此不接触 Compose。
 * 控件持久值按 [SlotElement.key] 落本插件设置存储；交互回调同时收到 [SlotContext]
 * （[sessionId] / [workspace]），把操作限定在**触发它的那个会话**。
 */
@Composable
fun PluginSlotRow(
    container: AppContainer,
    area: SlotArea,
    sessionId: String?,
    workspace: String,
    onOpenPanel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 直接读实时快照（不 remember）：插件装卸时槽位列表变化能触发重组。
    val slots = container.plugins.slots(area)
    if (slots.isEmpty()) return

    // 槽位只渲染输入板底部区（U101 停用顶部区）：对齐输入板控件行的
    // [DockControlSize]。同一区域的槽位统一一个尺寸，外观与内置控件完全一致。
    val chipSize = DockControlSize
    val chipShape: Shape = MaterialTheme.shapes.extraSmall
    val slotCtx = SlotContext(sessionId = sessionId, workspace = workspace)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEach { (pluginId, slot) ->
            val store = container.plugins.pluginSettings(pluginId)
            slot.elements.forEach { element ->
                key(slot.id, element.key) {
                    PluginSlotElement(store, element, slotCtx, onOpenPanel, chipSize, chipShape)
                }
            }
        }
    }
}

/** 把单个槽位元素映射到统一控件；[store] 是该元素所属插件的设置存储，[ctx] 是当前会话上下文。 */
@Composable
private fun PluginSlotElement(
    store: PluginSettingsStore,
    element: SlotElement,
    ctx: SlotContext,
    onOpenPanel: (String) -> Unit,
    size: Dp,
    shape: Shape,
) {
    when (element) {
        // 可选二次确认：`confirm` 为 true 时点击先弹宿主确认弹窗，确认后才执行回调。
        is SlotElement.Button -> {
            var confirming by remember(element.key) { mutableStateOf(false) }
            DockChip(
                value = element.label,
                icon = element.icon,
                size = size,
                shape = shape,
            ) {
                if (element.confirm) confirming = true else element.onClick(store, ctx)
            }
            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(element.label) },
                    text = {
                        Text(element.confirmMessage ?: stringResource(R.string.slot_confirm_default_message, element.label))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirming = false
                                element.onClick(store, ctx)
                            },
                        ) { Text(stringResource(R.string.action_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
                    },
                )
            }
        }

        is SlotElement.Text -> DockChip(
            value = element.text,
            icon = element.icon,
            size = size,
            shape = shape,
        )

        is SlotElement.Toggle -> {
            var on by remember(element.key) {
                mutableStateOf(store.getBoolean(element.key, element.default))
            }
            DockChip(value = element.label, icon = element.icon, active = on, size = size, shape = shape) {
                on = !on
                store.setBoolean(element.key, on)
                element.onChange?.invoke(on, ctx)
            }
        }

        is SlotElement.KeyValue -> DockChip(
            value = element.value,
            label = element.label,
            icon = element.icon,
            active = element.active,
            size = size,
            shape = shape,
        ) { element.onClick(store, ctx) }

        is SlotElement.Gauge -> {
            val fraction = if (element.max > 0f) (element.value / element.max).coerceIn(0f, 1f) else 0f
            DockGauge(
                fraction = fraction,
                centerText = "${(fraction * 100).roundToInt()}%",
                size = DockGaugeSize,
            ) { element.onClick(store, ctx) }
        }

        // 面板导航：点击后交给宿主打开该插件的面板（与内置「子智能体」入口同一交互）。
        is SlotElement.Panel -> DockChip(
            value = element.label,
            icon = element.icon,
            size = size,
            shape = shape,
        ) {
            onOpenPanel(element.panelId)
        }

        // 选项弹窗：点击弹出选项列表（单选）+ 可选开关列表（对齐内置「模型 / 思考强度」）。
        is SlotElement.Picker -> PickerChip(store, element, size, shape)
    }
}

/**
 * 选项弹窗 chip：单行显示 [SlotElement.Picker.label] + 当前选中项，点击弹出选项面板。
 * 选中项按 [SlotElement.Picker.key]、开关按各自 [ToggleSpec.key] 落本插件设置存储。
 */
@Composable
private fun PickerChip(store: PluginSettingsStore, element: SlotElement.Picker, size: Dp, shape: Shape) {
    var open by remember(element.key) { mutableStateOf(false) }
    var value by remember(element.key) {
        mutableStateOf(store.getString(element.key, element.value))
    }
    val display = element.options.firstOrNull { it.value == value }?.label ?: value
    DockChip(value = display, label = element.label, icon = element.icon, size = size, shape = shape) { open = true }

    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(element.label) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                element.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                value = option.value
                                store.setString(element.key, option.value)
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.value == value,
                            onClick = {
                                value = option.value
                                store.setString(element.key, option.value)
                            },
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                element.toggles.forEach { toggle ->
                    var on by remember(toggle.key) {
                        mutableStateOf(store.getBoolean(toggle.key, toggle.default))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            toggle.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = on,
                            onCheckedChange = {
                                on = it
                                store.setBoolean(toggle.key, it)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * 统一 chip：可选小标题（两行）+ 主值 + 可选矢量图标；可点击、可高亮（[active]）。
 *
 * [size] / [shape] 决定形态。底部槽位与输入板控件行同形：`DockControlSize +
 * MaterialTheme.shapes.extraSmall`，固定高、宽度随内容；传 `CircleShape` 即圆形
 * 图标按钮（**只显示图标**，文字留给无障碍描述）—— 顶部槽位停用（U101）后
 * 这是纯形态选项，不再有默认调用方。
 * @param description 读屏用的完整描述。单行 chip 不画小标题（底部输入板里的模型 / 思考强度
 *   就是这种形态），标题的信息只能靠它补回来 —— 否则读屏只听到「Sonnet 4.6」，
 *   不知道这是模型还是别的什么。
 */
@Composable
fun DockChip(
    value: String,
    label: String? = null,
    icon: String? = null,
    active: Boolean = false,
    size: Dp = DockControlSize,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    description: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val circle = shape == CircleShape
    // 圆形（顶部按钮）且带图标时即「图标按钮」，对齐内置 IconButton：只画图标，文字作描述。
    val iconOnly = circle && icon != null
    val bg = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        // 顶部图标按钮与内置 IconButton 一致不画底色；底部带文字的格子保留底色。
        iconOnly -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val semanticsMod = when {
        iconOnly -> Modifier.semantics { contentDescription = label ?: value }
        // mergeDescendants：否则读屏会把值念两遍（设定的描述 + 格子里那行字）。
        description != null -> Modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        }
        else -> Modifier
    }
    // U105：视觉高度仍是 30dp，外圈补到 48dp 命中区 —— 原先 chip 与圆环都只有 30dp，
    // 低于 Android 48dp 下限。宽度也补到 48dp：否则窄 chip（如思考强度「中」）只有 30×48。
    //
    // 按下反馈走 PressLayer 口径（2026-11-20）：命中区仍是 48dp，但压层只画在本体那个 chip
    // 形状里。此前 clickable 挂在这个透明的 48dp 外框上，M3 水波纹按外框铺开 —— 30dp 的
    // chip 按下去是一块 48dp 的方块，比本体大一圈还盖到旁边的格子。
    val interaction = remember { MutableInteractionSource() }
    val layer = rememberPressLayer(interaction)
    val layerColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(semanticsMod),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                // 圆形固定 size×size（真圆）；圆角矩形只固定高度，宽度随内容，避免压扁两行 chip。
                .then(if (circle) Modifier.size(size) else Modifier.heightIn(min = size))
                .clip(shape)
                .background(bg)
                .pressLayer(layer, layerColor)
                .padding(horizontal = if (iconOnly) 0.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (circle) Arrangement.Center else Arrangement.spacedBy(6.dp),
        ) {
            // 图标尺寸跟着形态走：圆形按钮里占满 24dp（与内置 IconButton 同）；带文字
            // 的格子里收到 15dp，否则图标比 12.5sp 的标题还高，整格会往上飘。
            icon?.let { SvgPathIcon(it, fg, Modifier.size(if (iconOnly) 24.dp else 15.dp)) }
            if (!iconOnly) {
                Column {
                    if (label != null) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (label != null) MaterialTheme.colorScheme.onSurface else fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 控件行统一高度（U100）：模型 / 思考 chip 与插件槽位同高。
 *
 * 30dp 是 26（文字挤）与 32（把 U99 刚收矮的板子重新顶高）之间的折中；改这一个值，
 * chip 相关三处一起变，不要再往调用点写魔法数字。
 */
val DockControlSize: Dp = 30.dp
