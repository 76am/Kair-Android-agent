package dev.kair.app.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.ApprovalDecision
import dev.kair.app.core.agent.ApprovalRequest
import dev.kair.app.ui.plugins.builtin.AskUserRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 审批卡片：agent 的待确认工具调用；决定直接回传给 [ApprovalGate.resolve]。
 *
 * [timeoutSeconds] 为 0 表示不显示超时提示；大于 0 时在卡片底部说明「无人操作会自动结束」，
 * 避免用户以为它可以无限期挂着。
 */
@Composable
internal fun ApprovalCard(
    req: ApprovalRequest,
    timeoutSeconds: Long,
    onDecide: (ApprovalDecision) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        // 审批闸门用**朱红**（U97）：这一张要的是「停下来，这是你的决定」，
        // 同一族色也让闸门与「你 · 时间」行的朱红对得上。
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.38f)),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PromptBadge(
                    icon = Icons.Filled.Warning,
                    tint = MaterialTheme.colorScheme.error,
                    background = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                )
                Text(
                    stringResource(R.string.approval_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 9.dp),
                )
            }
            // 子智能体发起的请求：明确标注来源，用户才知道自己在给谁放行。
            req.originLabel?.let { label ->
                Text(
                    stringResource(R.string.approval_from_subagent, label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(req.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.approval_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
            )
            if (timeoutSeconds > 0) {
                Text(
                    stringResource(R.string.approval_timeout_hint, timeoutSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                )
            }
            if (req.arguments.isNotBlank() && req.arguments != "{}") {
                // 完整展示参数：危险命令原文常是多行长命令，原先 take(600) + maxLines=4 会截断。
                // 改为限高可滚动区，内容超出时在卡片内滚动查看，SelectionContainer 仍可选中复制。
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    SelectionContainer {
                        Column(
                            Modifier
                                .padding(10.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                approvalBody(req.arguments),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            // 动作层级（U105，界面审查 P0-3）：
            // 主位 = **拒绝**（白底 + 3:1 描边，最先可达），允许一次 = 次级描边，
            // 本会话 / 永久允许 = 文字按钮并与前两个**分行**。
            // 四个语义一个不少（规范要求），但不许再把「永久允许」贴在「拒绝」旁边：
            // 原先四个等权 TextButton 间距只有 4dp，误触代价最高的一对正好相邻。
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onDecide(ApprovalDecision.DENY) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.approval_deny))
                    }
                    OutlinedButton(
                        onClick = { onDecide(ApprovalDecision.ALLOW_ONCE) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.approval_allow))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onDecide(ApprovalDecision.ALLOW_SESSION) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.approval_allow_session),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (req.rememberable) {
                        TextButton(
                            onClick = { onDecide(ApprovalDecision.ALLOW_ALWAYS) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                stringResource(R.string.approval_allow_always),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Composer 输入条 + 底部工具条的保守占位；宁可少给卡片一点，也不要把发送区挤出屏幕。 */
internal val AskCardComposerReserve = 132.dp

/** 平板 / 横屏下的整卡高度上限，避免一张提问卡吃掉整屏。 */
internal val AskCardMaxHeightCap = 560.dp

/** 卡片页脚（标题 / 提示 / 输入行 / 超时提示）的保守最小高度。 */
private val AskCardFooterMinHeight = 224.dp

/**
 * 提问卡的**整卡**高度预算：先给下方 Composer 留出 [AskCardComposerReserve]，余量才给卡片。
 *
 * 为什么按整卡算：卡片是 `ChatScreen` 外层 Column 的非 weight 子项，排在 Composer 之前，
 * 能先把剩余高度占满 —— 只限制正文的话，卡片页脚与发送区仍会被顶出屏幕。
 * 卡片内部再把「页脚固定高度」从这份预算里扣掉（正文用 `weight(1f, fill = false)`）。
 *
 * **兜底**：视口连「页脚 + Composer」都放不下时（横屏 + 键盘等极端情况），
 * 优先保证卡片可见、正文可滚动 —— 把可用高度全给卡片（Composer 被挤出屏幕，
 * 与修复前观感一致）。视口再小到页脚本身都放不下（约 < 260dp）属物理上限，
 * 只能看到标题 / 跳过；至少不会把卡片压成 0 高。
 */
internal fun askCardMaxHeight(available: Dp): Dp =
    if (available < AskCardComposerReserve + AskCardFooterMinHeight) {
        available.coerceIn(0.dp, AskCardMaxHeightCap)
    } else {
        (available - AskCardComposerReserve).coerceIn(0.dp, AskCardMaxHeightCap)
    }

/**
 * 提问卡片：`ask_user` 的选择 / 输入面板。有选项时点一下即回答；也可自由输入后
 * 发送，或跳过（回给模型一条错误结果，让它自行判断而不是反复追问）。
 */
@Composable
internal fun AskUserCard(
    req: AskUserRequest,
    timeoutSeconds: Long = 0,
    onAnswer: (String?) -> Unit,
) {
    // 按提问 id 重建输入内容：上一条回答完、下一条进来时不清空旧文字。
    var text by remember(req.id) { mutableStateOf("") }
    // 正文滚动位置同样按提问重建：新问题总是从顶部开始读。
    val bodyScroll = remember(req.id) { ScrollState(0) }
    BoxWithConstraints {
        // 提问正文由模型生成，长度不可控（实测能写满整屏）。整卡先按预算限高，
        // 正文再把「页脚之外」的高度吃满；超出部分在卡片内滚动 —— 标题、输入框、
        // 超时提示与下方 Composer 始终留在屏幕上。
        val cardMaxHeight = askCardMaxHeight(maxHeight)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = cardMaxHeight)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.large,
            // 方案②「主体白 + 描边」：卡片回到地面色，层级交给 1dp hairline；
            // 只有徽标保留主色（14% 底 + 蓝色图标）。不再用 secondaryContainer ——
            // 那层紫底会把提问卡和普通内容分成两套视觉语系。
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PromptBadge(
                        icon = Icons.Outlined.QuestionAnswer,
                        tint = MaterialTheme.colorScheme.primary,
                        background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    )
                    Text(
                        stringResource(R.string.ask_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 9.dp),
                    )
                    TextButton(
                        onClick = { onAnswer(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.ask_skip))
                    }
                }
                // 子智能体发起的提问：标明来源，避免用户误以为是自己会话在问。
                req.originLabel?.let { label ->
                    Text(
                        stringResource(R.string.approval_from_subagent, label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // 问题与选项共用一块滚动区：整卡预算减去页脚固定高度后，余量全给正文；
                // `fill = false` 让内容少时卡片收紧，不硬撑到上限。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(bodyScroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(req.question, style = MaterialTheme.typography.bodyMedium)
                    if (req.options.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            req.options.forEach { option ->
                                Surface(
                                    onClick = { onAnswer(option) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Row(
                                        // U105：选项是提问卡的主操作，行高补到 48dp（原 42dp）。
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            option,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 等待提示放在滚动区外：正文滚到哪一行都还能看到「agent 在等」。
                Text(
                    stringResource(R.string.ask_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.ask_input_hint)) },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    FilledIconButton(
                        onClick = { onAnswer(text.trim().takeIf { it.isNotBlank() }) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ask_send))
                    }
                }
                if (timeoutSeconds > 0) {
                    Text(
                        stringResource(R.string.ask_timeout_hint, timeoutSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 两种待决定卡片的抬头徽标：圆形底色 + 图标，避免标题行只有一行字。 */
@Composable
private fun PromptBadge(icon: ImageVector, tint: Color, background: Color) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 审批卡片展示的正文。工具参数是原始 JSON（如 `{"command":"export …\nrm -rf x","timeout":30}`），
 * 直接显示会把换行转义成字面的 `\n`、可读性差。若参数里有 `command` 字段（shell 工具），取它的
 * 正文按**真实换行**铺开；否则原样返回 JSON。解析失败也原样返回，不影响展示。
 */
internal fun approvalBody(raw: String): String = runCatching {
    val command = (Json.parseToJsonElement(raw).jsonObject["command"] as? JsonPrimitive)?.contentOrNull
    command ?: raw
}.getOrDefault(raw)
