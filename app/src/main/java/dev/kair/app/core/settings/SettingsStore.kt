package dev.kair.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import dev.kair.app.core.ai.ModelCatalog
import dev.kair.app.core.ai.TransportIds
import dev.kair.app.core.mcp.McpConfigCodec
import dev.kair.app.core.mcp.McpServerConfig
import dev.kair.app.core.proot.ContainerMirror
import dev.kair.app.core.proot.ContainerRunner

/**
 * SharedPreferences-backed settings；模型配置见 [SavedModel] 列表（非按提供商分区）。
 *
 * 构造收 [SharedPreferences] 一条缝（P4-1 / A2）：30 个属性的读写面不动，只把
 * 「存储后端」换成可注入的 —— `AppContainer` 才能在 JVM 上用内存假件构造，接线进得了单测。
 * 生产路径仍从 [Context] 派生（次构造）。没有把整个 SettingsStore 接口化：那要镜像
 * 30 个属性，成本是这条缝的十倍，而覆盖的是同一类缺陷（P4-1「不要做」第 3 条）。
 */
class SettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("kair_settings", Context.MODE_PRIVATE))

    // ---- 多模型配置（SavedModel）--------------------------------------------
    // 旧实现是「每个提供商一份 baseUrl/apiKey/model」，只能各存一个模型；现改为
    // 一份 SavedModel 列表 + 一个激活 id。旧键只在 migrate() 里读一次，之后不再写。

    /** 已保存的模型配置；顺序即用户排序（会话底部切换器按此顺序）。 */
    var savedModels: List<SavedModel>
        get() = decodeSavedModels(prefs.getString(KEY_SAVED_MODELS, null))
        set(value) = prefs.edit().putString(KEY_SAVED_MODELS, encodeSavedModels(value)).apply()

    /**
     * MCP 服务器配置（全局）。JSON 存在 prefs 里，与 `saved_models` 同一模式；
     * 连接真态由 [dev.kair.app.core.mcp.McpManager] 持有，这里只落盘配置。
     */
    var mcpServers: List<McpServerConfig>
        get() {
            val raw = prefs.getString(KEY_MCP_SERVERS, null) ?: return emptyList()
            val decoded = McpConfigCodec.decode(raw)
            // 非空的原文解不出来（损坏 / 手改出错）：先备份原文。否则下一次保存会把
            // 用户填的 headers / token 永久覆盖掉。
            if (decoded.isEmpty() && raw.isNotBlank() && raw.trim() != "[]") {
                prefs.edit().putString(KEY_MCP_SERVERS_BACKUP, raw).apply()
            }
            return decoded
        }
        set(value) = prefs.edit().putString(KEY_MCP_SERVERS, McpConfigCodec.encode(value)).apply()

    /** 当前激活的模型配置 id。 */
    var activeModelId: String
        get() = prefs.getString(KEY_ACTIVE_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACTIVE_MODEL, value).apply()

    /** 当前生效的模型配置；列表为空时返回 null（UI 应引导去设置页添加）。 */
    fun activeModel(): SavedModel? =
        savedModels.firstOrNull { it.id == activeModelId } ?: savedModels.firstOrNull()

    /** 新增或覆盖一条模型配置（按 id 匹配）；列表为空时自动设为激活。 */
    fun upsertModel(model: SavedModel) {
        val list = savedModels
        val next = if (list.any { it.id == model.id }) {
            list.map { if (it.id == model.id) model else it }
        } else {
            list + model
        }
        savedModels = next
        if (activeModel() == null || activeModelId.isBlank()) activeModelId = model.id
    }

    /** 删除一条模型配置；若删的是激活项，则顺延到相邻一条。 */
    fun deleteModel(id: String) {
        val next = savedModels.filterNot { it.id == id }
        savedModels = next
        if (activeModelId == id) activeModelId = next.firstOrNull()?.id.orEmpty()
    }

    /**
     * 子智能体默认使用的模型 id；空串 = 跟随发起委派那个会话的模型（旧行为）。
     *
     * 与子智能体能力的 `model` 参数（本次运行覆盖）不同：这是持久默认值，
     * 优先级见 [ModelResolution]。指向已删除模型时静默落到下一层，不报错。
     */
    var subagentModelId: String
        get() = prefs.getString("subagent_model_id", "").orEmpty()
        set(value) = prefs.edit().putString("subagent_model_id", value).apply()

    /**
     * 子智能体思考强度覆盖；空串 = 跟随所选子智能体模型自己的 `reasoningEffort`。
     *
     * 取值来自所选模型的 `reasoningLevels`，或 [ReasoningAuto]（不发送思考参数）。
     * 只影响子智能体；不回写模型配置，主会话仍按模型自己的默认档位工作。
     */
    var subagentReasoningEffort: String
        get() = prefs.getString("subagent_reasoning_effort", "").orEmpty()
        set(value) = prefs.edit().putString("subagent_reasoning_effort", value).apply()

    /** 会话级模型覆盖：未设置时回退到 [activeModelId]（见 AppContainer 解析）。 */
    fun sessionModelId(sessionId: String): String? =
        prefs.getString(KEY_SESSION_MODEL_PREFIX + sessionId, null)

    fun setSessionModelId(sessionId: String, modelId: String) =
        prefs.edit().putString(KEY_SESSION_MODEL_PREFIX + sessionId, modelId).apply()

    /**
     * 会话上次看到的工具集指纹（会话创建时记录、其后工具集变化时更新）。用于判定
     * 「本会话的工具集是否相对上次记录发生了变化」，从而决定是否注入一次变化提示。
     * 早于该特性创建的旧会话没有记录（返回 null），按「已变化」处理。
     */
    fun sessionToolsetFingerprint(sessionId: String): String? =
        prefs.getString(KEY_SESSION_TOOLSET_PREFIX + sessionId, null)

    fun setSessionToolsetFingerprint(sessionId: String, fingerprint: String) =
        prefs.edit().putString(KEY_SESSION_TOOLSET_PREFIX + sessionId, fingerprint).apply()

    /**
     * 一次性迁移旧的单模型配置：把「provider_id + base_url_x + api_key_x + model_x」
     * 搬成一条 [SavedModel]。仅在 `saved_models` 键尚不存在时执行（之后不重复、不覆盖）。
     */
    fun migrateLegacyModelConfigIfNeeded() {
        if (prefs.contains(KEY_SAVED_MODELS)) return
        val legacyProvider = prefs.getString("provider_id", null) ?: return
        val legacyModel = prefs.getString("model_$legacyProvider", null).orEmpty()
        if (legacyProvider.isBlank() && legacyModel.isBlank()) return
        val def = ModelCatalog.byId(legacyProvider)
        val providerId = if (def.transportId == TransportIds.ANTHROPIC_MESSAGES) "anthropic" else "openai"
        val migrated = SavedModel(
            id = "legacy-$legacyProvider",
            name = legacyModel.ifBlank { def.displayName },
            providerId = providerId,
            baseUrl = prefs.getString("base_url_$legacyProvider", null) ?: def.defaultBaseUrl,
            apiKey = prefs.getString("api_key_$legacyProvider", null).orEmpty(),
            model = legacyModel,
        )
        savedModels = listOf(migrated)
        activeModelId = migrated.id
    }

    /**
     * 单回合最大步数。**静钳到 ≥1**（U20）：0 / 负数会让 `TurnRunner` 在并入点之前
     * `break@inner` —— 通知永不并入，`runUser` 收尾的 sweep 空跑 `runLoop`，回合永不结束。
     * setter 钳新输入（设置页填 0 存进去就是 1，界面重开显示钳后的值，不弹错）；
     * getter 也钳：修掉历史版本已落盘的 0 / 负数（老设备不必重进设置页）。
     */
    var maxSteps: Int
        get() = prefs.getInt("max_steps", 200).coerceAtLeast(1)
        set(value) = prefs.edit().putInt("max_steps", value.coerceAtLeast(1)).apply()

    /**
     * 全局「附加系统指令」：追加到系统提示末尾的一段补充说明（`dev.kair.prompt.addendum`）。
     *
     * 与「身份定义」分属两件事：身份（开场白）由提示词预设按项目**替换**，这里只是**追加**
     * 规则且对全部项目生效。设置页的说明文案必须讲清这一点，否则用户会拿它改身份。
     */
    var systemPromptAddendum: String
        get() = prefs.getString(KEY_PROMPT_ADDENDUM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROMPT_ADDENDUM, value).apply()

    /** Auto-compact the transcript when the context nears the model window. */
    var autoCompaction: Boolean
        get() = prefs.getBoolean("auto_compaction", true)
        set(value) = prefs.edit().putBoolean("auto_compaction", value).apply()

    /**
     * 摘要请求使用的模型 id；空串 = 跟随当前会话模型（旧行为）。
     *
     * 与 [subagentModelId] 同一套语义：持久默认值，指向已删除模型时静默回退到会话模型。
     * 只换「谁来写摘要」，不换压缩阈值 —— 阈值仍按主请求的窗口算（溢出说的是主请求）。
     */
    var compactionModelId: String
        get() = prefs.getString("compaction_model_id", "").orEmpty()
        set(value) = prefs.edit().putString("compaction_model_id", value).apply()

    /** Send provider prompt-cache markers (Anthropic breakpoints / OpenAI cache key). */
    var promptCaching: Boolean
        get() = prefs.getBoolean("prompt_caching", true)
        set(value) = prefs.edit().putBoolean("prompt_caching", value).apply()

    /**
     * 每回合是否清系统提示段落缓存。
     *
     * 段落缓存的命中条件只包含「贡献点实例 / cwd / workspace / addendum / 工具列表」，
     * **看不到贡献点依赖的外部状态**（典型：记忆插件读 `.kair/memory.md`）。所以需要
     * 在「外部状态可能已变」的时机主动清缓存。
     *
     * 早期只在「工具执行后」清：纯聊天（无工具）时记忆文件即便被别的途径改了也读不到，
     * 只能等压缩上下文。现在改为**每回合**都清（含纯聊天回合），供后台子智能体定期
     * 改写记忆这类场景使用。重算不破坏前缀契约：贡献点契约要求确定性，外部状态未变时
     * 重算出的字节与上次逐字相同。内置段落是纯函数，重算开销可忽略；开销只在第三方
     * 插件的贡献点上。
     */
    var refreshPromptEveryTurn: Boolean
        // 旧键 refresh_prompt_after_tools 只作为迁移来源，新键落盘后即不再读。
        get() = prefs.getBoolean("refresh_prompt_every_turn", prefs.getBoolean("refresh_prompt_after_tools", true))
        set(value) = prefs.edit().putBoolean("refresh_prompt_every_turn", value).apply()

    /**
     * 压缩触发阈值：预留给模型自身输出的 token。上下文估算超过
     * 「窗口 - 该值」时触发压缩（见 `ContextStrategy.shouldCompact`）。
     * 因此该值越大，触发线越低、压缩越早。
     */
    var compactionReserveTokens: Int
        get() = prefs.getInt("compaction_reserve_tokens", CompactionFields.RESERVE_DEFAULT)
            .coerceIn(CompactionFields.RESERVE_MIN, CompactionFields.RESERVE_MAX)
        set(value) = prefs.edit()
            .putInt(
                "compaction_reserve_tokens",
                value.coerceIn(CompactionFields.RESERVE_MIN, CompactionFields.RESERVE_MAX),
            )
            .apply()

    /**
     * 压缩后大致保留多少近期 token 原文不摘要（`Compaction.findCutPoint` 的预算）。
     * 越大保留越多、压缩越轻；越小压得越狠。生效值还会被收敛到「窗口 - 预留」之内，
     * 见 `AgentSession.effectiveCompactionSettings`。
     */
    var compactionKeepRecentTokens: Int
        get() = prefs.getInt("compaction_keep_recent_tokens", CompactionFields.KEEP_DEFAULT)
            .coerceIn(CompactionFields.KEEP_MIN, CompactionFields.KEEP_MAX)
        set(value) = prefs.edit()
            .putInt(
                "compaction_keep_recent_tokens",
                value.coerceIn(CompactionFields.KEEP_MIN, CompactionFields.KEEP_MAX),
            )
            .apply()

    /** 被用户禁用的内置插件 id：下次启动不再装载，可在插件页重新启用。 */
    var disabledBuiltinPlugins: Set<String>
        get() = prefs.getStringSet("disabled_builtin_plugins", null) ?: emptySet()
        set(value) = prefs.edit().putStringSet("disabled_builtin_plugins", value).apply()

    /**
     * 被用户**关闭的工具名**集合：宿主重建工具终序时据此过滤，关闭的工具不进注册表，
     * 也就不会出现在请求 schema 与系统提示里（等价于未注册）。默认全部开启。
     *
     * 读取时把旧工具名（`bash` / `local_shell`）映射为新名：改名不重置用户的开关意图。
     */
    var disabledTools: Set<String>
        get() = (prefs.getStringSet("disabled_tools", null) ?: emptySet())
            .map { LEGACY_TOOL_NAMES[it] ?: it }
            .toSet()
        set(value) = prefs.edit().putStringSet("disabled_tools", value).apply()

    /**
     * 审批「永久放行」的工具归类键集合（ApprovalGate 持久化）。
     *
     * 归类键形如 `<approvalKey>:<命令>`，shell 工具的 approvalKey 就是工具名 ——
     * 读取时把旧名前缀（`bash:` / `local_shell:`）映射为新名，与 [disabledTools] 同一理由：
     * 改名不重置用户已放行的意图（同一条通道、同一条命令）。
     */
    var approvalAlwaysAllowed: Set<String>
        get() = (prefs.getStringSet("approval_always_allowed", null) ?: emptySet())
            .map { key ->
                val legacy = LEGACY_TOOL_NAMES.keys.firstOrNull { key.startsWith("$it:") }
                if (legacy == null) key else LEGACY_TOOL_NAMES.getValue(legacy) + key.removePrefix(legacy)
            }
            .toSet()
        set(value) = prefs.edit().putStringSet("approval_always_allowed", value).apply()

    /**
     * 存量执行通道设置（只读）。U78 起通道设置项已下线（ADR 0007），只有 proot 一档。
     *
     * 保留这个读取面是为了给存量升级一个**唯一**归一答案：老设备里存着 `chroot` / `auto` /
     * 非法值时，读出来一律是 [ContainerRunner.MODE_PROOT]，不会有任何路径把它当通道用。
     * 写入口已删除 —— 不再产生新的 `container_mode` 值。
     */
    val containerMode: String
        get() = ContainerRunner.MODE_PROOT

    /** 容器是否挂载手机共享存储（/sdcard）；授权真实生效才会挂载。 */
    var mountSdcard: Boolean
        get() = prefs.getBoolean("mount_sdcard", false)
        set(value) = prefs.edit().putBoolean("mount_sdcard", value).apply()

    /**
     * 容器内包管理器镜像的**预设 id**（apt / pip / rootfs 下载同源，见 `ContainerMirror`）。
     * 默认阿里云；未知 / 空 id 在解析时回退默认，不阻断容器。
     */
    var containerMirrorId: String
        get() = prefs.getString("container_mirror_id", ContainerMirror.DEFAULT.id).orEmpty()
        set(value) = prefs.edit().putString("container_mirror_id", value).apply()

    /** 首页当前选中的项目 id（整合界面用它恢复上次所在项目）。 */
    var lastProjectId: String
        get() = prefs.getString("last_project_id", "").orEmpty()
        set(value) = prefs.edit().putString("last_project_id", value).apply()

    /**
     * 第三方插件安装时的**版本兼容检查**是否严格：true（默认）拒绝不兼容插件，
     * false 仅告警放行（「跳过模式」）。无论哪种模式，用户都可在插件页对单个插件
     * 选择「强制安装」。
     */
    var pluginCompatStrict: Boolean
        get() = prefs.getBoolean("plugin_compat_strict", true)
        set(value) = prefs.edit().putBoolean("plugin_compat_strict", value).apply()

    // ---- 插件设置（按插件 id + 键分区）-------------------------------------
    // 类型失配约定（U51）：键存在但类型错了（典型：插件升级把 toggle 改成 number，
    // 重装不清设置键），读取返回 null 并删掉该键 —— 永不抛 ClassCastException。
    // 只兜这一类错，其它异常照旧抛（不用 runCatching，那会连 OutOfMemoryError 一起吞）。
    // 删键后状态退回「用户没设过」，下次写入就是干净的新类型；代价是旧值静默丢失，
    // 键改名 / 语义迁移另立批（任务文档 §9 挂账）。

    /** 键前缀隔离不同插件的设置，避免键名冲突。 */
    private fun pluginKey(pluginId: String, key: String) = "plugin_setting_${pluginId}_$key"

    fun pluginSettingString(pluginId: String, key: String): String? {
        val k = pluginKey(pluginId, key)
        return try {
            prefs.getString(k, null)
        } catch (_: ClassCastException) {
            prefs.edit().remove(k).apply()
            null
        }
    }

    fun setPluginSettingString(pluginId: String, key: String, value: String) =
        prefs.edit().putString(pluginKey(pluginId, key), value).apply()

    fun pluginSettingInt(pluginId: String, key: String): Int? {
        val k = pluginKey(pluginId, key)
        val raw = try {
            prefs.getInt(k, Int.MIN_VALUE)
        } catch (_: ClassCastException) {
            prefs.edit().remove(k).apply()
            return null
        }
        return raw.takeIf { it != Int.MIN_VALUE }
    }

    fun setPluginSettingInt(pluginId: String, key: String, value: Int) =
        prefs.edit().putInt(pluginKey(pluginId, key), value).apply()

    fun pluginSettingBool(pluginId: String, key: String): Boolean? {
        val k = pluginKey(pluginId, key)
        // contains 仍然要留：它区分「用户没设过」（返回 null）与「设过 false」（返回 false）。
        if (!prefs.contains(k)) return null
        return try {
            prefs.getBoolean(k, false)
        } catch (_: ClassCastException) {
            prefs.edit().remove(k).apply()
            null
        }
    }

    fun setPluginSettingBool(pluginId: String, key: String, value: Boolean) =
        prefs.edit().putBoolean(pluginKey(pluginId, key), value).apply()

    /** 清除一个插件的全部设置键（删除插件时回收）。 */
    fun clearPluginSettings(pluginId: String) {
        val prefix = "plugin_setting_${pluginId}_"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }

    private companion object {
        const val KEY_SAVED_MODELS = "saved_models"
        const val KEY_MCP_SERVERS = "mcp_servers"

        /** 解析失败时保留的原文（排查 / 手工恢复用），不参与任何读取路径。 */
        const val KEY_MCP_SERVERS_BACKUP = "mcp_servers_corrupt_backup"
        const val KEY_ACTIVE_MODEL = "active_model_id"
        const val KEY_SESSION_MODEL_PREFIX = "session_model_id_"
        const val KEY_SESSION_TOOLSET_PREFIX = "session_toolset_"

        /** 全局附加系统指令。键名沿用旧版，升级后用户原值继续生效。 */
        const val KEY_PROMPT_ADDENDUM = "system_prompt_addendum"

        /** 工具改名前后的名字映射：旧存档里关掉的工具开关继续生效。 */
        val LEGACY_TOOL_NAMES = mapOf(
            "bash" to "container_shell",
            "local_shell" to "android_shell",
        )
    }
}
