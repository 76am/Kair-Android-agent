package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.str
import dev.kair.app.core.tools.strProp
import kotlinx.serialization.json.JsonObject

/**
 * `install_plugin`：安装 / 重载一个动态插件。
 *
 * 典型流程：agent 用 `write` 把 `manifest.json` + 脚本写到工作区
 * `plugins/<id>/` 下，再调本工具（默认 source 即 `plugins/<id>`）；宿主把目录
 * 复制到持久插件目录并装载。
 *
 * **需要审批**（`requiresApproval = true`），且**不允许永久放行**
 * （`approvableForever = false`）—— 动态插件能覆盖内核能力（如命令后端），
 * 等价于任意代码执行，只能逐次或逐会话放行。
 */
class InstallPluginTool(private val host: DynamicPluginHost) : Tool {
    override val name = "install_plugin"
    override val description =
        "Install or reload a dynamic plugin. Write manifest.json plus scripts to a directory " +
            "in the workspace (default plugins/<id>/), then call this. Read the `plugin-authoring` " +
            "skill first to learn the format."
    override val promptSnippet = "install a dynamic plugin from a workspace directory"
    override val promptGuidelines = listOf(
        "Before installing a plugin, write its files under a workspace directory following the `plugin-authoring` skill",
        "install_plugin requires user approval; if denied, do not retry — explain the situation instead",
    )
    override val requiresApproval = true
    override val approvableForever = false
    override val parameters = schemaOf(
        props(
            "id" to strProp("Plugin id; must equal manifest.json's id field (also the directory name)"),
            "source" to strProp(
                "Workspace-relative directory containing manifest.json (default: plugins/<id>)",
            ),
        ),
        "id",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.reqStr("id")
        val source = args.str("source") ?: "plugins/$id"
        val src = java.io.File(dev.kair.app.core.tools.PathUtils.resolveToCwd(source, ctx.workspace.path))
        return host.installFrom(src, id).fold(
            onSuccess = { ToolResult(it) },
            onFailure = { ToolResult("安装失败：${it.message}", isError = true) },
        )
    }
}

/**
 * `uninstall_plugin`：卸载（禁用）一个动态插件；文件保留，重新 install 可恢复。
 * 同样需要审批、不允许永久放行。
 */
class UninstallPluginTool(private val host: DynamicPluginHost) : Tool {
    override val name = "uninstall_plugin"
    override val description = "Uninstall (disable) a dynamic plugin by id. Files are kept; reinstall to restore."
    override val promptSnippet = "uninstall a dynamic plugin by id"
    override val requiresApproval = true
    override val approvableForever = false
    override val parameters = schemaOf(
        props("id" to strProp("要卸载的插件 id")),
        "id",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.reqStr("id")
        return if (host.uninstall(id)) {
            ToolResult("已卸载插件 $id（文件保留，可重新安装）")
        } else {
            ToolResult("未找到已安装的动态插件：$id", isError = true)
        }
    }
}
