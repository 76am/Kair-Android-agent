package dev.kair.app.ui.plugins.builtin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kair.app.R
import dev.kair.app.core.agent.CacheReport
import dev.kair.app.core.agent.CacheSample
import kotlin.math.roundToInt

/**
 * 命中率趋势折线：纵轴固定 0%..100%，三条水平网格线分别为 0% / 50% / 100%。
 * 样本按时间从左到右排列，最新的在最右端。
 *
 * 随报告正文一起从 `CachePanel` 搬来 —— 它只被正文用，放在同一个文件里。
 */
@Composable
internal fun CacheHitRateChart(samples: List<CacheSample>) {
    if (samples.isEmpty()) return
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 4.dp),
    ) {
        val w = size.width
        val h = size.height
        listOf(0f, 0.5f, 1f).forEach { f ->
            val y = h * (1f - f)
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        val ys = samples.map { h * (1f - it.rate.toFloat().coerceIn(0f, 1f)) }
        if (ys.size == 1) {
            drawCircle(lineColor, radius = 4f, center = Offset(w / 2f, ys[0]))
            return@Canvas
        }
        val step = w / (ys.size - 1)
        val points = ys.mapIndexed { i, y -> Offset(i * step, y) }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
        points.forEach { drawCircle(lineColor, radius = 3f, center = it) }
    }
}

/**
 * 缓存报告的正文（命中率累计 + 趋势 / 段落缓存读数）。
 * 从 `CachePanel` 抽出：加完会话选择器后主函数体 203 行，撞 `MethodSizeGuardTest`
 * 的 200 阈值（正确做法是拆，不是加白名单）。
 *
 * **头部那几行解说在第四轮全删了**（负责人 2026-11-04）：模型名、前缀稳定性判定、
 * 前缀字节数、提供商实测 token。「前缀被改写」这个信号没丢 —— 它就在下面分段列表里，
 * 每段的变化列写「改写」并标红，比一句总判定更直接指出**是哪一段**动的。
 *
 * 只读：不参与请求装配，也不改变任何一位请求字节。
 */
@Composable
internal fun CacheReportBody(report: CacheReport, modifier: Modifier = Modifier) {
    // 不自带 padding：调用方（CachePanel 的 Column）已经垫了 16dp，再垫一次就是双重内边距。
    // 行距自带：这些行原本靠在 CachePanel 的 spacedBy(10.dp) 里，搬出来得自己带。
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 累计口径（按 token 加权）是面板里**唯一**的命中率数字（负责人 2026-11-04 定）：
        // 单发比值天生在两端跳（重写前缀那一发低、下一发几乎全命中），它只留在下面的
        // 趋势折线里逐点出现，不再单独占一行 —— 一行「最近命中率 0%」既刺眼又最容易被当结论读。
        // 提供商没回缓存字段的那些**发**仍要显形，不然趋势图少点而不说
        // （计数单位是「发」= 一次 usage 事件，一个回合最多 maxSteps 发 —— 第三轮 B 审 P2）。
        report.hitTally?.let { tally ->
            val percent = tally.weightedRate?.let { (it * 100).roundToInt() }
            Text(
                when {
                    percent != null && tally.unratedRequests == 0 -> stringResource(
                        R.string.cache_hit_weighted, percent, tally.ratedRequests,
                    )
                    percent != null -> stringResource(
                        R.string.cache_hit_weighted_unrated, percent, tally.ratedRequests, tally.unratedRequests,
                    )
                    else -> stringResource(R.string.cache_hit_weighted_none, tally.unratedRequests)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 命中率趋势：把同一会话最近若干次观测画成折线，判断缓存是在变好还是变坏。
        // 样本按会话取（`CacheInspector` 每会话各留一池），不再从全进程共用的池里筛
        // —— 后者会让并发会话把当前会话的历史挤成「只剩最近两发」。
        val samples = report.samples
        if (samples.isNotEmpty()) {
            Text(
                stringResource(R.string.cache_hit_trend, samples.size),
                style = MaterialTheme.typography.titleSmall,
            )
            CacheHitRateChart(samples)
        }

        // 本地段落缓存（U19 的观测面）：省的是**重算**（读工作区文件、拼正文），
        // 一个 token 都不省 —— 与上面提供商侧那两个口径分开放，别顺着读成一个数。
        report.sectionCache?.let { cache ->
            if (cache.sections > 0) {
                Text(
                    stringResource(R.string.cache_section_stats, cache.hits, cache.sections),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cache.misses == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (cache.misses > 0) {
                    Text(
                        stringResource(
                            R.string.cache_section_miss,
                            cache.misses, cache.missesNothingToReuse, cache.missesInputChanged,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 默认设置下「每回合刷新提示」是开的，于是每一发的请求装配都必然全未命中 ——
                // 这行不说清楚，读数看着就像坏了。（它同时也是方案 B 的靶子：那些重算值不值。）
                if (cache.missesNothingToReuse > 0) {
                    Text(
                        stringResource(R.string.cache_section_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
