package dev.kair.app.app

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.browser.BROWSER_TOOL_PREFIX
import dev.kair.app.core.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 装配接线判据（P4-1 / A2）：`AppContainer` 必须能在 **JVM**（无 Android）上构造。
 *
 * 这条红了就是「还有隐藏的 Context 依赖」——构造期任何一次 `appContext` / `Build` /
 * `context.*` 触碰都会在这里现形（历史上这类依赖只能靠真机发现）。
 * 设置存储经 [FakeSharedPreferences] 注入：`SharedPreferences` 是接口，纯 JVM 可自实现，
 * 不需要 Robolectric（P4-1「不要做」第 1 条）。
 */
class AppContainerWiringTest {

    private fun env(): AppEnvironment {
        val tmp = Files.createTempDirectory("kair-wiring").toFile()
        return AppEnvironment(
            storageRoot = File(tmp, "kair"),
            nativeLibraryDir = "/lib",
            primaryAbi = "arm64-v8a",
            // appContext 故意不给：JVM 里拿不到 Android Context，也不该需要。
        )
    }

    private fun container(): AppContainer = AppContainer(env(), SettingsStore(FakeSharedPreferences()))

    @Test
    fun `container builds without an android context`() {
        val e = env()
        val container = AppContainer(e, SettingsStore(FakeSharedPreferences()))
        // 抛异常 = 仍有隐藏的 Context 依赖（验收点 P4-1）。
        assertEquals("storageRoot 必须指向传入的临时目录", e.storageRoot, container.storageRoot)
        assertTrue("回收站应建在存储根之下", File(e.storageRoot, "trash").isDirectory)
    }

    @Test
    fun `platform ops fail fast without android`() {
        val err = runCatching { container().appContext }.exceptionOrNull()
        assertTrue(
            "appContext 在 JVM 里必须 fail-fast（拿到 null 才是事故形态），实测：$err",
            err is IllegalStateException && (err.message ?: "").contains("Android Context"),
        )
    }

    @Test
    fun `the injected settings store is the one in use`() {
        // 写穿注入的假件，再看**装配产物**里有没有它：只读回 `container.settings` 是自证
        // （那是同一个构造 val），证明不了装配路径用的是它 —— 评审 P2-3。
        val e = env()
        val container = AppContainer(e, SettingsStore(FakeSharedPreferences()))
        container.settings.systemPromptAddendum = "WIRING-PROBE"
        val prompt = container.agentSettings(null, File(e.storageRoot, "ws")).settings.systemPrompt
        assertTrue("注入的设置没有进装配产物：装配读的不是构造传入的 SettingsStore", prompt.contains("WIRING-PROBE"))
    }

    /**
     * 浏览器工具只给 `browser` 子智能体：主会话常驻的是 `subagent` 一个入口，
     * 10 个 `browser_*` schema 不再进每一发请求（它们仍然注册着，子智能体过滤时才取）。
     */
    @Test
    fun `browser tools stay out of the main session tool face`() {
        val container = container()
        val visible = container.tools.all().filter(container.toolEnabled(null)).map { it.name }
        assertTrue(
            "主会话不得常驻 browser_* 工具，实际：${visible.filter { it.startsWith(BROWSER_TOOL_PREFIX) }}",
            visible.none { it.startsWith(BROWSER_TOOL_PREFIX) },
        )
        assertTrue(
            "工具仍须注册，否则 browser 子智能体拿不到",
            container.tools.all().any { it.name.startsWith(BROWSER_TOOL_PREFIX) },
        )
        assertTrue("subagent 入口必须保留", visible.contains("subagent"))
    }
}
