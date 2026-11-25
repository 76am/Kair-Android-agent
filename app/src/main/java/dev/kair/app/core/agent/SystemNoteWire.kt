package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part

/**
 * 注入提示（[ChatMessage.isSystemNote]）在 wire 上的统一标记。
 *
 * 角色仍必须是 user（中途 system 跨 provider 不可移植），但模型必须能分辨这不是用户输入。
 * 只在装配时包裹：存盘文本与 UI 卡片保持原样；历史里的旧 note 也会被一致地标上。
 * 标签是稳定字节 —— 只随历史追加，不引入随机内容。
 */
internal const val SYSTEM_NOTE_OPEN = "<system-note>"
internal const val SYSTEM_NOTE_CLOSE = "</system-note>"

internal fun ChatMessage.wireSystemNote(): ChatMessage =
    if (!isSystemNote) this
    else copy(
        parts = parts.map { p ->
            if (p is Part.Text && p.text.isNotEmpty()) {
                p.copy(text = "$SYSTEM_NOTE_OPEN\n${p.text}\n$SYSTEM_NOTE_CLOSE")
            } else {
                p
            }
        },
    )

/** 压缩摘要输入里的角色标签：注入提示不得被总结成用户回合。 */
internal fun ChatMessage.summaryLabel(): String = if (isSystemNote) "[System note]" else "[User]"
