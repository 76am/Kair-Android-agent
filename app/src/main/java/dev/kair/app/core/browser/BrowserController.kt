package dev.kair.app.core.browser

import kotlinx.coroutines.flow.StateFlow

/** 浏览器面的面板 id。插件与导航共用这一个常量，避免两处字面量漂移。 */
const val BROWSER_PANEL_ID = "panels.browser"

/**
 * 内置浏览器工具的名字前缀。主会话与 coding / search 子智能体据此排除这组 schema ——
 * 浏览器工具只进 `browser` 子智能体（省下常驻上下文里的 10 个工具定义）。
 */
const val BROWSER_TOOL_PREFIX = "browser_"

/** WebView 独立数据目录后缀；`KairApplication` 在创建任何 WebView 之前设置。 */
const val BROWSER_DATA_DIR_SUFFIX = "agent-dev-browser"

/** 谁在操作页面：Agent 或人类。人类接管期间 Agent 的工具调用会被拒绝。 */
enum class BrowserMode { AGENT, HUMAN }

/** 快照里的一个可交互元素；[ref] 是本次快照内有效的短引用（如 `e3`）。 */
data class BrowserElement(
    val ref: String,
    val role: String,
    val name: String,
    val value: String = "",
    val checked: Boolean? = null,
    val disabled: Boolean = false,
)

/** 页面快照：结构化元素 + 截断后的正文。ref 只在最近一次快照内有效。 */
data class BrowserSnapshot(
    val url: String,
    val title: String,
    val elements: List<BrowserElement>,
    val text: String,
)

/** 打开页面的结果。[loaded] 为 false 表示等待超时，页面可能还在加载。 */
data class BrowserPageInfo(
    val url: String,
    val title: String,
    val loaded: Boolean,
)

data class BrowserConsoleLine(
    val level: String,
    val message: String,
    val at: Long,
)

data class BrowserNetworkLine(
    val method: String,
    val url: String,
    val status: Int? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val at: Long,
)

/** 面板「操作」页的一条 Agent 动作记录。 */
data class BrowserActionLine(
    val tool: String,
    val detail: String,
    val ok: Boolean,
    val at: Long,
)

/** 面板与工具共用的只读状态。所有字段都是纯数据，可在 JVM 上构造与断言。 */
data class BrowserPanelState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val mode: BrowserMode = BrowserMode.AGENT,
    /** Agent 正在执行一次浏览器动作；九宫格角标与面板状态条据此显示。 */
    val busy: Boolean = false,
    /**
     * 正在截图：面板会临时滑出（见 `CaptureHost`），这段时间锁住所有会收起面板的交互
     * （关闭 / 遮罩 / 接管）—— 面板一收，`PixelCopy` 就抓不到画面。
     */
    val capturing: Boolean = false,
    /** 页面历史里是否有上一页；面板后退按钮据此启用。 */
    val canGoBack: Boolean = false,
    val console: List<BrowserConsoleLine> = emptyList(),
    val network: List<BrowserNetworkLine> = emptyList(),
    val actions: List<BrowserActionLine> = emptyList(),
    /** 渲染进程重建次数；UI 据此重建 AndroidView，不让旧 WebView 残留。 */
    val rendererGeneration: Int = 0,
)

/**
 * 一次视口截图。[complete] 为 false 表示 WebView 被面板裁掉了一部分，
 * 只抓到了窗口内的可见区域；全屏下才是完整视口。
 *
 * [softwareFallback] 为 true 表示 `PixelCopy` 失败、退回了 `view.draw`：DOM 画得出来，
 * 但 canvas / WebGL 等 GPU 合成层会缺失（通常是一整块黑）。调用方必须把这件事告诉模型，
 * 不能把这种图当成正常截图。
 */
class BrowserScreenshot(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val complete: Boolean,
    val softwareFallback: Boolean = false,
)

/**
 * 浏览器工具与面板之间的唯一契约。
 *
 * 放在 `core` 且只依赖协程 / 纯数据：JVM 单测可以用假实现覆盖工具层，
 * Android 侧的实现（WebView）留在 `ui/browser`，不把平台类型带进内核。
 */
interface BrowserController {
    val state: StateFlow<BrowserPanelState>

    suspend fun open(url: String): BrowserPageInfo

    suspend fun snapshot(): BrowserSnapshot

    suspend fun click(ref: String): String

    /**
     * 在视口坐标（CSS 像素，原点为可见视口左上角）落一次**真实触摸**（`MotionEvent`
     * ACTION_DOWN / UP）。`click` 走 DOM `element.click()`，对 canvas / WebGL 控件无效；
     * 这里由 WebView 合成 touch / pointer 事件，页面看到的是可信输入。
     */
    suspend fun tap(x: Float, y: Float): String

    suspend fun type(ref: String, text: String): String

    suspend fun press(key: String): String

    suspend fun scroll(direction: String, amount: Int?): String

    /** 在页面里执行一段 JavaScript 表达式，返回值的字符串形式（已截断）。 */
    suspend fun eval(expression: String): String

    /** 把当前视口画成 PNG 字节；空页面 / 视图没有尺寸时抛 [BrowserActionException]。 */
    suspend fun screenshot(): BrowserScreenshot

    fun setMode(mode: BrowserMode)

    fun goBack()

    fun reload()

    /** 关闭当前页面并移除悬浮小窗；控制器仍可复用，下一次 [open] 重建 WebView。 */
    fun close()

    fun clearConsole()

    fun clearNetwork()
}

/** 浏览器动作失败：消息面向模型（英文），由工具层转成 `ToolResult(isError = true)`。 */
class BrowserActionException(message: String) : Exception(message)
