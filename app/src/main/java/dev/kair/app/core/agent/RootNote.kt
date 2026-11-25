package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role

/**
 * Root 授权 fact 的 system note 标记与判据（U116）。
 *
 * 与日期 note 同一套机制：历史尾部**只追加**、按「最后一条逐字相等」判重。
 * 标记由内核持有，正文由 [rootNoteText] 生成 —— root 状态来自设置页的手动探测。
 */
internal const val ROOT_NOTE_MARKER = "[root]"

/**
 * `android_shell` 的工具名（**内核这一侧**的判据用名）：root 提权只能经它，
 * 工具被禁用时不写 root note。与 `LocalShellTool` 自报名字的同源由 `RootNoteTest` 钉住。
 */
internal const val ANDROID_SHELL_TOOL_NAME = "android_shell"

/**
 * Root 授权 note 的正文（英文：随历史回灌给模型，属模型输入契约）。
 *
 * 授权被收回 / 探测失败时也要写否定句：历史里旧的正向 note 不会自动消失，
 * 最后一条 note 必须能纠正它。
 */
internal fun rootNoteText(granted: Boolean): String = "$ROOT_NOTE_MARKER " + if (granted) {
    "Root access is granted on this device: android_shell can run `su -c '<command>'` as uid 0."
} else {
    "Root access is not confirmed: su is unavailable, the Root manager denied it, or authorization timed out. Do not use su."
}

/** 历史里**最后一条** root note（没有则 null）：按「最后一条」判重，允许状态翻回来后补新事实。 */
internal fun lastRootNote(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER && it.isSystemNote && it.text.startsWith(ROOT_NOTE_MARKER) }?.text
