package dev.kair.app.core.plugin.dynamic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 动态插件清单（`plugins/<id>/manifest.json`，或 `.kplug` 包内同名文件）。
 *
 * 两种形态（[kind]）：
 *
 * - `script`（默认）：**纯数据 + 脚本**。宿主解释清单注册贡献点，不加载任何代码，
 *   不受 Compose 版本耦合影响。支持的工具 / 提示段 / 技能见各字段。
 * - `dex`：**代码插件**（`.kplug` 包 = 清单 + `classes.dex`）。宿主用 DexClassLoader
 *   装载并实例化 [entryClass]（实现 `dev.kair.plugin.api.Plugin`），贡献点由其
 *   `activate()` 注册。能力上限更高（可替换单例后端 / 提供传输层），安装同样
 *   强制审批。契约见 `docs/插件接口契约.md`。
 *
 * 通用约束：清单的 `id` 必须与安装目录名一致；`targetApi` 高于宿主
 * `KairApi.VERSION` 的 dex 插件会被拒绝装载（前向兼容）。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "0.1.0",
    /** 插件形态：`script` 或 `dex`。 */
    val kind: String = KIND_SCRIPT,
    /** dex 插件：实现 `dev.kair.plugin.api.Plugin` 的入口类全名。 */
    val entryClass: String? = null,
    /** dex 插件：目标插件 API 大版本。 */
    val targetApi: Int = 1,
    /**
     * 兼容性声明：要求的最低 Kair 应用版本（`major.minor.patch`，含）。缺省不限制。
     * 宿主版本低于此值时判定为「版本过低」，安装前拦截（严格模式）。
     */
    val minKairVersion: String? = null,
    /**
     * 兼容性声明：支持的最高 Kair 应用版本（`major.minor.patch`，含）。缺省不限制。
     * 宿主版本高于此值时判定为「版本过高」，安装前拦截（严格模式）。
     */
    val maxKairVersion: String? = null,
    /** script 插件：脚本工具列表。 */
    val tools: List<ToolSpec> = emptyList(),
    /** script 插件：静态系统提示段落。 */
    val prompts: List<PromptSpec> = emptyList(),
    /** script 插件：相对插件目录的技能根，如 `"skills"`；缺省表示无技能。 */
    val skills: String? = null,
    /** script 插件：声明式设置面板字段（数值 / 开关 / 文本 / 下拉），宿主用内置控件渲染。 */
    val settings: List<SettingSpec> = emptyList(),
    /** script 插件：设置面板标题；缺省用 [name]。 */
    val settingsTitle: String? = null,
    /**
     * script 插件：设置面板是否同时在「设置」页用横线分区渲染（双入口）。
     * 缺省 false，只在插件详情页显示。dex 插件走 SDK 的
     * `SettingsPanelSpec.showInSettings`。
     */
    val settingsShowInSettings: Boolean = false,
) {
    companion object {
        const val KIND_SCRIPT = "script"
        const val KIND_DEX = "dex"
    }
}

/**
 * 一个脚本工具（script 插件）。
 *
 * 执行方式：宿主把调用参数以 JSON 放进环境变量 `KAIR_ARGS`，把工具名放进
 * `KAIR_TOOL`，把本插件的全部设置值（JSON）放进 `KAIR_SETTINGS`，再以
 * `sh -c <script>` 运行脚本正文。脚本用 `$KAIR_ARGS` 取参、用 stdout 返回
 * 结果、用退出码表示成败。
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    /** 相对插件目录的脚本文件，如 `"echo.sh"`。 */
    val script: String,
    /** JSON Schema 参数定义；缺省为空对象 schema。 */
    val parameters: JsonObject? = null,
    /** 一行摘要，进系统提示的工具清单。 */
    val snippet: String = "",
    /** 行为守则，去重后进系统提示的规则段。 */
    val guidelines: List<String> = emptyList(),
    /**
     * 工具类别：`read` / `write` / `delete` / `execute`。审批面板按类别开关决定
     * 是否弹窗；**未声明时该工具默认需要审批**（想免审批必须显式声明类别）。
     */
    val category: String? = null,
    /**
     * 工具自身**强制**审批；默认 false。false 不会关闭面板的类别开关；
     * 未声明 [category] 时本字段会被装载层补成 true（保守默认）。
     * 通常不需要写：声明 category 即可由用户的类别开关控制。
     */
    val requiresApproval: Boolean = false,
    /** 是否允许永久放行；危险工具应置 false。 */
    val approvableForever: Boolean = true,
    /** 超时秒数；null 表示由后端默认。 */
    val timeoutSeconds: Long? = null,
)

/** 一个静态系统提示段落（文本为常量，天然满足缓存前缀的确定性要求）。 */
@Serializable
data class PromptSpec(
    val id: String,
    val order: Int = 50,
    val text: String,
)

/**
 * script 插件的一个设置字段（声明式数据）。宿主按 [type] 用内置控件渲染：
 *
 * - `toggle`：开关，值取 [defaultBool]；
 * - `number`：整数输入，值取 [defaultInt]，可带 [min] / [max]；
 * - `text`：单行文本，值取 [defaultText]；
 * - `choice`：下拉选择，候选项见 [options]，值取 [defaultText]（缺省取首项）。
 */
@Serializable
data class SettingSpec(
    val key: String,
    val label: String,
    /** 控件类型：`toggle` / `number` / `text` / `choice`。 */
    val type: String = "toggle",
    val description: String? = null,
    val defaultBool: Boolean? = null,
    val defaultInt: Int? = null,
    val defaultText: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<ChoiceOptionSpec> = emptyList(),
)

/** 下拉候选项：[value] 落库，[label] 展示。 */
@Serializable
data class ChoiceOptionSpec(val value: String, val label: String)
