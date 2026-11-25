package dev.kair.app.ui.plugins.builtin

import dev.kair.app.app.AppContainer
import dev.kair.app.core.plugin.Plugin

/**
 * Built-in plugins bundled with the app, ported from the shape of pi-web's
 * bundled `info` plugin (metadata + `activate` contributing panels/actions).
 */
fun builtinPlugins(container: AppContainer): List<Plugin> = listOf(
    StoragePlugin(container.storageRoot),
    ShellPlugin(),
    ProotPlugin(container), // 在 ShellPlugin 之后注册：就绪时覆盖本地 shell 后端
    TerminalPlugin(container), // 消费 ProotPlugin 发布的常驻会话；必须在它之后注册
    DesktopPlugin(container), // 图形桌面（U86）：同一条容器会话上的第三条通道（RFB 视图）
    BrowserPlugin(container), // 会话内 Agent Dev Browser（P1）：常驻 WebView + browser_* 工具
    ProviderTransportPlugin(),
    ContextPlugin(),
    SystemPromptPlugin(),
    MemoryPlugin(container), // 记忆段 order=25：在系统提示段落之后注册，与顺序无关
    PromptPresetsPlugin(container), // 按项目绑定的身份提示词（只改开场白）
    SkillsPlugin(container), // 索引段 order=15，插在工具清单与规则之间
    SubagentsPlugin(container),
    AskUserPlugin(container), // ask_user 工具：挂起等待用户回答（面板见 ChatScreen）
    CachePlugin(container),
    RequestLogPlugin(container), // 请求诊断：集中查看 / 导出本次运行的真实请求
    McpPlugin(container), // 需要 ProotPlugin 已提供 stdio 进程工厂（列表里更靠后即可）
    WorkspaceFilesPlugin(container),
    TrashPlugin(container), // 回收站独立面板：与文件管理器共用同一套列表件
    DevToolsPlugin(container),
    KeepAlivePlugin(container), // 后台常驻：前台服务 + 常驻通知
    PhantomKillerPlugin(container), // U90 进程保活：关幻影进程查杀 + 内置 adb 引导（设备级，独立于容器）
    SchedulerPlugin(container), // 定时任务：用插件作用域 + 会话桥，卸载即停
    ApprovalPlugin(container), // 最后注册：审批钩子对所有工具生效
    InfoPlugin(container),
)
