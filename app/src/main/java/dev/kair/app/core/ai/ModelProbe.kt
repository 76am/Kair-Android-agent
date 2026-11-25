package dev.kair.app.core.ai

import dev.kair.app.core.settings.ReasoningLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 服务商模型列表里的一条记录。字段缺失表示服务商没有公开该项。 */
data class ProbedModel(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    /** 服务商显式声明的思考档位（已归一化到 [ReasoningLevels]）；未公开时为空。 */
    val reasoningLevels: List<String> = emptyList(),
    /** 是否支持思考；null = 服务商没有公开。 */
    val reasoningSupported: Boolean? = null,
    /** 服务商声明的默认思考档位（如 DeepSeek 的 `effort.default_level`）。 */
    val defaultReasoningLevel: String? = null,
    /** 是否接受图片输入；null = 服务商没有公开（`input_modalities` / `capabilities`）。 */
    val supportsImage: Boolean? = null,
)

data class ModelListResult(
    val models: List<ProbedModel> = emptyList(),
    val error: String? = null,
)

data class CapabilityResult(
    /** 列表里匹配到的模型；null = 该模型不在服务商的模型列表中。 */
    val model: ProbedModel? = null,
    /** 服务商未公开上下文长度时，按模型名推断出的近似值。 */
    val inferredContextWindow: Int? = null,
    val error: String? = null,
)

/**
 * 通过服务商**元数据接口**探测模型能力（模型列表 / 上下文窗口 / 思考档位）。
 *
 * 只用各家的模型列表端点，不做「发请求试探参数」的黑盒探测：
 * 很多 OpenAI 兼容端点（例如 LongCat）对未知的 `reasoning_effort` 静默接受，
 * 既不报错也不改变输出，黑盒探测只会得到看似成功、实则无效的结论。
 * 因此思考档位只在服务商自己公开时才采信，否则交由用户手动配置。
 *
 * 失败一律以 [ModelListResult.error] / [CapabilityResult.error] 返回，不抛异常。
 */
object ModelProbe {

    private const val TIMEOUT_SECONDS = 25L

    /** 元数据请求很短，独立设置整体超时，避免共用「流式响应不超时」的客户端。 */
    private val client by lazy {
        HttpClient.client.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 拉取服务商的模型列表。 */
    suspend fun listModels(
        transportId: String,
        baseUrl: String,
        apiKey: String,
    ): ModelListResult = withContext(Dispatchers.IO) { fetchModels(transportId, baseUrl, apiKey) }

    /** 在模型列表中查找 [model]，返回它公开的能力。 */
    suspend fun probe(
        transportId: String,
        baseUrl: String,
        apiKey: String,
        model: String,
    ): CapabilityResult = withContext(Dispatchers.IO) {
        if (model.isBlank()) return@withContext CapabilityResult(error = "Missing model id")
        val list = fetchModels(transportId, baseUrl, apiKey)
        if (list.error != null) return@withContext CapabilityResult(error = list.error)

        val wanted = model.trim()
        val found = list.models.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
            ?: list.models.firstOrNull { it.id.substringAfterLast('/').equals(wanted, ignoreCase = true) }
        CapabilityResult(
            model = found,
            inferredContextWindow = if (found?.contextWindow == null) {
                ModelCatalog.contextWindow(wanted)
            } else {
                null
            },
        )
    }

    private fun fetchModels(transportId: String, baseUrl: String, apiKey: String): ModelListResult {
        val urls = modelListUrls(transportId, baseUrl)
        if (urls.isEmpty()) return ModelListResult(error = "Missing base URL")
        val headers = authHeaders(transportId, apiKey)

        var lastError: String? = null
        for (url in urls) {
            val response = runCatching { get(url, headers) }.getOrElse {
                return ModelListResult(error = it.message ?: it.javaClass.simpleName)
            }
            if (response.code !in 200..299) {
                lastError = "HTTP ${response.code}: ${response.body.take(300)}"
                // 只有路径不对才换下一种候选（例如用户只填了裸域名）；
                // 鉴权 / 限流等错误换路径也不会成功，立即返回。
                if (response.code != 404 && response.code != 405) {
                    return ModelListResult(error = lastError)
                }
                continue
            }
            val models = when (transportId) {
                TransportIds.ANTHROPIC_MESSAGES -> parseAnthropicModels(response.body)
                TransportIds.GEMINI_GENERATE -> parseGeminiModels(response.body)
                else -> parseOpenAiModels(response.body)
            }
            if (models.isNotEmpty()) return ModelListResult(models)
            lastError = "HTTP ${response.code}: empty model list"
        }
        return ModelListResult(error = lastError)
    }

    private fun authHeaders(transportId: String, apiKey: String): Map<String, String> = buildMap {
        when (transportId) {
            TransportIds.ANTHROPIC_MESSAGES -> {
                // 版本头与密钥相互独立：某些自建网关不需要密钥但仍校验版本。
                put("anthropic-version", "2023-06-01")
                if (apiKey.isNotBlank()) put("x-api-key", apiKey)
            }
            TransportIds.GEMINI_GENERATE -> if (apiKey.isNotBlank()) put("x-goog-api-key", apiKey)
            else -> if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
        }
    }

    private fun get(url: String, headers: Map<String, String>): HttpResult {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            return HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private class HttpResult(val code: Int, val body: String)
}

/**
 * 模型列表端点的候选地址（按尝试顺序）。
 *
 * - OpenAI 兼容：`{base}/models`；裸域名再补 `{base}/v1/models`。
 * - Anthropic：`{base}/v1/models`，base 已带 `/v1` 时先收回。
 * - Gemini：`{base}/v1beta/models`，base 已带 `/v1beta` 时不重复。
 */
internal fun modelListUrls(transportId: String, baseUrl: String): List<String> {
    val root = baseUrl.trim().trimEnd('/')
    if (root.isEmpty()) return emptyList()
    return when (transportId) {
        TransportIds.ANTHROPIC_MESSAGES -> {
            val base = if (root.endsWith("/v1", ignoreCase = true)) root.dropLast(3) else root
            listOf("$base/v1/models")
        }
        TransportIds.GEMINI_GENERATE -> {
            val base = if (root.endsWith("/v1beta", ignoreCase = true)) root else "$root/v1beta"
            listOf("$base/models")
        }
        else -> buildList {
            add("$root/models")
            if (!root.endsWith("/v1", ignoreCase = true)) add("$root/v1/models")
        }
    }
}

/** OpenAI 兼容的 `/models` 响应；也兼容直接返回数组的端点。 */
internal fun parseOpenAiModels(body: String): List<ProbedModel> {
    val array = runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { root ->
        (root as? JsonObject)?.get("data") as? JsonArray ?: root as? JsonArray
    } ?: return emptyList()

    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
        val provider = obj["top_provider"] as? JsonObject
        val context = obj.int(
            "context_window", "context_length", "max_context_length", "max_input_tokens",
            "input_token_limit", "max_seq_len", "context_size",
        ) ?: provider?.int("context_length", "max_input_tokens")
        val output = obj.int("max_output_tokens", "max_completion_tokens", "output_token_limit")
            ?: provider?.int("max_completion_tokens", "max_output_tokens")
        val reasoning = reasoningMetadata(obj)
        ProbedModel(
            id = id,
            displayName = obj.string("display_name") ?: obj.string("name")?.takeIf { it != id },
            contextWindow = context,
            maxOutputTokens = output,
            reasoningLevels = reasoning.levels,
            reasoningSupported = reasoning.supported,
            defaultReasoningLevel = reasoning.defaultLevel,
            supportsImage = imageSupport(obj),
        )
    }
}

/** Anthropic `/v1/models`：`data[].id` / `display_name`，不公开上下文长度。 */
internal fun parseAnthropicModels(body: String): List<ProbedModel> {
    val array = runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { root ->
        (root as? JsonObject)?.get("data") as? JsonArray
    } ?: return emptyList()

    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        ProbedModel(
            id = id,
            displayName = obj.string("display_name"),
        )
    }
}

/** Gemini `/v1beta/models`：`models[].name`（带 `models/` 前缀）与 input/outputTokenLimit。 */
internal fun parseGeminiModels(body: String): List<ProbedModel> {
    val array = runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let { root ->
        (root as? JsonObject)?.get("models") as? JsonArray
    } ?: return emptyList()

    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj.string("name") ?: obj.string("id"))?.removePrefix("models/") ?: return@mapNotNull null
        ProbedModel(
            id = id,
            displayName = obj.string("displayName"),
            contextWindow = obj.int("inputTokenLimit"),
            maxOutputTokens = obj.int("outputTokenLimit"),
        )
    }
}

/** 一条模型记录里公开的思考能力。 */
internal data class ReasoningMetadata(
    val levels: List<String> = emptyList(),
    val supported: Boolean? = null,
    val defaultLevel: String? = null,
)

/**
 * 从一条模型记录里读思考能力。只认服务商显式公开的字段：
 * `effort` / `reasoning` / `thinking` 对象、`capabilities` 与 `supported_parameters` 数组。
 */
internal fun reasoningMetadata(obj: JsonObject): ReasoningMetadata {
    val levels = linkedSetOf<String>()
    var supported: Boolean? = null
    var defaultLevel: String? = null

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        val level = if (value == "none" || value == "disabled") "off" else value
        return level.takeIf { it in ReasoningLevels }
    }

    fun collect(element: JsonElement?) {
        val array = element as? JsonArray ?: return
        array.forEach { item -> normalize(item.asString())?.let { levels += it } }
    }

    fun defaultOf(container: JsonObject): String? =
        normalize(
            container["default_level"]?.asString()
                ?: container["default_effort"]?.asString()
                ?: container["default"]?.asString(),
        )

    // DeepSeek：`effort: {supported_levels, default_level}`。
    (obj["effort"] as? JsonObject)?.let { effort ->
        collect(
            effort["supported_levels"] ?: effort["supported_efforts"]
                ?: effort["levels"] ?: effort["values"] ?: effort["efforts"],
        )
        defaultLevel = defaultLevel ?: defaultOf(effort)
        if (levels.isNotEmpty() || effort.containsKey("default_level") || effort.containsKey("default_effort")) {
            supported = true
        }
    }

    (obj["reasoning"] as? JsonObject)?.let { reasoning ->
        collect(
            reasoning["supported_efforts"] ?: reasoning["supported_levels"]
                ?: reasoning["efforts"] ?: reasoning["levels"],
        )
        defaultLevel = defaultLevel ?: defaultOf(reasoning)
        if (levels.isNotEmpty() || reasoning.containsKey("default_effort") || reasoning.containsKey("mandatory")) {
            supported = true
        }
    }

    when (val thinking = obj["thinking"]) {
        is JsonObject -> {
            collect(thinking["values"] ?: thinking["levels"] ?: thinking["supported_levels"])
            defaultLevel = defaultLevel ?: defaultOf(thinking)
            val enabled = (thinking["enabled"] as? JsonPrimitive)?.booleanOrNull
            if (enabled != null) {
                supported = enabled
            } else if (levels.isNotEmpty() || thinking.keys.any { it.contains("support", ignoreCase = true) }) {
                supported = true
            }
        }
        is JsonPrimitive -> thinking.booleanOrNull?.let { supported = it }
        else -> Unit
    }

    (obj["capabilities"] as? JsonArray)?.forEach { item ->
        val capability = item.asString()?.lowercase() ?: return@forEach
        if (capability == "thinking" || capability == "reasoning") supported = true
    }
    (obj["supported_parameters"] as? JsonArray)?.forEach { item ->
        val parameter = item.asString()?.lowercase() ?: return@forEach
        if (parameter.startsWith("reasoning") || parameter == "thinking") supported = true
    }

    return ReasoningMetadata(levels.toList(), supported, defaultLevel)
}

/**
 * 从一条模型记录里读图片输入能力。
 *
 * 认两种公开写法：`input_modalities`（DeepSeek / OpenRouter 的 `architecture.input_modalities`）
 * 与 `capabilities` 里的 `vision` / `image`。字段存在但不含 image 即视为不支持；
 * 完全没有公开则返回 null，不改动用户的勾选。
 */
internal fun imageSupport(obj: JsonObject): Boolean? {
    val modalities = obj["input_modalities"] as? JsonArray
        ?: (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
    if (modalities != null) {
        return modalities.any {
            val value = it.asString()?.lowercase()
            value == "image" || value == "vision" || value == "image_url"
        }
    }
    val capabilities = obj["capabilities"] as? JsonArray ?: return null
    return capabilities.any {
        it.asString()?.lowercase() in setOf("vision", "image", "image_input", "multimodal")
    }
}

private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonObject.string(key: String): String? = this[key]?.asString()

private fun JsonObject.int(vararg keys: String): Int? {
    for (key in keys) {
        val value = (this[key] as? JsonPrimitive)?.intOrNull
        if (value != null && value > 0) return value
    }
    return null
}
