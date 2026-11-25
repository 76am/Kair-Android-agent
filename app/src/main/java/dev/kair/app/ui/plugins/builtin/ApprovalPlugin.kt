package dev.kair.app.ui.plugins.builtin

import dev.kair.app.R
import dev.kair.app.app.AppContainer
import dev.kair.app.core.agent.ApprovalDecision
import dev.kair.app.core.agent.ApprovalRequest
import dev.kair.app.core.model.newId
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginSettingsStore
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.core.plugin.ToolCallHook
import dev.kair.app.core.plugin.ToolCallOutcome
import dev.kair.app.core.tools.PathUtils
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.str
import dev.kair.app.ui.icons.PluginGlyph
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 审批插件：把 [ApprovalGate] 挂到 `tool_call` 钩子上。
 *
 * 声明了 `requiresApproval` 的工具在执行前会**挂起等待用户决定**；拒绝时
 * 复用钩子既有的阻断语义（blockReason 作为错误结果回给模型）。策略是强制的
 * —— 不依赖提示词自觉。
 *
 * **为什么平时很难看到审批弹窗**：内置工具里只有 `install_plugin` /
 * `uninstall_plugin` 声明了 `requiresApproval = true`（写文件 / 跑命令都不声明）。
 * 因此不安装插件就永远不会弹。为此本插件提供一块**设置面板**，让用户按需把
 * container_shell（容器）/ android_shell（设备）/ 文件写入 / 全部工具**分别**纳入审批（见 [activate]）。
 *
 * 此外本插件**默认拦截删除类命令**（`rm` / `rmdir` / `unlink` / `shred` / `find -delete`，
 * 以及「先写个删除脚本再执行」的常见绕过）：命中即弹确认，且**不允许永久放行** ——
 * 要长期放行只能在设置面板里显式关掉该开关。
 *
 * 放行归类键按**调用内容**生成（`<approvalKey>:<命令或参数>`）：放行 `container_shell + ls -la` 之后，
 * `ls -l`、`rm -rf /tmp` 仍会弹确认 —— 粒度是「这一条命令」而非「这个工具」。危险命令在此
 * 基础上追加**独立后缀** `#danger`，与普通命令通道互不干扰：放行某条普通命令不会顺带放行
 * 危险命令，反之亦然。
 *
 * 工具解析使用**执行注册表**（`container.tools`），与 AgentSession 实际执行的
 * 是同一份，因此直接注册的工具（如 install_plugin 本身）同样受审批约束。
 */
class ApprovalPlugin(private val container: AppContainer) : Plugin {
    override val id = ID
    override val name = "审批"
    override val description = "对声明需审批的工具调用弹出用户确认（本次 / 本会话 / 永久）。"
    override val core = true

    override fun activate(ctx: PluginContext) {
        ctx.onToolCall { hook ->
            val tool = container.tools.get(hook.name) ?: return@onToolCall null
            val store = container.pluginSettings(id)
            val gate = container.approvalGate

            // 子智能体的工具调用：默认同样审批，但卡片要挂到**顶层父会话**，否则父会话
            // 按自己的 id 过滤 pending 时看不到卡片，子智能体就会永久挂起。
            val subagent = hook.sessionId?.let { container.subagents.info(it) }
            // 用户显式关闭「拦截子智能体」时彻底不拦（含危险命令）—— 适合无人监督的并行任务。
            if (subagent != null && !store.getBoolean(KEY_SUBAGENT, true)) return@onToolCall null

            // 危险命令优先：默认开启（见 [KEY_DANGER]），命中就用独立键走审批。
            val danger = if (store.getBoolean(KEY_DANGER, true)) dangerousReason(hook) else null
            if (danger == null &&
                !needsApproval(tool.name, tool.requiresApproval, tool.category, store)
            ) {
                return@onToolCall null
            }

            val key = approvalRuleKey(tool.approvalKey, hook.name, hook.arguments, danger != null)
            if (gate.isAllowed(key, hook.sessionId)) return@onToolCall null

            val request = ApprovalRequest(
                id = newId(),
                ruleKey = key,
                summary = if (danger != null) {
                    "危险操作待确认（$danger）：${tool.name}"
                } else {
                    summaryFor(hook)
                },
                arguments = hook.arguments.toString(),
                sessionId = hook.sessionId,
                // 危险操作 / 删除类工具都不允许永久放行：要长期放行只能去面板关掉对应开关。
                rememberable = tool.approvableForever &&
                    danger == null &&
                    tool.category != ToolCategory.DELETE,
                // 父会话兜底用 activeSessionId：极端情况下拿不到委派链也不能让卡片消失。
                ownerSessionId = subagent?.let {
                    container.subagents.rootSessionOf(it.id) ?: container.activeSessionId
                },
                originLabel = subagent?.name,
            )
            when (gate.request(request)) {
                ApprovalDecision.DENY -> ToolCallOutcome(
                    hook.arguments,
                    blockReason = if (danger != null) {
                        "The user denied this destructive operation. Do NOT work around the approval " +
                            "gate (no wrapper scripts, no alternative binaries, no encoded commands). " +
                            "Explain why the deletion is needed and let the user decide."
                    } else {
                        "The user denied this `${tool.name}` call. Do not retry; explain the situation " +
                            "and ask the user how to proceed."
                    },
                )
                // 超时按「未批准」处理：调用结束、原因回给模型，不让会话 / 子智能体继续悬着。
                ApprovalDecision.TIMEOUT -> ToolCallOutcome(
                    hook.arguments,
                    blockReason = "Approval timed out after ${gate.timeoutSeconds}s: the user did not " +
                        "respond, so this `${tool.name}` call was NOT executed. Ask the user whether " +
                        "to run it again before retrying.",
                )
                else -> null
            }
        }

        ctx.settingsPanel(
            SettingsPanelSpec(
                id = "settings.approval",
                title = "工具调用审批",
                icon = PluginGlyph.Lock,
                order = 20,
                fields = listOf(
                    SettingField.Toggle(
                        key = KEY_DANGER,
                        labelRes = R.string.approval_danger_label,
                        descriptionRes = R.string.approval_danger_desc,
                        default = true,
                    ),
                    SettingField.Toggle(
                        key = KEY_SUBAGENT,
                        labelRes = R.string.approval_subagent_label,
                        descriptionRes = R.string.approval_subagent_desc,
                        default = true,
                    ),
                    SettingField.Toggle(
                        key = KEY_BASH,
                        labelRes = R.string.approval_shell_label,
                        descriptionRes = R.string.approval_shell_desc,
                        default = false,
                    ),
                    SettingField.Toggle(
                        key = KEY_LOCAL_SHELL,
                        labelRes = R.string.approval_local_shell_label,
                        descriptionRes = R.string.approval_local_shell_desc,
                        default = false,
                    ),
                    SettingField.Toggle(
                        key = KEY_WRITE,
                        labelRes = R.string.approval_write_label,
                        descriptionRes = R.string.approval_write_desc,
                        default = false,
                    ),
                    SettingField.Toggle(
                        key = KEY_EXECUTE,
                        labelRes = R.string.approval_execute_label,
                        descriptionRes = R.string.approval_execute_desc,
                        default = false,
                    ),
                    SettingField.Toggle(
                        key = KEY_ALL,
                        label = "所有工具都需审批",
                        description = "为每一个工具调用弹确认（含只读工具），最严格。默认关闭。",
                        default = false,
                    ),
                    SettingField.Action(
                        key = "clear_rules",
                        label = "放行规则",
                        description = "清除「本会话」与「永久」放行记录，恢复为每次询问。",
                        buttonLabel = "清空放行规则",
                        onClick = {
                            // 会话级放行只清当前会话，避免 A 的操作撤销 B 的放行。
                            container.activeSessionId?.let { container.approvalGate.clearSessionRules(it) }
                            container.approvalGate.clearAlwaysRules()
                        },
                    ),
                ),
            ),
        )
    }

    /**
     * 危险命令判定：命令文本命中删除类模式（带「明显临时目标豁免」，见 [DangerCommand.commandReason]），
     * 或它引用到的**脚本文件内容**命中（后者用来兜「先写个删除脚本、再 `sh script` 执行」这类
     * 文本级绕过；脚本运行期 cwd 未知，只能保守判定）。返回面向用户的说明；null = 未命中。
     */
    private fun dangerousReason(hook: ToolCallHook): String? {
        if (hook.name !in SHELL_TOOLS) return null
        val command = hook.arguments.str("command") ?: return null
        DangerCommand.commandReason(command, hook.workspace)?.let { return "命令里有$it" }
        for (path in DangerCommand.scriptPaths(command)) {
            val text = scriptText(hook.workspace, path) ?: continue
            DangerCommand.match(text)?.let { return "脚本 $path 里有$it" }
        }
        return null
    }

    /** 读出命令引用的脚本文件内容；不存在、不是普通文件、过大或读失败都返回 null。 */
    private fun scriptText(workspace: String?, path: String): String? {
        val file = runCatching {
            if (workspace != null) File(PathUtils.resolveToCwd(path, workspace)) else File(path)
        }.getOrNull() ?: return null
        if (!file.isFile || file.length() > MAX_SCRIPT_BYTES) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /**
     * 审批摘要：安装/卸载插件时先走 [dev.kair.app.core.plugin.dynamic.DynamicPluginHost]
     * 的预览（清单里有什么、会替换什么），失败回退到通用摘要。
     */
    private fun summaryFor(hook: ToolCallHook): String {
        val args = hook.arguments
        return when (hook.name) {
            "install_plugin" -> {
                val id = args.str("id") ?: "?"
                val source = args.str("source") ?: "plugins/$id"
                val src = hook.workspace?.let { File(PathUtils.resolveToCwd(source, it)) }
                src?.let { container.dynamicPlugins.previewInstall(it) }
                    ?: "安装插件：$id"
            }
            "uninstall_plugin" -> {
                val id = args.str("id") ?: "?"
                container.dynamicPlugins.previewUninstall(id)
                    ?: "卸载插件：$id"
            }
            else -> "执行工具 `${hook.name}`"
        }
    }

    companion object {
        const val ID = "dev.kair.plugin.approval"

        /** 参与脚本内容扫描的文件大小上限；超过就放弃扫描（宁可漏，也不读大文件）。 */
        private const val MAX_SCRIPT_BYTES = 64L * 1024
    }
}

private const val KEY_DANGER = "approve_dangerous"
private const val KEY_SUBAGENT = "approve_subagent"
private const val KEY_BASH = "approve_bash"
private const val KEY_LOCAL_SHELL = "approve_local_shell"
private const val KEY_WRITE = "approve_write"
private const val KEY_EXECUTE = "approve_execute"
private const val KEY_ALL = "approve_all"

/** 容器命令通道：仅在 proot rootfs 就绪时注册。 */
private const val BASH_TOOL = "container_shell"

/** 设备系统命令通道：始终注册，作为容器不可用时的兜底。 */
private const val LOCAL_SHELL_TOOL = "android_shell"

/**
 * 设备 adb 通道（uid 2000）：已配对时注册，跑的是真机 toybox。参数名同为 `command`。
 *
 * 它不在 [needsApproval] 的开关表里（没有对应的面板项），但**必须**进 [SHELL_TOOLS]：
 * 这条通道执行的是真实的 `rm` / `dd` / `pm uninstall`，漏掉它等于给危险命令判定开一个后门
 * —— 模型只要改走 `device_shell` 就能绕开审批弹窗，而它比 `android_shell` 权限还高一档。
 */
private const val DEVICE_SHELL_TOOL = "device_shell"

/**
 * 三条命令通道：危险命令判定与「命令内容归类键」都按它们进行，漏掉任一条都等于给
 * 审批开后门。注意**面板开关**是分开的（[KEY_BASH] / [KEY_LOCAL_SHELL]），见 [needsApproval]。
 */
private val SHELL_TOOLS = setOf(BASH_TOOL, LOCAL_SHELL_TOOL, DEVICE_SHELL_TOOL)

private val FILE_WRITE_TOOLS = setOf("write", "edit")

/** 危险命令的独立归类键后缀：让它的放行规则与普通命令通道互不影响。 */
private const val DANGER_SUFFIX = "#danger"

/**
 * 有效审批判定：工具自身声明 > 类别开关 > 未声明时的工具名兼容规则。
 *
 * - `requiresApproval = true` 永远审批（安装 / 卸载插件、动态插件未声明类别的工具）；
 * - 声明了 [ToolCategory] 的按类别开关：READ 不拦，WRITE 看 [KEY_WRITE]，DELETE 看
 *   [KEY_DANGER]，EXECUTE 按通道（container_shell / android_shell）或 [KEY_EXECUTE]；
 * - 未声明类别时沿用旧规则（按工具名），保证内置工具行为不变。
 *
 * `container_shell`（容器）与 `android_shell`（设备）是**两个独立开关**：想只拦一条通道就只开那个，
 * 而不是像以前一开就同时拦两条。危险命令的默认拦截（[KEY_DANGER]）与这里无关，它始终
 * 覆盖两条通道。
 *
 * 纯函数、无宿主依赖，便于单测（见 `NeedsApprovalTest`）。
 */
internal fun needsApproval(
    toolName: String,
    requiresApproval: Boolean,
    category: ToolCategory,
    store: PluginSettingsStore,
): Boolean {
    if (requiresApproval) return true
    if (store.getBoolean(KEY_ALL, false)) return true
    return when (category) {
        ToolCategory.READ -> false
        ToolCategory.WRITE -> store.getBoolean(KEY_WRITE, false)
        // 与面板 / 危险命令路径保持一致：删除类默认就是需要审批的。
        ToolCategory.DELETE -> store.getBoolean(KEY_DANGER, true)
        ToolCategory.EXECUTE -> when (toolName) {
            BASH_TOOL -> store.getBoolean(KEY_BASH, false)
            LOCAL_SHELL_TOOL -> store.getBoolean(KEY_LOCAL_SHELL, false)
            else -> store.getBoolean(KEY_EXECUTE, false)
        }
        // 未声明类别：内置工具按工具名沿用原有开关；第三方工具在装载时已被补成需审批。
        ToolCategory.UNSPECIFIED -> when {
            toolName == BASH_TOOL -> store.getBoolean(KEY_BASH, false)
            toolName == LOCAL_SHELL_TOOL -> store.getBoolean(KEY_LOCAL_SHELL, false)
            toolName in FILE_WRITE_TOOLS -> store.getBoolean(KEY_WRITE, false)
            else -> false
        }
    }
}

/**
 * 生成放行归类键：按**调用内容**而非仅工具名归类，粒度从「放行 container_shell」收窄到「放行这条命令」。
 *
 * - shell 工具取 `command` 字段正文，因此「同样命令、不同 timeout」归为同一键，而
 *   `ls -la` 与 `ls -l` 是两个键（互不牵连）。
 * - 其余工具取完整参数 JSON 文本。
 * - 危险命令在末尾追加 [DANGER_SUFFIX]，与普通命令通道互不干扰：放行 `ls -la` 不会
 *   顺带放行危险命令，反之亦然。
 *
 * 纯函数、无宿主依赖，便于单测（见 `ApprovalRuleKeyTest`）。
 */
internal fun approvalRuleKey(
    approvalKey: String,
    toolName: String,
    arguments: JsonObject,
    danger: Boolean,
): String {
    val commandKey = if (toolName in SHELL_TOOLS) {
        arguments.str("command") ?: arguments.toString()
    } else {
        arguments.toString()
    }
    val base = "$approvalKey:$commandKey"
    return if (danger) base + DANGER_SUFFIX else base
}
