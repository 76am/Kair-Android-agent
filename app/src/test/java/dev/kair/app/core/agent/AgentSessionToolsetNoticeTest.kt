package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.Role
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具集变化提示的回归：旧会话的历史是在上一版本工具集下产生的，模型会锚定自身旧回合
 * （当时还没有某工具）而忽略当前已注入的清单。会话据此在历史尾部**追加**一条
 * `isSystemNote` 提示（只追加、不改写），且每次变化只提示一次（基线落盘在会话级设置）。
 *
 * 四条各判一种「变」的来源：基线缺席（老会话）、基线相符（不变）、基线不同（一次变化）、
 * **切模型**（U42 之后工具面出自本发快照，所以换模型本身就是一次变化 —— 两个面各提示一次，
 * 切回来再提示一次；漏掉这一格就会把「同一指纹提示两次 / 该提示时不提示」放过去）。
 */
class AgentSessionToolsetNoticeTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    /** 只在「视觉模型」那一面里出现的工具，用来把工具面做出差别（名字随本用例自取，不借真实工具）。 */
    private class LookTool : Tool {
        override val name = "look_image"
        override val description = "look"
        override val parameters = schemaOf(props("path" to strProp("path")), "path")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    /** 每回合只吐一段文本再收尾，不发起工具调用 —— 便于观察历史里的提示条。 */
    private class PlainTransport : ProviderTransport {
        override val id = "test-toolset-transport"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")).asFlow()
    }

    /** 基线读写：用可变字段模拟落盘的会话级工具集指纹。 */
    private class Baseline(var value: String? = null)

    private fun newSession(
        transport: ProviderTransport,
        baseline: Baseline,
        toolFace: (ToolRegistry) -> List<Tool> = ToolRegistry::all,
    ): AgentSession {
        ProviderTransports.register(transport)
        val registry = ToolRegistry().register(EchoTool())
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-session",
            workspace = File("."),
            tools = registry,
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                    tools = toolFace(registry),
                )
            },
            toolsetBaselineGet = { baseline.value },
            toolsetBaselineSet = { baseline.value = it },
        )
    }

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    /**
     * 等「至少出现过 [count] 条工具集提示」再等空闲 —— 用于必须看到提示才继续的那些回合。
     * 用 `withTimeoutOrNull` 而不是 `withTimeout`：等不到时**让下面的 assertEquals 说话**，
     * 变异取证时要看到的是一条「提示应为 1 实际 0」，而不是一句无信息的超时异常。
     */
    private suspend fun awaitNotices(session: AgentSession, count: Int) {
        withTimeoutOrNull(10_000) { while (notices(session) < count) delay(20) }
        withTimeoutOrNull(10_000) { while (session.isBusy) delay(20) }
    }

    private fun notices(session: AgentSession): Int =
        session.messages.value.count { it.text == AgentSession.TOOLSET_CHANGED_NOTICE }

    @Test
    fun appendsNoticeWhenBaselineMissingAndRecordsIt() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        // 旧会话：早于本特性创建，没有记录 → 首回合按「已变化」提示一次。
        val baseline = Baseline(null)
        val session = newSession(transport, baseline)
        try {
            session.send("go")
            awaitIdle(session)

            // 日期提示也是 system note，这里只认工具集提示那一条。
            val notice = session.messages.value.single { it.text == AgentSession.TOOLSET_CHANGED_NOTICE }
            assertEquals(Role.USER, notice.role)
            assertTrue(notice.isSystemNote)
            assertEquals(AgentSession.TOOLSET_CHANGED_NOTICE, notice.text)
            // 记录基线后不再重复提示。
            assertEquals("echo", baseline.value)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun noNoticeWhenBaselineMatchesCurrentToolset() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        // 基线等于当前工具集（仅 echo）→ 没有变化，不提示。
        val baseline = Baseline("echo")
        val session = newSession(transport, baseline)
        try {
            session.send("go")
            awaitIdle(session)

            assertTrue(session.messages.value.none { it.text == AgentSession.TOOLSET_CHANGED_NOTICE })
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun noticeAppendedOnlyOncePerChange() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        // 上次记录与当前工具集不同 → 首回合提示一次；其后基线已对齐，不再提示。
        val baseline = Baseline("echo,write")
        val session = newSession(transport, baseline)
        try {
            session.send("go")
            awaitIdle(session)
            session.send("again")
            awaitIdle(session)

            assertEquals(1, session.messages.value.count { it.text == AgentSession.TOOLSET_CHANGED_NOTICE })
            assertEquals("echo", baseline.value)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun switchingModelChangesTheToolsetFaceAndNoticesEachNewFace() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val lookTool = LookTool()
        val vision = AtomicBoolean(false)
        // 基线先对齐「只有 echo」那一面：首回合不该提示，变化只能由切模型带来（U42 后工具面出自本发快照）。
        val baseline = Baseline("echo")
        val session = newSession(transport, baseline) { registry ->
            if (vision.get()) registry.all() + lookTool else registry.all()
        }
        try {
            session.send("go")
            awaitIdle(session)
            assertEquals(0, notices(session))
            assertEquals("echo", baseline.value)

            vision.set(true)
            session.send("with vision")
            awaitNotices(session, 1)
            assertEquals(1, notices(session))
            assertEquals("echo,look_image", baseline.value)

            // 切回来是**另一次**变化，不是「提示过一次就完」；指纹也跟着回到 echo。
            vision.set(false)
            session.send("back to text")
            awaitNotices(session, 2)
            assertEquals(2, notices(session))
            assertEquals("echo", baseline.value)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }
}
