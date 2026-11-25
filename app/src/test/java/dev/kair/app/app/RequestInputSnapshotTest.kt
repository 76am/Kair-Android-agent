package dev.kair.app.app

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.agent.AgentSession
import dev.kair.app.core.agent.CompactionPlan
import dev.kair.app.core.agent.CompactionSettings
import dev.kair.app.core.agent.CompactionTrigger
import dev.kair.app.core.agent.ContextStrategy
import dev.kair.app.core.agent.ScriptedTransport
import dev.kair.app.core.agent.ScriptedTurn
import dev.kair.app.core.agent.await
import dev.kair.app.core.agent.harness
import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.settings.SavedModel
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.core.storage.SessionMeta
import dev.kair.app.core.tools.ReadImageToolName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * U42：单发请求的输入快照 —— 系统提示工具面与请求 schema 工具面必须同源。
 *
 * 走**真实容器接线**（`AppContainer.openSession` → `SessionLifecycle.sessionFor` →
 * `SessionAssembly.agentSession`），不在测试里手摆 spec：缺陷长在「创建时固定 schema 过滤器、
 * 装配时现算提示过滤器」这条生产接线上，手摆 spec 等于把待证的形状自己抄一遍。
 *
 * 判据对着「真正发出去的字节」：[ScriptedTransport] 记录每次 [dev.kair.app.core.ai.ChatRequest]，
 * 提示侧看 `<tools>` 清单的 `- read_image:` 行（ToolsContributor 产出），schema 侧看工具名集。
 * 两个方向都钉：非视觉 → 视觉（两侧都应含）与视觉 → 非视觉（两侧都不含）；
 * 只断言 ⇔ 不断言方向的话，「两侧都过期」也能假绿。
 */
class RequestInputSnapshotTest {

    @Test
    fun `switching to a vision model updates both prompt tools and schema tools`() = runBlocking {
        val fx = fixture(startModel = TEXT_MODEL, turns = 2)
        try {
            fx.turn("first")
            // 创建时是非视觉模型：两侧都不含 read_image（这一发 today 就是一致的）。
            assertEquals("第一发 systemPrompt 不该列 read_image", false, fx.promptHasReadImage(0))
            assertEquals("第一发 schema 不该含 read_image", false, fx.schemaHasReadImage(0))

            // 输入框切到视觉模型（会话级覆盖），再发一发。
            fx.settings.setSessionModelId(fx.meta.id, VISION_MODEL.id)
            fx.turn("second")

            val inPrompt = fx.promptHasReadImage(1)
            val inSchema = fx.schemaHasReadImage(1)
            assertEquals(
                "第二发请求的 systemPrompt 与 schema 必须同源：" +
                    "prompt 含 read_image=$inPrompt，schema 含 read_image=$inSchema",
                inPrompt,
                inSchema,
            )
            assertEquals("切到视觉模型后两侧都应列出 read_image（不是「两侧都过期」的假一致）", true, inPrompt)
        } finally {
            fx.close()
        }
    }

    @Test
    fun `switching back to a text model removes read_image from both prompt tools and schema tools`() = runBlocking {
        val fx = fixture(startModel = VISION_MODEL, turns = 2)
        try {
            fx.turn("first")
            assertEquals("第一发 systemPrompt 应列 read_image", true, fx.promptHasReadImage(0))
            assertEquals("第一发 schema 应含 read_image", true, fx.schemaHasReadImage(0))

            fx.settings.setSessionModelId(fx.meta.id, TEXT_MODEL.id)
            fx.turn("second")

            val inPrompt = fx.promptHasReadImage(1)
            val inSchema = fx.schemaHasReadImage(1)
            assertEquals(
                "第二发请求的 systemPrompt 与 schema 必须同源：" +
                    "prompt 含 read_image=$inPrompt，schema 含 read_image=$inSchema",
                inPrompt,
                inSchema,
            )
            assertEquals("切回非视觉模型后两侧都不该列 read_image", false, inPrompt)
        } finally {
            fx.close()
        }
    }

    // ------------------------------------------------------------------ 夹具

    @Test
    fun `settings changed mid-flight apply to the next request not the current one`() = runBlocking {
        val tmp = Files.createTempDirectory("kair-snapshot-race").toFile()
        val settings = SettingsStore(FakeSharedPreferences())
        settings.savedModels = listOf(RACE_MODEL_A, RACE_MODEL_B)
        settings.activeModelId = RACE_MODEL_A.id
        val container = AppContainer(
            AppEnvironment(storageRoot = File(tmp, "kair"), nativeLibraryDir = "/lib", primaryAbi = "arm64-v8a"),
            settings,
        )
        val transport = GatedTransport()
        ProviderTransports.register(transport)
        val project = container.storage.createProject("snapshot-race")
        val meta = container.newSession(project)
        val session = container.openSession(project, meta)
        try {
            session.send("first")
            // 第一发已解析完毕、流挂住：此刻改设置，模拟「响应回来之前设置变了」。
            withTimeout(10_000) { transport.issued.await() }
            settings.setSessionModelId(meta.id, RACE_MODEL_B.id)
            transport.gate.complete(Unit)
            await("第一回合收尾") { !session.isBusy }

            assertEquals("用例前提：第一发已记录", 1, transport.requests.size)
            val first = transport.requests[0]
            assertEquals("本发模型必须是旧值", "race-model-a", first.model)
            assertEquals("本发采样必须是旧值", 0.1, first.options.temperature!!, 0.0)
            assertEquals("本发鉴权必须是旧值", "key-A", transport.configs[0].apiKey)
            assertEquals("本发端点必须是旧值", "https://a.example/v1", transport.configs[0].baseUrl)
            assertEquals("本发提示不该列 read_image", false, first.systemPrompt.contains("- $ReadImageToolName:"))
            assertEquals("本发 schema 不该含 read_image", false, first.tools.any { it.name == ReadImageToolName })

            session.send("second")
            await("第二回合收尾") { !session.isBusy }
            assertEquals("恰好两发请求", 2, transport.requests.size)
            val second = transport.requests[1]
            assertEquals("下一发模型必须是新值", "race-model-b", second.model)
            assertEquals("下一发采样必须是新值", 0.9, second.options.temperature!!, 0.0)
            assertEquals("下一发鉴权必须是新值", "key-B", transport.configs[1].apiKey)
            assertEquals("下一发端点必须是新值", "https://b.example/v1", transport.configs[1].baseUrl)
            assertEquals("下一发提示应列 read_image", true, second.systemPrompt.contains("- $ReadImageToolName:"))
            assertEquals("下一发 schema 应含 read_image", true, second.tools.any { it.name == ReadImageToolName })
        } finally {
            container.releaseSession(meta.id)
            ProviderTransports.unregister(transport.id)
        }
    }

    @Test
    fun `overflow detection uses the context window of the request being answered`() = runBlocking {
        var window = 1_000
        val strategy = RecordingStrategy()
        val transport = GatedScriptedTransport(
            listOf(
                ScriptedTurn(
                    listOf(
                        StreamEvent.UsageEvent(Usage(input = 1_100, output = 1)),
                        StreamEvent.Completed(StopReason.STOP),
                    ),
                ),
                ScriptedTurn.text("压缩后继续"),
            ),
            id = "snapshot-window-transport",
        )
        val h = harness(
            transport,
            sessionId = "snapshot-window",
            contextWindowProvider = { window },
            contextStrategyProvider = { strategy },
        )
        try {
            h.session.send("go")
            // 本发已按窗口 1000 解析完毕、流挂住：此刻把窗口改大，模拟「响应回来前设置变了」。
            withTimeout(10_000) { transport.issued.await() }
            window = 200_000
            transport.gate.complete(Unit)
            h.awaitIdle()

            // 用量 1100 越的是本发窗口 1000，不越新窗口 200000：判定回读设置就不会触发救援。
            assertEquals("越窗信号必须按本发快照的窗口判定，触发一次溢出救援", 1, strategy.planCalls.size)
            assertEquals("救援成功后必须重新请求", 2, h.requests.size)
            assertTrue("重发的历史以压缩检查点开头", h.requests[1].messages.first().isCompaction)
        } finally {
            h.close()
        }
    }

    /** 第一发在记录请求后挂住，等测试改完设置再放行；记录每发的请求与鉴权配置。 */
    private class GatedTransport : ProviderTransport {
        override val id = "snapshot-race-transport"
        val requests = mutableListOf<ChatRequest>()
        val configs = mutableListOf<ProviderConfig>()
        val issued = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            requests += request
            configs += config
            issued.complete(Unit)
            if (requests.size == 1) gate.await()
            emit(StreamEvent.TextDelta("ok"))
            emit(StreamEvent.Completed(StopReason.STOP))
        }
    }

    /** harness 版同一形状：挂住后再交给 super 按脚本吐事件（requests 由 super 记录）。 */
    private class GatedScriptedTransport(
        turns: List<ScriptedTurn>,
        id: String,
    ) : ScriptedTransport(turns, id) {
        val issued = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
            issued.complete(Unit)
            gate.await()
            emitAll(super.stream(request, config))
        }
    }

    /** 只记录救援触发的假策略：阈值恒不压，plan 必给切点，摘要不走网络。 */
    private class RecordingStrategy : ContextStrategy {
        override val id = "snapshot-recording"
        val planCalls = mutableListOf<CompactionTrigger>()

        override fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings) = false

        override fun plan(
            messages: List<ChatMessage>,
            settings: CompactionSettings,
            trigger: CompactionTrigger,
        ): CompactionPlan {
            planCalls += trigger
            return CompactionPlan(
                summarized = messages.dropLast(1),
                kept = messages.takeLast(1),
                previousSummary = null,
                tokensBefore = 0,
            )
        }

        override suspend fun summarize(
            model: String,
            config: ProviderConfig,
            plan: CompactionPlan,
            customInstructions: String?,
            reserveTokens: Int,
            sessionId: String?,
        ): String = "摘要"

        override fun wrapSummary(plan: CompactionPlan, summary: String): String = summary
    }

    private class Fixture(
        val settings: SettingsStore,
        val meta: SessionMeta,
        private val container: AppContainer,
        private val transport: ScriptedTransport,
        private val session: AgentSession,
    ) {
        suspend fun turn(text: String) {
            session.send(text)
            await("回合应收尾并回到空闲") { !session.isBusy }
        }

        fun promptHasReadImage(index: Int): Boolean =
            transport.requests[index].systemPrompt.contains("- $ReadImageToolName:")

        fun schemaHasReadImage(index: Int): Boolean =
            transport.requests[index].tools.any { it.name == ReadImageToolName }

        fun close() {
            container.releaseSession(meta.id)
            ProviderTransports.unregister(transport.id)
        }
    }

    private fun fixture(startModel: SavedModel, turns: Int): Fixture {
        val tmp = Files.createTempDirectory("kair-snapshot").toFile()
        val settings = SettingsStore(FakeSharedPreferences())
        settings.savedModels = listOf(TEXT_MODEL, VISION_MODEL)
        settings.activeModelId = startModel.id
        val container = AppContainer(
            AppEnvironment(storageRoot = File(tmp, "kair"), nativeLibraryDir = "/lib", primaryAbi = "arm64-v8a"),
            settings,
        )
        val transport = ScriptedTransport(List(turns) { ScriptedTurn.text("ok") }, id = TRANSPORT_ID)
        ProviderTransports.register(transport)
        val project = container.storage.createProject("snapshot-project")
        val meta = container.newSession(project)
        val session = container.openSession(project, meta)
        return Fixture(settings, meta, container, transport, session)
    }

    private companion object {
        const val TRANSPORT_ID = "snapshot-transport"

        val TEXT_MODEL = SavedModel(
            id = "snapshot-text",
            name = "snapshot-text",
            providerId = "openai",
            model = "snapshot-text-model",
            transportId = TRANSPORT_ID,
        )

        val VISION_MODEL = SavedModel(
            id = "snapshot-vision",
            name = "snapshot-vision",
            providerId = "openai",
            model = "snapshot-vision-model",
            transportId = TRANSPORT_ID,
            isVisionModel = true,
        )

        val RACE_MODEL_A = SavedModel(
            id = "race-a",
            name = "race-a",
            providerId = "openai",
            model = "race-model-a",
            transportId = "snapshot-race-transport",
            baseUrl = "https://a.example/v1",
            apiKey = "key-A",
            temperature = 0.1,
            contextWindow = 100_000,
        )

        val RACE_MODEL_B = SavedModel(
            id = "race-b",
            name = "race-b",
            providerId = "openai",
            model = "race-model-b",
            transportId = "snapshot-race-transport",
            baseUrl = "https://b.example/v1",
            apiKey = "key-B",
            temperature = 0.9,
            contextWindow = 200_000,
            isVisionModel = true,
        )
    }
}
