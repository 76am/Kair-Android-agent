package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.DeliveryFailureKind
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.plugin.SessionRef
import dev.kair.app.core.plugin.TurnDigest
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.storage.StorageBackend
import dev.kair.app.core.subagent.SubagentManager
import dev.kair.app.core.subagent.SubagentType
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件运行时桥：会话 / 子智能体能力 + 注入限流。
 *
 * 为什么在这儿：这段原本是 [AppContainer] 里的一个匿名 `object`，但它持有三个与
 * 「装配主体」无关的东西 —— 按会话的限流窗口、采用分叉、子智能体转发。搬成独立类后
 * 容器只剩接线（体量棘轮 `SourceSizeGuardTest` 只许它变瘦）。
 *
 * **限流是宿主侧兜底，不靠插件自觉**：`send` + 事件订阅 + 自动整理三者串起来能形成
 * 环路（插件发消息 → 回合结束事件 → 插件又发消息）。
 *
 * 依赖一律显式注入（`get()` 型传 lambda、`private` 方法传函数引用），不反向持有容器 ——
 * 见 `docs/tasks/U31-拆AppContainer.md` §2.2。
 *
 * **所有方法都走正常主循环**：不绕过审批、不改写历史（注入消息是追加一条 system note），
 * 子智能体的深度与并发上限沿用内核的。（这段 KDoc 原本挂在第一个成员上，描述的是整个桥，
 * 终审时挪回类注释 —— 搬家应当只动「谁持有谁」。）
 */
internal class PluginRuntimeBridge(
    private val clock: () -> Long,
    /** 动态取：容器的 `storage` 是 `plugins.storage() ?: fallbackStorage`，传快照等于改变语义。 */
    private val storage: () -> StorageBackend,
    private val diagnostics: Diagnostics,
    private val sessions: SessionRegistry<AgentSession>,
    private val subagents: SubagentManager,
    /** 每次调用都要读：它是 `activeSession?.id` 的 getter，不是快照。 */
    private val activeSessionId: () -> String?,
    private val openSession: (Project, SessionMeta) -> AgentSession,
    private val sessionFor: (Project, SessionMeta) -> AgentSession,
) : AgentBridge {

    /**
     * 插件每分钟最多向同一会话注入几条消息。
     *
     * 为什么必须有：`send` + 事件订阅 + 自动整理三者串起来能形成环路（插件发消息 →
     * 回合结束事件 → 插件又发消息）。这是**宿主侧**的兜底，不靠插件自觉限流。
     */
    private val pluginSendLimitPerMinute = 6

    /**
     * 会话 id → 本窗口内的注入时间戳。
     *
     * 槽**只在会话关闭时清**（`SessionRegistry.Cleanup.SendWindow`），淘汰**不**清 ——
     * 淘汰后会话 id 还在用，清掉槽等于把「每分钟 [pluginSendLimitPerMinute] 条」的额度重置。
     * （改前的注释写「容量受会话数约束」，那是错的：槽从不清，容量受
     * **曾经存在过的会话数**约束，会一直涨。）
     *
     * 用并发容器是因为读写不在同一把锁下：注入路径 `getOrPut`，清理路径从 [sessions] 发起。
     */
    private val pluginSendLog = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * 全部会话的引用清单，按更新时间倒序。
     *
     * 消费方只有一个：容器的 [AppContainer.pluginSessions] 转发到这里，插件设置里
     * `SettingField.Choice` 且 `source == "sessions"` 的下拉在渲染时读这份清单
     * （`ui/components/SettingRow.kt` —— 插件在 `activate()` 里拿不到动态列表，所以由宿主解析）。
     */
    override fun sessions(): List<SessionRef> =
        storage().listProjects().flatMap { project ->
            val dir = storage().workspaceDir(project)
            storage().listSessions(project.id).map { meta ->
                SessionRef(
                    id = meta.id,
                    title = meta.name,
                    projectId = project.id,
                    projectName = project.name,
                    workspace = dir.path,
                    updatedAt = meta.updatedAt,
                )
            }
        }.sortedByDescending { it.updatedAt }

    override suspend fun send(sessionId: String, text: String): Boolean {
        val now = clock()
        val stamps = pluginSendLog.getOrPut(sessionId) { mutableListOf() }
        // 先占额度再解析目标：check 与 add 必须原子，否则并发注入能绕过上限。
        synchronized(stamps) {
            stamps.removeAll { now - it > 60_000 }
            if (stamps.size >= pluginSendLimitPerMinute) {
                // 临时失败：额度下个周期会放开，插件的重试有意义。
                diagnostics.recordDeliveryFailure(DeliveryFailureKind.TEMPORARY)
                return false
            }
            stamps.add(now)
        }
        // 会话可能没打开：按 id 反查项目后打开，找不到就是会话已删除。
        val opened = sessions.get(sessionId) ?: run {
            val found = storage().listProjects().firstNotNullOfOrNull { project ->
                storage().listSessions(project.id).firstOrNull { it.id == sessionId }?.let { project to it }
            }
            if (found == null) {
                // 目标会话不存在：退回刚占的额度，否则一次误配会把正常注入挤掉。
                synchronized(stamps) { stamps.remove(now) }
                // 永久失败：重试不会成功，用户需要去改插件的目标配置。
                diagnostics.recordDeliveryFailure(DeliveryFailureKind.PERMANENT)
                return false
            }
            // 用户还没有任何活动会话时没有可劫持的「当前会话」，沿用正常路径，
            // 让容器 / MCP 能按这个工作区照常启动；已有活动会话时只建实例，
            // 绝不把用户正在看的工作区与会话切走。
            if (mayAdoptActiveSession(activeSessionId())) {
                openSession(found.first, found.second)
            } else {
                sessionFor(found.first, found.second)
            }
        }
        // systemNote = true：界面渲染成提示卡片，模型也能区分「这不是用户说的」，
        // 且不计入用户回合数（否则「每 N 回合」的计数会被自己的注入推着走）。
        try {
            opened.send(text, systemNote = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 发送失败多为暂态（端点抖动 / 会话正忙）：记一次临时失败，语义不变地抛出，
            // 由调用方（如定时任务）决定是否下个周期重试。
            diagnostics.recordDeliveryFailure(DeliveryFailureKind.TEMPORARY)
            throw e
        }
        return true
    }

    override suspend fun runSubagent(
        prompt: String,
        type: String,
        timeoutMillis: Long?,
        modelId: String?,
    ): String = subagents.spawnAndWait(
        parentId = activeSessionId(),
        prompt = prompt,
        name = "plugin-task",
        timeoutMillis = timeoutMillis,
        type = SubagentType.from(type),
        modelId = modelId,
    )

    override suspend fun turnsSince(sessionId: String, afterTurn: Int, maxChars: Int): List<TurnDigest> =
        digestTurns(storage().loadMessages(sessionId), afterTurn, maxChars)

    /**
     * 会话关闭时清掉它的限流窗口（`SessionRegistry.Cleanup.SendWindow`）。
     *
     * **淘汰不清**：淘汰后会话 id 还在用，清槽等于把额度重置 —— 见 [pluginSendLog]。
     */
    fun releaseSendWindow(id: String) {
        pluginSendLog.remove(id)
    }
}
