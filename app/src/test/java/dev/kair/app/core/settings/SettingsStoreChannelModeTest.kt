package dev.kair.app.core.settings

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.proot.ContainerRunner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 执行通道的取值收敛（2026-11-08 删 `auto`；2026-11-12 U78 删 chroot 通道）。
 *
 * 通道设置项已下线（ADR 0007），存量值与脏值一律读成 proot：老设备里存着 `chroot` /
 * `auto` 时，升级后不得有任何路径把它当第二条通道用（那会重新引入 root 属主文件）。
 */
class SettingsStoreChannelModeTest {

    private fun store(vararg pairs: Pair<String, String?>): SettingsStore {
        val prefs = FakeSharedPreferences()
        pairs.forEach { (key, value) -> if (value != null) prefs.edit().putString(key, value).apply() }
        return SettingsStore(prefs)
    }

    @Test
    fun `default is proot`() {
        assertEquals(ContainerRunner.MODE_PROOT, store().containerMode)
    }

    @Test
    fun `legacy auto is read as proot`() {
        assertEquals(ContainerRunner.MODE_PROOT, store("container_mode" to "auto").containerMode)
    }

    @Test
    fun `legacy chroot is read as proot`() {
        assertEquals(ContainerRunner.MODE_PROOT, store("container_mode" to "chroot").containerMode)
    }

    @Test
    fun `unknown value is read as proot`() {
        assertEquals(ContainerRunner.MODE_PROOT, store("container_mode" to "whoknows").containerMode)
    }

    @Test
    fun `legacy disabled tool names map to the new ones`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet("disabled_tools", setOf("bash", "local_shell", "read")).apply()
        assertEquals(setOf("container_shell", "android_shell", "read"), SettingsStore(prefs).disabledTools)
    }

    @Test
    fun `writing back drops the legacy names`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet("disabled_tools", setOf("bash")).apply()
        val store = SettingsStore(prefs)
        store.disabledTools = store.disabledTools - "container_shell"
        assertEquals(emptySet<String>(), SettingsStore(prefs).disabledTools)
    }
}
