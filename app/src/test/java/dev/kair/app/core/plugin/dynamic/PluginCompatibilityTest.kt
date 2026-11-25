package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** J.1 第三方插件安装兼容性检查：版本比对、区间校验、id 冲突、严格 / 强制安装路径。 */
class PluginCompatibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest(
        id: String = "p1",
        kind: String = PluginManifest.KIND_SCRIPT,
        targetApi: Int = 1,
        minKairVersion: String? = null,
        maxKairVersion: String? = null,
    ) = PluginManifest(
        id = id,
        name = id,
        kind = kind,
        targetApi = targetApi,
        minKairVersion = minKairVersion,
        maxKairVersion = maxKairVersion,
    )

    // ---- 版本比对 ---------------------------------------------------------------

    @Test
    fun `compareVersions orders by dotted segments`() {
        assertTrue(PluginCompatibility.compareVersions("1.0.0", "1.0.0") == 0)
        assertTrue(PluginCompatibility.compareVersions("1.2.0", "1.1.9") > 0)
        assertTrue(PluginCompatibility.compareVersions("0.9.9", "1.0.0") < 0)
        // 段数不足按 0 补齐；预发布后缀忽略。
        assertTrue(PluginCompatibility.compareVersions("1.2", "1.2.0") == 0)
        assertTrue(PluginCompatibility.compareVersions("1.2.0-beta", "1.2.0") == 0)
    }

    // ---- 检查项 -----------------------------------------------------------------

    @Test
    fun `compatible manifest yields no reasons`() {
        val reasons = PluginCompatibility.check(
            manifest(minKairVersion = "0.9.0", maxKairVersion = "2.0.0"),
        )
        assertTrue(reasons.toString(), reasons.isEmpty())
    }

    @Test
    fun `host below min version reports too low`() {
        val reasons = PluginCompatibility.check(manifest(minKairVersion = "2.0.0"))
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("版本过低"))
    }

    @Test
    fun `host above max version reports too high`() {
        val reasons = PluginCompatibility.check(manifest(maxKairVersion = "0.5.0"))
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("版本过高"))
    }

    @Test
    fun `dex target api above host is flagged`() {
        val reasons = PluginCompatibility.check(
            manifest(kind = PluginManifest.KIND_DEX, targetApi = 99),
        )
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("targetApi"))
    }

    @Test
    fun `id clash with a builtin plugin is flagged`() {
        val reasons = PluginCompatibility.check(
            manifest(id = "dev.kair.plugin.subagents"),
            builtinPluginIds = setOf("dev.kair.plugin.subagents"),
        )
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("冲突"))
    }

    // ---- 宿主安装路径 -----------------------------------------------------------

    private class Fixture(val root: File, builtins: Set<String>, strict: Boolean) {
        val host = DynamicPluginHost(
            root = root,
            registry = PluginRegistry(),
            tools = ToolRegistry(),
            skills = SkillRegistry(),
            resync = {},
            builtinPluginIds = { builtins },
            strictCompat = { strict },
        )
    }

    private fun writeScriptPlugin(dir: File, id: String, minKair: String) {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """
            {
              "id": "$id",
              "name": "$id",
              "tools": [{"name": "t", "description": "d", "script": "run.sh"}],
              "minKairVersion": "$minKair"
            }
            """.trimIndent(),
        )
        File(dir, "run.sh").writeText("#!/bin/sh\necho ok\n")
    }

    @Test
    fun `strict mode rejects incompatible plugin with typed exception`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, builtins = emptySet(), strict = true)
        writeScriptPlugin(File(root, "p1"), "p1", "99.0.0")

        val result = fx.host.install("p1")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is PluginIncompatibleException)
        assertTrue((ex as PluginIncompatibleException).reasons.first().contains("版本过低"))
        assertTrue(fx.host.installedIds().isEmpty())
    }

    @Test
    fun `force install bypasses the compatibility gate`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, builtins = emptySet(), strict = true)
        writeScriptPlugin(File(root, "p1"), "p1", "99.0.0")

        val result = fx.host.install("p1", force = true)
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        // 结果里附带「跳过兼容检查」告警。
        assertTrue(result.getOrThrow().contains("跳过兼容检查"))
        assertEquals(listOf("p1"), fx.host.installedIds())
    }

    @Test
    fun `skip mode installs incompatible plugin with a warning`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, builtins = emptySet(), strict = false)
        writeScriptPlugin(File(root, "p1"), "p1", "99.0.0")

        val result = fx.host.install("p1")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertTrue(result.getOrThrow().contains("跳过兼容检查"))
        assertEquals(listOf("p1"), fx.host.installedIds())
    }

    @Test
    fun `id clash with builtin is shadowed instead of rejected`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, builtins = setOf("dev.kair.plugin.subagents"), strict = true)
        writeScriptPlugin(File(root, "dev.kair.plugin.subagents"), "dev.kair.plugin.subagents", "0.1.0")

        // U64 起「内置优先」：同名外部版本不装载、也不算失败，记成 shadowed 由插件页给卸载入口
        // （`PluginCompatibility.check` 仍会给出「与内置插件冲突」，但宿主装载路径先短路到 shadowed）。
        val result = fx.host.install("dev.kair.plugin.subagents")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("dev.kair.plugin.subagents"), fx.host.shadowedIds())
        assertTrue(fx.host.installedIds().isEmpty())
    }
}
