package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.core.storage.Project
import dev.kair.app.core.storage.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 附件回收（维护）：从 [AppContainer] 搬出的 GC 本体（U31 第二刀）。
 *
 * 只负责「怎么回收」，不负责「什么时候回收」：触发点（历史改写后去抖、删会话后立即、
 * `/gc` 手动）仍由宿主编排。容器持有的是委托，见 [AppContainer.gcAttachments]。
 *
 * 依赖一律显式传（`storage` 传 lambda，因为它是随插件热变更实时解析的派生属性）；
 * 不反向持有容器，也不为搬家抬高 `sessionFor` / `activeSession` 的可见性。
 *
 * 行为探针见 `AttachmentGcTest` 的 P-1…P-4 —— 搬家前后都必须绿。
 */
internal class AttachmentGc(
    private val storage: () -> StorageBackend,
    private val sessions: SessionRegistry<AgentSession>,
    private val scope: CoroutineScope,
    /**
     * 注册表之外的「在场会话」来源（U50：子智能体）。
     *
     * 子智能体**不进** [SessionRegistry]：它的实例由 `SubagentManager` 持有、历史不落盘，
     * 所以 `sessions.all()` 那一半覆盖不到它。注入一个只读 lambda 而不是让本类认识
     * `SubagentManager` —— 反向依赖会把「回收」和「子智能体」绑死，接线放在 `AppContainer`。
     */
    private val extraLiveSessions: () -> List<AgentSession>,
) {

    /**
     * 回收某项目下「已无人引用」的图片本体（项目工作区 `.kair/attachments/v1`）。
     *
     * 引用集合取**并集**：该项目所有会话的落盘日志 ∪ 所有在场会话的内存历史。取并集是刻意
     * 的 —— 图片引用写进历史前缀，误删仍被引用的本体就会让水合出的字节改变、破坏缓存前缀
     * （AGENTS.md §3），所以宁可多留、绝不误删。本体去重保证「同字节一份」，回收则负责把
     * 被压缩 / 编辑撤回 / 删除会话挤成**无引用**的残留清掉。
     *
     * @return 实际删除的文件数
     */
    fun gcAttachments(project: Project): Int {
        // 项目已经不在索引里就到此为止：**绝不能**再解析工作区 —— `workspaceDir()` 会走
        // `ensureDirName`，`mkdirs()` 把刚删的目录重建、映射写回 `project-dirs.json`（U33 的占名孤儿）。
        // 删掉 `deleteProject` 里那一次回收只堵住了同步入口，这条异步路径仍然可达：历史改写排的
        // 去抖回收有 2 秒窗口（[schedule]），窗口内删掉项目时 job 醒来照样走到这里；面板 / `/gc`
        // 手动回收也可能拿着删前的 `Project` 快照。项目没了，附件本体已随工作区一起被删 ——
        // 这次回收无事可做，而且**不能**拿旧 id 去 sweep 一个可能已被同名新项目认领的目录。
        if (storage().listProjects().none { it.id == project.id }) return 0
        val referenced = referencedAttachmentShas(project.id)
        return AttachmentStore.forWorkspace(storage().workspaceDir(project))
            .sweep(referenced, NEW_BLOB_GRACE_MS)
    }

    /** 回收某会话所属项目下无人引用的附件；会话不在任何已登记项目里时为 0。 */
    fun gcAttachmentsFor(session: AgentSession): Int =
        projectOf(session.workspace)?.let { gcAttachments(it) } ?: 0

    private fun projectOf(workspace: File): Project? {
        val path = workspace.absolutePath
        return storage().listProjects().firstOrNull { storage().workspaceDir(it).absolutePath == path }
    }

    /**
     * 引用集合：项目历史日志 ∪ 全部在场会话（注册表内的 + 子智能体）的内存消息。
     *
     * 两路「在场」都要收：注册表那路覆盖普通会话，[extraLiveSessions] 那路覆盖**不进注册表**
     * 的子智能体（它的历史不落盘，只存在于内存里）。漏掉任一路，正在跑的那个会话引用的图片
     * 就会在历史改写后的回收里被当成无引用删掉 —— 后续水合拿到 null，静默丢图。
     */
    private fun referencedAttachmentShas(projectId: String): Set<String> {
        val refs = HashSet<String>()
        fun collect(messages: List<ChatMessage>) {
            messages.forEach { m -> m.images.forEach { img -> img.ref?.sha256?.let(refs::add) } }
        }
        runCatching { storage().listSessions(projectId).forEach { collect(storage().loadMessages(it.id)) } }
        // 在场会话的内存历史可能比日志新（回合一结束才落盘）；子智能体的历史根本不落盘。
        sessions.all().forEach { collect(it.messages.value) }
        extraLiveSessions().forEach { collect(it.messages.value) }
        return refs
    }

    /** 已排期待回收的项目 id：一次改写会成串触发，合并成一次后台回收即可。 */
    private val gcScheduled = ConcurrentHashMap.newKeySet<String>()

    /** 去抖入口：同一项目在 [GC_DEBOUNCE_MS] 内多次改写只留一个后台 job。 */
    fun schedule(project: Project) {
        if (!gcScheduled.add(project.id)) return
        scope.launch(Dispatchers.IO) {
            delay(GC_DEBOUNCE_MS)
            gcScheduled.remove(project.id)
            runCatching { gcAttachments(project) }
        }
    }

    companion object {
        /** 历史改写后合并成一次后台回收的去抖窗口。 */
        private const val GC_DEBOUNCE_MS = 2_000L

        /** 新近写入本体的豁免时长：兜底「刚落盘、引用还没写进日志」的并发窗口。 */
        private const val NEW_BLOB_GRACE_MS = 120_000L
    }
}
