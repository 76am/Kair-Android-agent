package dev.kair.app.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 插件运行时能力：事件订阅 / 生命周期 / 作用域 / 私有目录。
 *
 * 这四件事是做「自动记忆」「定时任务」类插件的前置条件，缺一个就会有真实事故：
 * - 没有事件 → 插件只能用 `onContext` 数回合，那是装配路径上的副作用（违反钩子契约）；
 * - 没有 `onDeactivate` + `scope` → **卸载后定时器还在跑**，往已删除的会话里发消息；
 * - 没有 `dataDir` → 插件把"上次处理到第几回合"写进用户设置面板，用户一脸问号；
 * - 没有异常隔离 → 插件一处 NPE 把整个会话主循环带崩。
 */
class PluginRuntimeTest {

    private class TestPlugin(
        override val id: String = "test.runtime",
        val onActivate: (PluginContext) -> Unit = {},
    ) : Plugin {
        override val name = "test"
        override val description = "test"
        override fun activate(ctx: PluginContext) = onActivate(ctx)
    }

    private fun event(sessionId: String = "s1") = AgentEvent.TurnEnd(
        sessionId = sessionId,
        workspace = "/ws",
        turnCount = 1,
        stopReason = "stop",
        tools = emptyList(),
    )

    @Test
    fun `events reach subscribed handlers and stop after uninstall`() {
        val seen = mutableListOf<AgentEvent>()
        val registry = PluginRegistry()
        registry.install(TestPlugin { ctx -> ctx.onAgentEvent { seen.add(it) } })

        registry.emitEvent(event())
        assertEquals(1, seen.size)

        // 卸载后不得再收到：否则插件会继续写已删除的数据（"定时器停不下来"的同类问题）。
        registry.uninstall("test.runtime")
        registry.emitEvent(event())
        assertEquals(1, seen.size)
    }

    @Test
    fun `a throwing handler does not break the loop or other handlers`() {
        val good = AtomicInteger()
        val registry = PluginRegistry()
        registry.install(TestPlugin("bad") { ctx -> ctx.onAgentEvent { error("boom") } })
        registry.install(TestPlugin("good") { ctx -> ctx.onAgentEvent { good.incrementAndGet() } })

        // 不抛异常是硬要求：事件在会话主循环里广播，插件炸掉不能把会话带下水。
        registry.emitEvent(event())
        assertEquals(1, good.get())
    }

    @Test
    fun `onDeactivate runs once on uninstall and scope is cancelled`() = runBlocking {
        val deactivated = AtomicInteger()
        val started = AtomicBoolean(false)
        var capturedScope: CoroutineScope? = null
        val registry = PluginRegistry()
        registry.install(
            TestPlugin { ctx ->
                capturedScope = ctx.scope
                ctx.onDeactivate { deactivated.incrementAndGet() }
                ctx.scope.launch {
                    started.set(true)
                    while (isActive) delay(10)
                }
            },
        )

        // 卸载前作用域是活的。
        delay(100)
        assertTrue("卸载前后台任务应能跑", started.get())
        assertTrue(capturedScope!!.isActive)

        registry.uninstall("test.runtime")
        assertEquals("卸载回调应恰好跑一次", 1, deactivated.get())
        delay(50)
        assertFalse("卸载后作用域必须已取消", capturedScope!!.isActive)
    }

    @Test
    fun `dataDir is null without a root and created when one is configured`() {
        val registry = PluginRegistry()
        var dir: File? = null
        registry.install(TestPlugin { ctx -> dir = ctx.dataDir })
        assertNull("宿主没配数据根时必须是 null，插件据此降级", dir)

        val root = Files.createTempDirectory("kair-plugin-data").toFile()
        val registry2 = PluginRegistry()
        registry2.setPluginDataRoot(root)
        var dir2: File? = null
        registry2.install(TestPlugin { ctx -> dir2 = ctx.dataDir })
        assertNotNull(dir2)
        assertTrue("目录应已创建", dir2!!.isDirectory)
        assertTrue("按插件 id 隔离", dir2!!.path.endsWith("test.runtime"))
    }

    @Test
    fun `agents is null unless the host injects a bridge`() {
        val registry = PluginRegistry()
        var bridge: AgentBridge? = null
        registry.install(TestPlugin { ctx -> bridge = ctx.agents })
        assertNull("未注入时插件必须能拿到 null 并自行降级", bridge)

        val fake = object : AgentBridge {
            override fun sessions(): List<SessionRef> = emptyList()
            override suspend fun send(sessionId: String, text: String) = true
            override suspend fun runSubagent(
                prompt: String,
                type: String,
                timeoutMillis: Long?,
                modelId: String?,
            ) = "ok"
            override suspend fun turnsSince(sessionId: String, afterTurn: Int, maxChars: Int) =
                emptyList<TurnDigest>()
        }
        val registry2 = PluginRegistry()
        registry2.setAgentBridge(fake)
        var bridge2: AgentBridge? = null
        registry2.install(TestPlugin { ctx -> bridge2 = ctx.agents })
        assertNotNull(bridge2)
    }

    @Test
    fun `self triggered turns can be recognised through originPluginId`() {
        // 防环路的插件侧依据：注入消息触发的回合带来源 id，插件可跳过自己。
        val seen = mutableListOf<AgentEvent>()
        val registry = PluginRegistry()
        registry.install(TestPlugin("timer") { ctx -> ctx.onAgentEvent { seen.add(it) } })
        registry.emitEvent(
            AgentEvent.TurnEnd("s1", "/ws", 2, "stop", emptyList(), originPluginId = "timer"),
        )
        val e = seen.single() as AgentEvent.TurnEnd
        assertEquals("timer", e.originPluginId)
    }
}
