package dev.kair.app.core.storage

import dev.kair.app.core.ai.Json
import dev.kair.app.core.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 一套可复用的「助手身份」提示词。
 *
 * 只包含开场白（身份段）正文：工具清单 / 行为规则 / 工作目录这类段落改错会**静默**
 * 出错（写错路径、调用不存在的工具），仍由内核锁定，不进入预设。
 */
@Serializable
data class PromptPreset(
    val id: String,
    val name: String,
    /** 替换开场白的正文；空串 = 用内置开场白。 */
    val preamble: String = "",
    /** 关掉后该预设不生效：绑定它的项目回落到内置身份。 */
    val enabled: Boolean = true,
)

/**
 * 预设库 + 项目绑定关系（`<storageRoot>/prompt-presets.json`）。
 *
 * 为什么不写进 `Project`（`plugin-api` 的 `StorageBackend` 契约）：给数据类加构造参数
 * 会改掉 JVM 构造签名，旧插件编译出的 `Project(...)` 调用会 `NoSuchMethodError`。
 * 绑定关系放在这份独立索引里，插件二进制契约保持不变。
 *
 * 项目绑定优先于全局覆写：绑定即「这个项目由某个预设决定身份」；未绑定才看全局设置。
 */
class PromptPresetStore(private val root: File) {
    private val file = File(root, "prompt-presets.json")
    private val lock = Any()

    fun presets(): List<PromptPreset> = load().presets

    fun preset(id: String): PromptPreset? = load().presets.firstOrNull { it.id == id }

    /**
     * 项目绑定的预设 id；null = 内置默认。
     *
     * 指向已删除预设的绑定视为未绑定（删除接口本身会拒绝删被引用的预设，
     * 这里只是兜底手改文件的情况）。
     */
    fun boundPresetId(projectId: String): String? {
        val data = load()
        return data.bindings[projectId]?.takeIf { id -> data.presets.any { it.id == id } }
    }

    /** 项目绑定的预设（含停用状态）；null = 未绑定。 */
    fun boundPreset(projectId: String): PromptPreset? =
        boundPresetId(projectId)?.let { preset(it) }

    /** 项目实际生效的身份正文；null = 用内置开场白（未绑定 / 已停用 / 正文为空）。 */
    fun effectivePreamble(projectId: String): String? =
        boundPreset(projectId)?.takeIf { it.enabled }?.preamble?.takeIf { it.isNotBlank() }

    /** 正在使用该预设的项目 id（删除前检查，避免把项目身份打空）。 */
    fun boundProjectIds(presetId: String): List<String> =
        load().bindings.filterValues { it == presetId }.keys.toList()

    fun create(name: String, preamble: String = ""): PromptPreset = synchronized(lock) {
        val data = load()
        val preset = PromptPreset(
            id = newId(),
            name = name.trim().ifEmpty { DEFAULT_NAME },
            preamble = preamble,
        )
        save(data.copy(presets = data.presets + preset))
        preset
    }

    /** 覆盖一条预设（按 id 匹配）；id 不存在时忽略。 */
    fun update(preset: PromptPreset) = synchronized(lock) {
        val data = load()
        save(data.copy(presets = data.presets.map { if (it.id == preset.id) preset else it }))
    }

    /**
     * 删除一条预设。仍被项目引用时**拒绝**并返回 false：留着那条绑定的项目会
     * 悄悄换回内置身份，用户还以为自己选的那套还在。
     */
    fun delete(id: String): Boolean = synchronized(lock) {
        val data = load()
        if (data.bindings.containsValue(id)) {
            false
        } else {
            save(data.copy(presets = data.presets.filterNot { it.id == id }))
            true
        }
    }

    /** 绑定项目到某预设；[presetId] 为 null（或预设不存在）= 切回内置默认。 */
    fun bind(projectId: String, presetId: String?) = synchronized(lock) {
        val data = load()
        val valid = presetId?.takeIf { id -> data.presets.any { it.id == id } }
        val bindings = if (valid == null) data.bindings - projectId else data.bindings + (projectId to valid)
        save(data.copy(bindings = bindings))
    }

    private fun load(): PromptPresetFile {
        if (!file.exists()) return PromptPresetFile()
        return runCatching { Json.decodeFromString<PromptPresetFile>(file.readText()) }.getOrElse {
            // 解析失败先留副本：否则下一次保存会把唯一一份预设永久覆盖成空。
            runCatching {
                file.copyTo(
                    File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"),
                    overwrite = true,
                )
            }
            PromptPresetFile()
        }
    }

    private fun save(data: PromptPresetFile) {
        root.mkdirs()
        writeAtomically(file, Json.encodeToString(data))
    }

    private companion object {
        const val DEFAULT_NAME = "预设"
    }
}

@Serializable
private data class PromptPresetFile(
    val presets: List<PromptPreset> = emptyList(),
    /** projectId → presetId；没有条目 = 内置默认。 */
    val bindings: Map<String, String> = emptyMap(),
)
