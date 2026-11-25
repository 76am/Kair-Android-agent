package dev.kair.app.core

import org.junit.Assume.assumeTrue

/**
 * 测试平台判定（仅测试源集）。
 *
 * `docs/团队分工与交付流程.md` §3.1.1 实测：`:app:testDebugUnitTest` 里有 **36 个用例只能在
 * Linux 跑**，全部集中在执行通道 / 路径 / 权限三块（容器路径映射按 `/` 写死、依赖
 * `chmod` / `chown`、直接起 `/bin/sh`、依赖 tar 的权限位与符号链接语义）。
 * 在 Windows 上这是**平台不具备**，不是代码坏了：
 *
 * - 用 `@Ignore` 会让它们在任何平台都永久沉默（Linux 上也不再跑）；
 * - 用假设跳过，同一份用例在 Linux 上照常执行，Windows 上如实显示 skipped。
 *
 * 滥用防线：每个调用点都必须逐条登记在 `LinuxOnlyGuardTest` 的白名单里，多一处即失败 ——
 * 没有这条守卫，`assumeLinux()` 就成了「把红变绿」的万能开关
 * （`AGENTS.md` §7 与 §10.2：能靠开关复现的旧问题，等于没修）。
 */
fun assumeLinux(why: String) {
    assumeTrue(
        "需要 Linux 语义：$why（当前 OS=${System.getProperty("os.name")}）",
        isLinux(),
    )
}

/** 当前 JVM 跑在 Linux 上吗（Android 单测跑在宿主 JVM，不是设备）。 */
fun isLinux(): Boolean = System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)
