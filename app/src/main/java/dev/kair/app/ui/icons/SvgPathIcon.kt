package dev.kair.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** 插件图标的坐标系：所有路径都按 24×24 视口书写。 */
private const val VIEWPORT = 24f

/**
 * 线宽（视口单位），**按显示尺寸分两档** —— 光学补偿：
 *
 * - 常规（≥ 20dp）：2.0，即 Material outlined 图标的标准线宽。此前 1.7 在与首页
 *   `Icons.Filled.*`（实心，主体笔画等效约 2 视口单位）同屏时明显细一档，
 *   负责人 2026-11-17 反馈「新写的矢量按钮没有之前的清晰」，对照物就是那两个。
 * - 小尺寸（< 20dp，坞格子 15dp）：2.3。图标缩得越小，同样的相对线宽在视网膜上
 *   就越接近发丝；小一档光就要补一档粗，否则 15dp 下线稿糊成一片灰。
 *
 * 与「图纸与焊点」的线稿原则不冲突：加粗的是**笔**，不是把图标改成实心。
 */
private const val STROKE_REGULAR = 2.0f
private const val STROKE_SMALL = 2.3f

/**
 * 把一段 24×24 视口的 SVG path `d` 画成**线稿**图标。
 *
 * 与「图纸与焊点」一致：只描边、不填充。好处是同一份路径在任何尺寸下粗细一致
 * （描边随画布一起缩放），也不需要为每个图标准备实心 / 空心两版。
 *
 * [pathData] 为 null、空白或不像路径时（例如老插件还在传 emoji 字符），
 * 退化成 [PluginGlyph.Fallback]，**绝不**把那段字符当文字画出来。
 */
@Composable
fun SvgPathIcon(
    pathData: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember(pathData) { parseGlyph(pathData) }
    Canvas(modifier) {
        val scale = minOf(size.width, size.height) / VIEWPORT
        if (scale <= 0f) return@Canvas
        // 光学补偿见 [STROKE_SMALL] 的注释：小尺寸相对加粗一档。
        val stroke = if (minOf(size.width, size.height) < 20.dp.toPx()) STROKE_SMALL else STROKE_REGULAR
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** 路径解析结果；认不出就给 [PluginGlyph.Fallback]，不会返回「空白」。 */
private fun parseGlyph(pathData: String?): Path {
    val data = pathData?.trim()
    if (data != null && isGlyphPath(data)) {
        runCatching { PathParser().parsePathString(data).toPath() }.getOrNull()?.let { return it }
    }
    return PathParser().parsePathString(PluginGlyph.Fallback).toPath()
}

/**
 * 这段字符串能不能当路径用。
 *
 * 不做「先解析再看结果」：非法输入有时不抛异常，而是解出一条空路径 —— 那样
 * 图标会静默消失，比画错更难查。这里改成**字符白名单**：必须由命令字母开头，
 * 且只含命令字母、数字、`.`/`,`、正负号、`e`（科学计数）。emoji、汉字、
 * 普通单词全部当场否掉，正是需要的效果。
 */
internal fun isGlyphPath(data: String): Boolean {
    if (data.isEmpty()) return false
    if (data[0] !in "MmLlHhVvCcSsQqTtAaZz") return false
    return data.all { it in "MmLlHhVvCcSsQqTtAaZz0123456789.,-+eE \t\n" }
}
