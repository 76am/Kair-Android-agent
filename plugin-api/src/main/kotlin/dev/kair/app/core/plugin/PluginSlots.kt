package dev.kair.app.core.plugin

/**
 * 插件 UI 槽位的区域：会话页顶部栏 / 输入框底部区。
 *
 * 与 [SettingsPanelSpec] 同源——插件只声明「放什么控件 / 取什么数据」，宿主用内置控件
 * 渲染，插件因此**不接触 Compose**，UI 也不会与应用的 Compose 版本耦合。
 */
enum class SlotArea { TOP, BOTTOM }

/**
 * 声明式槽位贡献：插件在 [area] 指定的区域放一组内置控件。
 *
 * - **底部区**：渲染进输入框底部区（与其他会话页控件并排，空间不足时可横向滑动）。
 * - **顶部区已停用（U101，2026-11-17）**：宿主不再渲染 `TOP` —— 顶栏是身份区
 *   （返回 + 会话名），右上角又是单手指最难够到的位置。枚举值保留是给 registry 的
 *   过滤机制留兼容；声明 `TOP` 的槽位不再出现在界面上，要上界面请声明 `BOTTOM`。
 * - 控件值经 [PluginSettingsStore] 按各自 [SlotElement.key] 持久化，与设置面板同一份存储。
 * - 同一插件的同一 [id] 重复声明时替换旧声明（重载插件会再次 `activate`）。
 */
data class SlotContribution(
    /** 槽位 id，插件内唯一。 */
    val id: String,
    val area: SlotArea,
    /** 排序权重，小的靠前。 */
    val order: Int = 100,
    val elements: List<SlotElement>,
)

/**
 * 槽位控件交互时拿到的**会话上下文**：定位「这次点击属于哪个会话」。
 *
 * 槽位只渲染在某个会话的对话页里，因此回调拿到的 [sessionId] / [workspace] 就是
 * **触发它的那个会话**。插件据此把操作限定在该会话（如写入它自己的工作区），而不是
 * 全局「最近打开的会话」——多会话并发时后者会让 A 会话的按钮作用到 B 会话上。
 */
data class SlotContext(
    /** 当前会话 id；槽位脱离会话渲染时为 null。 */
    val sessionId: String?,
    /** 当前会话工作区的绝对路径；无会话时为空串。 */
    val workspace: String,
)

/**
 * 槽位里的一个控件；[key] 在该插件内（跨槽位）唯一，也是持久化键。
 *
 * 交互回调收到本插件的 [PluginSettingsStore]（读写持久化值）与 [SlotContext]
 * （触发它的会话 id / 工作区）。
 */
sealed interface SlotElement {
    val key: String

    /**
     * 可选线稿图标：一段 24×24 视口下 SVG path 的 `d`，宿主按 1.7 单位**描边**绘制
     * （画轮廓，不是填色块），颜色由宿主按语义给。不引入网页 / JS 运行时。
     *
     * **不要传 emoji 或任何字符**（U97）：以前这里能吃一个**插头表情**是宿主的漏洞 ——
     * 那些字符由设备 emoji 字体渲染，同一排按钮里会混进彩色番茄和粉色脑子。
     * 认不出的值一律画成通用方块，字符本身不会再出现在界面上。
     */
    val icon: String?

    /**
     * 可点击按钮（单行 chip）。
     *
     * [confirm] 缺省为 true：点击先弹宿主绘制的确认弹窗，确认后才执行 [onClick]
     * （对齐内置「压缩上下文」的二次确认，避免误触直接触发动作）；不需要确认的
     * 插件显式传 false。[confirmMessage] 给出弹窗正文，缺省用「确定执行「<label>」吗？」。
     */
    data class Button(
        override val key: String,
        val label: String,
        override val icon: String? = null,
        val confirm: Boolean = true,
        val confirmMessage: String? = null,
        val onClick: (PluginSettingsStore, SlotContext) -> Unit = { _, _ -> },
    ) : SlotElement

    /**
     * 开关：点击切换，激活态高亮。值按 [key] 持久化，改动后触发 [onChange] 供插件做副作用。
     */
    data class Toggle(
        override val key: String,
        val label: String,
        override val icon: String? = null,
        val default: Boolean = false,
        val onChange: ((Boolean, SlotContext) -> Unit)? = null,
    ) : SlotElement

    /** 只读状态文字。 */
    data class Text(
        override val key: String,
        val text: String,
        override val icon: String? = null,
    ) : SlotElement

    /**
     * 两行键值 chip：小标题 [label] + 主值 [value]，可点击、可高亮（[active]）。
     * 与底部会话页的「模型 / 思考强度」格子同一外观。
     */
    data class KeyValue(
        override val key: String,
        val label: String,
        val value: String,
        override val icon: String? = null,
        val active: Boolean = false,
        val onClick: (PluginSettingsStore, SlotContext) -> Unit = { _, _ -> },
    ) : SlotElement

    /**
     * 圆环进度：按 [value] / [max] 画一圈并显示中心百分比，可点击。
     * 与底部会话页的「上下文占用」圆环同一外观；[max] ≤ 0 时按空处理。
     */
    data class Gauge(
        override val key: String,
        val value: Float,
        val max: Float,
        override val icon: String? = null,
        val onClick: (PluginSettingsStore, SlotContext) -> Unit = { _, _ -> },
    ) : SlotElement

    /**
     * 面板导航入口：点击后由宿主打开 [panelId] 指定的面板（设置面板 / 面板贡献）。
     * 与内置的「子智能体」「Linux 会话」入口同一交互——插件据此把面板挂到顶部栏。
     *
     * [panelId] 缺省同 [key]；插件通常让二者一致，或显式指向自己声明的
     * [SettingsPanelSpec.id] / `PanelContribution.id`。
     */
    data class Panel(
        override val key: String,
        val label: String,
        override val icon: String? = null,
        val panelId: String = key,
    ) : SlotElement

    /**
     * 选项弹窗入口：点击后宿主弹出选项列表（单选）与可选开关列表，行为对齐内置的
     * 「模型切换 / 思考强度」（点一下弹出选择面板）。
     *
     * 选中项按 [key] 持久化（存 [ChoiceOption.value]），开关按各自 [ToggleSpec.key]
     * 持久化到 [PluginSettingsStore]。选项与开关都可为空。
     */
    data class Picker(
        override val key: String,
        val label: String,
        /** 缺省展示值（已被用户改过时以持久化值为准）。 */
        val value: String = "",
        override val icon: String? = null,
        val options: List<ChoiceOption> = emptyList(),
        val toggles: List<ToggleSpec> = emptyList(),
    ) : SlotElement
}

/** [SlotElement.Picker] 弹窗里可声明的一个开关；值按 [key] 持久化。 */
data class ToggleSpec(
    val key: String,
    val label: String,
    val default: Boolean = false,
)
