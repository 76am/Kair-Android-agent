package dev.kair.app.core.tools

import dev.kair.app.core.device.ScreenOutcome
import dev.kair.app.core.model.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/** 截屏的执行后端。真实实现是 `AdbLink.screenshot`；单测注入假实现，避免碰 Android。 */
fun interface DeviceScreenRunner {
    suspend fun capture(): ScreenOutcome
}

/**
 * U91 · `device_screen`：截一张这台手机的屏，**把图交回给模型看**。
 *
 * 为什么这条必须独立成工具，而不是让模型用 `device_shell` 跑 `screencap`：二进制回不来。
 * `device_shell` 的返回是 `String`，PNG 经 UTF-8 解码不可还原；就算让模型自己 base64，
 * 那也只是一段它读不懂的字符。只有走 [ToolResult.images]（与 `read_image` 同一条回灌通道）
 * 模型才真的「看得见」屏幕。点按滑**不**在这里——`input tap/swipe/text/keyevent` 是一行 shell，
 * 由 `device_shell` 承担，见 U91 任务书 §2.3。
 *
 * 描述与错误文案是英文：它们进系统提示的缓存稳定前缀、作为 tool 结果回给模型，不是界面文案；
 * core 里也不允许出现中文字面量（AGENTS.md §4 / DocsConsistencyTest A6-4）。
 */
class DeviceScreenTool(private val runner: DeviceScreenRunner) : Tool {
    override val name = "device_screen"

    override val category = ToolCategory.READ

    override val readOnly = true

    override val promptSnippet = "device_screen — screenshot this Android phone so you can see its UI"

    override val promptGuidelines = listOf(
        "Call device_screen before tapping anything and again after an action that should have changed " +
            "the screen. Do not assume a tap worked just because the call succeeded.",
        "If the screen shows a system or permission dialog, ask the user before dismissing or accepting it.",
    )

    override val description =
        "Take a screenshot of THIS Android phone and attach it so you can see the screen. Returns the " +
            "image plus its pixel size; `input tap X Y` / `input swipe X1 Y1 X2 Y2` (run through " +
            "device_shell) uses that same pixel space with the origin at the top-left of this image, so " +
            "read coordinates straight off the picture. It needs wireless debugging on and the ADB panel " +
            "paired once."

    override val parameters = schemaOf(props())

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.isAborted()) throw ToolException("Aborted")
        return when (val outcome = withContext(Dispatchers.IO) { runner.capture() }) {
            is ScreenOutcome.Ok -> ToolResult(
                content = "Screenshot ${outcome.width}x${outcome.height} px, attached for you to view. " +
                    "Use this same pixel space (origin top-left) for `input tap` / `input swipe` via " +
                    "device_shell.",
                images = listOf(
                    Part.Image(
                        base64 = Base64.getEncoder().encodeToString(outcome.png),
                        mediaType = "image/png",
                    ),
                ),
            )

            is ScreenOutcome.Failed -> ToolResult(adbFailureHint(outcome.reason, outcome.detail), isError = true)
        }
    }
}
