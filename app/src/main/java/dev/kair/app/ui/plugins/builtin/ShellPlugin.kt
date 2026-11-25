package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.tools.LocalCommandRunner

/**
 * 贡献内置的命令执行后端（Android 的 `/system/bin/sh`）。内核只认
 * [dev.kair.app.core.tools.CommandRunner] 契约；换成 proot 提供的完整 Linux
 * 用户态只需替换本插件，不动内核、不动 `container_shell` 工具。
 */
class ShellPlugin : Plugin {
    override val id = "dev.kair.plugin.shell"
    override val name = "命令后端"
    override val description = "内置命令执行后端：Android 自带的 /system/bin/sh（toybox）。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        ctx.commandRunner(LocalCommandRunner())
    }
}
