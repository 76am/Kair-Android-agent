package dev.kair.app.core.plugin

/**
 * 插件设置的持久化存取面。宿主按「插件 id + 键」分区注入实现；插件在
 * `activate()` 与工具执行期都可以用自己声明的键读取用户改过的值。
 *
 * 这是**声明式**设置的一部分：插件不接触任何 UI 代码，只声明「有哪些设置项」
 * （[SettingsPanelSpec]）并读写键值（本接口），宿主用内置控件渲染面板。
 */
interface PluginSettingsStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getString(key: String, default: String): String

    fun setBoolean(key: String, value: Boolean)
    fun setInt(key: String, value: Int)
    fun setString(key: String, value: String)
}

/**
 * 一个设置面板：宿主用内置控件（开关 / 数值 / 文本 / 下拉 / 按钮 / 文件编辑器）渲染，
 * 用户点击进入后直接编辑。面板值经 [PluginSettingsStore] 按各自 [SettingField.key] 持久化。
 *
 * 插件作者只声明数据，不写 Compose —— 这保证了第三方插件的 UI 不与应用
 * 的 Compose 版本耦合。
 */
data class SettingsPanelSpec(
    /** 面板 id，插件内唯一；也是导航路由键。 */
    val id: String,
    val title: String,
    /** 标题旁的短字符 / emoji。 */
    val icon: String? = null,
    /** 面板排序权重，小的在前。 */
    val order: Int = 100,
    val fields: List<SettingField>,
    /**
     * 是否同时在「设置」页显示本面板（双入口）。
     *
     * 默认 false —— 面板只在插件详情页出现。置 true 时，宿主会在设置页用横线
     * 分区渲染同一份声明，两处读写同一份数据；禁用该插件时两处一并消失。
     */
    val showInSettings: Boolean = false,
) {
    /**
     * [title] 的字符串资源 id：非 null 时宿主优先取资源，否则逐字回退字面量。
     *
     * 内置插件据此把显示文案登记进 `strings.xml`（`AGENTS.md` §4）；第三方（动态）插件
     * 没有资源上下文，不声明即可。**声明在类体而非构造器**：插件 dex 按旧签名构造本类，
     * 往构造器加参数会改变 JVM 构造器描述符，等于给旧插件留 `NoSuchMethodError`（§2.1）。
     */
    var titleRes: Int? = null
}

/** 一个设置项：开关 / 整数 / 单行文本 / 下拉选择 / 按钮动作 / 文件查看编辑。 */
sealed interface SettingField {
    /** 持久化键，在插件内唯一（按钮动作不使用键，但保留以统一标识）。 */
    val key: String

    /**
     * 文案（已解析的字符串）。用字符串资源时本项留空、改由 [labelRes] 提供：
     * 内置插件据此让文案统一登记在 `strings.xml`（AGENTS §4）；第三方插件没有
     * 资源上下文，照旧给本字段。
     */
    val label: String

    /** 控件下方的说明文字，可空（同样可经 [descriptionRes] 走资源）。 */
    val description: String?

    /** 可选的字符串资源 id；非 null 时优先于 [label]（内置插件用）。 */
    val labelRes: Int? get() = null

    /** 可选的字符串资源 id；非 null 时优先于 [description]（内置插件用）。 */
    val descriptionRes: Int? get() = null

    data class Toggle(
        override val key: String,
        override val label: String = "",
        override val description: String? = null,
        val default: Boolean = false,
        /**
         * 值被改动时的回调（宿主已落盘后再调用），供插件做副作用，
         * 例如按开关实时起停前台服务。为 null 时只落盘、无副作用。
         */
        val onChange: ((Boolean) -> Unit)? = null,
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField

    data class Number(
        override val key: String,
        override val label: String = "",
        override val description: String? = null,
        val default: Int = 0,
        val min: Int? = null,
        val max: Int? = null,
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField

    data class Text(
        override val key: String,
        override val label: String = "",
        override val description: String? = null,
        val default: String = "",
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField

    /**
     * 文件查看 / 编辑：宿主用内置编辑器打开工作区的某个文件，插件不接触 Compose。
     *
     * 与 [Text] 的区别：[Text] 的值存在**插件设置**里（[PluginSettingsStore]），本项
     * **直接读写工作区文件** —— 适合「查看 / 编辑插件自己维护的文件」（如记忆插件的
     * `.kair/memory.md`）：文件是唯一事实源，不再有「设置里的副本」与之不一致。
     *
     * 读写走宿主文件后端（与 `read` / `write` 工具同源）：容器在场时同样能读写
     * `bash`（root）建的、应用 uid 读不动的文件。
     */
    data class FileEditor(
        override val key: String,
        /** 工作区相对路径（如 `.kair/memory.md`）；绝对路径原样使用。 */
        val path: String,
        override val label: String = "",
        override val description: String? = null,
        /** 只读：仅查看，不显示保存。 */
        val readOnly: Boolean = false,
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField

    /** 下拉选择：从 [options] 里选一个，存其 [ChoiceOption.value]。 */
    data class Choice(
        override val key: String,
        override val label: String = "",
        override val description: String? = null,
        val options: List<ChoiceOption> = emptyList(),
        /**
         * **动态选项源**：`"sessions"`（当前项目的会话）/ `"models"`（已配置的模型）/
         * `"models_follow_session"`（同 `"models"`，首项是空值的「跟随当前会话模型」，
         * 供「不选 = 继承」的覆盖类设置使用）。
         *
         * 非 null 时忽略 [options]，由宿主在渲染时填充。为什么需要它：这两类列表随用户
         * 操作实时变化，插件在 `activate()` 里拿不到、也无法（不该）订阅 —— 写死静态
         * 选项等于让插件过期。
         */
        val source: String? = null,
        /** 缺省选中的 value；不在 options 中时取第一项。 */
        val default: String = options.firstOrNull()?.value.orEmpty(),
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField

    /**
     * 按钮动作：点一下触发插件自定义逻辑（不是持久化值）。
     * [onClick] 在点击时调用，宿主已把本插件的 [PluginSettingsStore] 传入，
     * 插件可据此读值或写值（写值后宿主会刷新面板）。
     */
    data class Action(
        override val key: String,
        override val label: String = "",
        override val description: String? = null,
        val buttonLabel: String,
        val onClick: (PluginSettingsStore) -> Unit = {},
        override val labelRes: Int? = null,
        override val descriptionRes: Int? = null,
    ) : SettingField {
        /** [buttonLabel] 的字符串资源 id，null = 逐字回退。**同 [SettingsPanelSpec.titleRes]
         *  的形态：dex 插件构造本类，所以资源 id 只能进类体，不进构造器（§2.1）。 */
        var buttonLabelRes: Int? = null
    }
}

/** 下拉选项：[value] 落库，[label] 展示。 */
data class ChoiceOption(val value: String, val label: String) {
    /** [label] 的字符串资源 id，null = 逐字回退。形态理由同 [SettingField.Action.buttonLabelRes]。 */
    var labelRes: Int? = null
}

/**
 * 宿主未注入真实实现时的进程内兜底：不落盘，仅本次进程有效。
 * 单元测试与未接设置存储的宿主可用它。
 */
class InMemoryPluginSettingsStore : PluginSettingsStore {
    private val values = mutableMapOf<String, Any>()

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun getString(key: String, default: String): String = values[key] as? String ?: default

    override fun setBoolean(key: String, value: Boolean) { values[key] = value }
    override fun setInt(key: String, value: Int) { values[key] = value }
    override fun setString(key: String, value: String) { values[key] = value }
}
