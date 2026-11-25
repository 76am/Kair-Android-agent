package dev.kair.app.core.agent

import dev.kair.app.core.tools.Tool

/**
 * 一次「设置 → agent 配置」装配的完整产物：正文配置 + 诊断 + 段落缓存读数。
 *
 * 放在内核侧而不是 `app/AgentSettingsFactory.kt` 里，是因为它现在要穿过内核的依赖面
 * （`TurnExecutor.Host.assembly`）：`core/` 不 import `dev.kair.app.app.*` 是这层一直守着的
 * 边界（`ArchitectureGuardTest` 的同类规则），把类型留在宿主包里就等于让内核反向依赖宿主。
 * 字段全是内核类型，装配函数 [dev.kair.app.app.buildAgentSettings] 仍留在宿主侧。
 *
 * [issues]（段落缺席 / 贡献点异常）与 [sectionCache]（U30 的命中率观测面）都跟正文来自
 * 同一次装配，但**都不进** [AgentSettings] —— 那个类型的契约是「同输入必须同字节」
 * （`AgentSettingsFactoryTest.same input yields the same bytes` 整对象比对），而读数恰恰随
 * 缓存冷热变。记不记它们都不改变任何一位输出字节。
 *
 * 为什么非要在这一层带出来（而不是「观测器存最近一次装配的数」）：一个回合里
 * `agentSettings` 会被调**好几次**（`TurnRunner.kt:213` 取 maxSteps、`:235` 取 model、
 * `CompactionRunner.kt:83/89` 估 token、`AgentSession.contextWindow()` 给界面用），
 * 请求之后那一次几乎必然全命中 —— 存「最近一份」就会把当前这发请求的读数改写成与它相反的值
 * （U30 第一轮 B 审 P1）。读数必须跟着**它自己那次装配**的产物走，由调用方一路交给
 * [CacheInspector.record]。本体已由 U31 ③-a 搬进 `app/app/SessionAssembly.kt`，
 * `AppContainer` 只留同名委托。
 */
data class AgentAssembly(
    val settings: AgentSettings,
    val issues: List<SectionIssue> = emptyList(),
    val sectionCache: SectionCacheStats? = null,
    /**
     * 本次装配实际使用的工具列表（U42）：系统提示的工具段由它产出，请求 schema 也必须
     * 从它派生 —— 两侧共用同一份，切模型 / 改工具开关才不会「提示有、schema 没有」。
     * 不进 [AgentSettings]：工具实例带执行能力，不是「同输入同字节」的纯配置。
     */
    val tools: List<Tool> = emptyList(),
)
