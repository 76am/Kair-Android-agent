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
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 「今天几号」system note 的回归：模型没有日期来源，会以为现在还是训练数据的时间。
 *
 * 判据全部用**注入时钟**（不许在断言里出现 `LocalDate.now()`，跨零点必 flaky —— U14 同类坑）：
 * 同一天第二个回合不重复追加 / 跨天后追加一条 / 提示被删（模拟压缩吞掉）后当天补回来。
 */
class AgentSessionDateNoteTest {

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo"
        override val parameters = schemaOf(props("text" to strProp("text")), "text")
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
    }

    /** 每回合只吐一段文本再收尾，不发起工具调用 —— 便于观察历史里的提示条。 */
    private class PlainTransport : ProviderTransport {
        override val id = "test-date-note-transport"
        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> =
            listOf(StreamEvent.TextDelta("done"), StreamEvent.Completed("stop")).asFlow()
    }

    private class Clock(var today: LocalDate)

    private fun newSession(transport: ProviderTransport, clock: Clock): AgentSession {
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
                    tools = registry.all(),
                )
            },
            todayProvider = { clock.today },
        )
    }

    private suspend fun awaitIdle(session: AgentSession) {
        withTimeout(10_000) {
            while (session.messages.value.isEmpty()) delay(20)
            while (session.isBusy) delay(20)
        }
    }

    private fun AgentSession.dateNotes() = messages.value.filter { it.isSystemNote && it.role == Role.USER }

    @Test
    fun sameDayDoesNotAppendTwice() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val clock = Clock(LocalDate.of(2026, 10, 29))
        val session = newSession(transport, clock)
        try {
            session.send("first")
            awaitIdle(session)
            session.send("second")
            awaitIdle(session)

            val notes = session.dateNotes()
            assertEquals(1, notes.size)
            assertEquals("2026-10-29 (Thu)", notes.single().text)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun nextDayAppendsANewNote() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val clock = Clock(LocalDate.of(2026, 10, 29))
        val session = newSession(transport, clock)
        try {
            session.send("first")
            awaitIdle(session)
            // 跨天：同一条历史里出现两条日期提示（旧的不删，只追加）。
            clock.today = LocalDate.of(2026, 10, 30)
            session.send("next day")
            awaitIdle(session)

            assertEquals(
                listOf("2026-10-29 (Thu)", "2026-10-30 (Fri)"),
                session.dateNotes().map { it.text },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun noteIsRestoredWhenCompactionDropsIt() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val clock = Clock(LocalDate.of(2026, 10, 29))
        val session = newSession(transport, clock)
        try {
            session.send("first")
            awaitIdle(session)
            val note = session.dateNotes().single()

            // 模拟压缩把这条提示吞掉：判据看「历史里有没有」，所以当天下一回合要补回来。
            session.deleteMessage(note.id)
            withTimeout(10_000) { while (session.messages.value.any { it.id == note.id }) delay(20) }

            session.send("after compaction")
            awaitIdle(session)

            val restored = session.dateNotes()
            assertEquals(1, restored.size)
            assertEquals("2026-10-29 (Thu)", restored.single().text)
            assertTrue("补回的是新消息，不是同一条被复活", restored.single().id != note.id)
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun dateNoteTextIsIsoWithShortEnglishWeekday() {
        assertEquals("2026-10-29 (Thu)", dateNoteText(LocalDate.of(2026, 10, 29)))
        // 2026-11-01 是周日；星期名不能错位一天（ISO dayOfWeek 1=周一…7=周日）。
        assertEquals("2026-11-01 (Sun)", dateNoteText(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun dateNoteDisplayTextLocalizesForUi() {
        assertEquals("2026-10-29（周四）", dateNoteDisplayText("2026-10-29 (Thu)"))
        // 旧会话里的中文格式原样显示，不做迁移改写。
        assertEquals("2026-10-29（周四）", dateNoteDisplayText("2026-10-29（周四）"))
        assertEquals("plain note", dateNoteDisplayText("plain note"))
    }

    @Test
    fun noteStartingWithTodayDoesNotSuppressTheDateNote() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val clock = Clock(LocalDate.of(2026, 10, 29))
        val session = newSession(transport, clock)
        try {
            // 插件注入的 note 恰好以今天日期开头，但不是日期提示：不能被前缀判重吞掉。
            session.send("2026-10-29 build finished", systemNote = true)
            awaitIdle(session)
            session.send("hello")
            awaitIdle(session)

            assertEquals(
                listOf("2026-10-29 build finished", "2026-10-29 (Thu)"),
                session.dateNotes().map { it.text },
            )
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }

    @Test
    fun legacyChineseNoteForTodayIsNotDuplicated() = kotlinx.coroutines.runBlocking {
        val transport = PlainTransport()
        val clock = Clock(LocalDate.of(2026, 10, 29))
        val session = newSession(transport, clock)
        try {
            // 升级前写入的旧格式日期提示：按 ISO 前缀认，今天不应再补一条英文的。
            session.send("2026-10-29（周四）", systemNote = true)
            awaitIdle(session)
            session.send("hello")
            awaitIdle(session)

            assertEquals(listOf("2026-10-29（周四）"), session.dateNotes().map { it.text })
        } finally {
            ProviderTransports.unregister(transport.id)
            session.abort()
        }
    }
}
