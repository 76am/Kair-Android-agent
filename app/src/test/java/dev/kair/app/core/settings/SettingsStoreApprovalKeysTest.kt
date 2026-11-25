package dev.kair.app.core.settings

import dev.kair.app.app.fakes.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具改名（2026-11-08）的审批放行键迁移：归类键形如 `<工具名>:<命令>`，
 * 旧名前缀读取时映射为新名 —— 与 `disabledTools` 同一理由，改名不重置用户已放行的意图。
 * 通道归一与开关映射由 `SettingsStoreChannelModeTest` 覆盖（A 侧先补），这里不重复。
 */
class SettingsStoreApprovalKeysTest {

    @Test
    fun `legacy approval keys map to new tool prefixes`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet(
            "approval_always_allowed",
            setOf("bash:ls -la", "local_shell:id -u", "container_shell:pwd", "install_plugin:foo"),
        ).apply()
        assertEquals(
            setOf("container_shell:ls -la", "android_shell:id -u", "container_shell:pwd", "install_plugin:foo"),
            SettingsStore(prefs).approvalAlwaysAllowed,
        )
    }

    @Test
    fun `legacy and new approval keys for the same command dedup`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putStringSet("approval_always_allowed", setOf("bash:ls", "container_shell:ls")).apply()
        assertEquals(setOf("container_shell:ls"), SettingsStore(prefs).approvalAlwaysAllowed)
    }
}
