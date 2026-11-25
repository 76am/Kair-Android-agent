package dev.kair.app.core

import dev.kair.app.core.plugin.ChoiceOption
import dev.kair.app.core.plugin.PanelContribution
import dev.kair.app.core.plugin.Plugin
import dev.kair.app.core.plugin.PluginContext
import dev.kair.app.core.plugin.PluginRegistry
import dev.kair.app.core.plugin.SettingField
import dev.kair.app.core.plugin.SettingsPanelSpec
import dev.kair.app.ui.components.resolveDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * U37 · 插件显示文本的资源通道。
 *
 * 探针分两类，别混：
 * - **钉住形态选择**（arity）：dex 会构造的类（`SettingsPanelSpec` / `SettingField.Action` /
 *   `ChoiceOption`）资源 id 只能在类体 —— 进构造器就改掉 JVM 描述符，旧插件在构造点
 *   `NoSuchMethodError`（`AGENTS.md` §2.1）。只有 `PanelContribution` 相反：第三方 SDK 的
 *   `PluginContext` 没有 `panel()`，没有 dex 构造它，所以它走构造器，代价是 `copy` 的语义要靠
 *   判据 6 钉住（判据 1 若被改成类体形态，判据 6 立刻红）。
 * - **钉住行为**：回退分支逐字返回、`titleRes` 覆盖优先。
 */
class PluginTextChannelTest {

    /** 真实构造器（排除带 `DefaultConstructorMarker` 的合成默认值构造器）的参数类型串。 */
    private fun realCtorSignature(clazz: Class<*>): List<String> {
        val real = clazz.constructors.filter {
            it.parameterTypes.lastOrNull()?.name != "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        // 多于一个 = 构造器形态已经不是「单主构造器的 data class」，本探针的前提失效，必须显式失败。
        assertEquals("预期只有一个非合成构造器：${clazz.simpleName}", 1, real.size)
        return real.single().parameterTypes.map { it.simpleName }
    }

    // ---- 钉住形态选择 ------------------------------------------------------

    @Test
    fun `panel contribution keeps titleRes in the constructor`() {
        // content 是 `@Composable () -> Unit`，编译期类型是 **Function2**（接收 Composer 与
        // return 槽位），不是 Function0 —— 实测钉在这里，别照「lambda = Function0」改回去。
        // U86：`order` 之后新增 `chrome: PanelChrome`（带默认值；没有插件 dex 构造本类，
        // 与 titleRes 同理由可进构造器），签名同步登记。
        assertEquals(
            listOf("String", "String", "String", "Integer", "String", "int", "PanelChrome", "Function2"),
            realCtorSignature(PanelContribution::class.java),
        )
    }

    @Test
    fun `settings panel spec constructor keeps its arity`() {
        // dex 插件自己构造本类：titleRes **必须**留在类体，这里出现 Integer 即 ABI 破坏。
        assertEquals(
            listOf("String", "String", "String", "int", "List", "boolean"),
            realCtorSignature(SettingsPanelSpec::class.java),
        )
    }

    @Test
    fun `action and choice option constructors keep their arity`() {
        assertEquals(
            listOf("String", "String", "String", "String", "Function1", "Integer", "Integer"),
            realCtorSignature(SettingField.Action::class.java),
        )
        assertEquals(listOf("String", "String"), realCtorSignature(ChoiceOption::class.java))
    }

    @Test
    fun `plugin res members are real default methods`() {
        // 钉的是编译选项：去掉 -Xjvm-default=all-compatibility，带默认实现的接口成员会退回
        // abstract + DefaultImpls 外壳。本接口只由 APK 内的实现（内置插件与 ApiPluginAdapter）
        // 实现，dex 侧契约是 dev.kair.plugin.api.Plugin（本批没动它），所以这条守的是
        // 「接口默认实现必须真是 default 方法」这一编译事实，不是跨 dex 的 ABI。
        for (name in listOf("getNameRes", "getDescriptionRes")) {
            val method = Plugin::class.java.getDeclaredMethod(name)
            assertFalse(
                "$name 必须是 default 方法（丢了 -Xjvm-default=all-compatibility 就成 abstract 外壳）",
                Modifier.isAbstract(method.modifiers),
            )
        }
        assertTrue(
            "dex 侧契约不许被本批改名",
            dev.kair.plugin.api.Plugin::class.java.methods.none { it.name == "getNameRes" },
        )
    }

    // ---- 钉住行为 ----------------------------------------------------------

    @Test
    fun `resolveDisplayText prefers resource and keeps literal intact`() {
        val reads = mutableListOf<Int>()
        val read: (Int) -> String = { id -> reads.add(id); "res#$id" }

        assertEquals("没声明 res 就不该碰资源系统", "字面量", resolveDisplayText("字面量", null, read))
        assertEquals(emptyList<Int>(), reads)

        assertEquals("res#7", resolveDisplayText("字面量", 7, read))
        assertEquals(listOf(7), reads)
    }

    @Test
    fun `panel copy carries titleRes and title stays independent of it`() {
        val panel = PanelContribution(id = "panels.probe", title = "字面量标题", titleRes = 123, content = {})
        assertEquals("资源 id 只是附加通道，title 必须原样保留", "字面量标题", panel.title)
        assertEquals(Integer.valueOf(123), panel.titleRes)
        // ctx.panel() 内部就是 panel.copy(pluginId = …)：titleRes 一旦退回类体，copy 会把它
        // 静默丢成 null（data class 的 copy 只携带构造器参数），本条与判据 1 一起把这条形状钉死。
        assertEquals("copy(pluginId) 丢掉 titleRes", Integer.valueOf(123), panel.copy(pluginId = "dev.x").titleRes)
        assertNull(PanelContribution(id = "panels.probe", title = "t", content = {}).titleRes)
    }

    @Test
    fun `ctx panel stores the contributed titleRes`() {
        val registry = PluginRegistry()
        registry.install(
            object : Plugin {
                override val id = "dev.test.panelres"
                override val name = "probe"
                override val description = "probe"

                override fun activate(ctx: PluginContext) {
                    ctx.panel(
                        PanelContribution(id = "panels.probe", title = "字面量标题", titleRes = 456, content = {}),
                    )
                }
            },
        )
        val stored = registry.panels().single { it.id == "panels.probe" }
        assertEquals("注册路径上资源 id 不丢", Integer.valueOf(456), stored.titleRes)
        assertEquals("dev.test.panelres", stored.pluginId)
    }
}
