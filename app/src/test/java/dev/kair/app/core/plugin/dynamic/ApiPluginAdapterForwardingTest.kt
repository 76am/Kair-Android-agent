package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.plugin.SessionRef
import dev.kair.app.core.plugin.TurnDigest
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import dev.kair.plugin.api.Plugin as ApiPlugin
import dev.kair.plugin.api.PluginContext as ApiPluginContext

/**
 * **转发层**的端到端：SDK 插件 → `ApiPluginAdapter` → `BridgedContext` → 宿主注册表。
 *
 * 为什么单独测这层：`PluginRuntimeTest` 直接拿宿主 `PluginContext` 测，测的是「注册表
 * 本身能用」；而第三方 dex 插件永远走 `BridgedContext`。v8 新增的五个成员都有**接口
 * 默认实现**（空函数 / 已取消的作用域 / null），所以漏转发不会编译失败、不会抛异常，
 * 只会**静默失效** —— 这正是本批被门卫抓出来的 P0。这条用例就是那个缺口的守卫。
 */
class ApiPluginAdapterForwardingTest {

    /** 一个最小的 SDK 插件：activate 时把 ctx 全部用一遍。 */
    private class FakeSdkPlugin(private val onActivate: (ApiPluginContext) -> Unit) : ApiPlugin {
        override val id = "fake.sdk.plugin"
        override val name = "fake"
        override val description = "fake"
        override fun activate(ctx: ApiPluginContext) = onActivate(ctx)
    }

    private class FakeBridge : AgentBridge {
        override fun sessions(): List<SessionRef> = emptyList()
        override suspend fun send(sessionId: String, text: String) = true
        override suspend fun runSubagent(
            prompt: String,
            type: String,
            timeoutMillis: Long?,
            modelId: String?,
        ): String = "ok"
        override suspend fun turnsSince(sessionId: String, afterTurn: Int, maxChars: Int) =
            emptyList<TurnDigest>()
    }

    @Test
    fun `sdk plugin receives agent events through the bridge layer`() {
        val received = AtomicInteger()
        val registry = PluginRegistry()
        // 走 adapter 安装（与真实 dex 插件完全同一条路径）。
        registry.install(ApiPluginAdapter(FakeSdkPlugin { ctx -> ctx.onAgentEvent { received.incrementAndGet() } }))

        registry.emitEvent(AgentEvent.TurnEnd("s1", "/ws", 1, "stop", emptyList()))

        assertEquals("BridgedContext 必须把 onAgentEvent 转给宿主，否则订阅静默失效", 1, received.get())
    }

    @Test
    fun `sdk plugin gets the host scope bridge and data dir`() {
        val registry = PluginRegistry()
        registry.setAgentBridge(FakeBridge())
        val root = Files.createTempDirectory("kair-forwarding").toFile()
        registry.setPluginDataRoot(root)

        var scope: kotlinx.coroutines.CoroutineScope? = null
        var agents: AgentBridge? = null
        var dataDir: File? = null
        var deactivated = false
        registry.install(
            ApiPluginAdapter(
                FakeSdkPlugin { ctx ->
                    scope = ctx.scope
                    agents = ctx.agents
                    dataDir = ctx.dataDir
                    ctx.onDeactivate { deactivated = true }
                },
            ),
        )

        assertNotNull("scope 必须是宿主作用域，不是 EmptyPluginScope", scope)
        assertTrue("宿主作用域应当是活的", scope!!.isActive)
        assertNotNull("agents 必须是注入的桥，不是 null", agents)
        assertNotNull("dataDir 必须按插件 id 创建", dataDir)
        assertTrue(dataDir!!.path.endsWith("fake.sdk.plugin"))

        registry.uninstall("fake.sdk.plugin")
        assertTrue("onDeactivate 必须转发到宿主", deactivated)
        assertFalse("卸载后作用域必须被取消", scope!!.isActive)
    }

    @Test
    fun `uninstall stops event delivery to sdk plugins`() {
        val received = AtomicInteger()
        val registry = PluginRegistry()
        registry.install(ApiPluginAdapter(FakeSdkPlugin { ctx -> ctx.onAgentEvent { received.incrementAndGet() } }))
        registry.emitEvent(AgentEvent.TurnEnd("s1", "/ws", 1, "stop", emptyList()))
        registry.uninstall("fake.sdk.plugin")
        registry.emitEvent(AgentEvent.TurnEnd("s1", "/ws", 2, "stop", emptyList()))
        assertEquals(1, received.get())
    }
}
