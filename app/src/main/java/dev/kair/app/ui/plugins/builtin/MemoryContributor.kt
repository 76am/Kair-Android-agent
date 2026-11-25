package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.agent.SystemPromptContext
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.plugin.PluginSettingsStore
import java.io.IOException
import java.time.LocalDate

/**
 * 记忆段贡献点（纯渲染，不写文件、不做副作用）。
 *
 * 位置：`order = 25` —— 在 `rules`（20）之后、`addendum`（30）之前；段 id 为
 * `dev.kair.prompt.memory`，由内置插件 `dev.kair.plugin.memory` 注册（不在
 * `BuiltinPromptSections` 里：那张表只装内核自带段落，插件贡献点不属于它）。
 *
 * 缓存契约：正文只由「文件内容 + 设置 + 当天日期」决定，同一输入两次渲染逐字节相等；
 * 段内不出现当天日期。文件被改后由插件 / 工具执行路径主动失效段落缓存。
 */
class MemoryContributor(
    private val settings: PluginSettingsStore,
    /** 诊断面（无效时可传空实现）：只记「过期隐藏 / 预算截断 / 读失败」，不参与正文。 */
    private val onNotice: (workspace: String, text: String) -> Unit = { _, _ -> },
    private val today: () -> LocalDate = { LocalDate.now() },
) : SystemPromptContributor {

    override val id = MemoryPlugin.SECTION_ID
    override val order = MemoryPlugin.SECTION_ORDER

    override fun section(ctx: SystemPromptContext): String {
        if (ctx.workspace.isBlank()) return ""
        val file = MemoryStore.fileOf(ctx.workspace)
        val text = try {
            MemoryStore.readFile(file)
        } catch (e: IOException) {
            // 明确诊断而不是空白记忆段：面板能看到原因，模型这一回合看不到记忆（不冒充「没有记忆」）。
            onNotice(ctx.workspace, "记忆文件不可读（${e.javaClass.simpleName}），可在「设置 → 工作区文件权限」修复")
            throw e
        }
        val snapshot = MemoryStore.settingsOf(settings)
        val rolledBack = settings.getBoolean(MemoryPlugin.KEY_ROLLED_BACK, false)
        val rendered = MemoryStore.render(
            MemoryStore.parse(text),
            today(),
            snapshot,
            if (rolledBack) listOf(MemoryPrompts.rolledBack()) else emptyList(),
        )
        val notices = buildList {
            if (rendered.expired > 0) {
                add("已隐藏 ${rendered.expired} 条过期近期条目（>${snapshot.retentionDays} 天）")
            }
            if (rendered.dropped > 0) {
                add("已截断 ${rendered.dropped} 条记忆（超出 ${snapshot.maxChars} 字预算）")
            }
        }
        onNotice(ctx.workspace, notices.joinToString("；"))
        return rendered.text
    }
}
