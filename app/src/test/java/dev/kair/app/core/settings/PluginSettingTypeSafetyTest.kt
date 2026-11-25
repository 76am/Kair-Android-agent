package dev.kair.app.core.settings

import dev.kair.app.app.fakes.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 插件设置类型失配不得崩设置页（U51）。
 *
 * 触发场景：插件升级把键从 toggle 改成 number（或 text 改成 toggle），重装不清设置键，
 * 下一版按新类型读旧值 —— 生产 `SharedPreferences.getXxx` 抛 `ClassCastException`，
 * 而读取点在 `SettingRow` 的组合期，设置页直接崩。
 *
 * 四条对称失配用例钉住「永不抛 + 返回 null + 删键」；两条回归钉住正常路径语义不变
 * （含「没设过 → null」与「设过 false → false」仍可区分）。prefs 桩复用同包既有的
 * [FakeSharedPreferences]（其 getXxx 已对齐生产的失配抛错语义），不另起一套。
 */
class PluginSettingTypeSafetyTest {

    private val prefs = FakeSharedPreferences()
    private val store = SettingsStore(prefs)

    @Test
    fun `reading an int where a string is stored returns null instead of throwing`() {
        prefs.edit().putString("plugin_setting_test_key", "abc").apply()
        val value = store.pluginSettingInt("test", "key")
        assertNull("类型失配必须返回缺省，而不是抛 ClassCastException", value)
        assertFalse("失配键应被清掉", prefs.contains("plugin_setting_test_key"))
    }

    @Test
    fun `reading a bool where a string is stored returns null instead of throwing`() {
        prefs.edit().putString("plugin_setting_test_key", "true").apply()
        val value = store.pluginSettingBool("test", "key")
        assertNull("类型失配必须返回缺省，而不是抛 ClassCastException", value)
        assertFalse("失配键应被清掉", prefs.contains("plugin_setting_test_key"))
    }

    @Test
    fun `reading a bool where an int is stored returns null instead of throwing`() {
        prefs.edit().putInt("plugin_setting_test_key", 42).apply()
        val value = store.pluginSettingBool("test", "key")
        assertNull("类型失配必须返回缺省，而不是抛 ClassCastException", value)
        assertFalse("失配键应被清掉", prefs.contains("plugin_setting_test_key"))
    }

    @Test
    fun `reading a string where a bool is stored returns null instead of throwing`() {
        prefs.edit().putBoolean("plugin_setting_test_key", true).apply()
        val value = store.pluginSettingString("test", "key")
        assertNull("类型失配必须返回缺省，而不是抛 ClassCastException", value)
        assertFalse("失配键应被清掉", prefs.contains("plugin_setting_test_key"))
    }

    @Test
    fun `bool still distinguishes never-set from explicit false`() {
        assertNull("没设过必须返回 null", store.pluginSettingBool("test", "unset"))
        store.setPluginSettingBool("test", "flag", false)
        assertEquals("设过 false 必须返回 false，不能与没设过混淆", false, store.pluginSettingBool("test", "flag"))
    }

    @Test
    fun `normal typed reads and writes round-trip unchanged`() {
        store.setPluginSettingString("test", "text", "hello")
        store.setPluginSettingInt("test", "count", 7)
        store.setPluginSettingBool("test", "flag", true)
        assertEquals("hello", store.pluginSettingString("test", "text"))
        assertEquals(7, store.pluginSettingInt("test", "count"))
        assertEquals(true, store.pluginSettingBool("test", "flag"))
        // 失配删键之后，状态退回「没设过」，按新类型重写必须正常工作。
        prefs.edit().putString("plugin_setting_test_count", "dirty").apply()
        assertNull(store.pluginSettingInt("test", "count"))
        store.setPluginSettingInt("test", "count", 9)
        assertEquals(9, store.pluginSettingInt("test", "count"))
    }
}
