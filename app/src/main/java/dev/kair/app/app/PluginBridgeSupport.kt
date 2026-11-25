package dev.kair.app.app

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Role
import dev.kair.app.core.plugin.TurnDigest

/**
 * 插件运行时桥的**纯**辅助逻辑：回合摘要 + 后台注入的作用域采用判定。
 *
 * 抽成顶层函数（而不是留在 `AppContainer` 里）有两个理由：
 * 1. `AppContainer` 要 `Context`，JVM 单测起不来，夹在里面的纯逻辑等于不可测；
 * 2. 装配主体只该剩接线 —— 体量棘轮（`SourceSizeGuardTest`）也不允许它继续变胖。
 */

/**
 * 把存档消息压成回合摘要（从第 [afterTurn]+1 个用户回合开始）。
 *
 * 用**用户回合序号**切分而不是消息下标：压缩会重写历史，下标会偏，序号只增不改。
 * 思考段与工具结果一律丢弃（整理类任务不需要），长文本按 [maxChars] 截断 ——
 * 截断策略只有这一处，便于测试，也免得每个插件各写一份把 token 烧穿。
 */
internal fun digestTurns(messages: List<ChatMessage>, afterTurn: Int, maxChars: Int): List<TurnDigest> {
    val out = mutableListOf<TurnDigest>()
    var turn = 0
    var user: ChatMessage? = null
    val assistants = mutableListOf<ChatMessage>()

    fun flush() {
        val u = user ?: return
        out.add(
            TurnDigest(
                turnCount = turn,
                userText = u.text.take(maxChars),
                assistantText = assistants.joinToString("\n") { it.text }.take(maxChars),
                tools = assistants.flatMap { m -> m.toolCalls.map { it.name } }.distinct().sorted(),
                stopReason = assistants.lastOrNull()?.stopReason ?: "stop",
                isSystemNote = u.isSystemNote,
            ),
        )
        assistants.clear()
    }

    for (m in messages) {
        when (m.role) {
            Role.USER -> {
                if (m.isSystemNote) continue // 注入的提示不算用户的回合
                flush()
                turn++
                user = m
            }
            Role.ASSISTANT -> assistants.add(m)
            else -> Unit
        }
    }
    flush()
    return out.filter { it.turnCount > afterTurn }
}

/**
 * 后台注入（定时任务 / 插件）能否沿用 [AppContainer.openSession]。
 *
 * `openSession` 会切「当前项目 / 当前会话」并重连 stdio MCP —— 那是**用户意图**的副作用。
 * 只有宿主还没有活动会话时才允许走它（那时没有可劫持的 UI 状态，且容器 / MCP 需要按
 * 目标工作区首次启动）；已有活动会话时一律走 `sessionFor`（只建实例）。
 *
 * 判定单独抽成函数是为了脱离 Android 直接测（见 `BackgroundInjectionTest`）：
 * 这条不变量一旦被改回「总是 openSession」，用户正在看的工作区会被后台悄悄切走。
 */
internal fun mayAdoptActiveSession(activeSessionId: String?): Boolean = activeSessionId == null
