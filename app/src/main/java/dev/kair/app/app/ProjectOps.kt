package dev.kair.app.app

import dev.kair.app.core.storage.Project
import dev.kair.app.ui.plugins.builtin.MemoryStore

/**
 * 项目名操作的失败原因。**不含文案** —— core / app 层不碰 `Context`，
 * 中文由界面映射到 `strings.xml`（`ui/components/ProjectNameDialog.kt` 的项目名对话框）。
 */
enum class ProjectNameError {
    /** 名字为空 / 全是空白。 */
    BLANK,

    /** 与已有项目同名（忽略大小写）。 */
    DUPLICATE,

    /** 该项目还有会话在忙（回合运行 / 排队 / 后台任务未投递），先停掉再改名。 */
    BUSY,

    /** 工作区目录搬不动（被占用 / 权限）：名字与目录必须一起改，不能只改一半。 */
    MOVE_FAILED,

    /** 新建时写索引失败。 */
    CREATE_FAILED,
}

sealed interface ProjectOpResult {
    data class Ok(val project: Project) : ProjectOpResult

    data class Failed(val error: ProjectNameError) : ProjectOpResult
}

/**
 * 新建项目：名字唯一（忽略大小写），工作区目录名由存储层从名字派生。
 *
 * 目录名可能被清洗 / 去重（`projectDirName`），所以**展示名与目录名不保证逐字相同**；
 * 界面只展示项目名，不展示目录名。
 */
fun AppContainer.createProject(name: String): ProjectOpResult {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ProjectOpResult.Failed(ProjectNameError.BLANK)
    if (storage.listProjects().any { it.name.equals(trimmed, ignoreCase = true) }) {
        return ProjectOpResult.Failed(ProjectNameError.DUPLICATE)
    }
    return try {
        val project = storage.createProject(trimmed)
        // 记忆文件随项目一起建（空两段模板）：面板编辑器与 read 工具有明确落点，
        // agent 不必猜「创建在哪里」。空模板不改变注入正文，缓存前缀不受影响。
        MemoryStore.ensureTemplate(storage.workspaceDir(project).absolutePath)
        ProjectOpResult.Ok(project)
    } catch (_: IllegalArgumentException) {
        // 校验在存储层也有一份（并发 / 插件直接调用），这里只把失败翻译成可展示的原因。
        ProjectOpResult.Failed(ProjectNameError.DUPLICATE)
    } catch (_: Exception) {
        ProjectOpResult.Failed(ProjectNameError.CREATE_FAILED)
    }
}

/**
 * 重命名项目：展示名与工作区目录**一起改**（目录名按新名字重新生成）。
 *
 * 规则（负责人 2026-10-30 定）：
 * - 不许同名：与其它项目重名（忽略大小写）直接拒绝；
 * - **有会话在忙就不改** —— 会话持有旧路径，回合运行 / 排队 / 后台任务未完成时搬目录会让
 *   它下一步读到不存在的路径。忙的定义与内存淘汰一致（[SessionRegistry] 的 `isIdle`）。
 * - 空闲会话先关闭再搬目录：历史在存储里，用户重开会话时按新路径加载，不丢消息。
 *
 * @return null 表示成功；否则是拒绝 / 失败的原因。
 */
fun AppContainer.renameProject(project: Project, name: String): ProjectNameError? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ProjectNameError.BLANK
    if (trimmed == project.name) return null
    if (storage.listProjects().any { it.id != project.id && it.name.equals(trimmed, ignoreCase = true) }) {
        return ProjectNameError.DUPLICATE
    }
    val workspacePath = storage.workspaceDir(project).absolutePath
    if (sessions.anyBusyWhere { it.workspace.absolutePath == workspacePath }) return ProjectNameError.BUSY
    // 空闲才走到这里；releaseWhere 会顺带清审批 / 子智能体 / 后台任务等登记资源。
    sessions.releaseWhere { it.workspace.absolutePath == workspacePath }
    return try {
        storage.renameProject(project.id, trimmed)
        null
    } catch (_: IllegalArgumentException) {
        ProjectNameError.DUPLICATE
    } catch (_: Exception) {
        ProjectNameError.MOVE_FAILED
    }
}
