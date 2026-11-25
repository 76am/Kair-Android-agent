package dev.kair.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.settings.SavedModel

/**
 * 「01 模型」行组：当前默认用主色蓝徽标标记，点击行进入整页编辑器。
 *
 * 旧版把「选为默认」做成单选圆点；设计稿把它降级为行尾「⋯」里的一个动作，
 * 行本身只负责编辑 —— 单选圆点占掉了标题的视觉重心，而且当前默认只该有一个标记。
 */
@Composable
internal fun SettingsModelSection(
    models: List<SavedModel>,
    activeId: String?,
    onAdd: () -> Unit,
    onEdit: (SavedModel) -> Unit,
    onActivate: (SavedModel) -> Unit,
    onDelete: (SavedModel) -> Unit,
) {
    SettingsGroup {
        if (models.isEmpty()) {
            ModelEmptyBlock(onAdd = onAdd)
        } else {
            models.forEachIndexed { index, model ->
                ModelRow(
                    model = model,
                    active = model.id == activeId,
                    divider = index > 0,
                    onEdit = { onEdit(model) },
                    onActivate = { onActivate(model) },
                    onDelete = { onDelete(model) },
                )
            }
            SettingsDivider()
            SettingsAddRow(text = stringResource(R.string.settings_models_add), onClick = onAdd)
        }
    }
}

@Composable
private fun ModelRow(
    model: SavedModel,
    active: Boolean,
    divider: Boolean,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column {
        if (divider) SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onEdit)
                .heightIn(min = 60.dp)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    model.name.ifBlank { model.model.ifBlank { stringResource(R.string.settings_models_new) } },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    modelSubtitle(model),
                    style = MonoLabel(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        stringResource(R.string.settings_models_active),
                        style = MonoLabel(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.settings_models_actions, modelDisplayName(model)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!active) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_models_set_active)) },
                            onClick = {
                                menu = false
                                onActivate()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun modelSubtitle(model: SavedModel): String {
    val provider = ModelCatalog.providers.firstOrNull { it.id == model.providerId }?.displayName
        ?: model.providerId
    val name = model.model.ifBlank { "—" }
    return "$provider · $name"
}

private fun modelDisplayName(model: SavedModel): String =
    model.name.ifBlank { model.model.ifBlank { model.providerId } }

/** 空态：虚线框 + 一句说明 + 添加按钮（与行组同一张面，不再套第二层卡片）。 */
@Composable
private fun ModelEmptyBlock(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.settings_models_empty_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_models_empty_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_models_add),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(role = Role.Button, onClick = onAdd),
        )
    }
}
