package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Role
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 系统提示段的刷新时机：`SettingsStore.refreshPromptEveryTurn`。
 *
 * 段落缓存的命中条件只有「贡献点实例 / cwd / workspace / addendum / 工具列表」，
 * 看不到贡献点依赖的外部状态（记忆插件读 `.kair/memory.md` 就是这种情况）。因此
 * 必须在请求装配前主动清缓存，否则外部状态的变化要等到压缩上下文才被看到。
 *
 * 这里守两件事：
 * 1. **纯聊天回合也要刷新** —— 用户没让模型调工具，后台子智能体照样可能在写记忆文件；
 *    旧实现只在「工具执行后」清，纯聊天时永远读不到新内容。
 * 2. **外部状态没变时，请求的系统提示逐字不变** —— 重算不等于重建：贡献点契约要求
 *    确定性，字节相同才不破坏缓存前缀（这是重算策略敢开的关键前提）。
 */
class PromptRefreshTest {

    /** 读文件当正文的贡献点：模拟记忆插件（正文完全由外部状态决定）。 */
    private class FileBackedContributor(private val file: File) : SystemPromptContributor {
        override val id = "test.prompt.file-backed"
        override val order = 25
        override fun section(ctx: SystemPromptContext): String? =
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 每次调用都正常收尾、不产生工具调用的传输层：模拟纯聊天。 */
    private class PlainChatTransport : ProviderTransport {
        // id 逐实例唯一：固定字面量会让上一条用例漏下的续跑按 id 借用本实例
        // （AgentSessionSystemNoteTest 的 RecordingTransport 注释记载过同一干扰类）。
        override val id = "test-prompt-refresh-transport-${nextOrdinal.incrementAndGet()}"
        val requests = mutableListOf<ChatRequest>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            requests += request
            emit(StreamEvent.TextDelta("ok"))
            emit(StreamEvent.Completed("stop"))
        }

        companion object {
            private val nextOrdinal = java.util.concurrent.atomic.AtomicInteger()
        }
    }

    private lateinit var dir: File
    private lateinit var transport: PlainChatTransport

    private fun newSession(refresh: Boolean, memoryFile: File): AgentSession {
        transport = PlainChatTransport()
        ProviderTransports.register(transport)
        val contributors = listOf(FileBackedContributor(memoryFile))
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-prompt-refresh",
            workspace = dir,
            tools = ToolRegistry(),
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(
                        model = "test-model",
                        systemPrompt = SystemPromptBuilder.build(
                            SystemPromptContext(cwd = dir.path, tools = emptyList(), addendum = "", workspace = dir.path),
                            contributors,
                        ),
                    )),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                )
            },
            refreshPromptEveryTurn = { refresh },
        )
    }

    private fun prepare(): Pair<File, File> {
        dir = Files.createTempDirectory("kair-prompt-refresh").toFile()
        val memory = File(dir, "memory.md")
        memory.writeText("# 记忆\n- 版本 v1")
        return dir to memory
    }

    /** 发一条纯聊天消息，等它的请求落地**且回合收尾**，返回这条 send 产出的那个请求。 */
    private suspend fun sendAndCapture(session: AgentSession, text: String): ChatRequest {
        val before = transport.requests.size
        session.send(text)
        withTimeout(10_000) {
            while (transport.requests.size <= before) delay(10)
        }
        // 等回合收尾再返回：不给下一条 send 留「上一回合还没结束」的交叠窗口
        // （计时红取证见 docs/handoff/2026-11-06-计时红两条取证.md）。
        withTimeout(10_000) {
            while (session.isBusy) delay(10)
        }
        val after = transport.requests.size
        if (after != before + 1) {
            org.junit.Assert.fail(
                "sendAndCapture(\"$text\"): before=$before after=$after —— 本 send 之外混进了多余请求，" +
                    "按 index 取到的可能不是本 send 的请求（B 审 P3：不许静默放行）",
            )
        }
        return transport.requests[before]
    }

    @After
    fun tearDown() {
        ProviderTransports.unregister(transport.id)
        // 这里**不再**清段落缓存（U19 第 ② 笔的收口判据）：缓存按工作区分作用域，
        // 每个用例用自己的临时目录当作用域，泄漏路径本就断了。留着一次全表清会把
        // 「测试之间靠全局兜底」这件事藏起来 —— 当初 U18 的 M4 变异就是被这条兜底连带红的。
    }

    @Test
    fun `pure chat turn picks up external state when refresh is on`() = runBlocking {
        val (_, memory) = prepare()
        val session = newSession(refresh = true, memoryFile = memory)
        try {
            val first = sendAndCapture(session, "hi")
            assertTrue("首回合应读到文件原内容", first.systemPrompt.contains("版本 v1"))

            // 纯聊天：模型没调任何工具，但外部状态被别的途径改了（后台子智能体写记忆）。
            memory.writeText("# 记忆\n- 版本 v2")

            val second = sendAndCapture(session, "再说一句")
            assertTrue(
                "开启后纯聊天回合也应重新读取外部状态，实际系统提示：${second.systemPrompt}",
                second.systemPrompt.contains("版本 v2"),
            )
        } finally {
            session.abort()
        }
    }

    @Test
    fun `pure chat turn keeps stale state when refresh is off`() = runBlocking {
        // 关掉开关后行为回到「只靠压缩/手动保存刷新」——这条不是推荐配置，而是把开关
        // 的真实语义钉住：关掉就等于接受读到旧值，免得以后有人以为它还能自动更新。
        val (_, memory) = prepare()
        val session = newSession(refresh = false, memoryFile = memory)
        try {
            val first = sendAndCapture(session, "hi")
            assertTrue(first.systemPrompt.contains("版本 v1"))

            memory.writeText("# 记忆\n- 版本 v2")

            val second = sendAndCapture(session, "再说一句")
            assertTrue(
                "关闭后纯聊天不应重算，仍应是旧值，实际：${second.systemPrompt}",
                second.systemPrompt.contains("版本 v1"),
            )
        } finally {
            session.abort()
        }
    }

    @Test
    fun `unchanged external state keeps the system prompt byte-identical`() = runBlocking {
        // 前缀契约的支点：每回合重算必须是**纯函数重算**，输出逐字一致。否则「每回合刷新」
        // 会变成「每回合破坏缓存前缀」。
        val (_, memory) = prepare()
        val session = newSession(refresh = true, memoryFile = memory)
        try {
            val first = sendAndCapture(session, "hi")
            val second = sendAndCapture(session, "再说一句")

            assertEquals(
                "外部状态未变时，两回合的系统提示必须逐字相同",
                first.systemPrompt,
                second.systemPrompt,
            )
            // 反向校验：改了外部状态之后必须真的不同，否则上一条断言是空转。
            memory.writeText("# 记忆\n- 版本 v2")
            val third = sendAndCapture(session, "第三句")
            assertNotEquals(
                "外部状态变化后系统提示必须随之变化（否则前面的相等断言无意义）",
                second.systemPrompt,
                third.systemPrompt,
            )
        } finally {
            session.abort()
        }
    }

    @Test
    fun `refreshed prompt reaches the model, not just the settings snapshot`() = runBlocking {
        // 请求侧的证据：刷新要体现在真正发出去的 ChatRequest 里，而不是只改了内存快照。
        val (_, memory) = prepare()
        val session = newSession(refresh = true, memoryFile = memory)
        try {
            sendAndCapture(session, "hi")
            memory.writeText("# 记忆\n- 版本 v2")
            val second = sendAndCapture(session, "再说一句")
            assertEquals(2, transport.requests.size)
            assertTrue(second.systemPrompt.contains("版本 v2"))
            // 两回合的用户消息都在，说明这是同一条会话的连续请求（不是新建会话导致的差异）。
            // 日期提示是 system note，不参与「用户回合」计数。
            assertEquals(
                "两回合的用户消息都在（计时红取证快照）：requests=${transport.requests.size} busy=${session.isBusy} " +
                    "captured=" + second.messages.joinToString { "${it.role}/${it.isSystemNote}" },
                2,
                second.messages.count { it.role == Role.USER && !it.isSystemNote },
            )
        } finally {
            session.abort()
        }
    }
}
