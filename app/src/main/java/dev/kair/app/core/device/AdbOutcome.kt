package dev.kair.app.core.device

/**
 * 设备 shell 一次调用的结果。失败只给**可判定的原因**，文案由调用方（UI / 工具）决定，
 * 这样 core 里不出现中文字面量（AGENTS.md §4 / DocsConsistencyTest A6-4），
 * 也不把「为什么失败」压成一句给模型看的自由文本。
 *
 * 纯类型：core 不得 import Android framework（ArchitectureGuardTest），所以只放数据与枚举，
 * 连接实现在 `ui/plugins/builtin/AdbLink.kt`（要 Context / libadb）。
 */
sealed interface AdbOutcome {
    data class Ok(val output: String) : AdbOutcome

    data class Failed(val reason: Reason, val detail: String = "") : AdbOutcome

    enum class Reason {
        /** 本机没有配对标记：先去 ADB 面板配对。 */
        NOT_PAIRED,

        /** 拿不到 adbd 的 TLS 端口，且没有可回退的上次地址。 */
        NO_PORT,

        /** 端口有但连不上：无线调试关了，或端口又变了。 */
        CONNECT_FAILED,

        TIMEOUT,
        IO,
    }
}
