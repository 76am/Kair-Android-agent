package dev.kair.app.core.plugin

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.kair.app.core.agent.SystemPromptContext
import dev.kair.app.core.agent.SystemPromptContributor
import dev.kair.app.core.skills.SkillRegistry
import dev.kair.app.core.tools.Tool
import dev.kair.app.core.tools.ToolContext
import dev.kair.app.core.tools.ToolResult
import dev.kair.app.core.tools.props
import dev.kair.app.core.tools.schemaOf
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件注册表 / 钩子链 / 技能注册表的**并发安全**判据（B9 / P4-2）。
 *
 * 结论来自审计而不是猜：`install_plugin` / `uninstall_plugin` 是**工具**，在会话协程里执行
 * （`AppContainer.scope` = `Dispatchers.Default`），它们经 `DynamicPluginHost` 直接调
 * `PluginRegistry.install/uninstall`；而同一时刻请求装配线程在读 `tools()` /
 * `systemPromptContributors()` / `hooks.runXxx()`。所以「只有主线程在改注册表」这条**注释里的
 * 约定并不成立** —— `P4-2` 推荐的做法 A（写入口加主线程断言）会当场把 `install_plugin` 打成异常，
 * 这里改用做法 B 的**收窄版**：只把「读多写少」的普通 List 换成 `CopyOnWriteArrayList`，
 * `mutableStateListOf` 那几处（Compose 快照态，读在组合线程）保持不动。
 *
 * **判据是结构性的**：反射扫注册表 / 钩子链 / 技能注册表的**字段**，凡 `MutableList` 必须是
 * `CopyOnWriteArrayList` 或 `SnapshotStateList`，凡 `MutableMap` 必须是 `ConcurrentHashMap` 或
 * `SnapshotStateMap`。局部变量不受此限，所以扫字段而不是扫源码。
 * 两种容器都要扫：B9 把 `pluginScopes` 从 `mutableMapOf` 换成 `ConcurrentHashMap`，
 * 只扫 List 的守卫会让这半条修复「免费改回去」（评审 P2-1）。
 * `SkillRegistry`（`core/skills/Skill.kt`）也在扫描范围：它的两个集合在 `2524c3e` 已改成
 * COW（G1 的 B9 点名的「两个 mutableListOf」），但**没有守卫的修复会被免费改回去**。
 *
 * 为什么**不**写「多线程压力用例」：我试过（300 次装载 + 3 个读线程），把 `plugins` 换回
 * `mutableListOf` 之后它**照样绿** —— 竞态窗口太小，跑绿只证明这次没撞上。
 * 一个抓不住 bug 的用例比没有用例更糟（它让「已覆盖」变成空话），所以删掉、只留结构判据。
 */
class PluginRegistryConcurrencyTest {

    /**
     * 结构判据：注册表里不允许出现非并发安全的可变 List / Map。
     *
     * 反射扫字段而不是扫描源码 —— `mutableListOf` 写在**局部变量**里是安全的，
     * 源码扫描分不清局部与字段，只会制造误报。
     */
    @Test
    fun `registry collections are concurrent safe`() {
        val offenders = mutableListOf<String>()
        var checked = 0

        fun inspect(owner: Any, label: String, depth: Int) {
            owner.javaClass.declaredFields.forEach { field ->
                field.isAccessible = true
                val value = runCatching { field.get(owner) }.getOrNull() ?: return@forEach
                when {
                    value is MutableList<*> -> {
                        checked++
                        if (value !is CopyOnWriteArrayList<*> && value !is SnapshotStateList<*>) {
                            offenders += "  $label.${field.name} = ${value.javaClass.name}"
                        }
                    }
                    value is MutableMap<*, *> -> {
                        checked++
                        if (value !is ConcurrentHashMap<*, *> && value !is SnapshotStateMap<*, *>) {
                            offenders += "  $label.${field.name} = ${value.javaClass.name}"
                        }
                    }
                    // ProviderHistory 这类小容器：再看一层它的字段（它内部也是一个可变 List）。
                    depth > 0 && value.javaClass.name.startsWith("dev.kair.app.core.plugin.") ->
                        inspect(value, "$label.${field.name}", depth - 1)
                    else -> Unit
                }
            }
        }

        inspect(PluginRegistry(), "PluginRegistry", depth = 1)
        inspect(HookBus(), "HookBus", depth = 1)
        inspect(SkillRegistry(), "SkillRegistry", depth = 0)

        assertTrue("一个可变集合都没扫到 —— 反射没拿到字段，守卫等于没跑", checked > 0)
        assertTrue(
            buildString {
                appendLine("插件注册表里有非并发安全的可变集合（B9）：")
                offenders.forEach { appendLine(it) }
                appendLine()
                append("插件装载（install_plugin 工具）跑在会话协程里，而请求装配线程同时在读这些集合：")
                append("普通 mutableListOf / mutableMapOf 会抛 CME 或读到半张表。换成 CopyOnWriteArrayList / ")
                append("ConcurrentHashMap（读多写少），Compose 快照态（mutableStateListOf / mutableStateMapOf）保持不动。")
            },
            offenders.isEmpty(),
        )
    }

    private fun plugin(id: String): Plugin = object : Plugin {
        override val id = id
        override val name = id
        override val description = ""
        override fun activate(ctx: PluginContext) {
            ctx.tool(
                object : Tool {
                    override val name = "tool-$id"
                    override val description = "d"
                    override val parameters = schemaOf(props())
                    override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult("ok")
                },
            )
            ctx.systemPrompt(
                object : SystemPromptContributor {
                    override val id = "prompt-$id"
                    override val order = 100
                    override fun section(ctx: SystemPromptContext) = "x"
                },
            )
            ctx.onInput { it }
        }
    }
}
