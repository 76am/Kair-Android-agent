package dev.kair.app.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * 用户保存的一条模型配置 —— 多模型管理的核心单元。
 *
 * 形态参考 pi 的 `models.json`（每个模型自带「能力表」），但按 Kair 的会话场景**压平**
 * 成「一条记录 = 一个可切换模型」：同一提供商可以有任意多条，各带 model / 输出上限 /
 * 能力标记 / 思考等级映射。用户的旧「按提供商存一份」配置由 SettingsStore 一次性迁移。
 *
 * 全部字段带默认值 + `ignoreUnknownKeys`，以便后续增字段不破坏已存配置。
 */
@Serializable
data class SavedModel(
    val id: String,
    /** 显示名称；会话底部切换器与设置列表都显示它。 */
    val name: String,
    /** 提供商 id：`openai`（自定义）/ `longcat` / `gemini`，见 `ModelCatalog`。 */
    val providerId: String,
    /**
     * 协议覆盖（仅「自定义」用）：`openai-chat` / `openai-responses` / `anthropic-messages`。
     * null = 用 `providerId` 对应提供商的默认协议；LongCat / Gemini 预设不写该字段。
     */
    val transportId: String? = null,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** 模型 id（发给服务端的 `model` 字段）。 */
    val model: String = "",
    /** 单次回复的输出上限（`max_tokens` / `max_completion_tokens`）；null = 用默认 8192。 */
    val maxTokens: Int? = null,
    /** 该模型的上下文窗口；null = 回退到按模型名内置推断。 */
    val contextWindow: Int? = null,
    /** 该模型的温度；null = 不发送，交给服务端默认。 */
    val temperature: Double? = null,
    /** 可接收图片（多模态）——勾选后才注册读图工具并允许发图。 */
    val isVisionModel: Boolean = false,
    /** 推理模型——勾选后会话底部才出现思考等级切换。 */
    val isReasoningModel: Boolean = false,
    /**
     * 是否把历史回合的思考内容回传给模型提供商。默认开启：DeepSeek 要求携带 tools 的
     * 请求在后续每一轮都完整回传 `reasoning_content`，否则直接 400。关闭只影响请求，
     * 界面与日志仍保留思考原文。
     */
    val replayThinking: Boolean = true,
    /** 该模型**支持**哪些思考等级（用户在设置里勾选；未勾选的不出现在会话里）。 */
    val reasoningLevels: List<String> = emptyList(),
    /** 思考等级 → 实际发送值；缺省用等级名本身（如 openai 的 `reasoning_effort: "low"`）。 */
    val thinkingLevelMap: Map<String, String> = emptyMap(),
    /** 当前选中的思考等级；null 或 [ReasoningAuto] = 不发送，交给服务端默认。 */
    val reasoningEffort: String? = null,
    // ---- 高级采样参数（仅 OpenAI 兼容协议支持；null = 不发送，见改进任务书第 8 项）----
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topP: Double? = null,
    val seed: Int? = null,
) {
    /** 实际要发送的思考参数值；null 表示不发送。 */
    fun reasoningValue(): String? {
        val level = reasoningEffort ?: return null
        if (level == ReasoningAuto) return null
        return thinkingLevelMap[level] ?: level
    }

    /**
     * 实际发送的输出上限：用户留空（null）时用 [DEFAULT_MAX_TOKENS]。
     * 在请求装配处兜底而不是保存时写死，旧配置与导入数据无需迁移。
     */
    fun effectiveMaxTokens(): Int = maxTokens ?: DEFAULT_MAX_TOKENS

    companion object {
        /** 输出上限留空时的默认值。 */
        const val DEFAULT_MAX_TOKENS = 8192
    }
}

/** UI 与传输层共用的思考等级取值（顺序即展示顺序）。 */
val ReasoningLevels: List<String> = listOf("minimal", "low", "medium", "high", "xhigh", "max", "off")

/** 会话里的「自动」：不发送任何思考参数，由服务端默认。 */
const val ReasoningAuto: String = "auto"

/** `SavedModel` 列表的持久化编解码（放在数据模型旁边，避免 SettingsStore 掺 JSON 细节）。 */
internal val SavedModelsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeSavedModels(models: List<SavedModel>): String =
    SavedModelsJson.encodeToString(ListSerializer(SavedModel.serializer()), models)

internal fun decodeSavedModels(raw: String?): List<SavedModel> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        // 逐条解码：某一条缺字段 / 类型不符时只丢这一条，其余模型仍然可用。
        // 之前整份列表一起解码，一条坏记录就会让所有模型从设置页消失。
        SavedModelsJson.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            runCatching {
                SavedModelsJson.decodeFromJsonElement(SavedModel.serializer(), element)
            }.getOrNull()
        }
    } catch (_: Exception) {
        // 连 JSON 结构都不是数组：降级为「没有模型」，让用户重新添加。
        emptyList()
    }
}
