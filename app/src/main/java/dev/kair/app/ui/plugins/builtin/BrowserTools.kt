package dev.kair.app.ui.plugins.builtin

import dev.kair.app.core.browser.BrowserActionException
import dev.kair.app.core.browser.BrowserConsoleLine
import dev.kair.app.core.browser.BrowserController
import dev.kair.app.core.browser.BrowserNetworkLine
import dev.kair.app.core.browser.BrowserSnapshot
import dev.kair.app.core.model.Part
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolCategory
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.bool
import dev.kair.app.core.tools.boolProp
import dev.kair.app.core.tools.int
import dev.kair.app.core.tools.numProp
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.reqStr
import dev.kair.app.core.tools.schemaOf
import dev.kair.app.core.tools.str
import dev.kair.app.core.tools.strProp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Base64

/**
 * 浏览器工具的共同骨架：把控制器异常收敛成模型可纠正的错误结果。
 *
 * 工具只在**执行时**经 [browser] 取控制器 —— 插件在 `AppContainer.init` 里 activate，
 * JVM 测试没有 Android Context，提前取会把测试环境打成宿主环境。
 */
internal abstract class BrowserTool(
    protected val browser: () -> BrowserController,
) : Tool {

    protected suspend fun browserAction(block: suspend BrowserController.() -> String): ToolResult = try {
        ToolResult(browser().block())
    } catch (e: BrowserActionException) {
        ToolResult(e.message ?: "Browser action failed.", isError = true)
    } catch (e: CancellationException) {
        // 取消不是失败：继续向上传播，否则停止 / 超时会被伪装成一条工具结果。
        throw e
    } catch (e: Exception) {
        ToolResult("${e.javaClass.simpleName}: ${e.message}", isError = true)
    }
}

internal class BrowserOpenTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_open"
    override val description =
        "Open an http(s) page in the session browser and wait for it to finish loading. " +
            "Only http(s) URLs load: to view a local file, start a static server in the workspace " +
            "(for example `python3 -m http.server 8000`) and open `http://127.0.0.1:8000/<path>`. " +
            "Use browser_snapshot afterwards to inspect the page."
    override val parameters = schemaOf(
        props("url" to strProp("Page URL, for example http://127.0.0.1:5173.")),
        "url",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = args.reqStr("url")
        return browserAction {
            val page = open(url)
            buildString {
                appendLine("Opened ${page.url}")
                appendLine("Title: ${page.title.ifBlank { "(untitled)" }}")
                append(if (page.loaded) "Loaded." else "Still loading after 20s; call browser_snapshot to check.")
            }
        }
    }
}

internal class BrowserSnapshotTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_snapshot"
    override val description =
        "Read the current page: URL, title, visible text, and interactive elements with " +
            "short refs (e1, e2, ...). Call this before browser_click or browser_type."
    override val parameters = schemaOf(props())
    override val category = ToolCategory.READ
    override val readOnly = true
    // 快照会给元素打 data-kair-ref、覆写 window.__kairRefs，**不是**并行安全的只读：
    // 两个快照交错会让前一份 ref 失效，所以这里不开 isParallelSafe。

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        browserAction { formatSnapshot(snapshot()) }
}

internal class BrowserScreenshotTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_screenshot"
    override val description =
        "Screenshot the browser viewport, save it under `.kair/shots/` in the workspace, and attach " +
            "the image so you can see the rendered page. Use it for layout, styling, and image-loading " +
            "problems that browser_snapshot cannot reveal. The returned path is workspace-relative; " +
            "pass it to read_image unchanged (for example from the parent session after this " +
            "subagent finishes)."
    override val parameters = schemaOf(
        props(
            "save_only" to boolProp(
                "Only save the PNG and return its path; do not attach the image. Use it when you " +
                    "need a file path for another tool or for the parent session.",
            ),
        ),
    )
    override val category = ToolCategory.READ
    override val readOnly = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val shot = browser().screenshot()
        val saved = withContext(Dispatchers.IO) { saveScreenshot(ctx.workspace, shot.png) }
        val notes = buildList {
            if (!shot.complete) {
                add("Visible area only; open the panel full screen and retry for the whole viewport.")
            }
            if (shot.softwareFallback) {
                add(
                    "GPU layers (canvas / WebGL) were not captured — keep the browser panel on " +
                        "screen and retry rather than trusting this image for rendering.",
                )
            }
        }
        val note = notes.joinToString(" ").let { if (it.isEmpty()) "" else " $it" }
        val summary = "Screenshot ${shot.width}x${shot.height}, ${shot.png.size / 1024} KB. " +
            "Saved to $saved (workspace-relative; pass it to read_image unchanged).$note"
        if (args.bool("save_only") == true) {
            ToolResult("$summary Use read_image with this path if you need to see it.")
        } else {
            ToolResult(
                content = "$summary Attached for you to view.",
                images = listOf(Part.Image(base64 = Base64.getEncoder().encodeToString(shot.png))),
            )
        }
    } catch (e: BrowserActionException) {
        ToolResult(e.message ?: "Screenshot failed.", isError = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult("${e.javaClass.simpleName}: ${e.message}", isError = true)
    }
}

internal class BrowserClickTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_click"
    override val description =
        "Click an interactive element by the ref returned from browser_snapshot."
    override val parameters = schemaOf(
        props("ref" to strProp("Element ref such as e3 from the latest browser_snapshot.")),
        "ref",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val ref = args.reqStr("ref")
        return browserAction { click(ref) }
    }
}

internal class BrowserTapTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_tap"
    override val description =
        "Tap the page at viewport coordinates with a real touch event (ACTION_DOWN / UP). " +
            "Use it for canvas, WebGL, and other controls where browser_click (DOM click) has " +
            "no effect. Coordinates are CSS pixels from the visible viewport's top-left; read " +
            "them with browser_eval, for example canvas.getBoundingClientRect()."
    override val parameters = schemaOf(
        props(
            "x" to numProp("CSS pixel X from the viewport's left edge."),
            "y" to numProp("CSS pixel Y from the viewport's top edge."),
        ),
        "x",
        "y",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val x = args.str("x")?.toFloatOrNull()
        val y = args.str("y")?.toFloatOrNull()
        if (x == null || y == null) {
            return ToolResult("browser_tap needs numeric x and y in CSS pixels.", isError = true)
        }
        return browserAction { tap(x, y) }
    }
}

internal class BrowserTypeTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_type"
    override val description =
        "Type text into an input, textarea, or contenteditable element by ref. " +
            "Dispatches input and change events so React-controlled forms update."
    override val parameters = schemaOf(
        props(
            "ref" to strProp("Element ref such as e1 from the latest browser_snapshot."),
            "text" to strProp("Text to type. Use an empty string to clear the field."),
        ),
        "ref",
        "text",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val ref = args.reqStr("ref")
        val text = args.reqStr("text")
        return browserAction { type(ref, text) }
    }
}

internal class BrowserPressTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_press"
    override val description =
        "Press a keyboard key in the page, for example Enter, Escape, Tab, or ArrowDown."
    override val parameters = schemaOf(
        props("key" to strProp("Key name passed to KeyboardEvent.key.")),
        "key",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val key = args.reqStr("key")
        return browserAction { press(key) }
    }
}

internal class BrowserScrollTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_scroll"
    override val description = "Scroll the page up, down, to the top, or to the bottom."
    override val parameters = schemaOf(
        props(
            "direction" to strProp("One of: up, down, top, bottom."),
            "amount" to numProp("Pixels for up/down. Default 600, max 5000."),
        ),
        "direction",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val direction = args.reqStr("direction")
        val amount = args.int("amount")
        return browserAction { scroll(direction, amount) }
    }
}

internal class BrowserEvalTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_eval"
    override val description =
        "Run a JavaScript expression in the page and return its value as text (max 8000 chars). " +
            "Use it to read app state or probe the DOM directly instead of writing markers into the page. " +
            "It runs with the page's own permissions."
    override val parameters = schemaOf(
        props(
            "expression" to strProp(
                "JavaScript expression, for example document.title or window.__STATE__.items.length.",
            ),
        ),
        "expression",
    )
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val expression = args.reqStr("expression")
        return browserAction { eval(expression) }
    }
}

internal class BrowserConsoleTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_console"
    override val description =
        "Read recent browser console messages. Use this after an action to see JavaScript errors."
    override val parameters = schemaOf(
        props(
            "limit" to numProp("Maximum number of recent messages. Default 60, max 200."),
            "only_errors" to boolProp("Only return error-level messages."),
        ),
    )
    override val category = ToolCategory.READ
    override val readOnly = true
    override val isParallelSafe = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val limit = (args.int("limit") ?: 60).coerceIn(1, 200)
        val onlyErrors = args.bool("only_errors") == true
        return browserAction { formatConsole(state.value.console, limit, onlyErrors) }
    }
}

internal class BrowserNetworkTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_network"
    override val description =
        "Read recent network requests (method, URL, status, duration, errors). " +
            "Use this to diagnose failed API calls."
    override val parameters = schemaOf(
        props(
            "limit" to numProp("Maximum number of recent requests. Default 60, max 200."),
            "only_failed" to boolProp("Only return requests with HTTP >= 400 or transport errors."),
        ),
    )
    override val category = ToolCategory.READ
    override val readOnly = true
    override val isParallelSafe = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val limit = (args.int("limit") ?: 60).coerceIn(1, 200)
        val onlyFailed = args.bool("only_failed") == true
        return browserAction { formatNetwork(state.value.network, limit, onlyFailed) }
    }
}

/**
 * 关闭页面。小窗的生命跟页面绑定，不主动关就会一直留在屏上；交给模型在收尾时调用。
 * 关闭后控制器仍可用，下一次 `browser_open` 重建 WebView 与小窗。
 */
internal class BrowserCloseTool(browser: () -> BrowserController) : BrowserTool(browser) {
    override val name = "browser_close"
    override val description =
        "Close the current page and remove the floating browser window. Call this when you are " +
            "done with the page; otherwise the window stays on screen until the user closes it."
    override val parameters = schemaOf(props())
    override val category = ToolCategory.EXECUTE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        browserAction {
            close()
            "Browser page closed."
        }
}

/** 一组内置浏览器工具，顺序即注册顺序（稳定的 prompt 前缀）。 */
internal fun browserTools(browser: () -> BrowserController): List<Tool> = listOf(
    BrowserOpenTool(browser),
    BrowserSnapshotTool(browser),
    BrowserScreenshotTool(browser),
    BrowserClickTool(browser),
    BrowserTapTool(browser),
    BrowserTypeTool(browser),
    BrowserPressTool(browser),
    BrowserScrollTool(browser),
    BrowserEvalTool(browser),
    BrowserConsoleTool(browser),
    BrowserNetworkTool(browser),
    BrowserCloseTool(browser),
)

/** 截图目录：工作区内的 `.kair/shots`，与附件 / 会话产物同一层级。 */
private const val SCREENSHOT_DIR = ".kair/shots"

/**
 * 截图落盘并返回工作区相对路径。
 *
 * 为什么必须落盘：`browser` 子智能体的 `ToolResult.images` 只回给子智能体自己的模型，
 * 子智能体结束后主会话拿不到；文件路径让主会话可以用 `read_image` 回看。
 * 同一毫秒重复截图时补序号，不覆盖前一张。
 */
private fun saveScreenshot(workspace: File, png: ByteArray): String {
    val dir = File(workspace, SCREENSHOT_DIR)
    dir.mkdirs()
    val stamp = System.currentTimeMillis()
    var file = File(dir, "$stamp.png")
    var suffix = 1
    while (file.exists()) {
        file = File(dir, "$stamp-$suffix.png")
        suffix++
    }
    file.writeBytes(png)
    return "$SCREENSHOT_DIR/${file.name}"
}

// ---- 工具输出（面向模型：英文）-----------------------------------------

internal fun formatSnapshot(page: BrowserSnapshot): String = buildString {
    appendLine("URL: ${page.url}")
    appendLine("Title: ${page.title}")
    if (page.elements.isEmpty()) {
        appendLine("Interactive elements: none")
    } else {
        appendLine("Interactive elements:")
        page.elements.forEach { element ->
            append("[${element.ref}] ${element.role} \"${element.name}\"")
            if (element.value.isNotEmpty()) append(" value=\"${oneLine(element.value)}\"")
            element.checked?.let { append(" checked=$it") }
            if (element.disabled) append(" disabled")
            appendLine()
        }
    }
    appendLine()
    appendLine("Page text:")
    append(page.text)
}

internal fun formatConsole(
    lines: List<BrowserConsoleLine>,
    limit: Int,
    onlyErrors: Boolean,
): String {
    val shown = lines
        .filter { !onlyErrors || it.level == "error" }
        .takeLast(limit)
    if (shown.isEmpty()) return if (onlyErrors) "No console errors." else "No console messages."
    return shown.joinToString("\n") { "[${it.level}] ${oneLine(it.message)}" }
}

internal fun formatNetwork(
    lines: List<BrowserNetworkLine>,
    limit: Int,
    onlyFailed: Boolean,
): String {
    val shown = lines
        .filter { !onlyFailed || it.error != null || (it.status ?: 0) >= 400 }
        .takeLast(limit)
    if (shown.isEmpty()) return if (onlyFailed) "No failed requests." else "No network requests."
    return shown.joinToString("\n") { line ->
        buildString {
            append(line.method).append(' ').append(line.status?.toString() ?: "ERR")
            append(' ').append(line.url)
            line.durationMs?.let { append(" (${it}ms)") }
            line.error?.let { append(" - ").append(oneLine(it)) }
        }
    }
}

private fun oneLine(text: String): String = text.replace('\n', ' ').take(500)
