package dev.kair.app.core.agent

import dev.kair.app.core.tools.Tool

/** 构建系统提示时可用的只读上下文。 */
data class SystemPromptContext(
    /** 命令实际执行处：容器生效时是容器内工作区路径（如 `/projects/<目录名>`），否则是宿主真实路径。 */
    val cwd: String,
    val tools: List<Tool>,
    /**
     * 设置页「附加系统指令」的正文（**全局**生效，不分项目）；空串表示用户没填。
     *
     * 由内置段落 `dev.kair.prompt.addendum` 消费，追加在系统提示末尾。身份（开场白）走
     * 「提示词预设」按项目**替换**，与本字段不是同一件事。
     */
    val addendum: String,
    /**
     * 宿主侧**真实**工作区路径（应用进程可直接用 `java.io.File` 读写）。
     *
     * 与 [cwd] 的区别只在容器生效时：那时 [cwd] 是容器内挂载点，宿主进程看不到，
     * 贡献点若需要读工作区里的文件（如记忆插件读 `.kair/memory.md`）必须用本字段。
     * 默认取 [cwd]，兼容不关心容器的工作区之外的调用方。
     */
    val workspace: String = cwd,
)

/**
 * 系统提示段落贡献点。实现由插件提供，内核只负责按 [order] 排序后拼接。
 *
 * - 内核按 `order` 升序拼接各段，同 `order` 按注册顺序（稳定排序）。
 * - [section] 返回 null 或空串表示本段不贡献。
 * - 段内容进入**缓存稳定前缀**，因此必须是确定性的：不得包含时间戳、随机数、
 *   或随会话变化的内容；易变内容应放在系统提示之外。
 *
 * 契约见 `docs/架构与内核边界.md` 第 5.1 节、`docs/插件接口契约.md`。
 */
interface SystemPromptContributor {

    /** 稳定标识，如 `dev.kair.prompt.rules`。 */
    val id: String

    /** 排序权重，小的在前。内置段落间隔 10，便于插件插入其间。 */
    val order: Int

    /** 生成本段正文；返回 null / 空串即不贡献。 */
    fun section(ctx: SystemPromptContext): String?
}
