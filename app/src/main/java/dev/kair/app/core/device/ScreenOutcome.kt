package dev.kair.app.core.device

/**
 * 一次设备截屏的结果。
 *
 * 为什么不像 [AdbOutcome] 那样把图塞进 `Ok.output`（String）：PNG 是二进制，经 UTF-8 解码后
 * 不可还原。所以这里给字节，由工具层编码成 base64 交给 `Part.Image`——**图片必须走
 * `ToolResult.images` 通道，模型才「看得见」**，回一段 base64 文本等于让它读天书。
 *
 * 宽高取自 PNG 的 IHDR：它既是截图像素尺寸，也就是 `input tap/swipe` 的坐标系
 * （input 用的是当前显示方向的坐标，与截图一致），所以不需要再单独查 `wm size` 或旋转。
 *
 * 纯类型：core 不得 import Android framework（ArchitectureGuardTest），实现留在
 * `ui/plugins/builtin/AdbLink.kt`。
 */
sealed interface ScreenOutcome {
    /**
     * 注意：`png` 是 `ByteArray`，data class 生成的 `equals` 按**引用**比较。这里没有把它当值
     * 比较的场景（单测断言的是「图进了 `ToolResult.images` 且能解回原字节」），所以不重写
     * `equals` 去凑内容语义——真要比内容请显式用 `contentEquals`。
     */
    data class Ok(val png: ByteArray, val width: Int, val height: Int) : ScreenOutcome

    data class Failed(val reason: AdbOutcome.Reason, val detail: String = "") : ScreenOutcome
}
