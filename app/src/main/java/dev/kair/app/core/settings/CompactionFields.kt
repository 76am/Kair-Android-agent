package dev.kair.app.core.settings

/**
 * 压缩设置的字段定义（key / 区间 / 默认值）—— 设置页与插件面板的**单一出处**。
 *
 * 为什么只收敛这些：文案在两个入口各有归属 —— 核心设置页的文案在 `strings.xml`
 * （AGENTS §4 要求经 `stringResource` 引用），而插件面板的文案由插件声明（插件 SDK
 * 零 Compose，只能给 `String`）。但 key / 区间 / 默认值这类**机器参数**写两处必然
 * 漂移（历史上就出现过同一字段「设置页 1024–65536、插件面板 1024–1,000,000」的
 * 两个区间），所以统一到这里；新增字段只改这一处 + 两个入口各自暴露。
 */
object CompactionFields {

    /** 自动压缩开关；插件设置键，映射到 [SettingsStore.autoCompaction]。 */
    const val KEY_ENABLED = "enabled"

    /** 压缩触发阈值；插件设置键，映射到 [SettingsStore.compactionReserveTokens]。 */
    const val KEY_RESERVE = "reserve_tokens"

    /** 压缩后保留的近期原文上限；插件设置键，映射到 [SettingsStore.compactionKeepRecentTokens]。 */
    const val KEY_KEEP = "keep_recent_tokens"

    /** 摘要请求使用的模型；插件设置键，映射到 [SettingsStore.compactionModelId]（空 = 跟随会话）。 */
    const val KEY_MODEL = "model"

    const val RESERVE_MIN = 1_024
    const val RESERVE_MAX = 65_536
    const val RESERVE_DEFAULT = 16_384

    const val KEEP_MIN = 1_024
    const val KEEP_MAX = 65_536
    const val KEEP_DEFAULT = 20_000
}
