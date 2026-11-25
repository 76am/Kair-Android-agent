package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.agent.SystemPromptContext
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.plugin.ChoiceOption
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * 由 [PluginManifest] 解释出来的插件。宿主在装配期调用 [activate] 注册贡献点；
 * 不涉及任何代码加载。
 */
class DynamicPlugin(
    private val manifest: PluginManifest,
    private val dir: File,
    private val skills: SkillRegistry,
) : Plugin {
    override val id = manifest.id
    override val name = manifest.name
    override val description = manifest.description

    override fun activate(ctx: PluginContext) {
        // 脚本工具能读到自己插件的设置值：把值以 JSON 放进 KAIR_SETTINGS。
        val settingsProvider = { settingsJson(manifest.settings, ctx.settings) }
        manifest.tools.forEach { spec -> ctx.tool(ScriptTool(spec, dir, settingsProvider)) }
        manifest.prompts.forEach { spec -> ctx.systemPrompt(StaticPromptContributor(spec)) }
        manifest.skills?.takeIf { it.isNotBlank() }?.let { rel ->
            skills.addRoot(File(dir, rel))
        }
        // 声明式设置面板：宿主用内置控件渲染，用户点击进入即可配置。
        manifest.settings.takeIf { it.isNotEmpty() }?.let { specs ->
            ctx.settingsPanel(
                SettingsPanelSpec(
                    id = "settings-${manifest.id}",
                    title = manifest.settingsTitle ?: manifest.name,
                    // 不指图标：`core` 不许反向依赖 `ui`（ArchitectureGuardTest）。
                    // 宿主在渲染设置面板时补默认齿轮（`PluginsScreen.PanelTitle`）。
                    icon = null,
                    // 清单声明 true 时，该面板同时在「设置」页显示（双入口）。
                    showInSettings = manifest.settingsShowInSettings,
                    fields = specs.map { it.toSettingField() },
                ),
            )
        }
    }

    /** 卸载时解除本插件注册的技能根；单例能力（若有）不回滚，需重启。 */
    fun deactivate() {
        manifest.skills?.takeIf { it.isNotBlank() }?.let { rel ->
            skills.removeRoot(File(dir, rel))
        }
    }

    fun toolNames(): List<String> = manifest.tools.map { it.name }
}

/** 常量文本的系统提示段落。 */
class StaticPromptContributor(private val spec: PromptSpec) : SystemPromptContributor {
    override val id = spec.id
    override val order = spec.order
    override fun section(ctx: SystemPromptContext): String? = spec.text.trim().ifEmpty { null }
}

/**
 * 脚本工具的宿主侧实现：把参数塞进环境变量、以 `sh -c` 跑插件脚本。
 *
 * `sh` 由**当前命令后端**解析 —— proot 就绪时即 Ubuntu 的 `/bin/sh`，
 * 否则是 Android 的 toybox，二者对 `sh -c` 与 `$VAR` 语义一致。
 */
class ScriptTool(
    spec: ToolSpec,
    private val dir: File,
    /** 声明式设置的值（JSON 字符串）提供者；null 表示该插件无设置。 */
    private val settingsProvider: (() -> String)? = null,
) : Tool {
    private val spec = spec
    private val scriptFile = File(dir, spec.script)

    override val name = spec.name
    override val description = spec.description
    override val promptSnippet = spec.snippet
    override val promptGuidelines = spec.guidelines
    override val category = ToolCategory.from(spec.category)

    /**
     * 未声明 / 拼错类别的第三方工具默认需审批：按**解析后**的类别判定，
     * 否则 `"wirte"` 这种拼写会被当成已声明并静默免审。
     */
    override val requiresApproval = spec.requiresApproval || category == ToolCategory.UNSPECIFIED
    override val approvableForever = spec.approvableForever

    override val parameters: JsonObject = spec.parameters ?: buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val script = scriptFile.takeIf { it.isFile }?.readText()
            ?: return ToolResult("Plugin script not found: ${spec.script}", isError = true)
        if (script.isBlank()) return ToolResult("Plugin script is empty: ${spec.script}", isError = true)

        val command = buildString {
            append("KAIR_ARGS=").append(shellQuote(args.toString())).append(' ')
            append("KAIR_TOOL=").append(shellQuote(name)).append(' ')
            settingsProvider?.let { append("KAIR_SETTINGS=").append(shellQuote(it())).append(' ') }
            append("sh -c ").append(shellQuote(script))
        }
        val out = ctx.commandRunner.run(command, ctx.workspace, spec.timeoutSeconds)
        val body = out.output.trim()
        return when {
            out.timedOut -> ToolResult("Plugin tool timed out.${if (body.isEmpty()) "" else "\n$body"}", isError = true)
            out.exitCode != 0 -> ToolResult(
                body.ifEmpty { "Plugin tool exited with code ${out.exitCode}" },
                isError = true,
            )
            else -> ToolResult(body)
        }
    }
}

/** POSIX 单引号转义：安全地把任意文本嵌进 shell 命令。 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

/** 清单里的设置字段 → SDK 声明式字段（宿主 UI 据此渲染内置控件）。 */
private fun SettingSpec.toSettingField(): SettingField = when (type.lowercase()) {
    "number" -> SettingField.Number(key, label, description, defaultInt ?: 0, min, max)
    "text" -> SettingField.Text(key, label, description, defaultText.orEmpty())
    "choice" -> SettingField.Choice(
        key, label, description,
        options.map { ChoiceOption(it.value, it.label) },
        defaultText ?: options.firstOrNull()?.value.orEmpty(),
    )
    else -> SettingField.Toggle(key, label, description, defaultBool ?: false)
}

/** 把插件的设置值拼成 JSON，供脚本工具经 `KAIR_SETTINGS` 读取。 */
private fun settingsJson(specs: List<SettingSpec>, store: PluginSettingsStore): String =
    buildJsonObject {
        specs.forEach { s ->
            when (s.type.lowercase()) {
                "number" -> put(s.key, JsonPrimitive(store.getInt(s.key, s.defaultInt ?: 0)))
                "toggle" -> put(s.key, JsonPrimitive(store.getBoolean(s.key, s.defaultBool ?: false)))
                else -> put(s.key, JsonPrimitive(store.getString(s.key, s.defaultText.orEmpty())))
            }
        }
    }.toString()
