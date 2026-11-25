package dev.kair.app.core.agent

import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.tools.Tool

/**
 * 一发请求的不可变输入快照（U42）：模型、鉴权、工具面、采样、窗口出自同一次解析，
 * 请求装配与溢出判定只认它，不再各自回读设置 —— 「提示用 A、鉴权走 B、窗口按 C」
 * 的错位只在多处现取时成立（`AGENTS.md` §10.4）。
 *
 * [assembly] 携带同一次装配的系统提示 / 诊断 / 段落缓存读数（后两者不进前缀，见 [AgentAssembly]）。
 * 主会话每发请求重新解析（设置热更新下一发即时生效）；子智能体创建时固定的只是**模型派生量**
 * （鉴权 / 窗口 / 工具面过滤器），`maxSteps`、附加系统指令与插件贡献段仍按调用时现取 ——
 * 差异由 SessionSpec 的显式字段承载，本类型不按「是不是子智能体」分叉。
 */
class ResolvedRequestInputs(
    val model: SavedModel?,
    val providerConfig: ProviderConfig,
    val contextWindow: Int,
    val assembly: AgentAssembly,
    /**
     * 压缩摘要请求的目标（U113）：设置项「压缩模型」优先，空 / 已删除时与本发请求同源。
     * 摘要必须用**它自己的**端点与鉴权，否则会把请求发到主模型的提供商；窗口不在这里 ——
     * 压缩阈值仍按主请求的窗口算（溢出说的是主请求）。
     */
    val compactionTarget: CompactionTarget = CompactionTarget(model?.model.orEmpty(), providerConfig),
) {
    /** 提示词与 schema 共用的工具面：就是本次装配列进系统提示的那份（U42 同源），不另存。 */
    val tools: List<Tool> get() = assembly.tools

    /** 采样与系统提示出自同一次装配，同样不另存一份。 */
    val sampling: SamplingOptions get() = assembly.settings.sampling
}

/** 一次摘要请求的落点：模型名与它自己的端点 / 鉴权（可以与主请求不同提供商）。 */
data class CompactionTarget(val model: String, val config: ProviderConfig)
