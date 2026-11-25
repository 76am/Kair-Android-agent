package dev.kair.app.core.settings

/**
 * 「这次运行到底用哪个模型」的唯一解析点。
 *
 * 背景：需要模型的地方有五处 —— 系统提示与采样参数（`agentSettings`）、鉴权与端点
 * （`providerConfigFor`）、视觉工具可见性（`toolEnabled`）、上下文窗口 / 压缩线
 * （`resolveContextWindow`）、以及子智能体会话。它们此前各自调 `sessionModel()` 重新解析，
 * 一旦某一处多一层覆盖，就会出现「提示用 A 模型、鉴权走 B、窗口按 C、视觉工具按 D」的错位：
 * 接错端点会 401/404，窗口算错会提前压缩或撞窗，视觉工具错位会让模型调用一个它用不了的
 * 工具。所以解析收敛到这里，其余四处只接受解析结果（见 `AGENTS.md` §10.4）。
 *
 * 覆盖优先级（高 → 低），任何一层指向**已被删除**的模型时静默落到下一层：
 *
 * 1. `runOverrideId` —— 本次运行级覆盖（`subagent` 工具的 `model` 参数、插件 `runSubagent`）
 * 2. `subagentDefaultId` —— 设置项「子智能体模型」
 * 3. `sessionOverrideId` —— 会话级切换（输入框底部的模型切换器）
 * 4. `appDefaultId` —— 全局激活项
 * 5. 列表第一条 —— 用户还没选过任何默认项时的兜底
 */
object ModelResolution {

    fun resolve(
        saved: List<SavedModel>,
        runOverrideId: String? = null,
        subagentDefaultId: String? = null,
        sessionOverrideId: String? = null,
        appDefaultId: String? = null,
    ): SavedModel? {
        if (saved.isEmpty()) return null
        val byId = saved.associateBy { it.id }
        // 逐层查找：无效 id（空串 / 已删除）不入结果，直接落到下一层。
        for (id in listOf(runOverrideId, subagentDefaultId, sessionOverrideId, appDefaultId)) {
            val found = id?.takeIf { it.isNotBlank() }?.let { byId[it] }
            if (found != null) return found
        }
        return saved.first()
    }

    /**
     * 压缩摘要使用的模型：设置项「压缩模型」> 本会话模型。
     *
     * 不套用 [resolve] 的列表兜底：会话说到底是**已解析出来的那个模型**（子智能体创建时
     * 固定的模型可能已被用户从列表里删掉），空 / 已删除 id 时应原样回到它，而不是跳到
     * 全局默认 —— 那会让一次摘要突然用上另一个端点。
     */
    fun resolveCompaction(
        saved: List<SavedModel>,
        compactionId: String?,
        sessionModel: SavedModel?,
    ): SavedModel? = compactionId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> saved.firstOrNull { it.id == id } }
        ?: sessionModel
}

/**
 * 把设置页的「子智能体思考强度」覆盖应用到解析后的模型上。
 *
 * 只改**这一次解析结果**，不回写 [SavedModel] 列表 —— 同一个模型在主会话里仍按自己的
 * 默认档位工作，子智能体才用覆盖值。
 *
 * 语义：
 * - [effort] 空白 / null：跟随模型配置（原样返回）；
 * - [ReasoningAuto]：强制「自动」（`reasoningValue()` 返回 null，不发送思考参数）；
 * - 模型 `reasoningLevels` 里声明的档位：覆盖，实际发送值仍走 `thinkingLevelMap`；
 * - 其它（模型被换掉、档位已不再支持）：忽略覆盖，落到模型自己的配置。
 *   设置页在换模型时也会清掉不再适用的值，这里兜住旧数据 / 并发写入。
 */
internal fun SavedModel.withReasoningOverride(effort: String?): SavedModel {
    if (!isReasoningModel) return this
    return when {
        effort.isNullOrBlank() -> this
        effort == ReasoningAuto -> copy(reasoningEffort = ReasoningAuto)
        effort in reasoningLevels -> copy(reasoningEffort = effort)
        else -> this
    }
}

/** 设置页换模型时用：当前覆盖值在这个模型上是否仍能生效。 */
internal fun SavedModel.supportsReasoningOverride(effort: String): Boolean = when {
    !isReasoningModel -> false
    effort.isBlank() -> true
    effort == ReasoningAuto -> true
    else -> effort in reasoningLevels
}
