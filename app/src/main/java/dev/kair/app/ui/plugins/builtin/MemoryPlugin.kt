package dev.kair.app.ui.plugins.builtin

import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.SystemPromptBuilder
import dev.kair.app.core.model.ChatMessage
import dev.kair.app.core.plugin.AgentEvent
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.plugin.SlotArea
import dev.kair.app.core.plugin.SlotContribution
import dev.kair.app.core.plugin.SlotElement
import dev.kair.app.core.subagent.SubagentManager
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 记忆插件（内置版，id 沿用外部 dex `dev.kair.plugin.memory` 以实现数据 / 设置继承）。
 *
 * 七件事：注册记忆段（order 25）、声明式设置面板、槽位「整理记忆」按钮、`TurnEnd`
 * 节拍计数、整理调度、跨项目查看面板（`panels.memory`，仅内置构造注册）。
 * （会话顶栏图标快捷入口已随 U101 撤下：顶栏槽位停用，入口走面板坞。）
 *
 * **节拍是插件自持的持久化计数**（`SharedPreferences`，键 `memory.state.*`）：绝不读
 * `AgentEvent.turnCount` —— 那是「当前历史里 USER 消息条数」，压缩会重写历史让它倒退
 * （§11 的既有缺陷），两套刻度混用会在长会话里静默停更。
 *
 * **整理 = 后台编辑**：子智能体用 read / edit / write 直接改宿主绝对路径
 * `<workspace>/.kair/memory.md`；宿主在它返回前后各读一次文件做机械校验（字符数必须下降，
 * 否则按原始字节回滚）。**`runSubagent` 返回的 String 只判失败**（是否以
 * `SubagentManager.ERROR_PREFIX` 开头），绝不当记忆正文覆盖写。
 *
 * **只在当前活跃会话里整理**：`activeSessionId == TurnEnd.sessionId` 才执行，否则推迟到
 * 该会话的下一个用户回合 —— 这样子智能体的工作区必然是这份记忆文件所属的项目。
 */
class MemoryPlugin internal constructor(
    private val deps: MemoryDeps,
    private val container: AppContainer? = null,
) : Plugin {

    /**
     * 生产构造：把容器能力包成最小依赖面；单测用假实现替换。
     * [container] 另持一份用于注册跨项目查看面板（内置面板可用 Compose）；
     * 单测的假依赖面不传它，面板不注册。
     */
    constructor(container: AppContainer) : this(ContainerMemoryDeps(container), container)

    override val id = ID
    override val name = ""
    override val description = ""
    override val nameRes: Int get() = R.string.memory_plugin_name
    override val descriptionRes: Int get() = R.string.memory_plugin_desc

    private lateinit var settings: PluginSettingsStore
    private var cadence: MemoryCadence? = null
    private var scope: CoroutineScope? = null
    private var agents: dev.kair.app.core.plugin.AgentBridge? = null

    /** 同一时刻只跑一次整理；被占用时本轮跳过，下一个用户回合再试。 */
    private val running = AtomicBoolean(false)

    override fun activate(ctx: PluginContext) {
        val store = ctx.settings
        settings = store
        cadence = MemoryCadence(store)
        scope = ctx.scope
        agents = ctx.agents

        ctx.systemPrompt(MemoryContributor(store, onNotice = deps::recordNotice))
        // 声明数据路径：权限修复（WorkspacePermissionRepair）据此把 `.kair/memory.md`
        // 纳入精准修复范围 —— root 写入的 600 文件靠这里改回应用所有。
        ctx.declareData(MemoryStore.FILE_PATH, MemoryStore.FILE_PATH)

        // 记忆合并面板（修正批，负责人拍板「合并成一个完整面板」）：spec 仍由本方法声明 ——
        // 它是设置项的唯一数据源，由 panels.memory 内嵌渲染；不再单独注册声明式面板，
        // 否则插件页出现两张卡 = 两个面板（AGENTS §10.3 删旧路径）。
        val panelSpec = memorySettingsPanel()

        // 会话顶栏快捷入口（TOP 槽位）已撤（U101）：顶栏是身份区，右上角难够到，
        // 且宿主自绘的线稿按钮与内置 Material 图标同排确实格格不入。入口走面板坞
        // 的「记忆」格子（下面的 PANEL_ID 面板声明），入口数量不变、位置更好够。
        // 合并面板：内置面板可用 Compose（WorkspaceFilesPlugin 同款通道）。一屏内
        // 项目切换 + 可编辑记忆文件 + 全部设置项。
        container?.let { c ->
            ctx.panel(
                PanelContribution(
                    id = PANEL_ID,
                    title = "",
                    titleRes = R.string.memory_viewer_title,
                    icon = PluginGlyph.Memory,
                    order = 35,
                    content = { MemoryPanel(c, panelSpec) },
                ),
            )
        }

        // 槽位按钮：只作用于**触发它的那个会话**（SlotContext 的 sessionId / workspace），
        // 不读「最近活跃会话」这类隐式全局状态。
        ctx.slot(
            SlotContribution(
                id = "memory.slot",
                area = SlotArea.BOTTOM,
                order = 90,
                elements = listOf(
                    SlotElement.Button(
                        key = KEY_CONSOLIDATE,
                        label = "整理记忆",
                        confirm = true,
                    ) { _, slot ->
                        val sessionId = slot.sessionId
                        if (sessionId != null && slot.workspace.isNotBlank()) {
                            launch(sessionId, slot.workspace, cadence?.total ?: 0)
                        }
                    },
                ),
            ),
        )

        ctx.onAgentEvent { event ->
            if (event is AgentEvent.TurnEnd) onTurnEnd(event)
        }
        ctx.onDeactivate { deps.clearNotices() }
    }

    /**
     * 记忆设置项声明（唯一数据源）：字段全走 `strings.xml`（内置插件 §4）；
     * 键完整沿用外部插件的 `memory.*`。由 `panels.memory` 合并面板内嵌渲染
     * （FileEditor 字段除外 —— 文件编辑由面板的项目级编辑器承担）。
     */
    private fun memorySettingsPanel(): SettingsPanelSpec = SettingsPanelSpec(
        id = "settings.memory",
        title = "",
        icon = PluginGlyph.Memory,
        order = 60,
        fields = listOf(
            SettingField.Toggle(
                key = KEY_AUTO,
                labelRes = R.string.memory_setting_auto,
                descriptionRes = R.string.memory_setting_auto_desc,
                default = MemoryStore.DEFAULT_AUTO,
            ),
            SettingField.Number(
                key = KEY_EVERY,
                labelRes = R.string.memory_setting_every,
                descriptionRes = R.string.memory_setting_every_desc,
                default = MemoryStore.DEFAULT_EVERY,
                min = 1,
                max = 50,
            ),
            SettingField.Number(
                key = KEY_MAX_CHARS,
                labelRes = R.string.memory_setting_max_chars,
                descriptionRes = R.string.memory_setting_max_chars_desc,
                default = MemoryStore.DEFAULT_MAX_CHARS,
                min = 400,
                max = 6000,
            ),
            SettingField.Number(
                key = KEY_MAX_LINES,
                labelRes = R.string.memory_setting_max_lines,
                descriptionRes = R.string.memory_setting_max_lines_desc,
                default = MemoryStore.DEFAULT_MAX_LINES,
                min = 10,
                max = 200,
            ),
            SettingField.Number(
                key = KEY_RETENTION_DAYS,
                labelRes = R.string.memory_setting_retention,
                descriptionRes = R.string.memory_setting_retention_desc,
                default = MemoryStore.DEFAULT_RETENTION_DAYS,
                min = 1,
                max = 365,
            ),
            SettingField.Choice(
                key = KEY_MODEL,
                labelRes = R.string.memory_setting_model,
                descriptionRes = R.string.memory_setting_model_desc,
                source = "models",
            ),
            SettingField.FileEditor(
                key = KEY_FILE,
                path = MemoryStore.FILE_PATH,
                labelRes = R.string.memory_setting_file,
                descriptionRes = R.string.memory_setting_file_desc,
            ),
        ),
    ).apply {
        titleRes = R.string.memory_panel_title
    }

    /**
     * 回合结束：自增持久化计数（**不看 event.turnCount**），再判断是否该整理。
     *
     * 触发条件（任一）：到 every 回合 / 注入被截断（预算超了）/ 行数超过 maxLines 软阈值 /
     * 首次读到超预算的文件（升级首次运行，与「截断」同一判据，无需第二套状态）。
     */
    private fun onTurnEnd(event: AgentEvent.TurnEnd) {
        val cadence = cadence ?: return
        val total = cadence.onTurnEnd()
        val store = settings
        if (!store.getBoolean(KEY_AUTO, MemoryStore.DEFAULT_AUTO)) return
        val sessionId = event.sessionId ?: return
        val workspace = event.workspace
        if (workspace.isBlank()) return
        val snapshot = MemoryStore.settingsOf(store)
        val file = MemoryStore.fileOf(workspace)
        val text = runCatching { MemoryStore.readFile(file) }.getOrNull() ?: return
        val rendered = MemoryStore.render(MemoryStore.parse(text), LocalDate.now(), snapshot)
        val due = cadence.due(snapshot.every)
        val overBudget = rendered.dropped > 0
        val overLines = MemoryStore.lineCount(text) > snapshot.maxLines
        if (!(due || overBudget || overLines)) return
        launch(sessionId, workspace, total)
    }

    private fun launch(sessionId: String, workspace: String, total: Int) {
        // 唯一的前台判定点（自动整理与槽位手动入口都走这里）：别的会话在前台时一律推迟。
        if (deps.activeSessionId() != sessionId) return
        if (!running.compareAndSet(false, true)) return
        val scope = scope
        if (scope == null) {
            running.set(false)
            return
        }
        scope.launch {
            try {
                consolidate(sessionId, workspace, total)
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * 跑一次整理（内部可见，供单测直接驱动；生产路径只经 [launch]）。
     *
     * 顺序：读原文件并备份 → 取增量摘录 → 子智能体直接编辑文件 → 读回文件机械校验 →
     * 不达标按原始字节回滚并置「未变短」标记（下一次注入的维护块会点名）。
     */
    internal suspend fun consolidate(sessionId: String, workspace: String, total: Int) {
        // 结构性兜底：从排队到真正开跑之间用户可能切了会话，进门前再核一次。
        if (deps.activeSessionId() != sessionId) return
        val store = settings
        val snapshot = MemoryStore.settingsOf(store)
        val file = MemoryStore.fileOf(workspace)
        val existed = file.isFile
        val before = runCatching { MemoryStore.readFile(file) }.getOrElse { return }
        val messages = runCatching { deps.loadMessages(sessionId) }.getOrDefault(emptyList())
        val watermarkKey = KEY_WATERMARK + sessionId
        var watermark = store.getInt(watermarkKey, 0)
        val turns = MemoryStore.eligibleTurnCount(messages)
        // 压缩重写会缩短历史：水位高于当前回合数时重基准为 0（宁可多整理一次，也不静默停更）。
        if (watermark > turns) watermark = 0
        val digest = MemoryStore.digest(messages, watermark)
        if (before.isBlank() && digest.isBlank()) return
        val today = LocalDate.now().toString()
        val path = file.absolutePath
        val prompt = if (before.isBlank()) {
            MemoryPrompts.appendPrompt(path, digest, today)
        } else {
            MemoryPrompts.mergePrompt(path, before, digest, today)
        }

        MemoryStore.backup(file)
        val bridge = agents ?: return
        val result = try {
            bridge.runSubagent(
                prompt = prompt,
                type = "coding",
                timeoutMillis = CONSOLIDATION_TIMEOUT_MS,
                modelId = store.getString(KEY_MODEL, "").ifBlank { null },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "${SubagentManager.ERROR_PREFIX} ${e.javaClass.simpleName}"
        }
        // 返回值**只判失败**：绝不当记忆正文写回文件（B 层第 11 条把这条钉死）。
        val failed = result.startsWith(SubagentManager.ERROR_PREFIX)
        val after = runCatching { MemoryStore.readFile(file) }.getOrDefault(before)
        val changed = after != before
        val accepted = !failed && MemoryStore.acceptConsolidation(before, after)
        if (accepted) {
            store.setInt(KEY_LAST_CONSOLIDATED, total)
            store.setInt(watermarkKey, turns)
            store.setBoolean(KEY_ROLLED_BACK, false)
        } else {
            // 子智能体跑挂时如果文件被动过（半途写入），同样按原始字节回滚。
            if (changed) MemoryStore.restore(file, before, existed)
            if (before.isNotBlank()) store.setBoolean(KEY_ROLLED_BACK, true)
        }
        // 无论成败都推进「上次尝试」：失败后等下一个 every 窗口，而不是每回合重试。
        store.setInt(KEY_LAST_ATTEMPT, total)
        deps.invalidatePrompt(workspace)
    }

    companion object {
        const val ID = "dev.kair.plugin.memory"

        /** 段落 id / 位置（rules=20 之后、addendum=30 之前）。 */
        const val SECTION_ID = "dev.kair.prompt.memory"
        const val SECTION_ORDER = 25

        const val KEY_AUTO = "memory.auto"
        const val KEY_EVERY = "memory.every"
        const val KEY_MAX_LINES = "memory.maxLines"
        const val KEY_MAX_CHARS = "memory.maxChars"
        const val KEY_MODEL = "memory.model"
        const val KEY_RETENTION_DAYS = "memory.retentionDays"
        const val KEY_FILE = "memory.file"
        const val KEY_CONSOLIDATE = "memory.consolidate"

        /** 跨项目查看面板 id（面板坞的「记忆」格子直达这里）。 */
        const val PANEL_ID = "panels.memory"

        const val KEY_TOTAL = "memory.state.total"
        const val KEY_LAST_CONSOLIDATED = "memory.state.lastConsolidated"
        const val KEY_LAST_ATTEMPT = "memory.state.lastAttempt"
        const val KEY_ROLLED_BACK = "memory.state.rolledBack"
        const val KEY_WATERMARK = "memory.state.watermark."

        /**
         * 整理子智能体的硬上界：30 分钟（技术负责人 2026-11-09 拍：5 分钟不够用；
         * 超时按失败处理，文件由宿主校验后再决定去留）。
         */
        const val CONSOLIDATION_TIMEOUT_MS = 1_800_000L
    }
}

/**
 * 到点判定（纯逻辑，供单测）。
 *
 * `total` 是插件自己在 `TurnEnd` 上自增的持久化计数；`lastConsolidated` / `lastAttempt`
 * 是上次成功 / 上次尝试的 `total` 水位。它们都不来自会话历史，因此压缩重写历史不会让
 * 计数倒退。
 */
internal class MemoryCadence(private val settings: PluginSettingsStore) {

    val total: Int get() = settings.getInt(MemoryPlugin.KEY_TOTAL, 0)
    val lastConsolidated: Int get() = settings.getInt(MemoryPlugin.KEY_LAST_CONSOLIDATED, 0)
    val lastAttempt: Int get() = settings.getInt(MemoryPlugin.KEY_LAST_ATTEMPT, 0)

    fun onTurnEnd(): Int {
        val next = total + 1
        settings.setInt(MemoryPlugin.KEY_TOTAL, next)
        return next
    }

    /**
     * 到点判据：距离**上次尝试**（成功时与 lastConsolidated 同步推进）至少 [every] 个用户回合。
     * 失败的那次也计入水位，避免整理失败后每回合重试。
     */
    fun due(every: Int): Boolean =
        total - maxOf(lastConsolidated, lastAttempt) >= every.coerceAtLeast(1)
}

/** 记忆插件的最小宿主依赖面：生产走 [ContainerMemoryDeps]，单测注入假实现。 */
internal interface MemoryDeps {
    fun loadMessages(sessionId: String): List<ChatMessage>
    fun activeSessionId(): String?
    fun invalidatePrompt(workspace: String)
    fun recordNotice(workspace: String, text: String)
    fun clearNotices()
}

private class ContainerMemoryDeps(private val container: AppContainer) : MemoryDeps {
    override fun loadMessages(sessionId: String): List<ChatMessage> =
        container.storage.loadMessages(sessionId)

    override fun activeSessionId(): String? = container.activeSessionId

    override fun invalidatePrompt(workspace: String) =
        SystemPromptBuilder.invalidate(workspace)

    override fun recordNotice(workspace: String, text: String) =
        container.diagnostics.recordNotice("memory:$workspace", text)

    override fun clearNotices() = container.diagnostics.clearNotices("memory:")
}
