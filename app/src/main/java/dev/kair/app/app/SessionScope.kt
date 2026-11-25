package dev.kair.app.app

import dev.kair.app.core.storage.SessionMeta
import java.io.File

/**
 * 会话作用域：一次交互属于哪个**会话 / 项目 / 工作区**。
 *
 * 执行层（`core` / 工具 / 插件宿主）需要工作区时，只能从显式传入的 [SessionScope] 里取；
 * 拿不到就 fail-fast，**绝不**回退到任何「最近活跃工作区」（那个访问器已随本批删除）——
 * 回退正是「静默写错目录」这类历史事故的入口（`AGENTS.md` §10.4）。
 *
 * 留在 `app` 包、不进 `plugin-api`：它是宿主的会话概念，插件只该拿到 `File` 参数。
 */
data class SessionScope(
    val sessionId: String,
    val projectId: String,
    val workspace: File,
)

/**
 * 当前活跃作用域；**没有打开的会话时为 null**（不回退 scratch，也不回退「第一个项目」）。
 *
 * **只读一次**当前会话，再按它的 `id` 查注册表取工作区：早先的写法是
 * `scopeOf(activeSessionMeta, activeSessionWorkspace())`，那两个参数各自读一次当前会话，
 * 切换会话期间能拼出「A 的 sessionId/projectId + B 的工作区」—— 一个自相矛盾的作用域。
 * 这个类型存在的意义就是三元组自洽，不自洽就只剩「不返回 scratch」这点价值（A 段评审 P2-3）。
 */
fun AppContainer.activeScope(): SessionScope? = resolveScope({ activeSessionMeta }, ::openWorkspaceOf)

/**
 * 作用域解析（纯函数）：**只调一次** [meta]，再按它的 id 取工作区 —— 三元组来自同一次快照。
 *
 * 单独抽出来是为了让「原子性」可测：`AppContainer` 要 Android `Context`，
 * 而这条性质正是 `SessionScopeTest.scope is a single snapshot` 要钉的。
 */
internal fun resolveScope(
    meta: () -> SessionMeta?,
    workspaceOf: (String) -> File?,
): SessionScope? {
    val snapshot = meta() ?: return null
    return scopeOf(snapshot, workspaceOf(snapshot.id))
}

/**
 * 作用域解析（纯函数，便于单测）：会话元信息或它仍在注册表里的工作区缺一即 null。
 *
 * 单独抽出来是为了钉住「作用域随会话关闭而失效」这条语义 —— 只按元信息构造的话，
 * 已关闭的会话仍会给出作用域，等于把「当前会话」这件事又变回隐式全局。
 */
internal fun scopeOf(meta: SessionMeta?, openWorkspace: File?): SessionScope? =
    if (meta == null || openWorkspace == null) null else SessionScope(meta.id, meta.projectId, openWorkspace)
