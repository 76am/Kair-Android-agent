package dev.kair.app.core.agent

import dev.kair.app.core.ai.LlmToolSpec
import dev.kair.app.core.ai.ProviderTransports
import dev.kair.app.core.ai.RequestCapture
import dev.kair.app.core.ai.StreamEvent
import dev.kair.app.core.ai.describeError
import dev.kair.app.core.ai.errorStatusCode
import dev.kair.app.core.ai.hostOf
import dev.kair.app.core.ai.localFailureMessage
import dev.kair.app.core.ai.parseErrorDetail
import dev.kair.app.core.ai.retryingStream
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.model.Part
import dev.kair.app.core.model.Role
import dev.kair.app.core.model.StopReason
import dev.kair.app.core.model.Usage
import dev.kair.app.core.model.newId
import dev.kair.app.core.plugin.HookBus
import dev.kair.app.core.storage.AttachmentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 一个 assistant 回合的**请求装配 + 流式消费**（A5 拆出的第三块）。
 *
 * 为什么单独成文件：这是 `AgentSession` 里最长的一段（279 行），而且它决定「发给模型的字节」——
 * 稳定前缀契约在这一段的具体形态是：投影链（被掐断的回合 / 思考回传开关 / 历史修复 / 附件水合）
 * 必须先喂给装配器、再喂给传输层，两处拿到同一份列表，前缀字节才等于实际发送字节。
 * 这类「一字之差就打掉缓存」的逻辑值得有自己的边界，而不是埋在会话对象里。
 *
 * **它不是新的状态持有者**：历史写入仍全部回到会话侧（[Host.updateAssistant] /
 * [Host.removeMessage] / [Host.onPersist]），执行器只读当前历史。搬家阶段刻意**不改函数内部
 * 结构**（`AGENTS.md` §10.2：一次搬完 500 行没人能评审）。A5 第二步只搬走了压缩 / 循环 / 历史
 * 改写，本类**没有**再拆 —— 它仍是「一个回合」的单一职责。
 */
internal class TurnExecutor(private val host: Host) {

    /**
     * 执行器对会话的依赖面：全部是 `AgentSession` 已有的能力，不新增状态、不新增写入点。
     *
     * 做成显式依赖面（而不是让执行器持有 `AgentSession`）有两个理由：
     * ① 执行器可以脱离会话被单测（本批的目标就是让最难测的语义可单测）；
     * ② 不必为了搬家把会话的私有成员降成 `internal` 对整个模块开放。
     */
    internal data class Host(
        val sessionId: String,
        /**
         * 本发请求输入的唯一入口（U42）：每发只调用一次，模型 / 鉴权 / 工具 / 采样 / 窗口
         * 与装配产物（settings + 诊断 + 段落缓存读数）全部来自这同一次解析，之后不再读设置。
         * 为什么不能反过来用「观测器里最近记到的那份」：见 [AgentAssembly] 的类注释。
         */
        val inputs: () -> ResolvedRequestInputs,
        val hooks: HookBus,
        /** 当前历史（只读）：投影链在它之上产出请求副本，执行器不回写。 */
        val history: () -> List<ChatMessage>,
        val cacheEnabled: () -> Boolean,
        val cacheInspector: CacheInspector,
        val attachments: AttachmentStore,
        /** 空回合计数的写入面（U48 ②）：空回合不落盘，诊断是它唯一的可见痕迹。 */
        val diagnostics: Diagnostics,
        val onPersist: (ChatMessage) -> Unit,
        val updateAssistant: (String, ChatMessage) -> Unit,
        val removeMessage: (String) -> ChatMessage?,
    )

    /**
     * 「发给模型」的历史投影：被掐断的回合（[StopReason.ABORTED]，SSE 中途停止）整条内容
     * 丢弃，换成一条中断提示。
     *
     * 为什么不能照发：被掐断的回合里，正文可能是半句话（例如刚说完「我这就查查」、紧随的
     * 工具调用就被掐掉），工具参数可能没吐完。模型见到「自己说过、却没做」的残缺回合会
     * 当成已完成的上下文继续推理，于是重复声明、误以为工具已执行或反复解释。半截思考同理
     * （`Part.Thinking` 本就不进请求），所以这一条不是「少发一点」，而是整条不发。
     *
     * 与 [HistorySanitizer.repairToolPairing] 的分工：那里**改写历史本身**（残缺调用必须清掉，否则请求会被
     * provider 拒绝）；这里**只改「发给模型」的那份**，界面上用户仍能看到被打断的半截内容。
     * 因此投影必须同时喂给装配前缀与实际请求（见 [streamAssistant]），前缀字节才等于实际
     * 发送字节，缓存契约成立。
     *
     * 不违反「只追加」：被掐断的回合从未发给模型（它诞生于流被中断的那一刻），这次投影发生
     * 在该回合首次进入模型可见历史之前，且只发生一次 —— 对模型可见历史而言仍是追加，
     * 前面已发出的前缀逐字节不变。
     *
     * 幂等：历史里没有被掐断的回合时原样返回同一个列表，不产生拷贝。
     */
    private fun modelVisibleHistory(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { it.role == Role.ASSISTANT && it.stopReason == StopReason.ABORTED }) return messages
        return messages.map { m ->
            if (m.role == Role.ASSISTANT && m.stopReason == StopReason.ABORTED) {
                // 保持 assistant 角色：换成 user 提示会出现连续两条 user 消息（同 [HistorySanitizer.repairToolPairing]）。
                m.copy(parts = listOf(Part.Text(AgentSession.INTERRUPTED_NOTICE)))
            } else {
                m
            }
        }
    }

    /**
     * 「思考回传」开关：关闭时把思考部件从**请求投影**里剔除（日志与界面仍保留原文），
     * 让开关真的生效；开启时由各传输层按自己的协议写回（`reasoning_content` /
     * Anthropic `thinking` / Responses reasoning item / Gemini `thought`）。
     *
     * 与 [modelVisibleHistory] 一样只改请求副本，不回写日志；关闭状态下前缀字节里
     * 不含思考，`RequestAssembler` 的摘要因此与真实请求一致。
     */
    private fun replayableHistory(messages: List<ChatMessage>, replay: Boolean): List<ChatMessage> {
        if (replay || messages.none { m -> m.parts.any { it is Part.Thinking } }) return messages
        return messages.map { m ->
            if (m.parts.none { it is Part.Thinking }) {
                m
            } else {
                // 只有思考的回合剔除后会变空：补一个空正文，保持「assistant 回合有内容」。
                val parts = m.parts.filterNot { it is Part.Thinking }
                m.copy(parts = parts.ifEmpty { listOf(Part.Text("")) })
            }
        }
    }

    suspend fun streamAssistant(): RequestOutcome {
        // 一次解析、一份快照（U42）：模型 / 鉴权 / 工具 / 采样 / 窗口与段落缓存读数全部
        // 出自这同一次调用，之后不再读设置 —— 请求中途改设置只影响下一发。
        val inputs = host.inputs()
        val built = inputs.assembly
        val settings = built.settings
        // Plugins may add or rewrite request headers (pi: before_provider_headers).
        val config = inputs.providerConfig.copy(headers = host.hooks.runHeaders(inputs.providerConfig.headers))

        // 通过内核请求装配器显式分段，落实「缓存稳定前缀」契约并留下观测快照。
        // 稳定前缀顺序固定为 system prompt → 工具列表 → 历史回合，易变尾部排在其后。
        val contextMessages = host.hooks.runContext(host.history())
        val assembly = RequestAssembler.assemble(
            model = settings.model,
            systemPrompt = settings.systemPrompt,
            // 被掐断的回合整条丢弃、换成中断提示（见 [modelVisibleHistory]）；随后修复
            // 悬空调用与非法工具参数（见 [HistorySanitizer.repairHistory]），请求期再把「引用式」图片
            // 水合回 base64。三者都只作用于请求副本，日志/内存不回写。
            messages = replayableHistory(
                hydrateAttachments(HistorySanitizer.repairHistory(modelVisibleHistory(contextMessages))),
                settings.replayThinking,
            ),
            tools = inputs.tools.map { LlmToolSpec(it.name, it.description, it.parameters) },
            options = settings.sampling,
            sessionId = host.sessionId,
            cache = host.cacheEnabled(),
            providerId = config.providerId,
            providerHost = hostOf(config.baseUrl),
        )
        val request = assembly.request
        host.cacheInspector.record(host.sessionId, settings.model, assembly, built.sectionCache)

        // 按到达顺序记录正文 / 推理段落，让「正文 → 工具 → 正文」「推理 → 正文」这类
        // 交替结构在转录里保持原顺序。旧事件（没有 Start / End）的 delta 追加到
        // 当前打开的默认段落，行为与改动前一致。
        val segments = ArrayList<Segment>()
        var openText: Segment? = null
        var openReasoning: Segment? = null
        val calls = LinkedHashMap<Int, ToolCallAcc>()
        val placeholderId = newId()
        var stopReason: String? = null
        var usage: Usage? = null
        var errorMessage: String? = null
        // 本次流式尝试的起点：诊断只在「这一发之后记录的请求」上回填流失败，
        // 防止本地异常（根本没发请求）把上一发的成功请求误标成失败。
        val streamStartedAt = System.currentTimeMillis()

        fun segment(id: String, kind: SegmentKind): Segment =
            segments.firstOrNull { it.kind == kind && it.id == id }
                ?: Segment(id, kind).also { segments += it }

        fun appendText(delta: String) {
            val seg = openText ?: Segment("", SegmentKind.TEXT).also {
                segments += it
                openText = it
            }
            seg.content.append(delta)
        }

        fun appendReasoning(delta: String) {
            val seg = openReasoning ?: Segment("", SegmentKind.REASONING).also {
                segments += it
                openReasoning = it
            }
            // 传输层是思考内容的**唯一入口**：所有协议（OpenAI 兼容 / Anthropic /
            // Responses / Gemini）都经这里落进 Part.Thinking。帧协议残留的控制字符
            // （NUL、长度前缀等）在这里一次剔干净，界面与后续回传都不会再见到。
            seg.content.append(stripControlChars(delta))
        }

        fun currentText(): String = segments
            .filter { it.kind == SegmentKind.TEXT }
            .joinToString("") { it.content }

        fun currentThinking(): String = segments
            .filter { it.kind == SegmentKind.REASONING }
            .joinToString("") { it.content }

        fun snapshot(): ChatMessage {
            val parts = ArrayList<Part>()
            segments.forEach { seg ->
                // Gemini 的签名可能挂在空正文段上；丢掉它就断了后续推理链。
                if (seg.content.isEmpty() && seg.signature == null) return@forEach
                when (seg.kind) {
                    SegmentKind.REASONING -> parts.add(Part.Thinking(seg.content.toString(), seg.signature, seg.redacted))
                    SegmentKind.TEXT -> parts.add(Part.Text(seg.content.toString(), seg.signature))
                }
            }
            calls.values.sortedBy { it.index }
                .forEach { parts.add(Part.ToolCall(it.id, it.name, it.args.toString(), it.signature)) }
            if (parts.isEmpty()) parts.add(Part.Text(""))
            return ChatMessage(
                id = placeholderId,
                role = Role.ASSISTANT,
                parts = parts,
                model = settings.model,
                // 来源取自**本发请求**（U76）：与请求侧同一个值，全发只归一化一次地址。
                providerId = request.providerId,
                providerHost = request.providerHost,
                usage = usage,
                stopReason = stopReason,
                errorMessage = errorMessage,
                isError = stopReason == StopReason.ERROR,
            )
        }

        // 流式增量每帧都重建一次快照（每个段落都要 content.toString()，长思考可达数十万
        // 字符），既产生大量短命字符串，又让 Compose 每帧重新排版正在增长的长文本 ——
        // 长回合里这是内存与主线程的主要压力，低内存机型会直接 OOM。按 ~15fps 推送即可
        // 满足「看着在流」；段落开始 / 结束 / 用量这类关键节点仍立即推送。
        var lastPushNs = 0L
        var dirty = false
        // 事件处理与节拍补推可能落在不同线程（会话跑在 Dispatchers.Default）：快照要读
        // segments / 累积器，并发读写的 StringBuilder 会抛异常或产出乱码，故串行化。
        val uiLock = Any()

        fun push(force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && now - lastPushNs < UI_PUSH_INTERVAL_NS) {
                dirty = true
                return
            }
            dirty = false
            lastPushNs = now
            host.updateAssistant(placeholderId, snapshot())
        }

        fun flushIfDirty() {
            synchronized(uiLock) {
                if (!dirty) return
                dirty = false
                lastPushNs = System.nanoTime()
                host.updateAssistant(placeholderId, snapshot())
            }
        }

        /** 处理一帧流式事件；与补推共享 [uiLock]，避免并发读写段落 / 累积器。 */
        fun handle(ev: StreamEvent) {
            synchronized(uiLock) {
                when (ev) {
                    is StreamEvent.TextStart -> {
                        openText = segment(ev.id, SegmentKind.TEXT)
                        push(force = true)
                    }
                    is StreamEvent.TextDelta -> {
                        appendText(ev.text); push()
                    }
                    is StreamEvent.TextEnd -> {
                        // 签名可能和结束帧一起到；先落到对应段上再关闭。
                        if (ev.signature != null) {
                            segments.firstOrNull { it.kind == SegmentKind.TEXT && it.id == ev.id }
                                ?.signature = ev.signature
                        }
                        // 只关闭与 id 匹配的段：迟到的 End 不能关掉已经新开的那一段。
                        if (openText?.id == ev.id) openText = null
                    }
                    is StreamEvent.ReasoningStart -> {
                        openReasoning = segment(ev.id, SegmentKind.REASONING)
                        push(force = true)
                    }
                    is StreamEvent.ReasoningDelta -> {
                        appendReasoning(ev.text); push()
                    }
                    is StreamEvent.ReasoningEnd -> {
                        // 签名可能和结束帧一起到（Anthropic signature_delta / Responses
                        // output_item.done）；晚到的签名也要落到对应段上，否则下一回合无法回传。
                        if (ev.signature != null) {
                            segments.firstOrNull { it.kind == SegmentKind.REASONING && it.id == ev.id }
                                ?.let { it.signature = ev.signature; it.redacted = ev.redacted }
                        }
                        if (openReasoning?.id == ev.id) openReasoning = null
                    }
                    is StreamEvent.ToolCallStarted -> {
                        // Started 携带权威 id/name：若 ArgsDelta 先到已建了占位累积器，
                        // 在此修正，避免占位 id 漏进转录。
                        val acc = calls.getOrPut(ev.index) { ToolCallAcc(ev.index, ev.id, ev.name) }
                        acc.id = ev.id
                        acc.name = ev.name
                        if (ev.signature != null) acc.signature = ev.signature
                        push(force = true)
                    }
                    is StreamEvent.ToolCallArgsDelta -> {
                        // 占位 id 带上本回合消息 id 的前缀：端点不回 id 时各回合的
                        // "call_N" 才不会互相覆盖（结果按 id 关联）。
                        calls.getOrPut(ev.index) { ToolCallAcc(ev.index, "call_${ev.index}_${placeholderId.take(8)}", "") }
                            .args.append(ev.delta)
                        push()
                    }
                    is StreamEvent.ToolCallsCompleted -> {
                        // Completed 的列表按调用顺序排列，而各 Provider 的 index 未必从 0
                        // 连续（Anthropic 的 content block 里可能夹着正文块），所以按
                        // 累积器顺序配对，而不是拿位置 i 当 key 再建一个重复调用。
                        val accs = calls.values.sortedBy { it.index }
                        ev.calls.forEachIndexed { i, c ->
                            val a = accs.getOrNull(i)
                                ?: ToolCallAcc(calls.size + i, c.id, c.name).also { calls[it.index] = it }
                            a.id = c.id
                            a.name = c.name
                            a.args.settleToolArguments(c.arguments)
                        }
                        push(force = true)
                    }
                    is StreamEvent.UsageEvent -> {
                        usage = ev.usage
                        host.cacheInspector.recordUsage(host.sessionId, ev.usage)
                        push(force = true)
                    }
                    is StreamEvent.Completed -> {
                        stopReason = ev.stopReason
                    }
                    is StreamEvent.Failure -> {
                        // 传输层给的是原始错误；这里做结构化解析并换成中文说明，用户不必
                        // 面对一段厂商 JSON。Unknown 分类保留原文（空响应 / 链路卡住等
                        // 已经自带完整中文说明，不再套前缀）。
                        val detail = parseErrorDetail(ev.message).ifBlank { "未知错误" }
                        val friendly = describeError(detail, errorStatusCode(ev.message))
                        errorMessage = friendly
                        // HTTP 200 之后流才失败时，诊断的状态码看不出问题：回填流结果，
                        // 让「最近请求」把它判成失败（U61）。
                        RequestCapture.markStreamFailure(host.sessionId, streamStartedAt, friendly)
                        stopReason = StopReason.ERROR
                        val existing = currentText()
                        appendText(if (existing.isEmpty()) friendly else "\n\n$friendly")
                    }
                }
            }
        }

        push(force = true)

        try {
            coroutineScope {
                // 节流只在「有事件流入」时自然生效：模型吐完一段后长时间沉默（或用户在
                // 这时点停止）时，最后一帧不能一直留在内存里 —— 用固定节拍补推，界面最迟
                // ~66ms 后跟上，同时仍只按 ~15fps 重建快照（长思考下这是内存与主线程的
                // 主要压力来源）。补推只在有积压（dirty）时才真正重建。
                val flusher = launch {
                    while (isActive) {
                        delay(UI_PUSH_INTERVAL_MS)
                        flushIfDirty()
                    }
                }
                try {
                    // 内核在传输层之上统一包一层瞬时失败重试：网络抖动 / 切网 / 429 / 5xx / 空响应
                    // 会自动重发，但**只在本次尝试尚未产出任何可见输出时**（见 TransientRetry）。
                    retryingStream { ProviderTransports.stream(request, config) }.collect { handle(it) }
                } finally {
                    flusher.cancel()
                }
            }
        } catch (e: CancellationException) {
            // 中止可能正落在「占位气泡已渲染、还没有任何产出」的窗口：这种空白
            // 气泡既无内容也未落盘，直接回收，避免暂停后一直残留在消息列表里。
            if (currentText().isBlank() && currentThinking().isBlank() && calls.isEmpty()) {
                host.removeMessage(placeholderId)
            } else {
                // SSE 被掐断的回合：这一轮输出**不完整** —— 正文可能只是半句话、工具参数
                // 可能没吐完，因此整条不得回灌给模型（见 [modelVisibleHistory]）。已生成的
                // 部分仍留在界面给用户看，落盘以保证重启后界面与请求前缀都不变。
                val aborted = snapshot().copy(stopReason = StopReason.ABORTED)
                host.updateAssistant(placeholderId, aborted)
                host.onPersist(aborted)
            }
            throw e
        } catch (e: Exception) {
            // 传输层之外的意外异常（第三方传输层直接抛错等）：就地转成一条错误回合，
            // 而不是让它逃到 runUser —— 那样占位气泡会以「空内容」永远留在列表里。
            // 没有 HTTP 状态码 = 本地异常，按类型给中文；服务端原文不翻译。
            val message = e.message?.takeIf { it.isNotBlank() }.orEmpty()
            val status = errorStatusCode(message)
            val friendly = describeError(
                if (status == null) localFailureMessage(e) else parseErrorDetail(message),
                status,
            )
            errorMessage = friendly
            stopReason = StopReason.ERROR
            val existing = currentText()
            appendText(if (existing.isEmpty()) friendly else "\n\n$friendly")
        }

        if (stopReason == null) stopReason = StopReason.STOP
        // 只有思考、没有正文/工具的干净收尾不是回答（网关会在推理后直接 EOS）：
        // 与「完全空」同属空回合；LENGTH / ERROR 里的思考仍照常落盘。
        val hasAnswer = currentText().isNotBlank() || calls.isNotEmpty()
        val final = snapshot()
        if (hasAnswer || (stopReason != StopReason.STOP && currentThinking().isNotBlank())) {
            host.updateAssistant(placeholderId, final)
            host.onPersist(final)
        } else {
            // 空回合（服务端只回了结束原因、或错误信息本身为空）：不落盘、不建气泡。
            // 否则列表里会永久残留一个空白 agent 气泡，且它还会作为空 assistant 消息进请求。
            host.removeMessage(placeholderId)
            // U48 ②：不落盘也要留痕 —— 诊断计一次，返回值改带 emptyResponse，回合壳
            // 据此广播 TurnEnd（被丢弃的回合在历史里查不到，返回值是唯一载体）。
            // 溢出救援不吃亏：OverflowDetector 的用量分支认 emptyResponse（见那里的注释）。
            host.diagnostics.recordEmptyResponse(host.sessionId)
            return RequestOutcome(final.copy(stopReason = StopReason.EMPTY_RESPONSE), inputs)
        }
        return RequestOutcome(final, inputs)
    }

    /**
     * 请求期水合：把「引用式」图片按内容读回 base64，供传输层序列化。
     *
     * 只在装配出的**请求副本**上进行，不回写内存 / 日志（日志始终保持无本体的引用形态）。
     * 内容寻址 → 同一引用永远水合出同一字节，故不破坏请求前缀的缓存稳定性。读不到本体
     * （文件缺失）时保留引用式部件，由传输层跳过该图，不阻断本次请求。
     */
    private fun hydrateAttachments(messages: List<ChatMessage>): List<ChatMessage> =
        messages.map { m ->
            if (m.images.none { it.ref != null && it.base64.isNullOrBlank() }) {
                m
            } else {
                m.copy(
                    parts = m.parts.map { p ->
                        val ref = (p as? Part.Image)?.ref
                        if (p is Part.Image && ref != null && p.base64.isNullOrBlank()) {
                            host.attachments.read(ref)?.let { p.copy(base64 = it) } ?: p
                        } else {
                            p
                        }
                    },
                )
            }
        }

    private data class ToolCallAcc(
        val index: Int,
        var id: String,
        var name: String,
        val args: StringBuilder = StringBuilder(),
        var signature: String? = null,
    )

    /** 正文 / 推理的一段；[id] 是传输层给的 part id（旧事件为空串）。 */
    private enum class SegmentKind { TEXT, REASONING }

    private class Segment(val id: String, val kind: SegmentKind) {
        val content = StringBuilder()

        /** 思考签名（Anthropic signature / Responses item JSON / Gemini thoughtSignature）。 */
        var signature: String? = null

        /** Anthropic `redacted_thinking`：内容已隐去，[signature] 即需原样回传的 `data`。 */
        var redacted: Boolean = false
    }

    companion object {
        /** 流式期间界面快照的最小推送间隔（约 15fps），见 [streamAssistant] 的 `push`。 */
        private const val UI_PUSH_INTERVAL_NS = 66_000_000L
        private const val UI_PUSH_INTERVAL_MS = 66L
    }
}

/**
 * 用收尾帧的参数覆盖流式累积：`ToolCallsCompleted` 给的是整发权威全文，
 * 即使已经收到过一小段前缀也必须覆盖 —— 有的兼容端点只把开头几个字符
 * 作为增量推出，其余全在收尾帧。旧写法「非空就不采信」会让卡片与工具
 * 都停在半截 JSON（真机表现为 `edit` 卡在 `{"edits":` / 3 tok）。
 * 收尾帧为空（无参数调用）时保留已收到的内容。
 */
internal fun StringBuilder.settleToolArguments(completed: String) {
    if (completed.isEmpty()) return
    setLength(0)
    append(completed)
}

/**
 * 一发请求的完整产物（U42）：助手回合 + 本发实际使用的输入快照。
 * 溢出判定必须认 [inputs] 里的模型 / 窗口 —— 响应返回后再回读设置，
 * 拿到的可能是「下一发」的值（设置中途被改），判定就与本发请求脱节。
 */
internal data class RequestOutcome(
    val assistant: ChatMessage,
    val inputs: ResolvedRequestInputs,
)
