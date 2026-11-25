package dev.kair.app.app

import dev.kair.app.app.fakes.FakeSharedPreferences
import dev.kair.app.core.KotlinSourceScanner
import dev.kair.app.core.plugin.AgentBridge
import dev.kair.app.core.settings.SettingsStore
import dev.kair.app.ui.plugins.builtin.SchedulerPlugin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * U39 判据：**运行时能力必须先注入，插件才可以 activate**。
 *
 * 为什么要有这条：`AppContainer` 有两个 `init`，插件在第一个 `init` 里就被 `installAll`
 * 激活，而运行时桥 / 插件数据根要到第二个 `init` 才注入。`SchedulerPlugin.activate`
 * 把 `ctx.agents` 存进字段，冷启动拿到 `null` 后**不会自愈** —— 它每 30 秒读一次这个字段，
 * 永远投递失败，面板上却显示「已启用」。
 *
 * 两条判据各守一半：
 * 1. 行为：真实 `AppContainer` 构造完之后，**注册表里那个已激活的实例**拿到的桥非空。
 *    （不是 `builtinPluginDefs` —— 那是一份新构造的清单，它没 activate 过，读了必是 null。）
 * 2. 形状：`AppContainer.kt` 里两处注入的源码位置必须早于任何激活入口。数据根没有
 *    行为判据可读（内置插件没有把 `ctx.dataDir` 存成字段的），所以它由形状守卫覆盖。
 */
class PluginBootstrapOrderTest {

    private val appContainerPath = "app/src/main/java/dev/kair/app/app/AppContainer.kt"

    @Test
    fun `builtin plugin sees a runtime bridge when it activates`() {
        val container = container()
        val scheduler = container.plugins.installed.firstOrNull { it.id == SchedulerPlugin.ID }
            ?: error("SchedulerPlugin 未被装载 —— 本用例的前提不成立（它默认是启用的）")
        val agents = readField(scheduler, "agents") as AgentBridge?
        assertNotNull(
            "冷启动 activate 时 ctx.agents 是 null：插件在运行时桥注入之前就被激活了（U39）。" +
                "它不会自愈 —— 后台循环每 30 秒读这个字段，永远投递失败，面板却显示「已启用」。",
            agents,
        )
    }

    @Test
    fun `runtime capabilities are injected before any plugin activation`() {
        val code = KotlinSourceScanner.stripped(repoFile(appContainerPath).readText(Charsets.UTF_8))
        val bridgeAt = code.indexOf("setAgentBridge(")
        val dataRootAt = code.indexOf("setPluginDataRoot(")
        assertTrue("AppContainer 里找不到 setAgentBridge( —— 注入口改名后本守卫失效，请同步更新", bridgeAt >= 0)
        assertTrue("AppContainer 里找不到 setPluginDataRoot( —— 注入口改名后本守卫失效，请同步更新", dataRootAt >= 0)

        val activationSites = listOf("installAll(", "discover()")
        val missing = activationSites.filter { code.indexOf(it) < 0 }
        assertTrue("AppContainer 里找不到激活入口 $missing —— 扫描落空不等于通过，请同步更新守卫", missing.isEmpty())

        val earliestActivation = activationSites.minOf { code.indexOf(it) }
        assertTrue(
            "`plugins.installAll(` / `dynamicPlugins.discover()` 出现在运行时桥注入之前（U39）：" +
                "插件会在 ctx.agents 为 null 时 activate。当前桥注入位置 $bridgeAt、最早激活位置 $earliestActivation。",
            bridgeAt < earliestActivation,
        )
        assertTrue(
            "插件数据根注入晚于激活入口（U39）：activate 期间 ctx.dataDir 为 null。" +
                "当前数据根注入位置 $dataRootAt、最早激活位置 $earliestActivation。",
            dataRootAt < earliestActivation,
        )
    }

    private fun readField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun container(): AppContainer = AppContainer(env(), SettingsStore(FakeSharedPreferences()))

    private fun env(): AppEnvironment {
        val tmp = Files.createTempDirectory("kair-bootstrap-order").toFile()
        return AppEnvironment(
            storageRoot = File(tmp, "kair"),
            nativeLibraryDir = "/lib",
            primaryAbi = "arm64-v8a",
        )
    }

    private fun repoFile(relativePath: String): File = findRepoRoot().resolve(relativePath)

    private fun findRepoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "docs").isDirectory && File(dir, "app").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（须含 docs/ 与 app/），形状守卫无法运行")
    }
}
