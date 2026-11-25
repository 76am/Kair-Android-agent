package dev.kair.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 插件显示文本的**唯一**判据：声明了资源 id 就用 [read] 取词，否则逐字回退 [literal]。
 *
 * 为什么需要资源通道：`AGENTS.md` §4 要求面向用户的文案进 `strings.xml`，但插件的贡献对象
 * （[dev.kair.app.core.plugin.Plugin.name] / [dev.kair.app.core.plugin.PanelContribution.title] /
 * [dev.kair.app.core.plugin.SettingsPanelSpec.title]）在**装配期**构造，那时拿不到 `Context`
 * （`AppEnvironment` 明文规定构造与装配一律不许碰 `appContext`，JVM 里碰即抛），
 * 所以只能把资源 id 声明在对象上、到渲染时再解析。`TerminalPlugin.activate()` 里有同一约束的注释。
 *
 * 回退分支不是摆设：第三方（动态）插件没有资源上下文，只会给字面量 —— 它必须永远显示得出来。
 */
internal fun resolveDisplayText(literal: String, res: Int?, read: (Int) -> String): String =
    if (res == null) literal else read(res)

/** 渲染点用的薄封装：把 `getString` 作为取词器交给 [resolveDisplayText]，判据不重复第二遍。 */
@Composable
internal fun pluginDisplayText(literal: String, res: Int?): String {
    val context = LocalContext.current
    return resolveDisplayText(literal, res) { context.getString(it) }
}
