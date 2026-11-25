package dev.kair.app.core.storage

import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.newId
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SessionMeta(
    val id: String = newId(),
    val projectId: String,
    val name: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 存储后端契约（内核只认它，实现由插件提供）。
 *
 * 职责：项目 / 会话元数据 / 消息记录的持久化，以及工作区目录的定位。
 *
 * **缓存安全**：同一会话的 [loadMessages] 必须**保序保真** —— 返回顺序与写入顺序一致、
 * 字段不丢不改。它是一条会话历史前缀的唯一来源，任何重排或规范化都会改写前缀、让命中率归零。
 *
 * **工作区**：[workspaceDir] 必须返回**真实文件系统路径**（工具用 `java.io.File` 读写，
 * 未来的 proot 工作区更要求真实目录）。这一项不可替换为数据库或远端存储。
 *
 * 契约见 `docs/架构与内核边界.md` 第 5.1 节、`docs/插件接口契约.md`。
 */
interface StorageBackend {

    // ---- 项目 ----

    fun listProjects(): List<Project>

    fun createProject(name: String): Project

    fun deleteProject(id: String)

    /**
     * 重命名项目：展示名与工作区目录名一起改（目录名由实现决定，通常按新名字生成）。
     * 调用方必须**先关闭该项目的会话** —— 会话持有旧工作区路径。
     */
    fun renameProject(id: String, name: String)

    /** 项目工作区目录（真实路径）。 */
    fun workspaceDir(project: Project): File

    /**
     * 项目工作区父目录（所有项目的真实路径都在它下面；容器把它整个挂到 `/projects`）。
     *
     * 默认从任一项目推导；没有项目时返回 null。内置本地存储会覆盖它，**空项目时也返回**
     * 稳定的父目录，让「先起常驻终端、后建项目」也能自动看到新目录。
     */
    fun workspaceBase(): File? = listProjects().firstOrNull()?.let { workspaceDir(it).parentFile }

    // ---- 会话 ----

    fun listSessions(projectId: String? = null): List<SessionMeta>

    fun createSession(projectId: String, name: String, model: String): SessionMeta

    fun renameSession(id: String, name: String)

    /** 标记会话最近活跃时间（用于排序）。 */
    fun touchSession(id: String)

    fun deleteSession(id: String)

    // ---- 消息 ----

    /** 追加一条消息（只追加，缓存安全）。 */
    fun appendMessage(sessionId: String, message: ChatMessage)

    /** 整体覆写会话记录；仅用于压缩、编辑、撤回这类显式操作。 */
    fun replaceMessages(sessionId: String, messages: List<ChatMessage>)

    /** 读取会话记录；必须保序保真。 */
    fun loadMessages(sessionId: String): List<ChatMessage>
}
