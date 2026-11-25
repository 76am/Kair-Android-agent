package dev.kair.app.core.mcp

import java.io.File

/**
 * stdio MCP 子进程工厂：由 ProotPlugin 在容器后端就绪时提供，MCP 管理器只消费。
 *
 * 归属：整个应用（容器能力是单例）。生命周期：ProotPlugin.activate 时替换，
 * 插件禁用 / rootfs 卸载时清空。并发：工厂本身无状态，每个服务器各自起一个进程。
 */
class McpProcessHost {
    @Volatile
    private var factory: ((McpTransportConfig.Stdio, File) -> Process?)? = null

    fun replace(factory: ((McpTransportConfig.Stdio, File) -> Process?)?) {
        this.factory = factory
    }

    fun clear() {
        factory = null
    }

    /** 容器不可用（rootfs 未就绪 / proot 缺失）时返回 null，由调用方如实报错。 */
    fun spawn(config: McpTransportConfig.Stdio, workspace: File): Process? =
        factory?.invoke(config, workspace)
}
