package dev.kair.app.core.subagent

import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.AgentSettings
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.agent.inputsOf
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * 子智能体的**时序**保证：记录（面板可见）先于 session 登记，用户可能在这个窗口点「终止」，
 * 若只把终止转成一次 `abort()`，那次调用在 session 还没登记时什么也找不到 —— 任务照常起跑，
 * 面板随后还显示「已完成」。
 */
class SubagentTest {

    @Test
    fun `killBeforePromptActuallyStops`() = runBlocking {
        val workspace = Files.createTempDirectory("kair-subagent").toFile()
        // createSession 是「记录已发布、session 尚未登记」这个窗口本身：
        // 在这里调 kill 复现的正是用户抢在起跑前点终止的时序。
        lateinit var manager: SubagentManager
        var created: AgentSession? = null
        manager = SubagentManager(
            createSession = { id, ws, _, _, _ ->
                manager.kill(id)
                session(id, ws).also { created = it }
            },
            workspaceOf = { workspace },
        )

        val result = manager.spawnAndWait(parentId = "parent", prompt = "do work", name = "t", timeoutMillis = null)

        assertEquals("${SubagentManager.ERROR_PREFIX} stopped by user.", result)
        val info = manager.agents.value.single()
        assertEquals("error", info.status)
        assertEquals("killed", info.error)
        // 真正的证据：回合从未启动 —— 历史为空（跑起来的话这里会有用户消息与失败回复）。
        assertTrue("被终止的子智能体不得起跑", created!!.messages.value.isEmpty())
        assertFalse("不得留在忙状态", created!!.isBusy)
    }

    @Test
    fun `spawnWithoutKillRunsTheTurn`() = runBlocking {
        // 对照组：同一套接线，没有终止时回合真的会启动 —— 否则上面那条用例可能只是
        // 在断言「会话从来就不工作」，测不到时序保证。
        val workspace = Files.createTempDirectory("kair-subagent").toFile()
        var created: AgentSession? = null
        val manager = SubagentManager(
            createSession = { id, ws, _, _, _ -> session(id, ws).also { created = it } },
            workspaceOf = { workspace },
        )

        val result = manager.spawnAndWait(parentId = "parent", prompt = "do work", name = "t", timeoutMillis = null)

        assertTrue("未注册传输层时应以失败文本返回，实际：$result", result.startsWith(SubagentManager.ERROR_PREFIX))
        assertTrue("回合应真的启动过", created!!.messages.value.isNotEmpty())
    }

    /**
     * `AGENTS.md` §10.4：解析不到「发起委派的会话」，就**不许**回退到「最近活跃工作区」。
     *
     * 回退的后果是把子智能体的读写落进别人的项目里（它会直接写文件，错了不可恢复）。
     * 这里 `workspaceOf` 返回 null，等价于宿主在注册表里查不到那个会话（已关闭 / 已淘汰）。
     */
    @Test
    fun `unknown parent fails instead of falling back to a global workspace`() = runBlocking {
        var created = false
        val manager = SubagentManager(
            createSession = { _, _, _, _, _ ->
                created = true
                error("解析不到工作区时不得创建会话")
            },
            workspaceOf = { null },
        )

        val result = manager.spawnAndWait(
            parentId = "closed-session",
            prompt = "do work",
            name = "t",
            timeoutMillis = null,
        )

        assertTrue("必须以失败文本返回，实际：$result", result.startsWith(SubagentManager.ERROR_PREFIX))
        assertFalse("没有工作区就不得起跑（否则会写进别人的项目）", created)
        assertEquals("error", manager.agents.value.single().status)
    }

    /**
     * 浏览器只有一个 WebView：两个会话同时派 `browser` 子智能体会互相点对方页面。
     * 这里用短排队上限验证「第二个明确失败」与「第一个结束后锁真的放掉」。
     */
    @Test
    fun `browser subagents are serialized across parent sessions`() = runBlocking {
        val workspace = Files.createTempDirectory("kair-subagent").toFile()
        // createSession 不是 suspend：用阻塞闩把第一个任务停在「已持有锁」的阶段。
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = SubagentManager(
            createSession = { id, ws, type, _, _ ->
                if (type == SubagentType.BROWSER) {
                    entered.countDown()
                    release.await()
                }
                session(id, ws)
            },
            workspaceOf = { workspace },
            browserLockWaitMillis = 100,
        )

        val first = async(Dispatchers.Default) {
            manager.spawnAndWait("parent-a", "browse", "first", null, SubagentType.BROWSER)
        }
        entered.await()

        val second = manager.spawnAndWait("parent-b", "browse", "second", null, SubagentType.BROWSER)
        assertTrue("第二个 browser 子智能体必须明确失败，实际：$second", second.startsWith(SubagentManager.ERROR_PREFIX))
        assertTrue("失败原因应是浏览器被占用，实际：$second", second.contains("busy"))

        release.countDown()
        first.await()

        val third = manager.spawnAndWait("parent-c", "browse", "third", null, SubagentType.BROWSER)
        assertFalse("上一个结束后必须能重新拿到浏览器，实际：$third", third.contains("busy"))
    }

    /** 最小可跑会话：没有注册传输层，`prompt` 必然以失败收尾，足够区分「跑过 / 没跑」。 */
    private fun session(id: String, workspace: File) = AgentSession(
        diagnostics = Diagnostics(),
        id = id,
        workspace = workspace,
        tools = ToolRegistry(),
        inputsProvider = {
            inputsOf(
                assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")),
                config = ProviderConfig("test-provider", "no-such-transport", "https://example.test/v1", ""),
            )
        },
    )
}
