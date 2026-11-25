package dev.kair.app.core.plugin.dynamic

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 运行期**二进制兼容**巡检：找出插件实现类缺失的宿主契约方法。
 *
 * 为什么需要：`PluginCompatibility` 只看清单里声明的 `targetApi` / 版本范围，管不到
 * 「插件 dex 是按旧版 API 编译的、宿主新增了接口成员」这种**类级别**的不兼容。
 * Kotlin 在没有 `-Xjvm-default` 时，接口成员的默认实现只存在于 `DefaultImpls`，
 * 接口上的方法仍是 abstract —— 旧插件类没有实现它，宿主一调用就 `AbstractMethodError`
 * （典型现场：`Tool.getReadOnly()`）。
 *
 * 现在契约接口统一用 `-Xjvm-default=all-compatibility` 编译，带默认实现的成员是真正的
 * default 方法，旧插件可以安全地继承。本巡检因此只会报出真正**没有默认实现**的成员
 * —— 那属于破坏性 API 变更，必须让用户看到并更新插件，而不是等到某次工具调用才闪退。
 *
 * 只扫描 `dev.kair.` 包下的宿主契约接口：插件自己的接口、Kotlin 标准库与 Android API
 * 不在检查范围。
 */
internal object PluginBinaryCompat {

    /** 宿主契约的包前缀；插件 dex 按父委派共享这些类。 */
    private const val CONTRACT_PREFIX = "dev.kair."

    /**
     * 返回 [instance] 的类缺失的契约成员（形如 `Tool.readOnly`）。
     * 空列表 = 所有抽象成员都有实现，可安全调用。
     */
    fun issues(instance: Any): List<String> {
        val cls = instance.javaClass
        val missing = LinkedHashSet<String>()
        for (iface in interfacesOf(cls)) {
            if (!iface.name.startsWith(CONTRACT_PREFIX)) continue
            for (method in iface.declaredMethods) {
                // default 方法（all-compatibility 下 Modifier.isAbstract == false）不是
                // 插件必须实现的，跳过；static 成员也不参与实例实现。
                if (Modifier.isStatic(method.modifiers) || !Modifier.isAbstract(method.modifiers)) continue
                val impl = runCatching { cls.getMethod(method.name, *method.parameterTypes) }.getOrNull()
                if (impl == null || Modifier.isAbstract(impl.modifiers)) {
                    missing += "${iface.simpleName}.${displayName(method)}"
                }
            }
        }
        return missing.toList()
    }

    /** 方法名 → 契约里的属性名（`getReadOnly` → `readOnly`），便于用户读懂。 */
    private fun displayName(method: Method): String {
        val name = method.name
        val count = method.parameterCount
        return when {
            count == 0 && name.startsWith("get") && name.length > 3 ->
                name[3].lowercaseChar() + name.substring(4)
            count == 0 && name.startsWith("is") && name.length > 2 ->
                name[2].lowercaseChar() + name.substring(3)
            count == 1 && name.startsWith("set") && name.length > 3 ->
                name[3].lowercaseChar() + name.substring(4)
            else -> name
        }
    }

    /** 类实现（含父类实现）的全部接口，去重；顺序稳定，便于输出稳定。 */
    private fun interfacesOf(cls: Class<*>): List<Class<*>> {
        val out = LinkedHashSet<Class<*>>()
        fun walk(c: Class<*>?) {
            if (c == null) return
            c.interfaces.forEach { iface ->
                if (out.add(iface)) walk(iface)
            }
            walk(c.superclass)
        }
        walk(cls)
        return out.toList()
    }
}
