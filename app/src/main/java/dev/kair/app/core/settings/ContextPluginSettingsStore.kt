package dev.kair.app.core.settings

import dev.kair.app.core.plugin.PluginSettingsStore

/**
 * 上下文压缩插件（`dev.kair.plugin.context`）的设置存储适配器。
 *
 * 它的四个配置项（自动压缩开关 / 压缩触发阈值 / 压缩保留原文 / 压缩模型）直接映射到应用级
 * [SettingsStore]，从而与「设置」页里的同名项**共用同一份数据**（单一事实来源）—— 在插件面板里
 * 改，设置页同步变化，反之亦然。其余未知键回退到按插件前缀的通用实现。
 * 键名与区间见 [CompactionFields]（字段定义的单一出处）。
 */
class ContextPluginSettingsStore(
    private val settings: SettingsStore,
    private val fallback: PluginSettingsStore,
) : PluginSettingsStore {

    override fun getBoolean(key: String, default: Boolean): Boolean = when (key) {
        KEY_ENABLED -> settings.autoCompaction
        else -> fallback.getBoolean(key, default)
    }

    override fun getInt(key: String, default: Int): Int = when (key) {
        KEY_RESERVE -> settings.compactionReserveTokens
        KEY_KEEP -> settings.compactionKeepRecentTokens
        else -> fallback.getInt(key, default)
    }

    override fun getString(key: String, default: String): String = when (key) {
        KEY_MODEL -> settings.compactionModelId
        else -> fallback.getString(key, default)
    }

    override fun setBoolean(key: String, value: Boolean) {
        when (key) {
            KEY_ENABLED -> settings.autoCompaction = value
            else -> fallback.setBoolean(key, value)
        }
    }

    override fun setInt(key: String, value: Int) {
        when (key) {
            KEY_RESERVE -> settings.compactionReserveTokens = value
            KEY_KEEP -> settings.compactionKeepRecentTokens = value
            else -> fallback.setInt(key, value)
        }
    }

    override fun setString(key: String, value: String) {
        when (key) {
            KEY_MODEL -> settings.compactionModelId = value
            else -> fallback.setString(key, value)
        }
    }

    companion object {
        const val KEY_ENABLED = CompactionFields.KEY_ENABLED
        const val KEY_RESERVE = CompactionFields.KEY_RESERVE
        const val KEY_KEEP = CompactionFields.KEY_KEEP
        const val KEY_MODEL = CompactionFields.KEY_MODEL
    }
}
