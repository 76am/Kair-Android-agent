package dev.kair.app.core.agent

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.tools.parseToolArguments

/**
 * 历史修复（内核）：**纯函数集合**，`List<ChatMessage> → List<ChatMessage>`，不持有任何状态。
 *
 * 为什么单独成文件：这几段直接决定「下一回合上传的字节」——一处悬空的 `tool_call` 会让之后
 * **每一回合**的请求被 provider 拒绝；而它们此前埋在 `AgentSession` 里读写 `_messages`，
 * 于是「中断收尾到底改了什么」只能靠起会话才能验证。搬成纯函数后它可以单测
 * （`HistorySanitizerTest`），会话侧只留「干净就不写盘」那一个判断（`AgentSession` 的
 * `sanitizeInterruptedTurn`）。
 *
 * 三条契约（`AGENTS.md` §3 的缓存规则在这一点上的具体形态），逐条有用例守着：
 * - **幂等**：同一历史修两次等于修一次 —— 每次请求装配前都会跑一遍；
 * - **不改输入**：只返回新列表，绝不就地改写传入的消息；
 * - **本就干净时返回同一个实例**：不产生拷贝，调用点据此短路掉历史重写与落盘。
 */
internal object HistorySanitizer {
    /**
     * 修复工具调用配对：provider 要求「assistant 的每个 tool_call 都有紧随其后的
     * tool_result」且「每个 tool_result 都能对应到某个 tool_call」，严格一一配对。
     * 悬空调用（无结果）与无主结果（无调用）都会让请求被直接拒绝。
     *
     * 做法：全历史扫描一次，丢掉没有对应 tool_result 的 tool_call，以及没有对应
     * tool_call 的 tool_result；若某 assistant 回合因此被掏空，则用一条中断提示替换其
     * 正文（保持 assistant 角色，避免出现连续两条 user 消息）。
     *
     * 幂等：历史本就配对时逐字节不变（缓存安全），只有真正悬空的回合会被改写。因此只在
     * [AgentSession.loadHistory]（开 / 重启会话时修复旧数据）与中断收尾
     * （`AgentSession.sanitizeInterruptedTurn`）各跑一次。
     */
    fun repairToolPairing(messages: List<ChatMessage>): List<ChatMessage> {
        val callIds = HashSet<String>()
        val resultIds = HashSet<String>()
        for (m in messages) {
            for (p in m.parts) {
                when (p) {
                    is Part.ToolCall -> callIds.add(p.id)
                    is Part.ToolResult -> resultIds.add(p.toolCallId)
                    else -> Unit
                }
            }
        }
        var changed = false
        val repaired = messages.mapNotNull { m ->
            when (m.role) {
                Role.ASSISTANT -> {
                    val kept = m.parts.filterNot { it is Part.ToolCall && it.id !in resultIds }
                    if (kept.size == m.parts.size) m
                    else {
                        changed = true
                        if (kept.isEmpty()) m.copy(parts = listOf(Part.Text(AgentSession.INTERRUPTED_NOTICE)))
                        else m.copy(parts = kept)
                    }
                }
                Role.TOOL_RESULT -> {
                    val kept = m.parts.filterNot { it is Part.ToolResult && it.toolCallId !in callIds }
                    if (kept.size == m.parts.size) m
                    else {
                        changed = true
                        if (kept.isEmpty()) null else m.copy(parts = kept)
                    }
                }
                else -> m
            }
        }
        return if (changed) repaired else messages
    }

    /**
     * 历史修复的统一入口：配对修复 + 工具参数规范化。幂等 —— 干净历史逐字节不变。
     *
     * 用在开 / 重启会话、中断收尾、编辑重发，以及**每次请求装配前**。放在装配前是刻意的：
     * 模型可能吐出非法 JSON 的工具参数（`edit` 里嵌了未转义的引号 / `$`），或参数被输出
     * 上限截断；这种 `tool_call` 会永久留在历史里，而 OpenAI 兼容端点会原样回传
     * `arguments` 字符串 —— 端点解析失败就把整轮请求吃掉（表现为「空响应 / 非法格式」，
     * 且之后每回合都失败，只能靠撤回重发绕过）。请求副本里把它规范成 `{}`（工具结果本就
     * 带一条参数错误），旧会话因此自愈，无需用户手动撤回。
     */
    fun repairHistory(messages: List<ChatMessage>): List<ChatMessage> =
        repairToolArguments(repairToolPairing(messages))

    /**
     * 中断收尾 / 会话载入的统一修复入口：配对与参数修复之后，再给尾条工具结果封口。
     *
     * 封口只能走这两个入口：请求装配前也会跑 [repairHistory]，但那时回合可能正跑到
     * 「工具结果已回、下一段输出未到」的窗口 —— 封口会把仍在进行的回合判成已结束。
     */
    fun repairInterrupted(messages: List<ChatMessage>): List<ChatMessage> =
        sealInterruptedTail(repairHistory(messages))

    /**
     * 中断收尾的「封口」：回合的尾条 agent 消息若是工具结果，标上 [StopReason.ABORTED]。
     *
     * 为什么需要：停止可能正落在「工具结果已回、下一段输出还没到」的窗口（弱网下请求卡在
     * 网络前置阶段，取消要十几秒才生效，见 `core/ai/PreConnect.kt`）。此时这一轮**没有产出
     * 任何 assistant 消息**（空白占位气泡被 `TurnExecutor` 回收），[repairToolPairing] 也
     * 无从下手 —— 配对本就完整。历史于是停在一条「看起来还能继续」的工具结果上，界面把
     * 下一条用户消息当成回合内引导折进上一轮（真机：停止成功后发的消息被识别成引导）。
     *
     * 为什么只加标记、不补一条 assistant 提示：提示会进入下一发的请求字节与界面气泡，
     * 而这里只需要回答「这一轮已经结束」。现有消费者对工具结果的 `stopReason` 不做展示、
     * 不进请求，标记额外影响面为零。
     *
     * 幂等：尾条已是 assistant（正常收口 / 流式中断已标过），或工具结果已封口时，返回同一实例。
     */
    fun sealInterruptedTail(messages: List<ChatMessage>): List<ChatMessage> {
        // 尾条可能跟着回合内引导（user 角色），封口要落在最后一条 assistant / 工具结果上。
        val tail = messages.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.TOOL_RESULT }
            ?: return messages
        if (tail.role != Role.TOOL_RESULT || tail.stopReason == StopReason.ABORTED) return messages
        return messages.map { if (it.id == tail.id) it.copy(stopReason = StopReason.ABORTED) else it }
    }

    /**
     * 反序列化工具参数的唯一判据：必须是**严格**合法 JSON **对象**（`{}` 也算）。
     *
     * 判据本身在 `parseToolArguments`（与工具执行共用）；这里只回答「要不要修」。
     * 宽松解析会把 `<longcat_arg_value>` 这类占位符当成字符串接受，而端点按严格
     * JSON 解析会整轮拒收 —— 这次真机空响应就是这么漏过去的。
     */
    private fun isValidToolArgs(raw: String): Boolean = parseToolArguments(raw) != null

    /**
     * 把非法 JSON 的工具参数规范成 `{}`：只改**请求副本**，界面与日志仍保留模型原文，
     * 用户能看清它到底吐了什么。幂等。
     */
    private fun repairToolArguments(messages: List<ChatMessage>): List<ChatMessage> {
        fun broken(m: ChatMessage) = m.parts.any { it is Part.ToolCall && !isValidToolArgs(it.arguments) }
        if (messages.none(::broken)) return messages
        return messages.map { m ->
            if (!broken(m)) {
                m
            } else {
                m.copy(
                    parts = m.parts.map { p ->
                        if (p is Part.ToolCall && !isValidToolArgs(p.arguments)) p.copy(arguments = "{}") else p
                    },
                )
            }
        }
    }

    /**
     * 清掉**用户回合**里的控制字符（历史版本没有在入口处过滤，用户从缓存面板 / 终端复制
     * 的 `\u0000`、`\u0001`、ESC 会永久留在历史里并回灌给模型）。只动 user 文本：
     * assistant 的正文 / 思考可能带厂商签名，改写会让签名失配。
     */
    fun sanitizeUserText(messages: List<ChatMessage>): List<ChatMessage> {
        fun dirty(m: ChatMessage) =
            m.role == Role.USER && m.parts.any { it is Part.Text && containsControlChar(it.text) }
        if (messages.none(::dirty)) return messages
        return messages.map { m ->
            if (!dirty(m)) {
                m
            } else {
                m.copy(
                    parts = m.parts.map { p ->
                        if (p is Part.Text) p.copy(text = stripControlChars(p.text)) else p
                    },
                )
            }
        }
    }
}

/**
 * 不可见控制字符（C0 除换行 / 回车 / 制表，外加 DEL）。
 *
 * 这些字符常来自终端输出、日志或二进制的误粘贴；它们在 JSON 里可以合法转义，但模型
 * 无法「看见」内容、界面只会渲染成方块，并且会永久占据思考回传 / 请求前缀的字节。
 */
internal fun isControlChar(c: Char): Boolean =
    (c < ' ' && c != '\n' && c != '\r' && c != '\t') || c == '\u007F'

internal fun containsControlChar(text: String): Boolean = text.any(::isControlChar)

/** 剔除 [isControlChar]；文本本就干净时返回同一个实例，避免无谓拷贝。 */
internal fun stripControlChars(text: String): String =
    if (!containsControlChar(text)) text else text.filterNot(::isControlChar)