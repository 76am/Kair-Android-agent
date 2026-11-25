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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Root 授权事实的回归（U116）：设置页手动探测后，宿主把结论作为 `isSystemNote` 的 user 回合
 * **追加**到历史尾部 —— 授权状态会变，不能进系统提示前缀。
 *
 * 判据是「历史里**最后一条** root note 是否逐字等于当前结论」，所以除了「同一结论不重复写」，
 * 还必须钉住**授权收回后要补否定句**：不能停在过期的事实上。
 */
class AgentSessionRootNoteTest {

    /** 每回合只吐一段文本再收尾，不发起工具调用 —— 便于数历史里的提示条。 */
    private class PlainTransport : ProviderTransport {
        override val id = "test-root-note-transport"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")).asFlow()
    }

    private fun newSession(
        transport: ProviderTransport,
        rootGranted: () -> Boolean?,
        toolName: String = ANDROID_SHELL_TOOL_NAME,
    ): AgentSession {
        ProviderTransports.register(transport)
        val registry = ToolRegistry().register(EchoTool(toolName))
        return AgentSession(
            diagnostics = Diagnostics(),
            id = "test-session",
            workspace = File("."),
            tools = registry,
            rootGranted = rootGranted,
            inputsProvider = {
                inputsOf(
                    assembly = AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")),
                    config = ProviderConfig(
                        providerId = "test-provider",
                        transportId = transport.id,
                        baseUrl = "https://example.test/v1",
                        apiKey = "",
                    ),
                    tools = registry.all(),
                )
            },
        )
    }

    /** 名字可换的桩工具：默认占 `android_shell` 这个名字（root note 的适用性前置看的就是它）。 */
    private class EchoTool(private val toolName: String) : Tool {
        override val name = toolName
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    private fun rootNotes(session: AgentSession): List<String> =
        session.messages.value.filter {
            it.isSystemNote && it.role == Role.USER && it.text.startsWith(ROOT_NOTE_MARKER)
        }.map { it.text }

    private fun runSession(
        rootGranted: () -> Boolean?,
        toolName: String = ANDROID_SHELL_TOOL_NAME,
    ) = runBlocking {
        val transport = PlainTransport()
        val session = newSession(transport, rootGranted, toolName)
        try {
            session.send("go")
            awaitIdle(session)
            rootNotes(session)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun `granted appends one note`() {
        val notes = runSession({ true })
        assertEquals("首回合应恰好一条", 1, notes.size)
        assertEquals(rootNoteText(true), notes.single())
    }

    @Test
    fun `denied also appends a note`() {
        val notes = runSession({ false })
        assertEquals(1, notes.size)
        assertEquals(rootNoteText(false), notes.single())
    }

    @Test
    fun `not probed appends nothing`() {
        val notes = runSession({ null })
        assertTrue("没探测就不假设，也不自动弹授权", notes.isEmpty())
    }

    @Test
    fun `android shell hidden appends nothing`() {
        val notes = runSession({ true }, toolName = "container_shell")
        assertTrue("看不到 android_shell 的会话不该收到 root 事实", notes.isEmpty())
    }

    @Test
    fun `same state does not append again`() = runBlocking {
        val transport = PlainTransport()
        var granted: Boolean? = true
        val session = newSession(transport, { granted })
        try {
            session.send("go")
            awaitIdle(session)
            session.send("again")
            awaitIdle(session)
            assertEquals("同一结论不重复写", 1, rootNotes(session).size)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun `revoked appends a correction`() = runBlocking {
        val transport = PlainTransport()
        var granted: Boolean? = true
        val session = newSession(transport, { granted })
        try {
            session.send("go")
            awaitIdle(session)
            granted = false
            session.send("again")
            awaitIdle(session)
            val notes = rootNotes(session)
            assertEquals("正向 → 否定各一条", 2, notes.size)
            assertEquals(rootNoteText(false), notes.last())
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }
}
