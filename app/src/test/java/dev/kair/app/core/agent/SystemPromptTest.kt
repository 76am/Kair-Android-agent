package dev.kair.app.core.agent

import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.storage.Delivery
import dev.kair.app.core.tools.BashTool
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.ui.plugins.builtin.SystemPromptPlugin
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    private class FixedContributor(
        override val id: String,
        override val order: Int,
        private val body: String?,
    ) : SystemPromptContributor {
        override fun section(ctx: SystemPromptContext): String? = body
    }

    /** 正文取自「缓存输入之外」的可变状态，用于验证缓存失效时机（类比记忆插件读文件）。 */
    private class StateBackedContributor(
        override val id: String,
        override val order: Int,
        var body: String?,
    ) : SystemPromptContributor {
        override fun section(ctx: SystemPromptContext): String? = body
    }

    private fun context(addendum: String = "") = SystemPromptContext("/work", emptyList(), addendum)

    @Test
    fun `sections are assembled by order, not registration order`() {
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("b", 20, "B"))
                ctx.systemPrompt(FixedContributor("a", 10, "A"))
            }
        })

        val prompt = SystemPromptBuilder.build(context(), registry.systemPromptContributors())

        assertEquals("A\n\nB", prompt)
    }

    @Test
    fun `blank and null sections are skipped`() {
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("a", 0, "A"))
                ctx.systemPrompt(FixedContributor("empty", 10, ""))
                ctx.systemPrompt(FixedContributor("null", 20, null))
                ctx.systemPrompt(FixedContributor("c", 30, "C"))
            }
        })

        assertEquals("A\n\nC", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))
    }

    @Test
    fun `builtin plugin produces every section in a stable position`() {
        val registry = PluginRegistry().install(SystemPromptPlugin())

        val prompt = SystemPromptBuilder.build(context(), registry.systemPromptContributors())

        assertTrue(prompt.startsWith("You are an expert coding assistant operating inside Kair"))
        // 交付路径：段落缺席时模型只能自己猜 `/sdcard/Download/`（曾经的回归：
        // 段落对象进了锁定集 / 内置清单，却漏了内置插件里的 ctx.systemPrompt 注册）。
        assertTrue("交付路径必须在系统提示里", prompt.contains(Delivery.PATH))
        // Kair 能力自述：紧跟开场白、在工具清单之前，且说清「container_shell 需要用户先装容器」
        assertTrue(prompt.contains("<kair>"))
        assertTrue(prompt.contains("Linux workspace"))
        assertTrue(prompt.contains("`container_shell` is registered only once its rootfs is ready"))
        assertTrue(prompt.contains("http://127.0.0.1:<port>"))
        // 图形桌面：静态能力自述，给出截图闭环；桌面启停本身不得写进前缀。
        assertTrue(prompt.contains("Graphical desktop (optional)"))
        assertTrue(prompt.contains("DISPLAY=:1"))
        assertTrue(prompt.contains("scrot"))
        assertTrue(prompt.contains("<tools>\n(none)"))
        assertTrue(prompt.contains("<rules>"))
        assertTrue(prompt.contains("<cwd>\n/work\n</cwd>"))
        assertFalse(prompt.contains("<addendum>"))

        val deliveryAt = prompt.indexOf(Delivery.PATH)
        val kairAt = prompt.indexOf("<kair>")
        val toolsAt = prompt.indexOf("<tools>")
        val rulesAt = prompt.indexOf("<rules>")
        val cwdAt = prompt.indexOf("<cwd>")
        assertTrue("交付路径应排在能力自述之前（order 2）", deliveryAt in 1 until kairAt)
        assertTrue(kairAt in 1 until toolsAt)
        assertTrue(toolsAt in 1 until rulesAt)
        assertTrue(rulesAt < cwdAt)
    }

    @Test
    fun `graphical desktop facts are constant, not runtime state`() {
        // 桌面启停不能改变前缀：能力段只给常量事实（DISPLAY / scrot / read_image），
        // 同一上下文连续两次产出必须逐字相同；启停状态走不了这里。
        val first = CapabilitiesContributor.section(context())
        assertEquals(first, CapabilitiesContributor.section(context()))
        assertTrue(first.contains("read_image"))
        assertTrue(first.contains("device_screen"))
    }

    @Test
    fun `rules forbid controlling the phone without permission`() {
        // 手机是用户的私人设备：未经明确要求不得切后台 / 点击 / 截图；容器里的桌面环境不受限。
        val ctx = SystemPromptContext("/work", emptyList(), "")
        val prompt = SystemPromptBuilder.build(ctx, listOf(RulesContributor))
        assertTrue(prompt.contains("Never control the user's phone"))
        assertTrue(prompt.contains("no device screenshots"))
        assertTrue(prompt.contains("graphical desktop inside the Linux container"))
    }

    @Test
    fun `builtin registry is the only source of sections`() {
        // 注册清单、锁定集、面板内置文案都从 BuiltinPromptSections 派生：
        // 注册必须与之逐 id 相等（多注册 / 少注册都要红）。
        val registered = PluginRegistry().install(SystemPromptPlugin())
            .systemPromptContributors()
            .map { it.id }
            .toSet()
        assertEquals(BuiltinPromptSections.contributors.map { it.id }.toSet(), registered)

        // 每个内置段落都必须真的产出正文：加进注册表却永远返回空串同样是静默缺席。
        val ctx = SystemPromptContext("/work", emptyList(), "note", "/work")
        BuiltinPromptSections.contributors.forEach { c ->
            val body = c.section(ctx)
            assertTrue("内置段落 ${c.id} 不该沉默", body != null && body.isNotBlank())
        }

        // 锁定集只由注册表决定，不再有第二份手写名单。
        assertEquals(
            BuiltinPromptSections.entries.filter { it.locked }.map { it.contributor.id }.toSet(),
            SystemPromptBuilder.LockedSectionIds,
        )
        assertTrue(SystemPromptBuilder.LockedSectionIds.all { id -> id in registered })
    }

    @Test
    fun `rules explain the system-note wrapper`() {
        val prompt = SystemPromptBuilder.build(
            SystemPromptContext("/work", emptyList(), ""),
            listOf(RulesContributor),
        )
        assertTrue("模型必须知道 <system-note> 是 Kair 注入的", prompt.contains("<system-note>"))
        assertTrue("规则必须说明它不是用户输入", prompt.contains("not typed by the user"))
    }

    @Test
    fun `bash guidelines carry scheduling truth and mount persistence`() {
        // 调度透明（X2）：同回合多条 bash 串行 + 并行安全清单 + 两种并发姿势。
        // agent 之前只能靠猜 —— 真机已证实它会误以为 bash 并行、白排一整轮队。
        // 挂载常驻（X1）：setsid 逃逸进程仍能看到 /projects 的承诺已写进守则，
        // 删掉任何一句都必须红在这里。
        val ctx = SystemPromptContext("/work", listOf(BashTool()), "")
        val prompt = SystemPromptBuilder.build(ctx, listOf(RulesContributor))
        assertTrue("调度事实（串行）不得从守则消失", prompt.contains("one at a time"))
        assertTrue("并发姿势（& + wait / subagent）不得消失", prompt.contains("`&` + `wait`"))
        assertTrue("挂载常驻承诺不得消失", prompt.contains("Mounts persist across commands"))
        // 并行清单必须与代码声明一致：远端 MCP 工具 isParallelSafe=false，只有 mcp_list 是 true。
        assertTrue("并行清单漏了 mcp_list", prompt.contains("mcp_list"))
        assertFalse("远端 MCP 工具默认串行，不得声称它们并行", prompt.contains("MCP tools run in parallel"))
    }

    @Test
    fun `bash rules let the model resolve which channel it runs on`() {
        // U36：执行通道（chroot / proot）只有宿主知道，容器内 `id -u` 两档都回 0。
        // 守则若只甩一个通道名词，模型只能猜（真机怀疑它默认当成 proot，白绕 setsid 出口）。
        // 所以守则必须给出自查抓手，且**不得**断言当前取值 —— 取值进前缀就随设备变化。
        val ctx = SystemPromptContext("/work", listOf(BashTool()), "")
        val prompt = SystemPromptBuilder.build(ctx, listOf(RulesContributor))
        assertTrue("setsid 守则要给出自查变量", prompt.contains("KAIR_CHANNEL"))
        assertFalse("不得把宿主内部说法当判据甩给模型", prompt.contains("chroot channel only"))
        // description 进 TOOLS 段（同样是前缀）：断言真 root 只有 chroot 档成立。
        assertFalse(
            "描述不得替模型断言它在真 root 下（proot 是 ptrace 假装）",
            BashTool().description.contains("as root"),
        )
    }

    @Test
    fun `same logical state yields identical prompt`() {
        val registry = PluginRegistry().install(SystemPromptPlugin())

        val first = SystemPromptBuilder.build(context(), registry.systemPromptContributors())
        val second = SystemPromptBuilder.build(context(), registry.systemPromptContributors())

        assertEquals(first, second)
    }

    @Test
    fun `invalidate refreshes sections whose state is outside the cache key`() {
        val contributor = StateBackedContributor("test.prompt.state", 10, "old")
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.state"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(contributor)
            }
        })

        assertEquals("old", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))

        // 外部状态变了，但缓存输入（实例 / cwd / addendum / 工具列表）没变 → 仍复用旧段。
        contributor.body = "new"
        assertEquals("old", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))

        // 压缩等「前缀本就重写」的时机清缓存后，才重新读取外部状态。
        SystemPromptBuilder.invalidate()
        assertEquals("new", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))
    }

    @Test
    fun `null section is not cached so a transient read failure can recover`() {
        SystemPromptBuilder.invalidate()
        // 类比记忆插件：文件读失败 → null；权限修好后同一缓存键下应能重新产出。
        val contributor = StateBackedContributor("test.prompt.null-recover", 10, null)
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.null-recover"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(contributor)
            }
        })

        assertEquals("", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))

        contributor.body = "## 跨会话记忆"
        assertEquals(
            "## 跨会话记忆",
            SystemPromptBuilder.build(context(), registry.systemPromptContributors()),
        )
    }

    @Test
    fun `a throwing contributor is skipped without breaking the whole prompt`() {
        // 类比第三方贡献点 / 读到不可读 SKILL.md 的内置技能段：抛异常只能让这一段缺席。
        val thrower = object : SystemPromptContributor {
            override val id = "test.prompt.throwing"
            override val order = 10
            var fail = true
            override fun section(ctx: SystemPromptContext): String? {
                if (fail) error("boom")
                return "RECOVERED"
            }
        }
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.throwing"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("test.prompt.before", 0, "A"))
                ctx.systemPrompt(thrower)
                ctx.systemPrompt(FixedContributor("test.prompt.after", 20, "C"))
            }
        })

        assertEquals("A\n\nC", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))

        // 异常不冻结成永久缺席：恢复后同一缓存键下应重新产出。
        thrower.fail = false
        assertEquals(
            "A\n\nRECOVERED\n\nC",
            SystemPromptBuilder.build(context(), registry.systemPromptContributors()),
        )
    }

    @Test
    fun `throwingContributorDegradesToAbsentSection`() {
        // 隔离舱：外部贡献点抛异常 → 该段缺席，其余段落齐全，整段装配不抛。
        val thrower = object : SystemPromptContributor {
            override val id = "test.prompt.throwing-detail"
            override val order = 10
            override fun section(ctx: SystemPromptContext): String? = error("boom")
        }
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.throwing-detail"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("test.prompt.before", 0, "A"))
                ctx.systemPrompt(thrower)
                ctx.systemPrompt(FixedContributor("test.prompt.after", 20, "C"))
            }
        })

        val assembly = SystemPromptBuilder.buildDetailed(context(), registry.systemPromptContributors())

        assertEquals("A\n\nC", assembly.text)
        // 隔离与可见必须成对：缺席要能说清是哪个贡献点、抛了什么类型的异常。
        assertEquals(
            listOf(SectionIssue(thrower.id, SectionIssue.Kind.FAILED, "IllegalStateException")),
            assembly.issues,
        )
    }

    @Test
    fun `absentSectionIsReported`() {
        // 注册了就应当有产出：返回 null / 空串同样是静默缺席，必须能在诊断里看见 id。
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.absent"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("test.prompt.absent.a", 0, "A"))
                ctx.systemPrompt(FixedContributor("test.prompt.absent.null", 10, null))
                ctx.systemPrompt(FixedContributor("test.prompt.absent.blank", 20, "   "))
            }
        })

        val assembly = SystemPromptBuilder.buildDetailed(context(), registry.systemPromptContributors())

        assertEquals("A", assembly.text)
        assertEquals(
            mapOf(
                "test.prompt.absent.null" to SectionIssue.Kind.ABSENT,
                "test.prompt.absent.blank" to SectionIssue.Kind.ABSENT,
            ),
            assembly.issues.associate { it.id to it.kind },
        )
        // 缺席条目不携带异常类型：ABSENT 是「没产出」，不是「抛了」。
        assertTrue(assembly.issues.all { it.detail == null })
    }

    @Test
    fun `normalPathBytesUnchanged`() {
        // 缓存前缀契约：诊断接入不得改变任何一位输出字节；两次装配逐字节相等，
        // 且 build 与 buildDetailed 是同一条装配路径。
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.bytes"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(FixedContributor("test.prompt.bytes.a", 0, "A"))
                ctx.systemPrompt(FixedContributor("test.prompt.bytes.b", 10, "B"))
            }
        })
        SystemPromptBuilder.invalidate()

        val first = SystemPromptBuilder.buildDetailed(context(), registry.systemPromptContributors())
        val second = SystemPromptBuilder.buildDetailed(context(), registry.systemPromptContributors())

        assertEquals("A\n\nB", first.text)
        assertEquals(first.text, second.text)
        assertEquals(first.text, SystemPromptBuilder.build(context(), registry.systemPromptContributors()))
        assertTrue("正常路径不该有诊断条目", first.issues.isEmpty() && second.issues.isEmpty())
    }

    @Test
    fun `normal path with builtin sections has no issues`() {
        // 默认配置（用户没填「附加系统指令」）就是一条诊断都不该有：
        // 面板的「一切正常」只在 issues 为空时出现，内置段落的合法空白不该占这个位置。
        SystemPromptBuilder.invalidate()
        val assembly = SystemPromptBuilder.buildDetailed(
            SystemPromptContext(cwd = "/work", tools = emptyList(), addendum = ""),
            BuiltinPromptSections.contributors,
        )
        assertTrue(
            "默认配置（未填附加指令）不该有诊断条目，实际：${assembly.issues}",
            assembly.issues.isEmpty(),
        )
    }

    @Test
    fun `isolation range is decided by builtin instance not id`() {
        // 内置段落抛异常是内核 bug，必须是响亮的失败；判据用实例，撞名的插件不受豁免。
        assertTrue(BuiltinPromptSections.isBuiltin(PreambleContributor))
        assertFalse(BuiltinPromptSections.isBuiltin(FixedContributor(PreambleContributor.id, 0, "fake")))
    }

    @Test
    fun `builtin contributor failure bubbles instead of becoming an absent section`() {
        // 上一条只测**判据**（isBuiltin 认不认得），没测**行为**（真抛了会不会冒泡）。
        // 主程变异 1 红的那两条都在守「外部要隔离」，没有一条在守「内置要冒泡」——
        // 于是「把隔离舱扩大到全部贡献点」这种回退（= 基线旧行为）当时没有任何用例变红。
        // 桩：内置 ToolsContributor 会读 ctx.tools 的 promptSnippet，塞一个 getter 抛异常的
        // Tool 就能制造「内核 bug」，不必改生产代码。
        val hostile = object : Tool {
            override val name = "test.hostile-tool"
            override val description = "hostile"
            override val parameters = JsonObject(emptyMap())
            override val promptSnippet: String get() = throw IllegalStateException("builtin section boom")
            override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
                throw AssertionError("本用例不执行工具")
        }
        SystemPromptBuilder.invalidate()

        val thrown = runCatching {
            SystemPromptBuilder.buildDetailed(SystemPromptContext("/work", listOf(hostile), ""), listOf(ToolsContributor))
        }.exceptionOrNull()

        assertTrue(
            "内置段落抛异常必须冒泡（内核 bug 要响亮失败），实际异常：${thrown?.javaClass?.name}",
            thrown is IllegalStateException,
        )
        assertEquals("builtin section boom", thrown?.message)
    }

    @Test
    fun `alternating contexts reuse their own cached section`() {
        SystemPromptBuilder.invalidate()
        var calls = 0
        val contributor = object : SystemPromptContributor {
            override val id = "test.prompt.multi-context"
            override val order = 0
            override fun section(ctx: SystemPromptContext): String {
                calls++
                return "addendum=${ctx.addendum}"
            }
        }
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.multi-context"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(contributor)
            }
        })

        // 主会话与子智能体（不同上下文）交替装配：各自的槽位不得互相覆盖。
        assertEquals("addendum=a", SystemPromptBuilder.build(context("a"), registry.systemPromptContributors()))
        assertEquals("addendum=b", SystemPromptBuilder.build(context("b"), registry.systemPromptContributors()))
        assertEquals("addendum=a", SystemPromptBuilder.build(context("a"), registry.systemPromptContributors()))
        assertEquals(2, calls)
    }

    @Test
    fun `section computed across an invalidate is not frozen into the cache`() {
        SystemPromptBuilder.invalidate()
        var body = "old"
        val contributor = object : SystemPromptContributor {
            override val id = "test.prompt.invalidate-race"
            override val order = 0
            override fun section(ctx: SystemPromptContext): String {
                val produced = body
                if (produced == "old") {
                    // 模拟另一个会话在本次计算期间失效缓存（工具执行 / 文件保存后）。
                    body = "new"
                    SystemPromptBuilder.invalidate()
                }
                return produced
            }
        }
        val registry = PluginRegistry().install(object : Plugin {
            override val id = "test.prompt.invalidate-race"
            override val name = "test"
            override val description = ""
            override fun activate(ctx: PluginContext) {
                ctx.systemPrompt(contributor)
            }
        })

        assertEquals("old", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))
        // 失效后写回的旧结果必须被丢弃，否则旧正文会一直被冻结到下次失效。
        assertEquals("new", SystemPromptBuilder.build(context(), registry.systemPromptContributors()))
    }

    @Test
    fun `addendum appears only when non-blank and before cwd`() {
        val registry = PluginRegistry().install(SystemPromptPlugin())

        val prompt = SystemPromptBuilder.build(
            context(addendum = "  be terse  "),
            registry.systemPromptContributors(),
        )

        assertTrue(prompt.contains("<addendum>\nbe terse\n</addendum>"))
        assertTrue(prompt.indexOf("<addendum>") < prompt.indexOf("<cwd>"))
    }

    @Test
    fun `cwd section also exposes host workspace path when container is active`() {
        val registry = PluginRegistry().install(SystemPromptPlugin())

        // 容器未生效：cwd 即宿主工作区，不额外出 <workspace>。
        val noContainer = SystemPromptBuilder.build(
            SystemPromptContext("/work", emptyList(), ""),
            registry.systemPromptContributors(),
        )
        assertTrue(noContainer.contains("<cwd>\n/work\n</cwd>"))
        assertFalse(noContainer.contains("<workspace>"))
        // 容器未生效时映射规则一并缺席：宿主路径在那里合法，警告反而误导。

        SystemPromptBuilder.invalidate()

        // 容器生效：cwd 是容器内挂载点，另给宿主真实路径，避免模型把两者混为一谈。
        val withContainer = SystemPromptBuilder.build(
            SystemPromptContext("/workspace", emptyList(), "", workspace = "/data/user/0/dev.kair.app/files/kair/projects/workspaces/x"),
            registry.systemPromptContributors(),
        )
        assertTrue(withContainer.contains("<workspace>\n/data/user/0/dev.kair.app/files/kair/projects/workspaces/x\n</workspace>"))
        assertTrue(withContainer.contains("<cwd>\n/workspace\n</cwd>"))
        assertTrue(withContainer.indexOf("<workspace>") < withContainer.indexOf("<cwd>"))
        // 映射规则必须贴着两段路径（真机实测：agent 看着 <workspace> 的宿主绝对路径去 container_shell
        // 里 wc .kair/memory.md → No such file → find / 全盘扫吃满超时）。
        assertTrue("宿主/容器路径映射规则不得缺席", withContainer.contains("Never pass the host path to container_shell"))
    }
}
