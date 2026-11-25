package dev.kair.app.ui.screens.chat

import dev.kair.app.core.plugin.PanelChrome
import dev.kair.app.core.plugin.PanelContribution

/**
 * 面板入口一格的数据。
 *
 * 存在的理由是**可测**：入口原先直接读 `container.plugins.panels()`，于是任何针对它的
 * UI 测试都得先造一个 `AppContainer`（连带插件加载、proot、会话库）。把「要显示什么」
 * 收成这个纯数据类，入口就退化成 `List<DockPanelEntry> -> 像素`。
 *
 * 2026-11-18（设计稿 v2）：入口形态从「输入板上方的横滑坞」改成「顶栏九宫格 →
 * 底部 sheet 3 列网格」，渲染见 [QuickPanelSheet]；本类与筛选逻辑原样复用。
 */
internal data class DockPanelEntry(
    val id: String,
    val icon: String?,
    val label: String,
    /** 计数角标；0 = 不显示。目前只有子智能体用到。 */
    val badge: Int = 0,
    /** 覆盖读屏描述；null = 沿用「图标 + 标题」的默认读法。 */
    val description: String? = null,
    /**
     * 点开即全屏（沉浸面板）。
     *
     * 这一格子点了会**换宿主**（覆盖层 → 独立路由），而旁边的格子只是拉起覆盖层。
     * 「点之前就知道」比点完发现「会话被盖住了/被切走了」好 —— 读屏据此说
     * 「桌面，全屏打开」（[dev.kair.app.R.string.panel_open_fullscreen]）。
     */
    val fullscreen: Boolean = false,
)

/**
 * 从插件注册表里挑出**该给入口**的面板。
 *
 * 2026-11-17 之前这里过滤掉沉浸面板（图形桌面），理由是「必须整块屏幕」。后果是
 * 桌面在会话里**一个入口都没有** —— 负责人原话「有的面板不支持放底部，比如桌面，
 * 我就没有看见按钮」。而宿主其实早就分好路了（`KairNav` 按 `PanelHostKind` 分流：
 * FullScreen 走独立路由，Overlay 走覆盖层），所以那条过滤只是凭空造出一个失踪的入口。
 *
 * 现在全部进入口列表，沉浸面板的格子带 [DockPanelEntry.fullscreen] 标记。
 *
 * 顺序 = 注册表顺序（插件声明序）。**刻意不按使用频率重排**：重排会让用户每次
 * 重新寻找目标，省下的那次点击被找的时间吃回去。
 */
internal fun dockPanels(panels: List<PanelContribution>): List<PanelContribution> = panels

/** 沉浸面板点开即全屏（见 [dockPanels]）。 */
internal fun isFullscreenPanel(panel: PanelContribution): Boolean =
    panel.chrome == PanelChrome.Immersive
