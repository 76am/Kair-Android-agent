package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.storage.LocalStorage
import java.io.File

/**
 * 贡献内置的本地文件存储后端。内核只认 `StorageBackend` 契约；
 * 换介质（SQLite / 加密 / 远端同步）只需替换本插件，不动内核。
 */
class StoragePlugin(private val root: File) : Plugin {
    override val id = "dev.kair.plugin.storage"
    override val name = "存储"
    override val description = "内置本地文件存储后端（项目 / 会话 / 消息）。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        ctx.storage(LocalStorage(root))
    }
}
