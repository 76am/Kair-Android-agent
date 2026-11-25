package dev.kair.app.app

import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.storage.LocalStorage
import dev.kair.app.core.subagent.SubagentManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * U31 第一刀的探针：A 段搬进 [PluginRuntimeBridge] 后，有四处语义不能靠「搬家」本身保证 ——
 * 后台注入的**采用分叉两个方向**（有活动会话不许切走 / 没有活动会话必须起容器）、
 * **每分钟 6 条**的宿主侧限流，以及**过窗口后额度要放开**。
 *
 * 会话实例不参与判定：`sessionFor` / `openSession` 用 [ForkHit] 截断，
 * 只要 `send` 走到打开会话那一步就证明分叉方向；限流则在解析会话**之前**返回。
 */
class PluginRuntimeBridgeTest {

    private val now = 1_000L

    @Test
    fun `已有活动会话时后台注入不得切走当前会话`() = runBlocking {
        val storage = LocalStorage(Files.createTempDirectory("kair-bridge").toFile())
        val project = storage.createProject("p")
        val meta = storage.createSession(project.id, "target", "m")
        var opened = 0
        var forked = 0
        val bridge = PluginRuntimeBridge(
            clock = { now },
            storage = { storage },
            diagnostics = Diagnostics(),
            sessions = emptyRegistry(),
            subagents = subagents(),
            activeSessionId = { "current" },
            openSession = { _, _ -> opened++; throw ForkHit() },
            sessionFor = { _, _ -> forked++; throw ForkHit() },
        )

        val err = runCatching { bridge.send(meta.id, "后台注入") }.exceptionOrNull()

        assertTrue("注入应在打开会话处被探针截断，实测：$err", err is ForkHit)
        assertEquals("已有活动会话时不得走 openSession（那是把用户正看的会话切走）", 0, opened)
        assertEquals("已有活动会话时应走 sessionFor（只建实例、不切换）", 1, forked)
    }

    @Test
    fun `同一会话每分钟第 7 条注入被拒且记一次 TEMPORARY`() = runBlocking {
        val storage = LocalStorage(Files.createTempDirectory("kair-bridge").toFile())
        val project = storage.createProject("p")
        val meta = storage.createSession(project.id, "target", "m")
        val diagnostics = Diagnostics()
        var forked = 0
        val bridge = PluginRuntimeBridge(
            clock = { now },
            storage = { storage },
            diagnostics = diagnostics,
            sessions = emptyRegistry(),
            subagents = subagents(),
            activeSessionId = { "current" },
            openSession = { _, _ -> throw ForkHit() },
            sessionFor = { _, _ -> forked++; throw ForkHit() },
        )

        repeat(6) { index ->
            val err = runCatching { bridge.send(meta.id, "第 ${index + 1} 条") }.exceptionOrNull()
            assertTrue("前 6 条应过限流、在打开会话处被截断，第 ${index + 1} 条实测：$err", err is ForkHit)
        }
        assertFalse("第 7 条必须被限流拦下", bridge.send(meta.id, "第 7 条"))

        val failures = diagnostics.deliveryFailures.value
        assertEquals("限流拒绝记一次 TEMPORARY", 1, failures.temporary)
        assertEquals("限流拒绝不是永久失败", 0, failures.permanent)
        assertEquals("第 7 条不应再走到会话解析", 6, forked)
    }

    /**
     * 终审补的第一条反向探针。
     *
     * 原两条只钉住「有活动会话时不许切换」（走 `sessionFor`）这一侧；把分叉写成
     * **恒走 `sessionFor`** 时那两条照样绿 —— 而那是另一半语义：宿主还没有任何活动会话时
     * 必须真的 `openSession`，否则容器 / stdio MCP 不会按目标工作区起来（见
     * `PluginBridgeSupport.mayAdoptActiveSession` 的 KDoc）。
     */
    @Test
    fun `没有活动会话时后台注入应走 openSession 把容器带起来`() = runBlocking {
        val storage = LocalStorage(Files.createTempDirectory("kair-bridge").toFile())
        val project = storage.createProject("p")
        val meta = storage.createSession(project.id, "target", "m")
        var opened = 0
        var forked = 0
        val bridge = PluginRuntimeBridge(
            clock = { now },
            storage = { storage },
            diagnostics = Diagnostics(),
            sessions = emptyRegistry(),
            subagents = subagents(),
            activeSessionId = { null },
            openSession = { _, _ -> opened++; throw ForkHit() },
            sessionFor = { _, _ -> forked++; throw ForkHit() },
        )

        val err = runCatching { bridge.send(meta.id, "后台注入") }.exceptionOrNull()

        assertTrue("注入应在打开会话处被探针截断，实测：$err", err is ForkHit)
        assertEquals("没有活动会话时必须走 openSession（容器 / stdio 要按这个工作区起来）", 1, opened)
        assertEquals("没有活动会话时不该只建实例", 0, forked)
    }

    /**
     * 终审补的第二条：限流窗口的**放开**侧。
     *
     * 原两条用的是固定时钟，只钉住「第 7 条被拒」；`stamps.removeAll { now - it > 60_000 }`
     * 那一行删掉后，额度就永久卡死 —— 插件的定时注入在第一次撞满之后再也不会有内容。
     * 这条用例把时钟推进过窗口，专门守那一行。
     */
    @Test
    fun `过了一分钟窗口后同一会话的注入额度重新放开`() = runBlocking {
        val storage = LocalStorage(Files.createTempDirectory("kair-bridge").toFile())
        val project = storage.createProject("p")
        val meta = storage.createSession(project.id, "target", "m")
        val diagnostics = Diagnostics()
        var tick = now
        var forked = 0
        val bridge = PluginRuntimeBridge(
            clock = { tick },
            storage = { storage },
            diagnostics = diagnostics,
            sessions = emptyRegistry(),
            subagents = subagents(),
            activeSessionId = { "current" },
            openSession = { _, _ -> throw ForkHit() },
            sessionFor = { _, _ -> forked++; throw ForkHit() },
        )

        repeat(6) { index ->
            val err = runCatching { bridge.send(meta.id, "第 ${index + 1} 条") }.exceptionOrNull()
            assertTrue("前 6 条应过限流，第 ${index + 1} 条实测：$err", err is ForkHit)
        }
        assertFalse("第 7 条必须被限流拦下", bridge.send(meta.id, "第 7 条"))
        assertEquals("撞上限只记一次临时失败", 1, diagnostics.deliveryFailures.value.temporary)

        tick += 60_001L
        val after = runCatching { bridge.send(meta.id, "过窗口后的第一条") }.exceptionOrNull()
        assertTrue("过窗口后应重新放开（不放开就是 removeAll 那行没生效）", after is ForkHit)
        assertEquals("放开后应走到会话解析", 7, forked)
    }

    private fun emptyRegistry(): SessionRegistry<AgentSession> = SessionRegistry(
        resources = object : SessionResources {
            override fun releaseApprovalRules(id: String) = Unit
            override fun releasePendingQuestions(id: String) = Unit
            override fun abortSubagents(id: String) = Unit
            override fun releaseBackgroundTasks(id: String) = Unit
            override fun cancelTrackJob(id: String) = Unit
            override fun removeRunningMark(id: String) = Unit
            override fun releaseCacheRecords(id: String) = Unit
            override fun releaseDiagnostics(id: String) = Unit
            override fun releaseRequestRecords(id: String) = Unit
            override fun releaseSendWindow(id: String) = Unit
        },
        describe = { error("探针不需要描述会话") },
        discardAction = { error("探针不需要关闭会话") },
    )

    private fun subagents(): SubagentManager = SubagentManager(
        createSession = { _, _, _, _, _ -> error("探针不建子智能体会话") },
        workspaceOf = { null },
    )

    private class ForkHit : RuntimeException()
}
