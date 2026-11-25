package dev.kair.app.core.ai

/**
 * 内置提供商目录。协议入口收敛为 **OpenAI Chat Completions / OpenAI Responses /
 * Anthropic Messages / Gemini 原生** 四类，连接信息（baseUrl / apiKey / model）
 * 全部由用户保存的模型配置（`SavedModel`）自带。
 *
 * 只为「URL 有明确默认值且协议不同」的常用端点预置条目（如 LongCat、Gemini）；
 * 其余 OpenAI 兼容厂商走「OpenAI 兼容（自定义 URL）」，由用户自行填写。
 * 插件仍可通过 `ctx.provider(def)` 注册额外端点。
 */
object ModelCatalog {

    private val builtins: List<ProviderDef> = listOf(
        ProviderDef(
            id = "openai",
            displayName = "自定义",
            defaultBaseUrl = "https://api.openai.com/v1",
            transportId = TransportIds.OPENAI_CHAT,
            needsKey = true,
        ),
        ProviderDef(
            id = "longcat",
            displayName = "美团 LongCat",
            defaultBaseUrl = "https://api.longcat.chat/openai/v1",
            transportId = TransportIds.OPENAI_CHAT,
            needsKey = true,
        ),
        ProviderDef(
            id = "gemini",
            displayName = "Google Gemini（原生）",
            defaultBaseUrl = "https://generativelanguage.googleapis.com",
            transportId = TransportIds.GEMINI_GENERATE,
            needsKey = true,
        ),
        // 旧版本保存的 Anthropic 模型仍按此条目解析；新建入口已从选择器隐藏，
        // 改由「自定义」的协议选择进入，避免选择器膨胀。
        ProviderDef(
            id = "anthropic",
            displayName = "Anthropic（自定义）",
            defaultBaseUrl = "https://api.anthropic.com",
            transportId = TransportIds.ANTHROPIC_MESSAGES,
            needsKey = true,
        ),
    )

    /** Insertion-ordered registry seeded with the built-ins; plugins may add more. */
    private val registry = LinkedHashMap<String, ProviderDef>().apply {
        builtins.forEach { put(it.id, it) }
    }

    /** All known providers, built-in first, in registration order. */
    val providers: List<ProviderDef> get() = registry.values.toList()

    /**
     * Add or replace a provider definition. Plugins do this via `ctx.provider(def)`,
     * which lets an extension expose a new endpoint in the provider picker without
     * editing the catalog. Returns false for a blank id.
     */
    fun register(def: ProviderDef): Boolean {
        if (def.id.isBlank()) return false
        registry[def.id] = def
        return true
    }

    /** Remove a plugin-registered provider. Built-ins cannot be removed. */
    fun unregister(id: String): Boolean {
        if (builtins.any { it.id == id }) return false
        return registry.remove(id) != null
    }

    fun byId(id: String): ProviderDef = registry[id] ?: registry.values.first()

    /**
     * Best-effort context window for a model id, used to trigger compaction. Providers do
     * not expose this uniformly, so we match on well-known families and fall back to a
     * conservative 128k. The user can override this in settings.
     */
    fun contextWindow(model: String): Int {
        val m = model.lowercase()
        return when {
            "claude" in m -> 200_000
            "gemini" in m -> 1_000_000
            "longcat" in m -> 1_000_000
            "gpt-4.1" in m || "gpt-5" in m -> 1_000_000
            "o1" in m || "o3" in m || "o4" in m -> 200_000
            "gpt-4" in m || "gpt-3.5" in m -> 128_000
            "deepseek" in m -> 65_536
            "grok" in m -> 131_072
            "llama-3.3" in m || "llama-3.1" in m -> 131_072
            "qwen" in m || "llama" in m -> 32_768
            else -> 128_000
        }
    }
}
