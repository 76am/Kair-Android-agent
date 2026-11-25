package dev.kair.app.core.tools

import dev.kair.app.core.device.AdbOutcome

/**
 * 把 adb 的可判定失败折成「下一步做什么」，别让模型对着一个 reason 干猜。
 *
 * `device_shell` 与 `device_screen` 共用这一份：两条通道的失败面完全相同（同一个 `AdbLink`、
 * 同一把密钥、同一条回环连接），各写一份迟早会漂。
 *
 * 文案是英文且不带中文——它进 tool 结果回给模型，不是界面文案，core 里也不允许中文字面量
 * （AGENTS.md §4 / DocsConsistencyTest A6-4）。
 */
internal fun adbFailureHint(reason: AdbOutcome.Reason, detail: String = ""): String = when (reason) {
    AdbOutcome.Reason.NOT_PAIRED ->
        "This device is not paired yet. Ask the user to open the ADB panel and pair once " +
            "(Settings -> Developer options -> Wireless debugging), then retry."

    AdbOutcome.Reason.NO_PORT ->
        "Cannot determine the adbd TLS port. Ask the user to switch wireless debugging on. If it still " +
            "fails, they can enter the connection address once in the ADB panel."

    AdbOutcome.Reason.CONNECT_FAILED ->
        "Connected nowhere: adbd refused $detail. Wireless debugging is probably off, or its " +
            "port changed after it was restarted."

    AdbOutcome.Reason.TIMEOUT ->
        "The call did not finish in time ($detail). It may be waiting for input or running " +
            "forever; narrow it down instead of retrying the same thing."

    AdbOutcome.Reason.IO ->
        "adb I/O error: $detail"
}
