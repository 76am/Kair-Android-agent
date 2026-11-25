package dev.kair.app.ui.components

import java.util.Locale

internal fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/**
 * 解码速度（U111）：10 以上取整（`28`），10 以下保留一位（`9.6`），不足 1 显示 `<1`。
 *
 * 不一直带小数：状态条每秒刷新，尾数抖动会在等宽小字上闪；取整后读数更稳，
 * 低速率区间才需要一位小数来区分。
 */
internal fun formatSpeed(tps: Double): String = when {
    tps <= 0.0 || tps.isNaN() -> "0"
    tps < 1.0 -> "<1"
    tps < 10.0 -> String.format(Locale.US, "%.1f", tps)
    else -> Math.round(tps).toString()
}

/** 解码耗时 / 首字延迟（U111）：秒为单位，保留一位小数。 */
internal fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms.coerceAtLeast(0L) / 1000.0)

/**
 * 字节数的人类可读表示，**必须带单位**。
 *
 * 缓存面板显示的是请求前缀的「字节」大小，而会话底部圆环显示的是「token」数；两者口径
 * 不同、数值本就不同（同一段中文大约 3 字节 / token）。不带单位时用户会把
 * 「800 KB 前缀」误读成「800k token」，这也是「底部 200k、面板 800k」疑问的来源。
 */
internal fun formatSize(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1f MB", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1f KB", n / 1_000.0)
    else -> "$n B"
}
