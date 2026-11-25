package dev.kair.app.app

import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.AgentSettings
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.agent.SystemPromptContext
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.ai.SamplingOptions
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.tools.Tool
import java.io.File

/**
 * 「设置 → AgentSettings」的装配输入快照：只含装配真正读的设置项。
 *
 * 抽出来的目的（P4-1 / A2）：`agentSettings` 原来夹在 `AppContainer` 里直接读
 * `SettingsStore`（依赖 Android Context），「接线错」—— 设置项还在、值没传进
 * `AgentSettings` —— 没有任何测试能红（历史实例：`systemPromptAddendum` 退化，
 * 全量单测全绿，因为它测的是 `SystemPromptBuilder`，不是**装配**）。
 * 现在装配是纯函数：同输入必须同输出，每个字段的去向都有单测点名。
 */
data class SettingsSnapshot(
    val maxSteps: Int,
    val systemPromptAddendum: String,
)

/**
 * 会话生效的 Agent 配置（**纯函数**，无 IO / 无副作用 / 无 Android）。
 *
 * 职责边界：只做「装配」——
 * - 工具清单由调用方过滤好传入（与发给模型的 schema 一致，如未勾选视觉模型时不列 `read_image`）；
 * - 贡献段落由调用方传入（`plugins.systemPromptContributors()`）；
 * - `<cwd>` 解析复用 [promptCwd]（容器内工作区路径优先，见 `PromptWiring.kt`）。
 *
 * [model] 的尾部参数（温度 / 输出上限 / 思考强度 / 采样参数）也在这里映射：
 * 「设置 → AgentSettings」的接线错有一半出在「模型配置字段没传进 `SamplingOptions`」。
 *
 * 返回 [AgentAssembly]（诊断 + 段落缓存读数一起带出宿主边界）：**纯**指「不进设置、不改
 * 任何一位输出字节」，不是指产物里没有旁路读数 —— 那两个字段随缓存冷热变，所以绝不能
 * 并进 [AgentSettings]。为什么读数要跟着产物走而不是让观测器存最近一份，见类型的 KDoc。
 */
fun buildAgentSettings(
    snapshot: SettingsSnapshot,
    model: SavedModel?,
    workspace: File,
    /** 工作区在容器内的真实路径（`/projects/<目录名>`）；不在容器里时 null。 */
    containerCwd: String?,
    tools: List<Tool>,
    contributors: List<SystemPromptContributor>,
    projectId: String?,
): AgentAssembly {
    val assembly = SystemPromptBuilder.buildDetailed(
        SystemPromptContext(
            // <cwd> 取命令「实际」执行处：会进容器时给容器内工作区路径，否则给宿主真实路径。
            // 每次请求实时解析 —— rootfs 安装/卸载、执行通道切换后，旧会话下一回合即对齐。
            // 注意：该值进入系统提示前缀，变化会失效该会话此前的前缀缓存（已知代价）。
            cwd = promptCwd(containerCwd, workspace),
            tools = tools,
            // 全局附加指令（设置页「附加系统指令」）：追加在提示末尾，对所有项目生效。
            // 身份（开场白）走「提示词预设」按项目替换，两者不是一回事。
            addendum = snapshot.systemPromptAddendum,
            // 宿主真实工作区：容器生效时 cwd 是容器内挂载点，插件读工作区文件必须用这个。
            workspace = workspace.path,
        ),
        contributors,
        projectId,
    )
    val settings = AgentSettings(
        model = model?.model.orEmpty(),
        systemPrompt = assembly.text,
        maxSteps = snapshot.maxSteps,
        // 请求尾部参数全部来自当前模型配置：改参数不影响缓存前缀（见 SamplingOptions）。
        sampling = SamplingOptions(
            temperature = model?.temperature,
            maxTokens = model?.effectiveMaxTokens(),
            reasoningEffort = model?.reasoningValue(),
            frequencyPenalty = model?.frequencyPenalty,
            presencePenalty = model?.presencePenalty,
            topP = model?.topP,
            seed = model?.seed,
        ),
        // 模型配置缺省时按「开启」处理：DeepSeek 这类强制回传的服务商才不会 400。
        replayThinking = model?.replayThinking ?: true,
    )
    return AgentAssembly(settings, assembly.issues, assembly.sectionCache, tools)
}
