package dev.kair.app.ui.plugins.builtin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 放行归类键按**调用内容**生成：同样命令的不同 timeout 视为同一键，不同命令互不牵连，
 * 危险命令带独立 `#danger` 后缀（见 Issue #8）。
 */
class ApprovalRuleKeyTest {

    private fun shellArgs(command: String, timeout: Int = 30) = buildJsonObject {
        put("command", JsonPrimitive(command))
        put("timeout", JsonPrimitive(timeout))
    }

    @Test
    fun `same command with different timeout shares one key`() {
        val a = approvalRuleKey("container_shell", "container_shell", shellArgs("ls -la", timeout = 30), danger = false)
        val b = approvalRuleKey("container_shell", "container_shell", shellArgs("ls -la", timeout = 60), danger = false)
        assertEquals(a, b)
    }

    @Test
    fun `different commands get different keys`() {
        val ls = approvalRuleKey("container_shell", "container_shell", shellArgs("ls -la"), danger = false)
        val lsShort = approvalRuleKey("container_shell", "container_shell", shellArgs("ls -l"), danger = false)
        val rm = approvalRuleKey("container_shell", "container_shell", shellArgs("rm -rf /tmp"), danger = false)
        assertNotEquals(ls, lsShort)
        assertNotEquals(ls, rm)
    }

    @Test
    fun `dangerous command carries its own key`() {
        val normal = approvalRuleKey("container_shell", "container_shell", shellArgs("rm -rf /tmp"), danger = false)
        val danger = approvalRuleKey("container_shell", "container_shell", shellArgs("rm -rf /tmp"), danger = true)
        assertNotEquals(normal, danger)
        assertEquals("container_shell:rm -rf /tmp#danger", danger)
    }

    @Test
    fun `non-shell tool keys on the full arguments`() {
        val args = buildJsonObject { put("id", JsonPrimitive("demo")) }
        assertEquals(
            "install_plugin:{\"id\":\"demo\"}",
            approvalRuleKey("install_plugin", "install_plugin", args, danger = false),
        )
    }
}
