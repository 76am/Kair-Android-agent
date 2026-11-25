package dev.kair.app.core.subagent

import dev.kair.app.core.agent.AgentAssembly
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.AgentSettings
import dev.kair.app.core.agent.Diagnostics
import dev.kair.app.core.agent.inputsOf
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * U45：子智能体任务的生命周期由**任务条目**持有，`SubagentInfo.status` 只是展示投影。
 *
 * 本类只测「启动收口」与「回收判据」两处看得见的行为，不测内部结构：
 * - 启动阶段（工作区解析 / `createSession`）抛错不得把异常漏给调用方，也不得留下
 *   一条永远清不掉的 `working` 记录；
 * - 回收只针对**确实结束**的条目：被请求终止但本轮仍在收尾的，不许提前从面板抹掉。
 *
 * 既有 `SubagentTest` 的三条时序用例保留不动（它们测的是「记录先于会话登记」）。
 */
class SubagentLifecycleTest {

    // ---- V1 / V2：启动收口 + 失败记录可回收 ------------------------------------

    @Test
    fun `createSession failure is reported as an error instead of escaping`() = runBlocking {
        val manager = SubagentManager(
            createSession = { _, _, _, _, _ -> error("构造失败") },
            workspaceOf = { workspace() },
        )

        val result = runCatching { manager.spawnAndWait("parent", "p", "n", null) }
            .getOrElse { "THREW ${it.javaClass.simpleName}: ${it.message}" }

        assertTrue(
            "启动阶段抛错必须以失败文本返回（把异常漏给调用方会让父回合直接崩），实际：$result",
            result.startsWith(SubagentManager.ERROR_PREFIX),
        )
        assertEquals("面板上要能看见这条失败", "error", manager.agents.value.single().status)
    }

    @Test
    fun `failed launches do not grow the record list without bound`() = runBlocking {
        val manager = SubagentManager(
            createSession = { _, _, _, _, _ -> error("构造失败") },
            workspaceOf = { workspace() },
        )

        repeat(SubagentManager.MAX_HISTORY + 10) {
            runCatching { manager.spawnAndWait("parent", "p", "n", null) }
        }

        assertTrue(
            "失败记录必须能被回收：一条都没有会话实例，按会话表计数就永远算不出超额。" +
                "实际残留 ${manager.agents.value.size} 条",
            manager.agents.value.size <= SubagentManager.MAX_HISTORY + 1,
        )
    }

    // ---- V3：收尾中的条目不得被回收 --------------------------------------------

    /**
     * 「已请求终止」不等于「已结束」。
     *
     * 旧判据按展示状态挑 `status != "working"` 的记录回收，而 `kill` 会立刻把状态写成 `error`
     * —— 于是**本轮仍在收尾**（不可取消的工具调用可能还在跑）的任务会被当成已完成丢掉。
     * 这里复现那个窗口：外层任务在 `createSession` 里被 kill，回收随即发生，外层必须还在。
     */
    @Test
    fun `a task whose turn is still winding down is not pruned`() = runBlocking {
        lateinit var manager: SubagentManager
        var nesting = false
        var outerAlive = false

        manager = SubagentManager(
            createSession = { id, ws, _, _, _ ->
                if (!nesting) {
                    nesting = true
                    // 先把记录表填到上限之上：每次内层都登记一个会话（回合失败，但实例留在表里）。
                    repeat(SubagentManager.MAX_HISTORY + 3) {
                        runCatching { runBlocking { manager.spawnAndWait("parent", "p", "filler", null) } }
                    }
                    // 外层被请求终止，但它这轮还没结束 —— 收尾未完成。
                    manager.kill(id)
                    // 再触发一次回收：新判据必须跳过它。
                    runCatching { runBlocking { manager.spawnAndWait("parent", "p", "trigger", null) } }
                    outerAlive = manager.agents.value.any { it.id == id }
                    nesting = false
                }
                session(id, ws)
            },
            workspaceOf = { workspace() },
        )

        manager.spawnAndWait("parent", "p", "outer", null)

        assertTrue(
            "被请求终止但仍在收尾的任务不得被回收 —— 旧判据把 kill 写的 error 当成「已完成」",
            outerAlive,
        )
    }

    private fun workspace(): File = Files.createTempDirectory("kair-subagent").toFile()

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
