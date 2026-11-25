package dev.kair.app.core.settings

import dev.kair.app.core.plugin.PluginSettingsStore

/**
 * [PluginSettingsStore] 的落盘实现：把某插件的设置键值存进 [SettingsStore]
 * （SharedPreferences），键按插件 id 前缀隔离。
 */
class SharedPrefsPluginSettingsStore(
    private val settings: SettingsStore,
    private val pluginId: String,
) : PluginSettingsStore {

    override fun getBoolean(key: String, default: Boolean): Boolean =
        settings.pluginSettingBool(pluginId, key) ?: default

    override fun getInt(key: String, default: Int): Int =
        settings.pluginSettingInt(pluginId, key) ?: default

    override fun getString(key: String, default: String): String =
        settings.pluginSettingString(pluginId, key) ?: default

    override fun setBoolean(key: String, value: Boolean) =
        settings.setPluginSettingBool(pluginId, key, value)

    override fun setInt(key: String, value: Int) =
        settings.setPluginSettingInt(pluginId, key, value)

    override fun setString(key: String, value: String) =
        settings.setPluginSettingString(pluginId, key, value)
}
