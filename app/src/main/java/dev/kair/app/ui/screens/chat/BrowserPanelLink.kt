package dev.kair.app.ui.screens.chat

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 会话宿主提供的「打开浏览器面板」入口。
 *
 * 用 CompositionLocal 而不是给 `Tool` / `Part.ToolCall` 加字段：工具卡在很深的层级
 * （FeedRowItem → MessageList → ToolCallBlock），为了一个按钮把回调穿五层
 * 会把展示组件绑死在宿主上；局部没有提供者时（单元测试 / 别的页面）就不渲染入口。
 */
internal val LocalBrowserPanelOpen = staticCompositionLocalOf<(() -> Unit)?> { null }
