package dev.kair.app.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * 插件能观察到的智能体事件（**只读通知**，不能改写数据流）。
 *
 * 与钩子（`onInput` / `onContext` / `onToolCall` …）的分工：
 * - 钩子参与装配，契约要求**纯函数、无副作用**，用来改数据；
 * - 事件只是「发生了什么」的广播，用来让插件做**副作用**（写文件、计数、起后台任务）。
 *
 * 早期没有事件通道时，插件想「每 N 回合做一次」只能挂 `onContext` 自己数 —— 那是在
 * 装配路径上做副作用，与钩子契约冲突，而且装配失败时会少记一次。现在分开。
 *
 * **不影响缓存**：事件不进入请求，字段里可以用毫秒时间戳。
 */
sealed interface AgentEvent {

    /** 触发事件的会话；宿主注入的全局事件（如定时器）没有会话时为 null。 */
    val sessionId: String?

    /** 宿主侧真实工作区路径（容器生效时也指向宿主机真实目录，插件可直接用 File 读写）。 */
    val workspace: String

    /** 该会话累计完成的用户回合数（从 1 开始；压缩历史不会让它回退）。 */
    val turnCount: Int

    /**
     * 一个回合结束（正常收尾、出错、被用户中止都算）。
     *
     * 在历史落盘之后广播，因此此时读会话存档能看到本回合的完整内容。
     */
    data class TurnEnd(
        override val sessionId: String?,
        override val workspace: String,
        override val turnCount: Int,
        /** `stop` / `tool_use` / `length` / `error` / `aborted`。 */
        val stopReason: String,
        /** 本回合实际执行过的工具名（去重、按名排序，便于插件做确定性处理）。 */
        val tools: List<String>,
        /** 由插件注入的消息触发的回合会带上来源插件 id，插件可据此跳过自己。 */
        val originPluginId: String? = null,
    ) : AgentEvent

    /** 单个工具执行完毕（结果已回灌前触发）。 */
    data class ToolExecuted(
        override val sessionId: String?,
        override val workspace: String,
        override val turnCount: Int,
        val toolName: String,
        val isError: Boolean,
    ) : AgentEvent

    /** 上下文被压缩（历史被重写，此后前缀缓存必然失效，插件可借此清理本地索引）。 */
    data class Compacted(
        override val sessionId: String?,
        override val workspace: String,
        override val turnCount: Int,
        val tokensBefore: Int,
        val tokensAfter: Int,
    ) : AgentEvent
}

/** 会话的只读标识（给插件列会话、选目标用）。 */
data class SessionRef(
    val id: String,
    val title: String,
    val projectId: String,
    val projectName: String,
    /** 宿主侧真实工作区路径。 */
    val workspace: String,
    /** 最近一次活动时间（毫秒），用于排序。 */
    val updatedAt: Long,
)

/** 一个回合的摘要（给后台整理类任务用；**不是**回放上下文用的消息列表）。 */
data class TurnDigest(
    /** 该回合的用户回合序号（与 [AgentEvent.turnCount] 同源）。 */
    val turnCount: Int,
    val userText: String,
    /** 助手正文（不含思考段与工具结果）。 */
    val assistantText: String,
    /** 本回合执行过的工具（去重、排序）。 */
    val tools: List<String>,
    val stopReason: String,
    /** 宿主/插件注入的提示回合（整理记忆时应跳过）。 */
    val isSystemNote: Boolean,
)

/**
 * 插件与「会话 / 子智能体」之间的桥。
 *
 * 宿主没提供时插件拿到 null（旧宿主），必须自行降级 —— 见 `PluginContext.agents`。
 *
 * **安全边界**：这里不绕过任何审批。`send` 只是往会话里投递一条消息（走正常主循环，
 * 工具调用照旧过 `ApprovalGate`）；`runSubagent` 复用内核的子智能体，深度与并发上限
 * 由宿主强制。
 */
interface AgentBridge {

    /** 当前所有项目的会话（按最近活动时间倒序）。 */
    fun sessions(): List<SessionRef>

    /**
     * 向指定会话投递一条消息（入队，**不等待回复**）。
     *
     * 消息以 system note 形式追加在历史尾部（只追加，不改写已有回合），并带上本插件
     * 的 id 作为来源标记。返回 false 表示会话不存在 / 已关闭 / 被速率上限拦下。
     *
     * **速率上限**由宿主记账（防插件与自身事件形成环路），插件不需要自己限流。
     */
    suspend fun send(sessionId: String, text: String): Boolean

    /**
     * 跑一个子智能体并等它结束，返回最终文本（以错误前缀开头表示失败）。
     *
     * [type] 为 `"coding"`（全量工具）或 `"search"`（只读工具）；未知值按 coding。
     * [modelId] 指定本次用的模型；null = 按设置项「子智能体模型」/父会话解析。
     * [timeoutMillis] 为 null 表示不限时（不推荐，插件应自带超时）。
     */
    suspend fun runSubagent(
        prompt: String,
        type: String = "search",
        timeoutMillis: Long? = null,
        modelId: String? = null,
    ): String

    /**
     * 取某会话**上次处理之后**的回合摘要（按 [afterTurn] 之后开始）。
     *
     * 为什么不是 `List<ChatMessage>`：那样每个插件都要自己写一遍「跳过思考段 / 跳过工具
     * 结果 / 跳过 system note / 截断长文本」，写错就是一次 token 灾难；宿主统一后
     * 截断策略只有一处、可测试。
     *
     * [afterTurn] 用用户回合序号而非消息下标 —— **压缩会重写历史**，下标会偏。
     */
    suspend fun turnsSince(sessionId: String, afterTurn: Int, maxChars: Int = 4_000): List<TurnDigest>
}

/**
 * 宿主不支持时的空作用域：`launch` 立即返回已取消的 Job。
 *
 * 这样旧宿主上的插件不会崩溃、也不会偷偷跑后台任务（静默不做比"做了但停不下来"安全）。
 */
val EmptyPluginScope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    .also { it.cancel() }

/** 插件私有数据目录（不存在时返回 null）。卸载时宿主按 [PluginContext.declareData] 的约定处理。 */
typealias PluginDataDir = File
