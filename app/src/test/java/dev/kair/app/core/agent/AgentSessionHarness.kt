package dev.kair.app.core.agent

import dev.kair.app.core.ai.ChatRequest
import dev.kair.app.core.ai.ProviderConfig
import dev.kair.app.core.ai.ProviderTransport
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.storage.AttachmentStore
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.FileBackend
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.LocalFileBackend
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/*
 * `AgentSession` 主循环测试底座（仅测试源集）。
 *
 * 为什么需要：`OverflowTest` 这类单测证明的是「检测函数正确」，而缺陷常常长在
 * **调用点**（溢出判定曾一度嵌在 ERROR 分支里，另两条入口永远不可达，全部单测仍是绿的）。
 * 「函数正确、接线错」只在跑完整主循环时才会暴露，所以这里把传输层、会话、等待辅助
 * 组装成同一个场景，让用例能断言「请求字节 / 历史落盘 / 状态转移」三者。
 *
 * 用法：
 * ```
 * val transport = ScriptedTransport(listOf(ScriptedTurn.text("ok")))
 * val h = harness(transport)
 * try {
 *     h.runTurn("hi")
 *     assertEquals(1, h.requests.size)
 * } finally {
 *     h.close()
 * }
 * ```
 * 设计约束（`AGENTS.md` §9）：只做组装，不包新抽象。所有可注入参数原样转发给
 * [AgentSession] 构造函数，需要覆盖哪一项就传哪一项。
 */

/**
 * 一个脚本化回合：第 N 次请求要吐出的事件序列，以及吐完后是否挂住。
 *
 * 回合按**请求序号**取，而不是「第一次 / 之后」：多回合用例与重试、压缩后的补请求
 * 都靠同一份脚本描述，序号越界必须报错（静默复用最后一回合会让用例骗人）。
 */
data class ScriptedTurn(
    val events: List<StreamEvent>,
    /**
     * 吐完事件后挂住（模拟 SSE 被掐断 / 用户在流式中途点停止）。
     * 挂住的回合只能靠 `abort()` 收尾，否则会话一直处于 RUNNING。
     */
    val hangAfter: Boolean = false,
) {
    companion object {
        /** 一段普通正文回合。 */
        fun text(text: String, stopReason: String = StopReason.STOP) = ScriptedTurn(
            listOf(StreamEvent.TextDelta(text), StreamEvent.Completed(stopReason)),
        )

        /**
         * 静默溢出：正常收尾（STOP），但输入用量越过窗口。
         * 这是 provider 不报错时的溢出形态，只有集成层能发现「判定没被调用」。
         */
        fun silentOverflow(contextWindow: Int) = ScriptedTurn(
            listOf(
                StreamEvent.UsageEvent(Usage(input = contextWindow + 1, output = 1)),
                StreamEvent.Completed(StopReason.STOP),
            ),
        )

        /** LENGTH 且零输出：输入已填满窗口、没有留出输出空间（服务端截断输入的形态）。 */
        fun lengthZeroOutput(contextWindow: Int) = ScriptedTurn(
            listOf(
                StreamEvent.UsageEvent(Usage(input = contextWindow, output = 0)),
                StreamEvent.Completed(StopReason.LENGTH),
            ),
        )

        /**
         * 吐出 [before] 后失败。
         *
         * 固定 `retryable = false`：否则内核的瞬时失败重试会**再发一次请求**，
         * 脚本回合被重试吃掉，用例就测不到 ERROR 分支。
         */
        fun failure(message: String, before: List<StreamEvent> = emptyList()) = ScriptedTurn(
            before + StreamEvent.Failure(message, retryable = false),
        )

        /** 吐几个事件后挂住（模拟流式中途被掐断）。 */
        fun hanging(vararg events: StreamEvent) = ScriptedTurn(events.toList(), hangAfter = true)
    }
}

/**
 * 按回合序号吐事件的传输层，并记录每次请求 —— 断言的是「真正发出去的字节」，
 * 不是「界面显示了什么」。
 */
open class ScriptedTransport(
    private val turns: List<ScriptedTurn>,
    override val id: String = "harness-transport",
    /** 声明端点像素上限（U21 用例用它把提交前的缝打开：内联图会因此调用注入的 [ImageDownscaler.Scaler]）。 */
    private val imagePixelCap: Long? = null,
) : ProviderTransport {

    val requests: MutableList<ChatRequest> = mutableListOf()

    override fun maxImagePixels(config: ProviderConfig): Long? = imagePixelCap

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<StreamEvent> = flow {
        val index = requests.size
        requests += request
        val turn = turns.getOrNull(index)
            ?: error("脚本只有 ${turns.size} 个回合，第 ${index + 1} 次请求没有对应的事件序列")
        turn.events.forEach { emit(it) }
        if (turn.hangAfter) delay(HANG_MS)
    }

    private companion object {
        const val HANG_MS = 10_000L
    }
}

/**
 * 一个跑了主循环的会话 + 它的脚本传输层。
 *
 * [close] 必须放在 `finally` 里：`ProviderTransports` 是全局注册表，不注销会让
 * 下一个用例命中上一个用例的传输层（隐式全局状态，本批不新增、但必须收干净）。
 */
class SessionHarness(
    val transport: ScriptedTransport,
    val session: AgentSession,
    val workspace: File,
    /** 注入会话的诊断面；用例据此断言「失败可见」，与宿主的接线方式一致。 */
    val diagnostics: Diagnostics = Diagnostics(),
) {
    val requests: List<ChatRequest> get() = transport.requests

    /** 跑一个完整用户回合，等到会话真正空闲（含中止后的取消收尾）。 */
    suspend fun runTurn(text: String) {
        require(!session.isBusy) { "上一回合还没收尾，不能开始新回合" }
        session.send(text)
        awaitIdle()
    }

    /** 等会话回到空闲；`isBusy` 已包含「已点停止、收尾还没跑完」的窗口。 */
    suspend fun awaitIdle() {
        await("回合应收尾并回到空闲") { !session.isBusy }
    }

    fun close() {
        session.abort()
        ProviderTransports.unregister(transport.id)
    }
}

/**
 * 等待条件成立。**不等空闲** —— 中止前的回合本就一直处于 RUNNING（传输层挂住等用户
 * 点停止），收尾完成与否只能用它产生的效果来观察。
 */
suspend fun await(what: String, predicate: () -> Boolean) {
    try {
        withTimeout(AWAIT_TIMEOUT_MS) { while (!predicate()) delay(20) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("等待超时：$what", e)
    }
    assertTrue(what, predicate())
}

/**
 * 等条件成立的**挂起守卫**，不是行为上界：满载时 20ms 轮询的协程可能被饿到秒级。
 * 10s 在 Windows 全量里实测被打穿过一次（`AgentSessionInterruptTest`，U14）；放宽到
 * 30s，真挂起的回合仍会失败，不会无限等。
 */
private const val AWAIT_TIMEOUT_MS = 30_000L

/**
 * 一行起一个会话：默认空工具表、默认 [AgentSettings]、临时 workspace。
 *
 * 其余参数与 [AgentSession] 构造函数同名同义、原样转发；需要什么就覆盖什么，
 * 不在中间层再做一次抽象（新增接缝会把契约做宽，且难删）。
 */
fun harness(
    transport: ScriptedTransport,
    sessionId: String = "harness-session",
    workspace: File = Files.createTempDirectory("kair-harness").toFile(),
    tools: ToolRegistry = ToolRegistry(),
    configProvider: () -> ProviderConfig = {
        ProviderConfig(
            providerId = "test-provider",
            transportId = transport.id,
            baseUrl = "https://example.test/v1",
            apiKey = "",
        )
    },
    assemblyProvider: () -> AgentAssembly = { AgentAssembly(AgentSettings(model = "test-model", systemPrompt = "")) },
    compactionProvider: () -> CompactionSettings = { CompactionSettings() },
    contextStrategyProvider: () -> ContextStrategy? = { null },
    contextWindowProvider: (String) -> Int = { AgentSession.DEFAULT_CONTEXT_WINDOW },
    cacheEnabled: () -> Boolean = { true },
    refreshPromptEveryTurn: () -> Boolean = { true },
    hooks: HookBus = HookBus(),
    onEvent: (AgentEvent) -> Unit = {},
    cacheInspector: CacheInspector = CacheInspector(),
    diagnostics: Diagnostics = Diagnostics(),
    commandRunner: () -> CommandRunner = { LocalCommandRunner() },
    filesProvider: () -> FileBackend = { LocalFileBackend(workspace.path) },
    toolFilter: (Tool) -> Boolean = { true },
    toolsetBaselineGet: (() -> String?)? = null,
    toolsetBaselineSet: ((String) -> Unit)? = null,
    onPersist: (ChatMessage) -> Unit = {},
    onReplaceHistory: (List<ChatMessage>) -> Unit = {},
    attachments: AttachmentStore = AttachmentStore.forWorkspace(workspace),
    /** 位图缩放能力（内核不实现位图）；默认不缩放，与生产缺省一致。U21 窗口用例用它钉开提交前的缝。 */
    imageScaler: ImageDownscaler.Scaler = ImageDownscaler.None,
    // 固定时钟：harness 下的会话每回合都会注入一条日期 system note，若用墙上时钟，
    // 断言里的消息条数 / 文本会随运行日期漂移（U14 同类坑）。
    todayProvider: () -> LocalDate = { LocalDate.of(2026, 10, 29) },
    /** 停止硬上界（U57）：用例传小值，免得每条用例真等 20 秒；生产唯一来源是 `StopHardLimitMs`。 */
    stopHardLimitMs: Long = StopHardLimitMs,
): SessionHarness {
    ProviderTransports.register(transport)
    val session = AgentSession(
        id = sessionId,
        workspace = workspace,
        tools = tools,
        inputsProvider = {
            val built = assemblyProvider()
            inputsOf(
                assembly = built,
                config = configProvider(),
                tools = tools.all().filter(toolFilter),
                contextWindow = contextWindowProvider(built.settings.model),
            )
        },
        compactionProvider = compactionProvider,
        contextStrategyProvider = contextStrategyProvider,
        cacheEnabled = cacheEnabled,
        refreshPromptEveryTurn = refreshPromptEveryTurn,
        hooks = hooks,
        onEvent = onEvent,
        cacheInspector = cacheInspector,
        diagnostics = diagnostics,
        commandRunner = commandRunner,
        filesProvider = filesProvider,
        toolsetBaselineGet = toolsetBaselineGet,
        toolsetBaselineSet = toolsetBaselineSet,
        onPersist = onPersist,
        onReplaceHistory = onReplaceHistory,
        attachments = attachments,
        imageScaler = imageScaler,
        todayProvider = todayProvider,
        stopHardLimitMs = stopHardLimitMs,
    )
    return SessionHarness(transport, session, workspace, diagnostics)
}

/**
 * 把测试的 granular fake 合成一份输入快照（U42）：直接构造 [AgentSession] 的用例与
 * [harness] 共用这一种组合，不在各用例里各拼一遍快照构造参数。
 */
fun inputsOf(
    assembly: AgentAssembly,
    config: ProviderConfig,
    tools: List<Tool> = emptyList(),
    contextWindow: Int = AgentSession.DEFAULT_CONTEXT_WINDOW,
): ResolvedRequestInputs = ResolvedRequestInputs(
    model = null,
    providerConfig = config,
    contextWindow = contextWindow,
    assembly = assembly.copy(tools = tools),
)
