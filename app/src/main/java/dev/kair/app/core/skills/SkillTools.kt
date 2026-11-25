package dev.kair.app.core.skills

import dev.kair.app.core.agent.SystemPromptContext
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.strProp
import kotlinx.serialization.json.JsonObject

/**
 * 技能索引段（系统提示第 15 段，插在「工具清单」与「规则」之间）：
 * 只列**名称 + 描述**，正文不进前缀。技能集合为空时整段不贡献。
 */
class SkillsContributor(private val registry: SkillRegistry) : SystemPromptContributor {
    override val id = "dev.kair.skills.index"
    override val order = 15

    override fun section(ctx: SystemPromptContext): String? {
        val skills = registry.list()
        if (skills.isEmpty()) return null
        val listing = skills.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "(no description)" }}" }
        return "<skills>\n" +
            "The following skills are available. When a task matches a skill's description, " +
            "call the `skill` tool with its name to load the full instructions before proceeding.\n" +
            "$listing\n</skills>"
    }
}

/**
 * `skill` 工具：按名称读回一个技能的正文。
 *
 * 这是渐进式披露的入口 —— 正文经由工具结果进入上下文（尾部追加），
 * 因此不影响系统提示的缓存稳定前缀。
 */
class SkillTool(private val registry: SkillRegistry) : Tool {
    override val name = "skill"

    // 只读：仅按名称读回技能正文，不改动任何外部状态，可放进只读子智能体（search 类型）。
    override val category = ToolCategory.READ
    override val readOnly = true
    override val description =
        "Load the full instructions for an available skill by name. " +
            "Call this when a task matches a skill listed in the system prompt."
    override val promptSnippet = "load a skill's full instructions by name"
    override val promptGuidelines = listOf(
        "When a task matches an available skill's description, load it with the `skill` tool before acting",
    )
    override val parameters: JsonObject = schemaOf(
        props("name" to strProp("Skill name exactly as listed in the system prompt")),
        "name",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.reqStr("name")
        val skill = registry.find(name)
            ?: return ToolResult(
                "Unknown skill: $name. Available: ${registry.list().joinToString(", ") { it.name }.ifEmpty { "(none)" }}",
                isError = true,
            )
        // 附带真实目录：技能可能来自用户技能根或插件自带根，模型不该靠猜路径。
        val dirLine = skill.dir?.let { "\n\nSkill directory: $it" }.orEmpty()
        return ToolResult(skill.body + dirLine)
    }
}
