package dev.kair.app.core.plugin.dynamic

import dev.kair.app.core.assumeLinux
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.tools.CommandOutput
import dev.kair.app.core.tools.CommandRunner
import dev.kair.app.core.tools.LocalCommandRunner
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolRegistry
import dev.kair.app.core.tools.ToolResult
import dev.kair.plugin.api.KairApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.kair.plugin.api.Plugin as ApiPlugin
import dev.kair.plugin.api.PluginContext as ApiPluginContext

/** 动态插件宿主：发现 / 安装 / 覆盖 / 卸载 / 禁用持久化，以及脚本工具执行。 */
class DynamicPluginHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一个 host + 它的注册表夹具，便于断言；可注入假 dex 装载器覆盖 dex 链路。 */
    private class Fixture(val root: File, dexLoader: KplugLoader? = null) {
        val registry = PluginRegistry()
        val tools = ToolRegistry()
        val skills = SkillRegistry()
        var resyncCount = 0
        val host = DynamicPluginHost(root, registry, tools, skills, { resyncCount++ }, dexLoader = dexLoader)
    }

    /** JVM 侧的假 dex 装载器：从 test classpath 直接 loadClass，覆盖除真实 dex 外的整条链路。 */
    private fun fakeDexLoader() = KplugLoader(
        File(tmp.newFolder("dex-opt"), "opt"),
        javaClass.classLoader!!,
    ) { _ -> javaClass.classLoader!! }

    private fun writePlugin(
        dir: File,
        id: String,
        toolName: String = "greet",
        script: String = "echo hi",
        version: String = "0.1.0",
    ) {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """
            {
              "id": "$id",
              "name": "$id plugin",
              "description": "test",
              "version": "$version",
              "tools": [{
                "name": "$toolName",
                "description": "test tool",
                "script": "run.sh",
                "snippet": "greet someone"
              }],
              "prompts": [{ "id": "$id.prompt", "order": 55, "text": "Be extra polite." }],
              "skills": "skills"
            }
            """.trimIndent(),
        )
        File(dir, "run.sh").writeText("#!/bin/sh\n$script\n")
        val skillDir = File(dir, "skills/demo").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: demo\ndescription: demo skill\n---\nDemo body.")
    }

    // ---- 安装 / 装载 -----------------------------------------------------------

    @Test
    fun `install registers tools prompts and skills`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "p1"), "p1")

        val result = fx.host.install("p1")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("p1"), fx.host.installedIds())
        assertTrue(fx.resyncCount >= 1)

        val tool = fx.registry.tools().firstOrNull { it.name == "greet" }
        assertNotNull(tool)
        assertEquals("greet someone", tool!!.promptSnippet)
        assertTrue(fx.skills.list().any { it.name == "demo" })
        assertTrue(fx.registry.systemPromptContributors().any { it.id == "p1.prompt" })
    }

    @Test
    fun `version falls back to manifest when plugin declares none`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "p1"), "p1", version = "1.2.3")

        assertTrue(fx.host.install("p1").isSuccess)
        assertEquals("1.2.3", fx.host.version("p1"))
    }

    @Test
    fun `version of disabled plugin is still readable`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "p1"), "p1", version = "1.2.3")
        assertTrue(fx.host.install("p1").isSuccess)

        assertTrue(fx.host.uninstall("p1"))
        assertEquals("1.2.3", fx.host.version("p1"))
    }

    @Test
    fun `version is null when manifest is missing`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        assertNull(fx.host.version("nope"))
    }

    @Test
    fun `plugin with a builtin id is shadowed instead of loaded`() {
        val root = tmp.newFolder("plugins-shadow")
        val registry = PluginRegistry()
        val host = DynamicPluginHost(
            root = root,
            registry = registry,
            tools = ToolRegistry(),
            skills = SkillRegistry(),
            resync = {},
            builtinPluginIds = { setOf("dev.kair.plugin.memory") },
        )
        writePlugin(File(root, "dev.kair.plugin.memory"), "dev.kair.plugin.memory")

        val result = host.install("dev.kair.plugin.memory")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        // 内置优先：外部版本不装载、不算失败，单独记成 shadowed（插件页给卸载入口）。
        assertEquals(listOf("dev.kair.plugin.memory"), host.shadowedIds())
        assertTrue(host.installedIds().isEmpty())
        assertTrue(host.failedIds().isEmpty())
        assertTrue(registry.tools().isEmpty())

        // 卸载外部版本只删插件文件（测试里是文本文件），不涉及数据文件。
        assertTrue(host.delete("dev.kair.plugin.memory"))
        assertTrue(host.shadowedIds().isEmpty())
    }

    @Test
    fun `install fails cleanly on bad manifest`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        File(fx.root, "bad").mkdirs()
        File(fx.root, "bad/manifest.json").writeText("{ not json")

        val result = fx.host.install("bad")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("解析失败"))
        assertEquals(0, fx.host.installedIds().size)
    }

    @Test
    fun `install rejects id mismatch with directory`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "dir-a"), "different-id")
        val result = fx.host.install("dir-a")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不一致"))
    }

    @Test
    fun `install rejects missing script`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val dir = File(fx.root, "p1").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"id":"p1","name":"p","tools":[{"name":"t","description":"d","script":"gone.sh"}]}""",
        )
        val result = fx.host.install("p1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("gone.sh"))
    }

    @Test
    fun `install rejects path traversal id`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        assertTrue(fx.host.install("../evil").isFailure)
        assertTrue(fx.host.install("a/b").isFailure)
    }

    // ---- 覆盖语义 ---------------------------------------------------------------

    @Test
    fun `dynamic tool overrides builtin by name`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val builtin = object : Tool {
            override val name = "greet"
            override val description = "builtin"
            override val parameters = buildJsonObject { }
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("builtin")
        }
        fx.registry.install(object : Plugin {
            override val id = "builtin-holder"
            override val name = "h"
            override val description = ""
            override fun activate(ctx: PluginContext) { ctx.tool(builtin) }
        })
        assertEquals("builtin", fx.registry.tools().first { it.name == "greet" }.description)

        writePlugin(File(fx.root, "p1"), "p1", toolName = "greet")
        assertTrue(fx.host.install("p1").isSuccess)
        assertEquals("test tool", fx.registry.tools().first { it.name == "greet" }.description)
    }

    // ---- 卸载 / 禁用 ------------------------------------------------------------

    @Test
    fun `uninstall removes contributions and disables rediscovery`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "p1"), "p1")
        assertTrue(fx.host.install("p1").isSuccess)
        assertTrue(fx.registry.tools().any { it.name == "greet" })

        assertTrue(fx.host.uninstall("p1"))
        assertFalse(fx.host.installedIds().contains("p1"))
        assertTrue(fx.registry.tools().none { it.name == "greet" })
        assertTrue(fx.skills.list().none { it.name == "demo" })
        assertEquals(listOf("p1"), fx.host.disabledIds())

        // 模拟重启：discover 跳过已禁用者（文件仍在）。
        val rebooted = Fixture(fx.root)
        assertTrue(rebooted.host.discover().isEmpty())
        assertTrue(rebooted.registry.tools().none { it.name == "greet" })

        // 重新 install 解除禁用。
        assertTrue(rebooted.host.install("p1").isSuccess)
        assertTrue(rebooted.host.disabledIds().isEmpty())
    }

    @Test
    fun `uninstall unknown id returns false`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        assertFalse(fx.host.uninstall("nope"))
    }

    @Test
    fun `discover installs all valid plugins sorted`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "b"), "b")
        writePlugin(File(fx.root, "a"), "a")
        File(fx.root, "not-a-plugin").mkdirs()

        val results = fx.host.discover()
        assertEquals(setOf("a", "b"), results.keys)
        assertTrue(results.values.all { it.isSuccess })
        assertEquals(listOf("a", "b"), fx.host.installedIds())
    }

    // ---- 从工作区安装 -------------------------------------------------------------

    @Test
    fun `installFrom copies workspace dir into plugin root`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val src = tmp.newFolder("workspace", "plugins", "cool")
        writePlugin(src, "cool")

        val result = fx.host.installFrom(src, "cool")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("cool"), fx.host.installedIds())
        assertTrue(File(fx.root, "cool/manifest.json").isFile)
        assertTrue(File(fx.root, "cool/run.sh").isFile)
    }

    @Test
    fun `installFrom without manifest fails`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val src = tmp.newFolder("workspace", "plugins", "empty").apply { mkdirs() }
        assertTrue(fx.host.installFrom(src, "empty").isFailure)
    }

    @Test
    fun `reinstall reloads without duplicates`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        writePlugin(File(fx.root, "p1"), "p1")
        assertTrue(fx.host.install("p1").isSuccess)
        assertTrue(fx.host.install("p1").isSuccess)

        assertEquals(listOf("p1"), fx.host.installedIds())
        assertEquals(1, fx.registry.tools().count { it.name == "greet" })
        assertEquals(1, fx.registry.systemPromptContributors().count { it.id == "p1.prompt" })
    }

    // ---- 脚本工具执行 -------------------------------------------------------------

    private fun parseManifest(dir: File): PluginManifest =
        dev.kair.app.core.ai.Json.decodeFromString<PluginManifest>(File(dir, "manifest.json").readText())

    @Test
    fun `script tool passes args via env and captures stdout`() = runBlocking {
        assumeLinux("脚本工具走 /bin/sh")
        val dir = tmp.newFolder("plugin")
        writePlugin(dir, "p1", toolName = "echoer", script = "echo \"tool=\$KAIR_TOOL args=\$KAIR_ARGS\"")
        val tool = ScriptTool(parseManifest(dir).tools.first(), dir)

        val out = tool.execute(
            buildJsonObject { put("name", "world") },
            ToolContext(workspace = tmp.newFolder("ws"), sessionId = "test", commandRunner = LocalCommandRunner(shell = "/bin/sh")),
        )
        assertFalse(out.isError)
        assertTrue(out.content, out.content.contains("tool=echoer"))
        assertTrue(out.content, out.content.contains("args="))
    }

    @Test
    fun `script tool reports non-zero exit as error`() = runBlocking {
        assumeLinux("脚本工具走 /bin/sh")
        val dir = tmp.newFolder("plugin")
        writePlugin(dir, "p1", toolName = "failer", script = "echo boom >&2; exit 3")
        val tool = ScriptTool(parseManifest(dir).tools.first(), dir)

        val out = tool.execute(
            buildJsonObject { },
            ToolContext(workspace = tmp.newFolder("ws"), sessionId = "test", commandRunner = LocalCommandRunner(shell = "/bin/sh")),
        )
        assertTrue(out.isError)
        assertTrue(out.content.contains("boom"))
    }

    @Test
    fun `manifest spec flags flow into tool approval surface`() {
        val dir = tmp.newFolder("plugin")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """
            {"id":"p","name":"p","tools":[{
              "name":"danger","description":"d","script":"s.sh",
              "requiresApproval":true,"approvableForever":false
            }]}
            """.trimIndent(),
        )
        File(dir, "s.sh").writeText("echo ok")
        val tool = ScriptTool(parseManifest(dir).tools.first(), dir)
        assertTrue(tool.requiresApproval)
        assertFalse(tool.approvableForever)
    }

    // ---- dex 插件（.kplug）--------------------------------------------------------

    /** 打一个 .kplug 包（zip）：manifest.json + classes.dex（测试里是假 dex）。 */
    private fun packKplug(target: File, manifest: String, fakeDex: String = "fake dex bytes") {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(fakeDex.toByteArray())
            zos.closeEntry()
        }
    }

    private fun dexManifest(
        id: String,
        entryClass: String = "dev.kair.app.core.plugin.dynamic.TestDexPlugin",
        targetApi: Int = 1,
        extra: String = "",
    ) = """{"id":"$id","name":"$id dex","kind":"dex","entryClass":"$entryClass","targetApi":$targetApi$extra}"""

    @Test
    fun `dex plugin installs via injected loader and registers contributions`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.test").apply { mkdirs() }
        File(dir, "manifest.json").writeText(dexManifest("dex.test"))
        File(dir, "classes.dex").writeText("fake dex bytes")

        val result = fx.host.install("dex.test")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("dex.test"), fx.host.installedIds())

        // 贡献点由 activate() 注册：命令后端 + 工具。
        assertEquals("dex-runner", fx.registry.commandRunner()?.id)
        assertTrue(fx.registry.tools().any { it.name == "dex-tool" })
        // 插件代码声明的版本优先于清单。
        assertEquals("9.9.9", fx.host.version("dex.test"))
    }

    @Test
    fun `dex plugin manifest version is used when plugin declares none`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.broken").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            dexManifest(
                "dex.broken",
                entryClass = "dev.kair.app.core.plugin.dynamic.BrokenActivationPlugin",
                extra = ""","version":"2.3.4"""",
            ),
        )
        File(dir, "classes.dex").writeText("fake dex bytes")

        // 装载失败（activate 抛错）时版本仍要能展示。
        assertTrue(fx.host.install("dex.broken").isFailure)
        assertEquals("2.3.4", fx.host.version("dex.broken"))
    }

    @Test
    fun `dex plugin uninstall rolls back singleton command runner`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.test").apply { mkdirs() }
        File(dir, "manifest.json").writeText(dexManifest("dex.test"))
        File(dir, "classes.dex").writeText("fake dex bytes")
        assertTrue(fx.host.install("dex.test").isSuccess)
        assertEquals("dex-runner", fx.registry.commandRunner()?.id)

        // 卸载：单例后端回滚（无更早提供者 → null，内核回退默认实现）。
        assertTrue(fx.host.uninstall("dex.test"))
        assertNull(fx.registry.commandRunner())
        assertTrue(fx.registry.tools().none { it.name == "dex-tool" })
    }

    @Test
    fun `kplug package is discovered and installed`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        // 包名与 manifest id 无关：按清单 id 装载落位。
        packKplug(File(root, "community-thing.kplug"), dexManifest("dex.test"))

        val results = fx.host.discover()
        assertEquals(setOf("dex.test"), results.keys)
        assertTrue(results.values.all { it.isSuccess })
        assertEquals(listOf("dex.test"), fx.host.installedIds())
        assertEquals("dex-runner", fx.registry.commandRunner()?.id)
        // 解包后的目录形态（下次启动走目录扫描）。
        assertTrue(File(root, "dex.test/manifest.json").isFile)
    }

    @Test
    fun `installFrom accepts kplug package file`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val pkg = tmp.newFile("downloaded.kplug")
        packKplug(pkg, dexManifest("dex.test"))

        val result = fx.host.installFrom(pkg, "dex.test")
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("dex.test"), fx.host.installedIds())
    }

    @Test
    fun `installPackage derives id from manifest and installs`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        // UI「安装 .kplug」路径：调用方只给包文件，id 由清单决定。
        val pkg = tmp.newFile("picked.kplug")
        packKplug(pkg, dexManifest("dex.test"))

        val result = fx.host.installPackage(pkg)
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertTrue(result.getOrThrow().contains("dex.test"))
        assertEquals(listOf("dex.test"), fx.host.installedIds())
        assertEquals("dex-runner", fx.registry.commandRunner()?.id)
    }

    @Test
    fun `installPackage accepts a script directory source`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val src = tmp.newFolder("picked", "cool")
        writePlugin(src, "cool")

        val result = fx.host.installPackage(src)
        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(listOf("cool"), fx.host.installedIds())
        assertTrue(fx.registry.tools().any { it.name == "greet" })
    }

    @Test
    fun `installPackage rejects invalid package`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        val pkg = tmp.newFile("junk.kplug")
        pkg.writeText("not a zip")

        val result = fx.host.installPackage(pkg)
        assertTrue(result.isFailure)
        assertTrue(fx.host.installedIds().isEmpty())
    }

    @Test
    fun `kplug with id mismatch is rejected`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val pkg = tmp.newFile("liar.kplug")
        packKplug(pkg, dexManifest("dex.test"))

        val result = fx.host.installFrom(pkg, "other.id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不一致"))
        assertTrue(fx.host.installedIds().isEmpty())
    }

    @Test
    fun `dex plugin activation link error is reported instead of crashing`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.broken").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            dexManifest(
                "dex.broken",
                entryClass = "dev.kair.app.core.plugin.dynamic.BrokenActivationPlugin",
            ),
        )
        File(dir, "classes.dex").writeText("fake dex bytes")

        // discover 不能让 activate() 里的 AbstractMethodError 冒到启动路径把应用带崩。
        val results = fx.host.discover()
        val result = results.getValue("dex.broken")
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!,
            result.exceptionOrNull()!!.message!!.contains("不兼容"),
        )
        assertEquals(listOf("dex.broken"), fx.host.failedIds())
        assertTrue(fx.host.failureReason("dex.broken")!!.contains("不兼容"))
        assertTrue(fx.host.installedIds().isEmpty())
        assertTrue(fx.registry.tools().none { it.name == "dex-tool" })
    }

    @Test
    fun `compatible dex plugin reports no binary issues`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.test").apply { mkdirs() }
        File(dir, "manifest.json").writeText(dexManifest("dex.test"))
        File(dir, "classes.dex").writeText("fake dex bytes")

        assertTrue(fx.host.install("dex.test").isSuccess)
        assertTrue(fx.host.compatIssues("dex.test").isEmpty())
        assertTrue(fx.host.failedIds().isEmpty())
    }

    @Test
    fun `dex plugin above host api version is rejected`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.test").apply { mkdirs() }
        File(dir, "manifest.json").writeText(dexManifest("dex.test", targetApi = KairApi.VERSION + 1))
        File(dir, "classes.dex").writeText("fake dex bytes")

        val result = fx.host.install("dex.test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("targetApi"))
    }

    @Test
    fun `dex manifest must not declare script contributions`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root, fakeDexLoader())
        val dir = File(root, "dex.test").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            dexManifest("dex.test", extra = ""","tools":[{"name":"t","description":"d","script":"s.sh"}]"""),
        )
        File(dir, "classes.dex").writeText("fake dex bytes")

        val result = fx.host.install("dex.test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("activate()"))
    }

    @Test
    fun `kplug extraction rejects zip slip entries`() {
        val zip = tmp.newFile("evil.kplug")
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val target = tmp.newFolder("slip-out")
        assertTrue(Kplug.extract(zip, target).isFailure)
        assertFalse(File(target.parentFile, "evil.txt").exists())
    }

    // ---- 审批预览 -----------------------------------------------------------------

    @Test
    fun `previewInstall describes script directory source`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root)
        val src = tmp.newFolder("ws", "plugins", "p1")
        writePlugin(src, "p1")

        val preview = fx.host.previewInstall(src)
        assertNotNull(preview)
        assertTrue(preview!!.contains("安装脚本插件"))
        assertTrue(preview.contains("p1"))
        assertTrue(preview.contains("greet"))
    }

    @Test
    fun `previewInstall describes kplug package source`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root)
        val pkg = tmp.newFile("thing.kplug")
        packKplug(pkg, dexManifest("dex.test"))

        val preview = fx.host.previewInstall(pkg)
        assertNotNull(preview)
        assertTrue(preview!!.contains("安装代码插件"))
        assertTrue(preview.contains("dex.test"))
    }

    @Test
    fun `previewInstall returns null for invalid source`() {
        val fx = Fixture(tmp.newFolder("plugins"))
        assertNull(fx.host.previewInstall(File("does-not-exist")))
    }

    @Test
    fun `previewUninstall describes installed plugin or null`() {
        val root = tmp.newFolder("plugins")
        val fx = Fixture(root)
        writePlugin(File(root, "p1"), "p1")
        assertTrue(fx.host.install("p1").isSuccess)

        val preview = fx.host.previewUninstall("p1")
        assertNotNull(preview)
        assertTrue(preview!!.contains("p1"))
        assertNull(fx.host.previewUninstall("nope"))
    }

    // ---- shellQuote ---------------------------------------------------------------

    @Test
    fun `shellQuote escapes single quotes safely`() {
        assertEquals("''\\''evil'\\'''", shellQuote("'evil'"))
        assertEquals("'plain'", shellQuote("plain"))
    }
}

/** dex 链路的测试入口类：在 test classpath 上，由注入的假 KplugLoader 直接加载实例化。 */
class TestDexPlugin : ApiPlugin {
    override val id = "dex.test"
    override val name = "Test Dex Plugin"
    override val description = "registered by test"
    override val version = "9.9.9"

    override fun activate(ctx: ApiPluginContext) {
        ctx.commandRunner(DexTestRunner)
        ctx.tool(DexTestTool)
    }
}

object DexTestRunner : CommandRunner {
    override val id = "dex-runner"
    override val displayName = "Dex Runner"
    override val description = "test runner from dex plugin"
    override suspend fun run(command: String, workspace: File, timeoutSeconds: Long?) =
        CommandOutput("dex", 0)
}

object DexTestTool : Tool {
    override val name = "dex-tool"
    override val description = "tool contributed by dex plugin"
    override val parameters = buildJsonObject { }
    override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("from dex")
}

/** 模拟「按旧版 API 编译的插件在 activate() 里撞上缺失方法」的现场。 */
class BrokenActivationPlugin : ApiPlugin {
    override val id = "dex.broken"
    override val name = "Broken Plugin"
    override val description = "simulates AbstractMethodError during activation"

    override fun activate(ctx: ApiPluginContext): Unit = throw AbstractMethodError("abstract method \"boolean dev.kair.app.core.tools.Tool.getReadOnly()\"")
}
