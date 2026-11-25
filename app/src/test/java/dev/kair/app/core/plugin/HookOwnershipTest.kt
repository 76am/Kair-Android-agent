package dev.kair.app.core.plugin

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U40 判据：**钩子归插件所有，卸载必须把它的钩子一起收走**。
 *
 * 现状（改动前）是五种钩子只往 `HookBus` 的链上追加、不记 owner、也没有移除入口：
 * 卸载只回收贡献点，钩子留着继续执行；重装同一个插件就叠加两份。审批插件靠
 * `tool_call` 钩子弹窗，残留的钩子意味着「停了还在弹」。
 */
class HookOwnershipTest {

    @Test
    fun `uninstall removes the plugin's input hook`() {
        val registry = PluginRegistry()
        registry.install(ProbePlugin("dev.probe.one") { ctx -> ctx.onInput { "$it!" } })
        assertEquals("装载后钩子应生效", "x!", registry.hooks.runInput("x"))

        registry.uninstall("dev.probe.one")

        assertEquals("卸载后钩子必须消失", "x", registry.hooks.runInput("x"))
        assertEquals("卸载后不应留下任何钩子", 0, registry.hooks.size)
    }

    @Test
    fun `reinstalling the same plugin keeps exactly one copy`() {
        val registry = PluginRegistry()
        registry.install(ProbePlugin("dev.probe.one") { ctx -> ctx.onInput { "$it!" } })
        registry.uninstall("dev.probe.one")
        registry.install(ProbePlugin("dev.probe.one") { ctx -> ctx.onInput { "$it!" } })

        assertEquals("卸载-重装后只剩一份钩子（当前实现会叠加成两份）", "x!", registry.hooks.runInput("x"))
    }

    @Test
    fun `uninstalling one plugin keeps the other's hook and its order`() {
        val registry = PluginRegistry()
        registry.install(ProbePlugin("dev.probe.a") { ctx -> ctx.onInput { "${it}a" } })
        registry.install(ProbePlugin("dev.probe.b") { ctx -> ctx.onInput { "${it}b" } })
        assertEquals("xa b", "xab", registry.hooks.runInput("x"))

        registry.uninstall("dev.probe.b")

        assertEquals("只收走 B 的钩子，A 仍按原顺序生效", "xa", registry.hooks.runInput("x"))
    }

    @Test
    fun `uninstall also removes tool call hooks`() {
        val registry = PluginRegistry()
        // 与 ApprovalPlugin 同形：tool_call 钩子自己不做「插件是否启用」判断。
        registry.install(ProbePlugin("dev.probe.approval") { ctx ->
            ctx.onToolCall { outcome ->
                ToolCallOutcome(outcome.arguments, blockReason = "需要用户确认")
            }
        })
        val blocked = runBlockingCall(registry, "bash")
        assertEquals("装载后 tool_call 钩子应生效", "需要用户确认", blocked.blockReason)

        registry.uninstall("dev.probe.approval")

        assertNull(
            "卸载后 tool_call 钩子必须消失（否则审批弹窗会在插件禁用后继续弹）",
            runBlockingCall(registry, "bash").blockReason,
        )
    }

    @Test
    fun `a plugin whose activate fails leaves no hook behind`() {
        val registry = PluginRegistry()
        val broken = ProbePlugin("dev.probe.broken", fail = true) { ctx ->
            ctx.onInput { "$it!" }
            ctx.onToolCall { outcome -> ToolCallOutcome(outcome.arguments, blockReason = "残留") }
        }

        try {
            registry.install(broken)
            throw AssertionError("activate 抛错时 install 必须把异常交给调用方")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("activate 失败（探针）"))
        }

        assertEquals("失败的插件必须被回滚，钩子不能留", "x", registry.hooks.runInput("x"))
        assertNull("失败的插件留下的 tool_call 钩子同样要清掉", runBlockingCall(registry, "bash").blockReason)
        assertEquals("失败的插件不应留在注册表", 0, registry.installed.size)
    }

    private fun runBlockingCall(registry: PluginRegistry, toolName: String): ToolCallOutcome =
        kotlinx.coroutines.runBlocking {
            registry.hooks.runToolCall(toolName, buildJsonObject { put("command", "true") })
        }

    private class ProbePlugin(
        override val id: String,
        private val fail: Boolean = false,
        private val register: (PluginContext) -> Unit,
    ) : Plugin {
        override val name: String = "探针 $id"
        override val description: String = "hook ownership probe"

        override fun activate(ctx: PluginContext) {
            register(ctx)
            if (fail) error("activate 失败（探针）")
        }
    }
}
