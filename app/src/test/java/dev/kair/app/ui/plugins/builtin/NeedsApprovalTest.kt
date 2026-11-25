package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.plugin.InMemoryPluginSettingsStore
import dev.kair.app.core.tools.ToolCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * container_shell（容器）与 android_shell（设备）是两个**独立开关**：开一个只拦那一条通道，
 * 而不是像以前那样一开就同时拦两条。键名与 `ApprovalPlugin` 里的常量保持一致
 * （这里用字面量锁定持久化键，顺带防止误改键名破坏老用户的设置）。
 *
 * 分类开关（write / delete / execute）只作用于主动声明了 [ToolCategory] 的工具；
 * 未声明的第三方工具由装载层补成 `requiresApproval = true`，不在这里兜底。
 */
class NeedsApprovalTest {

    private val store = InMemoryPluginSettingsStore()

    private fun needs(tool: String, category: ToolCategory, requiresApproval: Boolean = false) =
        needsApproval(tool, requiresApproval, category, store)

    @Test
    fun `both switches off gates neither channel`() {
        assertFalse(needs("container_shell", ToolCategory.EXECUTE))
        assertFalse(needs("android_shell", ToolCategory.EXECUTE))
    }

    @Test
    fun `container_shell switch gates only container_shell`() {
        store.setBoolean("approve_bash", true)
        assertTrue(needs("container_shell", ToolCategory.EXECUTE))
        assertFalse(needs("android_shell", ToolCategory.EXECUTE))
    }

    @Test
    fun `android_shell switch gates only android_shell`() {
        store.setBoolean("approve_local_shell", true)
        assertTrue(needs("android_shell", ToolCategory.EXECUTE))
        assertFalse(needs("container_shell", ToolCategory.EXECUTE))
    }

    @Test
    fun `tool own declaration overrides the switches`() {
        assertTrue(needs("install_plugin", ToolCategory.UNSPECIFIED, requiresApproval = true))
    }

    @Test
    fun `all-tools switch gates every tool`() {
        store.setBoolean("approve_all", true)
        assertTrue(needs("container_shell", ToolCategory.EXECUTE))
        assertTrue(needs("read", ToolCategory.READ))
        assertTrue(needs("third_party_read", ToolCategory.READ))
    }

    @Test
    fun `read category is never gated by write or delete switches`() {
        store.setBoolean("approve_write", true)
        store.setBoolean("approve_dangerous", true)
        assertFalse(needs("third_party_read", ToolCategory.READ))
    }

    @Test
    fun `write category follows the write switch`() {
        assertFalse(needs("third_party_write", ToolCategory.WRITE))
        store.setBoolean("approve_write", true)
        assertTrue(needs("third_party_write", ToolCategory.WRITE))
    }

    @Test
    fun `delete category is on by default like the danger switch`() {
        assertTrue(needs("third_party_delete", ToolCategory.DELETE, requiresApproval = false))
        store.setBoolean("approve_dangerous", false)
        assertFalse(needs("third_party_delete", ToolCategory.DELETE))
    }

    @Test
    fun `non-shell execute category follows the execute switch`() {
        assertFalse(needs("third_party_exec", ToolCategory.EXECUTE))
        store.setBoolean("approve_execute", true)
        assertTrue(needs("third_party_exec", ToolCategory.EXECUTE))
    }

    @Test
    fun `execute switch does not gate the shell channels`() {
        store.setBoolean("approve_execute", true)
        assertFalse(needs("container_shell", ToolCategory.EXECUTE))
        assertFalse(needs("android_shell", ToolCategory.EXECUTE))
    }

    @Test
    fun `undeclared builtin tools keep the legacy name-based rules`() {
        store.setBoolean("approve_write", true)
        assertTrue(needs("write", ToolCategory.UNSPECIFIED))
        assertFalse(needs("read", ToolCategory.UNSPECIFIED))
    }
}
